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
import dev.readthat.data.db.AndroidDatabaseProvider
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/** Process-death-safe drain for the Room outbox written by the shared telemetry exporter. */
object TelemetryUploadScheduler {
    private const val UPLOAD_WORK = "performance-telemetry-upload"
    private const val PERIODIC_WORK = "performance-telemetry-periodic"

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun initialize(context: Context) {
        val periodic = PeriodicWorkRequestBuilder<PerformanceUploadWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraint)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic,
        )
    }

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<PerformanceUploadWorker>()
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

class PerformanceUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    override suspend fun doWork(): Result {
        val dao = AndroidDatabaseProvider.get(applicationContext).performanceOutboxDao()
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
                val pending = dao.oldest(BATCH_SIZE)
                if (pending.isEmpty()) return Result.success()
                val decoded = pending.mapNotNull { row ->
                    runCatching { json.decodeFromString<PerformanceEvent>(row.payloadJson) }
                        .getOrNull()
                        ?.sanitizedForExport()
                }
                if (decoded.isEmpty()) {
                    dao.delete(pending.map { it.id })
                    return@repeat
                }
                try {
                    client.sendPerformanceBatch(PerformanceBatch(
                        platform = "android",
                        appVersion = BuildConfig.VERSION_NAME,
                        buildType = BuildConfig.BUILD_TYPE,
                        // Accept both the retired Android recorder ids and shared KMP ids so an
                        // upgrade can drain every schema-compatible row already on the device.
                        sessionId = performanceUploadSessionId(pending.first().id),
                        events = decoded,
                    ))
                    dao.delete(pending.map { it.id })
                } catch (error: ReadThatHttpException) {
                    if (!isPermanentTelemetryHttpFailure(error.status)) throw error
                    dao.delete(pending.map { it.id })
                }
            }
            if (dao.count() > 0) Result.retry() else Result.success()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: ReadThatHttpException) {
            Result.retry()
        } catch (_: Throwable) {
            Result.retry()
        }
    }

    private companion object {
        const val BATCH_SIZE = 50
        const val MAX_BATCHES_PER_RUN = 4
    }
}

internal fun isPermanentTelemetryHttpFailure(status: Int): Boolean =
    status in 400..499 && status != 408 && status != 429

/** Returns an anonymous UUID accepted by the telemetry contract for either outbox producer. */
internal fun performanceUploadSessionId(
    pendingId: String,
    fallbackSessionId: String = UUID.randomUUID().toString(),
): String {
    val candidates = listOf(pendingId.substringBefore(':'), pendingId.substringAfter(':', ""))
    return candidates.firstOrNull(::isCanonicalUuid) ?: fallbackSessionId
}

private fun isCanonicalUuid(value: String): Boolean =
    runCatching { UUID.fromString(value).toString().equals(value, ignoreCase = true) }
        .getOrDefault(false)
