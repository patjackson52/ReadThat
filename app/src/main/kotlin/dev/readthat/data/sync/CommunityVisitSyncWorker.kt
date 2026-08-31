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
import dev.readthat.client.OfflineFirstRepository
import dev.readthat.data.db.AndroidDatabaseProvider
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext

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
        if (AndroidDatabaseProvider.get(context).communityDrawerDao().pendingMutationCount(accountId) > 0) {
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
        if (AndroidDatabaseProvider.get(applicationContext).accountDao().active()?.id != accountId) {
            return Result.success()
        }
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
            if (runtime.client.restoreSession()?.id != accountId) return Result.success()
            val repository = OfflineFirstRepository(
                client = runtime.client,
                database = runtime.database,
                scope = CoroutineScope(currentCoroutineContext()),
                accountIdOverride = accountId,
                maintainGlobalState = false,
            )
            repository.syncPendingCommunityVisits()
            if (runtime.database.communityDrawerDao().pendingMutationCount(accountId) == 0) {
                runCatching { repository.refreshCommunityDrawer(force = true) }
                Result.success()
            } else {
                Result.retry()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            if (runAttemptCount >= MAX_RETRIES) Result.failure() else Result.retry()
        }
    }

    private companion object {
        const val MAX_RETRIES = 5
    }
}
