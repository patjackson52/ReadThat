package dev.readthat.data

import androidx.room.withTransaction
import dev.readthat.data.db.CacheScope
import dev.readthat.data.db.AppDatabase
import dev.readthat.data.db.GroupEntity
import dev.readthat.data.db.RemoteKeyEntity
import dev.readthat.data.db.SyncMetadataEntity
import dev.readthat.domain.WireCell
import dev.readthat.domain.WireFeedPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import dev.readthat.observability.PerformanceEvent
import dev.readthat.observability.PerformanceMetric
import dev.readthat.observability.PerformanceSurface
import dev.readthat.observability.PerformanceTelemetry

/**
 * One synchronization implementation shared by Paging, foreground refresh, and
 * WorkManager. Network data is never emitted directly: successful responses are
 * committed to Room and Room invalidation is the only UI update path.
 */
class FeedSyncEngine(
    private val db: AppDatabase,
    private val remote: FeedRemoteSource,
    private val json: Json,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.feedDao()
    suspend fun refresh(
        accountId: String,
        feedId: String = CacheScope.HOME_FEED_ID,
    ): WireFeedPage = singleFlight(accountId, feedId) {
        drainVoteOutbox(accountId)
        val page = remote.loadPage(null)
        persistPage(accountId, feedId, page, replace = true)
        page
    }

    suspend fun append(
        accountId: String,
        feedId: String,
        cursor: String,
    ): WireFeedPage = singleFlight(accountId, feedId) {
        drainVoteOutbox(accountId)
        // The mediator read [cursor] before it entered this process-wide lock.
        // A worker may have refreshed the feed while this append was queued, so
        // re-read the authoritative cursor after acquiring the lock.
        val storedKey = dao.remoteKey(feedId, accountId)
        val effectiveCursor = when {
            storedKey == null -> cursor
            storedKey.nextCursor == null -> return@singleFlight WireFeedPage(emptyList(), null)
            else -> storedKey.nextCursor
        }
        val page = remote.loadPage(effectiveCursor)
        check(page.nextCursor != effectiveCursor) {
            "Feed server returned a non-advancing cursor"
        }
        persistPage(accountId, feedId, page, replace = false)
        page
    }

    /**
     * Drains durable mutations in creation order.
     *
     * @return true when every queued mutation was acknowledged, false when a
     * transient failure left work for WorkManager to retry.
     */
    suspend fun drainVoteOutbox(accountId: String): Boolean {
        for (pending in dao.pendingVotes(accountId)) {
            try {
                val confirmed = remote.votePost(pending.itemId, pending.value, pending.mutationId)
                if (confirmed == null) {
                    dao.deletePendingVote(pending.itemId, pending.mutationId, accountId)
                } else {
                    dao.confirmVote(
                        pending.itemId,
                        pending.mutationId,
                        confirmed.score,
                        confirmed.value,
                        accountId,
                    )
                }
                PerformanceTelemetry.record(PerformanceEvent(
                    name = PerformanceMetric.MUTATION_SERVER_ACK,
                    value = (nowMillis() - pending.createdAt).toDouble().coerceAtLeast(0.0),
                    surface = PerformanceSurface.BACKGROUND,
                    attributes = mapOf(
                        "mutation_type" to when (pending.value) {
                            1 -> "post_upvote"
                            -1 -> "post_downvote"
                            else -> "post_vote_clear"
                        },
                    ),
                ))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return false
            }
        }
        return true
    }

    private suspend fun persistPage(
        accountId: String,
        feedId: String,
        page: WireFeedPage,
        replace: Boolean,
    ) = db.withTransaction {
        if (replace) {
            dao.clearGroups(accountId, feedId)
            dao.clearRemoteKeys(accountId, feedId)
        }
        // A live ranked feed can overlap at a page boundary when votes change a
        // post's rank after the cursor was minted. Preserve the first-seen
        // position for those rows, update their payload in place, and allocate
        // monotonically increasing positions only to genuinely new groups.
        // This prevents visible jumps and duplicate sort positions while the
        // next refresh remains the point at which ranking is deliberately reset.
        val groups = page.groups.distinctBy { it.groupId }
        val existingPositions = if (replace || groups.isEmpty()) {
            emptyMap()
        } else {
            dao.groupsById(accountId, feedId, groups.map { it.groupId })
                .associate { it.groupId to it.sortIndex }
        }
        var nextIndex = if (replace) 0 else dao.maxSortIndex(accountId, feedId) + 1
        dao.upsertGroups(groups.map { group ->
            GroupEntity(
                groupId = group.groupId,
                sortIndex = existingPositions[group.groupId] ?: nextIndex++,
                payloadJson = json.encodeToString(group),
                accountId = accountId,
                feedId = feedId,
            )
        })
        groups.forEach { group ->
            group.cells.filterIsInstance<WireCell.ActionBar>().firstOrNull()?.let { bar ->
                dao.seedStateIfAbsent(
                    itemId = group.groupId,
                    likeCount = bar.score,
                    liked = bar.liked,
                    downvoted = bar.vote == -1,
                    accountId = accountId,
                )
            }
        }
        if (replace) dao.pruneOrphanedItemState(accountId)
        dao.putRemoteKey(RemoteKeyEntity(feedId, page.nextCursor, accountId))
        if (replace) {
            dao.putSyncMetadata(SyncMetadataEntity(
                accountId = accountId,
                scopeKey = feedId,
                lastSuccessfulSyncAt = nowMillis(),
            ))
        }
    }

    private suspend fun <T> singleFlight(accountId: String, feedId: String, block: suspend () -> T): T {
        val key = "$accountId/$feedId"
        // Engines are created independently by Paging and WorkManager. The lock
        // registry is process-wide so an append can never land after a competing
        // foreground/background refresh replaced its cursor generation.
        val mutex = syncLocks.computeIfAbsent(key) { Mutex() }
        return mutex.withLock { block() }
    }

    private companion object {
        val syncLocks = ConcurrentHashMap<String, Mutex>()
    }
}
