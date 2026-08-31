package dev.readthat.client

import dev.readthat.data.db.AppDatabase
import dev.readthat.data.db.PendingPerformanceEventEntity
import dev.readthat.observability.PerformanceBatch
import dev.readthat.observability.PerformanceEvent
import dev.readthat.observability.PerformanceRecorder
import dev.readthat.observability.PerformanceTelemetry
import dev.readthat.observability.sanitizedForExport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Non-blocking L1 intake plus bounded Room outbox, exported through the unified client. */
class PerformanceTelemetryExporter(
    private val client: ReadThatClient,
    database: AppDatabase,
    private val platform: String,
    private val appVersion: String,
    private val buildType: String,
) : PerformanceRecorder {
    private val dao = database.performanceOutboxDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val flushMutex = Mutex()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val sessionId = platformMutationId("process").substringAfter(':')
    private var nextFlushAfterMillis = 0L
    private var failureBackoffMillis = INITIAL_BACKOFF_MILLIS

    fun install() {
        PerformanceTelemetry.install(this)
        scope.launch { flush() }
    }

    override fun record(event: PerformanceEvent) {
        scope.launch {
            dao.insert(PendingPerformanceEventEntity(
                id = platformMutationId("metric"),
                payloadJson = json.encodeToString(event),
                createdAt = event.recordedAtEpochMs,
            ))
            dao.trimToNewest(MAX_EVENTS)
            if (dao.count() >= FLUSH_THRESHOLD) flush()
        }
    }

    suspend fun flush() = flushMutex.withLock {
        if (!client.enabled) return@withLock
        val now = platformEpochMillis()
        if (now < nextFlushAfterMillis) return@withLock
        val rows = dao.oldest(BATCH_SIZE)
        if (rows.isEmpty()) return@withLock
        // Sanitize persisted rows at the export boundary as well as at producers. This lets a
        // newer client drain older schema-compatible rows instead of wedging the whole FIFO on
        // one retired or accidentally high-cardinality dimension.
        val events = rows.mapNotNull { row ->
            runCatching { json.decodeFromString<PerformanceEvent>(row.payloadJson) }
                .getOrNull()
                ?.sanitizedForExport()
        }
        if (events.isEmpty()) {
            dao.delete(rows.map(PendingPerformanceEventEntity::id))
            return@withLock
        }
        try {
            client.sendPerformanceBatch(PerformanceBatch(
                platform = platform,
                appVersion = appVersion,
                buildType = buildType,
                sessionId = sessionId,
                events = events,
            ))
            dao.delete(rows.map(PendingPerformanceEventEntity::id))
            nextFlushAfterMillis = 0L
            failureBackoffMillis = INITIAL_BACKOFF_MILLIS
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ReadThatHttpException) {
            if (error.status in 400..499 && error.status !in setOf(408, 429)) {
                // Sanitization handles known legacy shapes; a remaining non-retryable client
                // rejection is still poison and must not wedge the cross-platform Room FIFO.
                dao.delete(rows.map(PendingPerformanceEventEntity::id))
                nextFlushAfterMillis = 0L
                failureBackoffMillis = INITIAL_BACKOFF_MILLIS
            } else {
                nextFlushAfterMillis = now + failureBackoffMillis
                failureBackoffMillis = (failureBackoffMillis * 2).coerceAtMost(MAX_BACKOFF_MILLIS)
            }
        } catch (_: Throwable) {
            // The Room outbox is intentionally retained for the next foreground/background run.
            nextFlushAfterMillis = now + failureBackoffMillis
            failureBackoffMillis = (failureBackoffMillis * 2).coerceAtMost(MAX_BACKOFF_MILLIS)
        }
    }

    private companion object {
        const val MAX_EVENTS = 1_000
        const val FLUSH_THRESHOLD = 20
        const val BATCH_SIZE = 50
        const val INITIAL_BACKOFF_MILLIS = 60_000L
        const val MAX_BACKOFF_MILLIS = 30L * 60_000L
    }
}
