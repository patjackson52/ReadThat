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
import dev.readthat.data.community.CommunityDetailGraph
import dev.readthat.data.db.AppDatabase
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

object CommunityMembershipSyncScheduler {
    internal const val KEY_ACCOUNT_ID = "account_id"

    fun enqueue(context: Context, accountId: String) {
        val request = OneTimeWorkRequestBuilder<CommunityMembershipSyncWorker>()
            .setInputData(Data.Builder().putString(KEY_ACCOUNT_ID, accountId).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "community-membership:$accountId",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    suspend fun resumePending(context: Context, accountId: String) {
        if (AppDatabase.get(context).communityDetailDao().pendingCount(accountId) > 0) {
            enqueue(context, accountId)
        }
    }
}

class CommunityMembershipSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val accountId = inputData.getString(CommunityMembershipSyncScheduler.KEY_ACCOUNT_ID)
            ?: return Result.failure()
        if (AppDatabase.get(applicationContext).accountDao().active()?.id != accountId) {
            return Result.success()
        }
        return try {
            val first = AppDatabase.get(applicationContext).communityDetailDao()
                .pending(accountId, limit = 1).firstOrNull() ?: return Result.success()
            val repository = CommunityDetailGraph.repository(applicationContext, accountId, first.name)
            repeat(MAX_BATCHES_PER_RUN) {
                if (repository.flushMembershipMutations()) return Result.success()
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
