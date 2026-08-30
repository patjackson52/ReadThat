package dev.readthat.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.readthat.observability.PerformanceEvent
import dev.readthat.observability.PerformanceMetric
import dev.readthat.observability.PerformanceOutcome
import dev.readthat.observability.PerformanceSurface
import dev.readthat.observability.PerformanceTelemetry
import dev.readthat.data.backend.BackendGraph
import dev.readthat.data.backend.BackendHttpException
import dev.readthat.data.db.AppDatabase
import dev.readthat.data.db.SubredditEntity
import dev.readthat.data.db.CommunityMembershipEntity
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

object SubredditCreationScheduler {
    const val KEY_MUTATION_ID = "mutation_id"

    fun enqueue(context: Context, mutationId: String) {
        val request = OneTimeWorkRequestBuilder<SubredditCreationWorker>()
            .setInputData(Data.Builder().putString(KEY_MUTATION_ID, mutationId).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "subreddit-create:$mutationId",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    suspend fun resumePending(context: Context, accountId: String) {
        AppDatabase.get(context).subredditOutboxDao().resumable(accountId).forEach { pending ->
            enqueue(context, pending.mutationId)
        }
    }
}

class SubredditCreationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val mutationId = inputData.getString(SubredditCreationScheduler.KEY_MUTATION_ID)
            ?: return Result.failure()
        val database = AppDatabase.get(applicationContext)
        val dao = database.subredditOutboxDao()
        val pending = dao.get(mutationId) ?: return Result.success()
        if (pending.remoteSubredditId != null) return Result.success()
        if (database.accountDao().active()?.id != pending.accountId) {
            dao.updateProgress(mutationId, "waiting_account", null)
            return Result.success()
        }

        return try {
            dao.updateProgress(mutationId, "creating", null)
            val created = BackendGraph.repository(applicationContext).createSubreddit(
                name = pending.name,
                displayName = pending.displayName,
                description = pending.description,
                accessType = pending.accessType,
                clientMutationId = pending.mutationId,
            )
            dao.completeWithMembership(
                mutationId,
                SubredditEntity(
                    accountId = pending.accountId,
                    id = created.id,
                    name = created.name.lowercase(),
                    displayName = created.displayName,
                    description = created.description,
                    accessType = created.accessType,
                    viewerRole = created.viewerRole,
                    subscriberCount = created.subscriberCount,
                    updatedAt = System.currentTimeMillis(),
                ),
                CommunityMembershipEntity(
                    accountId = pending.accountId,
                    id = created.id,
                    name = created.name.lowercase(),
                    displayName = created.displayName,
                    accessType = created.accessType,
                    viewerRole = created.viewerRole ?: "owner",
                    source = "remote",
                    syncedAt = System.currentTimeMillis(),
                ),
            )
            // A dependent post may currently be sleeping under WorkManager's
            // exponential backoff. Community creation is the ordering barrier,
            // so release all resumable commands for this account immediately.
            PostUploadScheduler.releaseCommunityBarrier(
                applicationContext,
                pending.accountId,
                pending.name,
            )
            PerformanceTelemetry.record(PerformanceEvent(
                name = PerformanceMetric.MUTATION_SERVER_ACK,
                value = (System.currentTimeMillis() - pending.createdAt).toDouble().coerceAtLeast(0.0),
                surface = PerformanceSurface.BACKGROUND,
                attributes = mapOf("mutation_type" to "subreddit_create"),
            ))
            Result.success()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            val permanent = error is BackendHttpException &&
                error.status in 400..499 && error.status !in setOf(408, 429)
            if (permanent || runAttemptCount >= MAX_RETRIES) {
                dao.fail(pending, error.message ?: "Could not create community")
                PerformanceTelemetry.record(PerformanceEvent(
                    name = PerformanceMetric.MUTATION_SERVER_ACK,
                    value = (System.currentTimeMillis() - pending.createdAt).toDouble().coerceAtLeast(0.0),
                    surface = PerformanceSurface.BACKGROUND,
                    outcome = PerformanceOutcome.FAILURE,
                    attributes = mapOf("mutation_type" to "subreddit_create"),
                ))
                Result.failure()
            } else {
                dao.updateProgress(mutationId, "retrying", error.message)
                Result.retry()
            }
        }
    }

    private companion object { const val MAX_RETRIES = 5 }
}
