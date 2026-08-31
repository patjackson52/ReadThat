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
        AndroidDatabaseProvider.get(context).subredditOutboxDao().resumable(accountId).forEach { pending ->
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
            val pending = runtime.database.subredditOutboxDao().get(mutationId)
            val result = SharedCreationOutboxProcessor(
                client = runtime.client,
                database = runtime.database,
                scope = CoroutineScope(currentCoroutineContext()),
            ).processCommunity(mutationId, terminalAttempt = runAttemptCount >= MAX_RETRIES)
            when (result) {
                SharedCreationOutboxResult.Completed -> {
                    // A dependent post may currently be sleeping under WorkManager's
                    // exponential backoff. Native scheduling releases the shared ordering barrier.
                    if (pending != null) {
                        PostUploadScheduler.releaseCommunityBarrier(
                            applicationContext,
                            pending.accountId,
                            pending.name,
                        )
                    }
                    Result.success()
                }
                SharedCreationOutboxResult.NoWork -> {
                    // Recover the native scheduling side effect if the process died after the
                    // shared Room transaction committed but before dependent work was released.
                    if (pending?.remoteSubredditId != null) {
                        PostUploadScheduler.releaseCommunityBarrier(
                            applicationContext,
                            pending.accountId,
                            pending.name,
                        )
                    }
                    Result.success()
                }
                SharedCreationOutboxResult.WaitingForAccount -> Result.success()
                SharedCreationOutboxResult.Retry -> Result.retry()
                SharedCreationOutboxResult.Failed -> Result.failure()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            if (runAttemptCount >= MAX_RETRIES) Result.failure() else Result.retry()
        }
    }

    private companion object { const val MAX_RETRIES = 5 }
}
