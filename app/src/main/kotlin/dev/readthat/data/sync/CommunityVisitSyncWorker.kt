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
import dev.readthat.data.community.CommunityGraph
import dev.readthat.data.db.AppDatabase
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

object CommunityVisitSyncScheduler {
    internal const val KEY_ACCOUNT_ID = "account_id"

    fun enqueue(context: Context, accountId: String) {
        val request = OneTimeWorkRequestBuilder<CommunityVisitSyncWorker>()
            .setInputData(Data.Builder().putString(KEY_ACCOUNT_ID, accountId).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "community-visits:$accountId",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    suspend fun resumePending(context: Context, accountId: String) {
        if (AppDatabase.get(context).communityDrawerDao().pendingMutationCount(accountId) > 0) {
            enqueue(context, accountId)
        }
    }

}

class CommunityVisitSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val accountId = inputData.getString(CommunityVisitSyncScheduler.KEY_ACCOUNT_ID)
            ?: return Result.failure()
        if (AppDatabase.get(applicationContext).accountDao().active()?.id != accountId) {
            return Result.success()
        }
        return try {
            val repository = CommunityGraph.repository(applicationContext, accountId)
            repeat(MAX_BATCHES_PER_RUN) {
                if (repository.flushVisitMutations()) {
                    runCatching { repository.refresh(force = true) }
                    return Result.success()
                }
            }
            Result.retry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            if (runAttemptCount >= MAX_RETRIES) Result.failure() else Result.retry()
        }
    }

    private companion object {
        const val MAX_BATCHES_PER_RUN = 10
        const val MAX_RETRIES = 5
    }
}
