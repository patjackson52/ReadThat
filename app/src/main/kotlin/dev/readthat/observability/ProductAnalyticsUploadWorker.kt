package dev.readthat.observability

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.readthat.BuildConfig
import dev.readthat.client.AndroidReadThatClientConfiguration
import dev.readthat.client.AndroidReadThatClientRegistry
import dev.readthat.client.ReadThatHttpException
import dev.readthat.client.sanitizedForProductAnalytics
import dev.readthat.data.db.AndroidDatabaseProvider
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/** Process-death-safe drain for the Room outbox written by shared product analytics. */
object ProductAnalyticsUploadScheduler {
    private const val UPLOAD_WORK = "product-analytics-upload"
    private const val PERIODIC_WORK = "product-analytics-periodic"
    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun initialize(context: Context) {
        val periodic = PeriodicWorkRequestBuilder<ProductAnalyticsUploadWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraint)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic,
        )
    }

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<ProductAnalyticsUploadWorker>()
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UPLOAD_WORK,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}

class ProductAnalyticsUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    override suspend fun doWork(): Result {
        val dao = AndroidDatabaseProvider.get(applicationContext).productAnalyticsOutboxDao()
        return try {
            val client = AndroidReadThatClientRegistry.get(
                applicationContext,
                AndroidReadThatClientConfiguration(
                    baseUrl = BuildConfig.READTHAT_API_BASE_URL,
                    appVersion = BuildConfig.VERSION_NAME,
                    demoUsername = BuildConfig.READTHAT_DEMO_USERNAME,
                    demoPassword = BuildConfig.READTHAT_DEMO_PASSWORD,
                ),
            ).client
            repeat(MAX_BATCHES_PER_RUN) {
                val scope = dao.oldest() ?: return Result.success()
                val pending = dao.oldestForScope(
                    scope.sessionId,
                    scope.installationId,
                    scope.accountId,
                    BATCH_SIZE,
                )
                val decoded = pending.mapNotNull { row ->
                    runCatching { json.decodeFromString<ProductEvent>(row.payloadJson) }
                        .getOrNull()
                        ?.sanitizedForProductAnalytics()
                }
                if (decoded.isEmpty()) {
                    dao.delete(pending.map { it.id })
                    return@repeat
                }
                try {
                    client.sendProductAnalyticsBatch(
                        ProductAnalyticsBatch(
                            platform = "android",
                            appVersion = BuildConfig.VERSION_NAME,
                            buildType = BuildConfig.BUILD_TYPE,
                            installationId = scope.installationId,
                            sessionId = scope.sessionId,
                            events = decoded,
                        ),
                        expectedAccountId = scope.accountId,
                    )
                    dao.delete(pending.map { it.id })
                } catch (error: ReadThatHttpException) {
                    if (!isPermanentProductAnalyticsHttpFailure(error.status)) return Result.retry()
                    dao.delete(pending.map { it.id })
                }
            }
            if (dao.count() > 0) Result.retry() else Result.success()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            Result.retry()
        }
    }

    private companion object {
        const val BATCH_SIZE = 50
        const val MAX_BATCHES_PER_RUN = 10
    }
}

internal fun isPermanentProductAnalyticsHttpFailure(status: Int): Boolean =
    status in 400..499 && status !in setOf(408, 429)
