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
import dev.readthat.BuildConfig
import dev.readthat.client.AndroidReadThatClientConfiguration
import dev.readthat.client.AndroidReadThatClientRegistry
import dev.readthat.client.SharedCreationOutboxProcessor
import dev.readthat.client.SharedCreationOutboxResult
import dev.readthat.data.db.AndroidDatabaseProvider
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable

@Serializable
internal data class PendingMediaUpload(
    val name: String,
    val contentType: String,
    val localPath: String,
    val byteSize: Long,
    val width: Int? = null,
    val height: Int? = null,
    val durationSeconds: Int? = null,
    val remoteMediaId: String? = null,
)

object PostUploadScheduler {
    const val KEY_MUTATION_ID = "mutation_id"

    fun enqueue(context: Context, mutationId: String) {
        enqueue(context, mutationId, ExistingWorkPolicy.KEEP)
    }

    private fun enqueue(context: Context, mutationId: String, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<PostUploadWorker>()
            .setInputData(Data.Builder().putString(KEY_MUTATION_ID, mutationId).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "post-upload:$mutationId",
            policy,
            request,
        )
    }

    suspend fun resumePending(context: Context, accountId: String) {
        AndroidDatabaseProvider.get(context).postOutboxDao().resumable(accountId).forEach { pending ->
            enqueue(context, pending.mutationId)
        }
    }

    /**
     * Cancel the barrier retry/backoff and run only posts targeting the community
     * that just reconciled. REPLACE prevents two workers publishing one command.
     */
    suspend fun releaseCommunityBarrier(context: Context, accountId: String, subreddit: String) {
        AndroidDatabaseProvider.get(context).postOutboxDao()
            .resumableForSubreddit(accountId, subreddit)
            .forEach { pending -> enqueue(context, pending.mutationId, ExistingWorkPolicy.REPLACE) }
    }
}

class PostUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val mutationId = inputData.getString(PostUploadScheduler.KEY_MUTATION_ID)
            ?: return Result.failure()
        return try {
            val runtime = AndroidReadThatClientRegistry.get(
                applicationContext,
                AndroidReadThatClientConfiguration(
                    baseUrl = BuildConfig.READTHAT_API_BASE_URL,
                    appVersion = BuildConfig.VERSION_NAME,
                    demoUsername = BuildConfig.READTHAT_DEMO_USERNAME,
                    demoPassword = BuildConfig.READTHAT_DEMO_PASSWORD,
                ),
            )
            val result = SharedCreationOutboxProcessor(
                client = runtime.client,
                database = runtime.database,
                scope = CoroutineScope(currentCoroutineContext()),
            ).processPost(mutationId, terminalAttempt = runAttemptCount >= MAX_RETRIES)
            when (result) {
                SharedCreationOutboxResult.Completed -> {
                    FeedSyncScheduler.enqueueRefresh(applicationContext)
                    Result.success()
                }
                SharedCreationOutboxResult.NoWork,
                SharedCreationOutboxResult.WaitingForAccount,
                -> Result.success()
                SharedCreationOutboxResult.Retry -> Result.retry()
                SharedCreationOutboxResult.Failed -> Result.failure()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            if (runAttemptCount >= MAX_RETRIES) Result.failure() else Result.retry()
        }
    }

    private companion object {
        const val MAX_RETRIES = 5
    }
}
