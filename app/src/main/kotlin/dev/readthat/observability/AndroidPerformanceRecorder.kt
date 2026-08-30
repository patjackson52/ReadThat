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
import dev.readthat.observability.PerformanceBatch
import dev.readthat.observability.PerformanceEvent
import dev.readthat.observability.PerformanceRecorder
import dev.readthat.BuildConfig
import dev.readthat.data.backend.BackendGraph
import dev.readthat.data.backend.BackendHttpException
import dev.readthat.data.db.AppDatabase
import dev.readthat.data.db.PendingPerformanceEventEntity
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** L1 bounded channel -> Room L2 -> network-constrained WorkManager exporter. */
class AndroidPerformanceRecorder(context: Context) : PerformanceRecorder {
    private val appContext = context.applicationContext
    private val processSessionId = UUID.randomUUID().toString()
    private val sequence = AtomicLong()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val events = Channel<PerformanceEvent>(
        capacity = L1_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val json = Json { explicitNulls = false }

    init {
        TelemetryUploadScheduler.initialize(appContext)
        scope.launch {
            for (event in events) {
                val dao = AppDatabase.get(appContext).performanceOutboxDao()
                dao.insert(PendingPerformanceEventEntity(
                    id = "$processSessionId:${sequence.incrementAndGet()}",
                    payloadJson = json.encodeToString(event),
                    createdAt = event.recordedAtEpochMs,
                ))
                dao.trimToNewest(L2_CAPACITY)
                TelemetryUploadScheduler.enqueue(appContext)
            }
        }
    }

    override fun record(event: PerformanceEvent) {
        events.trySend(event)
    }

    private companion object {
        const val L1_CAPACITY = 256
        const val L2_CAPACITY = 1_000
    }
}

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
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = false }

    override suspend fun doWork(): Result {
        val dao = AppDatabase.get(applicationContext).performanceOutboxDao()
        return try {
            repeat(MAX_BATCHES_PER_RUN) {
                val pending = dao.oldest(BATCH_SIZE)
                if (pending.isEmpty()) return Result.success()
                val decoded = pending.mapNotNull { row ->
                    runCatching { json.decodeFromString<PerformanceEvent>(row.payloadJson) }.getOrNull()
                }
                if (decoded.isEmpty()) {
                    dao.delete(pending.map { it.id })
                    return@repeat
                }
                BackendGraph.client(applicationContext).sendPerformanceBatch(PerformanceBatch(
                    platform = "android",
                    appVersion = BuildConfig.VERSION_NAME,
                    buildType = BuildConfig.BUILD_TYPE,
                    sessionId = pending.first().id.substringBefore(':'),
                    events = decoded,
                ))
                dao.delete(pending.map { it.id })
            }
            if (dao.count() > 0) TelemetryUploadScheduler.enqueue(applicationContext)
            Result.success()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: BackendHttpException) {
            // A 4xx means the local schema is incompatible. Retrying a poison
            // batch forever burns battery and never makes it valid.
            if (error.status in 400..499 && error.status != 408 && error.status != 429) {
                dao.oldest(BATCH_SIZE).takeIf { it.isNotEmpty() }?.let { dao.delete(it.map { row -> row.id }) }
                Result.failure()
            } else {
                Result.retry()
            }
        } catch (_: Throwable) {
            Result.retry()
        }
    }

    private companion object {
        const val BATCH_SIZE = 50
        const val MAX_BATCHES_PER_RUN = 4
    }
}
