package dev.readthat.client

import dev.readthat.data.db.AppDatabase
import dev.readthat.data.db.PendingPostEntity
import dev.readthat.data.db.PendingSubredditEntity
import dev.readthat.observability.PerformanceEvent
import dev.readthat.observability.PerformanceMetric
import dev.readthat.observability.PerformanceOutcome
import dev.readthat.observability.PerformanceSurface
import dev.readthat.observability.PerformanceTelemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

/**
 * Platform-neutral execution result. Android maps it to WorkManager's Result while an Apple
 * background-task adapter can map the same policy to BGTask completion and rescheduling.
 */
enum class SharedCreationOutboxResult {
    Completed,
    NoWork,
    WaitingForAccount,
    Retry,
    Failed,
}

/**
 * Executes durable creation commands using the shared Room database, authenticated client,
 * upload implementation, retry classification, and telemetry contract. Platform code owns only
 * scheduling constraints and lifecycle integration.
 */
class SharedCreationOutboxProcessor(
    private val client: ReadThatClient,
    private val database: AppDatabase,
    scope: CoroutineScope,
) {
    private val repository = OfflineFirstRepository(
        client = client,
        database = database,
        scope = scope,
        maintainGlobalState = false,
    )

    suspend fun processPost(
        mutationId: String,
        terminalAttempt: Boolean,
    ): SharedCreationOutboxResult {
        val dao = database.postOutboxDao()
        val pending = dao.get(mutationId) ?: return SharedCreationOutboxResult.NoWork
        if (pending.remotePostId != null) return SharedCreationOutboxResult.NoWork
        return try {
            if (!isActiveAccount(pending.accountId)) {
                dao.updateProgress(mutationId, "waiting_account", pending.mediaId, null)
                SharedCreationOutboxResult.WaitingForAccount
            } else {
                val community = database.subredditOutboxDao()
                    .getByName(pending.accountId, pending.subreddit)
                when {
                    community?.state == "failed" -> {
                        dao.fail(mutationId, COMMUNITY_FAILURE_MESSAGE)
                        recordPostAcknowledgement(pending, PerformanceOutcome.FAILURE)
                        SharedCreationOutboxResult.Failed
                    }
                    community != null && community.remoteSubredditId == null -> {
                        dao.updateProgress(mutationId, "waiting_community", pending.mediaId, null)
                        SharedCreationOutboxResult.Retry
                    }
                    else -> {
                        repository.retryPendingPost(mutationId)
                        recordPostAcknowledgement(pending)
                        SharedCreationOutboxResult.Completed
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (error.isPermanentMutationFailure() || terminalAttempt) {
                dao.fail(mutationId, error.userMessage("Could not create post"))
                recordPostAcknowledgement(pending, PerformanceOutcome.FAILURE)
                SharedCreationOutboxResult.Failed
            } else {
                val latest = dao.get(mutationId) ?: pending
                dao.updateProgress(
                    mutationId,
                    "retrying",
                    latest.mediaId,
                    error.userMessage("Waiting for a network connection"),
                )
                SharedCreationOutboxResult.Retry
            }
        }
    }

    suspend fun processCommunity(
        mutationId: String,
        terminalAttempt: Boolean,
    ): SharedCreationOutboxResult {
        val dao = database.subredditOutboxDao()
        val pending = dao.get(mutationId) ?: return SharedCreationOutboxResult.NoWork
        if (pending.remoteSubredditId != null) return SharedCreationOutboxResult.NoWork
        return try {
            if (!isActiveAccount(pending.accountId)) {
                dao.updateProgress(mutationId, "waiting_account", null)
                SharedCreationOutboxResult.WaitingForAccount
            } else {
                repository.retryPendingCommunity(mutationId)
                recordCommunityAcknowledgement(pending)
                SharedCreationOutboxResult.Completed
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (error.isPermanentMutationFailure() || terminalAttempt) {
                dao.fail(pending, error.userMessage("Could not create community"))
                recordCommunityAcknowledgement(pending, PerformanceOutcome.FAILURE)
                SharedCreationOutboxResult.Failed
            } else {
                dao.updateProgress(
                    mutationId,
                    "retrying",
                    error.userMessage("Waiting for a network connection"),
                )
                SharedCreationOutboxResult.Retry
            }
        }
    }

    private suspend fun isActiveAccount(accountId: String): Boolean {
        if (database.accountDao().active()?.id != accountId) return false
        if (client.activeAccountId == accountId) return true
        return client.restoreSession()?.id == accountId
    }

    private fun recordPostAcknowledgement(
        pending: PendingPostEntity,
        outcome: PerformanceOutcome = PerformanceOutcome.SUCCESS,
    ) {
        runCatching {
            PerformanceTelemetry.record(PerformanceEvent(
                name = PerformanceMetric.MUTATION_SERVER_ACK,
                value = elapsedSince(pending.createdAt),
                surface = PerformanceSurface.BACKGROUND,
                outcome = outcome,
                attributes = mapOf(
                    "mutation_type" to "post_create",
                    "content_kind" to pending.kind.lowercase(),
                ),
            ))
        }
    }

    private fun recordCommunityAcknowledgement(
        pending: PendingSubredditEntity,
        outcome: PerformanceOutcome = PerformanceOutcome.SUCCESS,
    ) {
        runCatching {
            PerformanceTelemetry.record(PerformanceEvent(
                name = PerformanceMetric.MUTATION_SERVER_ACK,
                value = elapsedSince(pending.createdAt),
                surface = PerformanceSurface.BACKGROUND,
                outcome = outcome,
                attributes = mapOf("mutation_type" to "subreddit_create"),
            ))
        }
    }

    private fun elapsedSince(createdAt: Long): Double =
        (platformEpochMillis() - createdAt).toDouble().coerceAtLeast(0.0)

    private companion object {
        const val COMMUNITY_FAILURE_MESSAGE =
            "Community creation failed; retry the community before this post"
    }
}

private fun Throwable.isPermanentMutationFailure(): Boolean =
    this is IllegalArgumentException ||
        this is ReadThatHttpException && status in 400..499 && status !in setOf(408, 429)

private fun Throwable.userMessage(fallback: String): String =
    message?.takeIf(String::isNotBlank) ?: fallback
