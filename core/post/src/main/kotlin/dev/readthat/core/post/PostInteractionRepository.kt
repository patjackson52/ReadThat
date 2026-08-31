package dev.readthat.core.post

import androidx.room3.withWriteTransaction
import dev.readthat.observability.PerformanceEvent
import dev.readthat.observability.PerformanceMetric
import dev.readthat.observability.PerformanceOutcome
import dev.readthat.observability.PerformanceSurface
import dev.readthat.observability.PerformanceTelemetry
import dev.readthat.observability.performanceTimer
import dev.readthat.data.db.AppDatabase
import dev.readthat.data.db.CacheScope
import dev.readthat.data.db.ItemStateEntity
import dev.readthat.data.db.PendingVoteEntity
import dev.readthat.shared.VoteSnapshot
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ConfirmedPostVote(val score: Int, val value: Int)

fun interface PostVoteRemoteSource {
    suspend fun vote(postId: String, value: Int, clientMutationId: String): ConfirmedPostVote
}

/**
 * One account-scoped optimistic write path shared by feed, MediaFeed, and detail.
 * The durable outbox is committed in the same Room transaction as visible state;
 * cancellation or network failure can therefore never lose the user's intent.
 */
class PostInteractionRepository(
    private val db: AppDatabase,
    private val remote: PostVoteRemoteSource,
    private val accountId: () -> String = { CacheScope.DEFAULT_ACCOUNT_ID },
    private val onVoteQueued: () -> Unit = {},
) {
    private val mutationLocks = Array(64) { Mutex() }

    suspend fun vote(
        postId: String,
        requestedValue: Int,
        surface: PerformanceSurface,
        baseline: VoteSnapshot? = null,
    ): VoteSnapshot = mutate(postId, requestedValue, surface, baseline, toggleMatchingVote = true)

    /** Writes an already-resolved vote value, as used by detail's optimistic ViewModel state. */
    suspend fun setVote(
        postId: String,
        desiredValue: Int,
        surface: PerformanceSurface,
        baseline: VoteSnapshot? = null,
    ): VoteSnapshot = mutate(postId, desiredValue, surface, baseline, toggleMatchingVote = false)

    private suspend fun mutate(
        postId: String,
        value: Int,
        surface: PerformanceSurface,
        baseline: VoteSnapshot?,
        toggleMatchingVote: Boolean,
    ): VoteSnapshot {
        require(postId.isNotBlank())
        require(value in -1..1)
        val account = accountId()
        val dao = db.feedDao()
        val timer = performanceTimer()
        val lockIndex = ((31 * account.hashCode() + postId.hashCode()) and Int.MAX_VALUE) % mutationLocks.size
        val local = mutationLocks[lockIndex].withLock {
            val current = dao.stateFor(postId, account)
            val currentVote = when {
                current?.liked == true -> 1
                current?.downvoted == true -> -1
                else -> baseline?.viewerVote ?: 0
            }
            val nextVote = if (toggleMatchingVote && currentVote == value) 0 else value
            val baseScore = current?.likeCount ?: baseline?.score ?: 0
            val optimistic = VoteSnapshot(
                score = baseScore - currentVote + nextVote,
                viewerVote = nextVote,
            )
            val mutationId = UUID.randomUUID().toString()
            db.withWriteTransaction {
                dao.putState(ItemStateEntity(
                    accountId = account,
                    itemId = postId,
                    likeCount = optimistic.score,
                    liked = nextVote == 1,
                    downvoted = nextVote == -1,
                ))
                dao.enqueueVote(PendingVoteEntity(
                    accountId = account,
                    itemId = postId,
                    mutationId = mutationId,
                    value = nextVote,
                    createdAt = System.currentTimeMillis(),
                ))
            }
            LocalVote(nextVote, mutationId, optimistic)
        }
        val nextVote = local.value
        val mutationId = local.mutationId
        val optimistic = local.snapshot
        val mutationType = when (nextVote) {
            1 -> "post_upvote"
            -1 -> "post_downvote"
            else -> "post_vote_clear"
        }
        PerformanceTelemetry.duration(
            PerformanceMetric.MUTATION_LOCAL_COMMIT,
            timer,
            surface = surface,
            attributes = mapOf("mutation_type" to mutationType, "cache_tier" to "room"),
        )
        onVoteQueued()

        return try {
            val confirmed = remote.vote(postId, nextVote, mutationId)
            dao.confirmVote(postId, mutationId, confirmed.score, confirmed.value, account)
            PerformanceTelemetry.duration(
                PerformanceMetric.MUTATION_SERVER_ACK,
                timer,
                surface = surface,
                attributes = mapOf("mutation_type" to mutationType),
            )
            VoteSnapshot(confirmed.score, confirmed.value)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            PerformanceTelemetry.record(PerformanceEvent(
                name = PerformanceMetric.MUTATION_SERVER_ACK,
                value = timer.elapsedMilliseconds(),
                surface = surface,
                outcome = PerformanceOutcome.QUEUED,
                attributes = mapOf("mutation_type" to mutationType),
            ))
            optimistic
        }
    }

    private data class LocalVote(
        val value: Int,
        val mutationId: String,
        val snapshot: VoteSnapshot,
    )
}
