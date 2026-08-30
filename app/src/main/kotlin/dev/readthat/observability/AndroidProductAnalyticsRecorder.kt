package dev.readthat.observability

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
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
import dev.readthat.observability.ProductAnalyticsBatch
import dev.readthat.observability.ProductAnalyticsRecorder
import dev.readthat.observability.ProductEvent
import dev.readthat.observability.ProductEventName
import dev.readthat.observability.ProductEventReason
import dev.readthat.observability.ProductSurface
import dev.readthat.BuildConfig
import dev.readthat.data.backend.BackendGraph
import dev.readthat.data.backend.BackendHttpException
import dev.readthat.data.db.AppDatabase
import dev.readthat.data.db.PendingProductAnalyticsEventEntity
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * ProcessLifecycleOwner observer plus L1 channel -> Room L2 -> WorkManager exporter.
 *
 * A foreground engagement session survives short background transitions and
 * rotates after 30 minutes or an account change. Checkpoints make duration and
 * queued behavior recoverable after process death and usable while offline.
 */
class AndroidProductAnalyticsRecorder(
    context: Context,
    private val accountId: () -> String?,
    private val identityReady: () -> Boolean = { true },
) : ProductAnalyticsRecorder, DefaultLifecycleObserver {
    private val appContext = context.applicationContext
    private val store = ProductIdentityStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val events = Channel<ScopedProductEvent>(
        capacity = L1_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val json = Json { explicitNulls = false }
    private val lock = Any()
    private var checkpoint = store.checkpoint()
    private var foregroundStartedElapsedMs: Long? = null
    private var recoveredPriorProcess = false
    private var appForeground = false

    init {
        ProductAnalyticsUploadScheduler.initialize(appContext)
        scope.launch {
            val dao = AppDatabase.get(appContext).productAnalyticsOutboxDao()
            for (scoped in events) {
                val inserted = dao.insert(PendingProductAnalyticsEventEntity(
                    // The engagement session can survive process death, so a
                    // process-local sequence would collide after restoration.
                    id = UUID.randomUUID().toString(),
                    installationId = store.installationId,
                    sessionId = scoped.sessionId,
                    accountId = scoped.accountId,
                    payloadJson = json.encodeToString(scoped.event),
                    dedupeKey = scoped.dedupeKey,
                    createdAt = scoped.event.recordedAtEpochMs,
                ))
                if (inserted != -1L) {
                    dao.trimToNewest(L2_CAPACITY)
                    ProductAnalyticsUploadScheduler.enqueue(appContext)
                }
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        val queued = synchronized(lock) {
            appForeground = true
            enterForeground(System.currentTimeMillis())
        }
        queued.forEach(events::trySend)
    }

    override fun onStop(owner: LifecycleOwner) {
        val queued = synchronized(lock) {
            leaveForeground(System.currentTimeMillis()).also { appForeground = false }
        }
        queued.forEach(events::trySend)
    }

    /** Called when the auth StateFlow changes so one session never spans principals. */
    fun identityChanged() {
        val queued = synchronized(lock) {
            val current = checkpoint ?: return@synchronized emptyList()
            val nextAccount = currentAccountId()
            if (current.accountId == nextAccount) return@synchronized emptyList()
            if (appForeground) {
                rotateSession(ProductEventReason.IDENTITY_CHANGE, System.currentTimeMillis(), nextAccount)
            } else {
                checkpoint = null
                foregroundStartedElapsedMs = null
                store.clearCheckpoint()
                listOf(finishSession(current, ProductEventReason.IDENTITY_CHANGE))
            }
        }
        queued.forEach(events::trySend)
    }

    override fun record(event: ProductEvent) {
        val queued = synchronized(lock) {
            val now = System.currentTimeMillis()
            val output = mutableListOf<ScopedProductEvent>()
            var current = checkpoint
            val nextAccount = currentAccountId()
            if (current == null) {
                current = newCheckpoint(now, nextAccount, appForeground)
                checkpoint = current
                output += scoped(current, sessionEvent(ProductEventName.SESSION_START, current, ProductEventReason.COLD_START))
            } else if (current.accountId != nextAccount) {
                output += rotateSession(ProductEventReason.IDENTITY_CHANGE, now, nextAccount)
                current = checkNotNull(checkpoint)
            }
            current = current.copy(lastEventAtEpochMs = now)
            checkpoint = current
            store.checkpoint(current)
            output += scoped(current, event, dedupeKey(current, event))
            output
        }
        queued.forEach(events::trySend)
    }

    private fun enterForeground(now: Long): List<ScopedProductEvent> {
        recoverPriorProcessIfNeeded()
        val nextAccount = currentAccountId()
        val existing = checkpoint
        val inactivityAt = existing?.lastBackgroundAtEpochMs?.takeIf { it > 0L }
            ?: existing?.lastEventAtEpochMs
        val timedOut = inactivityAt != null && now - inactivityAt >= SESSION_TIMEOUT_MS
        val output = mutableListOf<ScopedProductEvent>()
        if (existing != null && (timedOut || existing.accountId != nextAccount)) {
            output += finishSession(
                existing,
                if (timedOut) ProductEventReason.TIMEOUT else ProductEventReason.IDENTITY_CHANGE,
            )
            checkpoint = null
        }

        var current = checkpoint
        if (current == null) {
            current = newCheckpoint(now, nextAccount, foreground = true)
            output += scoped(
                current,
                sessionEvent(
                    ProductEventName.SESSION_START,
                    current,
                    if (existing == null) ProductEventReason.COLD_START else ProductEventReason.FOREGROUND,
                ),
            )
        } else {
            current = current.copy(
                lastEventAtEpochMs = now,
                lastBackgroundAtEpochMs = 0L,
                wasForeground = true,
                foregroundStartedAtEpochMs = now,
            )
            output += scoped(
                current,
                sessionEvent(ProductEventName.SESSION_FOREGROUND, current, ProductEventReason.FOREGROUND),
            )
        }
        checkpoint = current
        foregroundStartedElapsedMs = SystemClock.elapsedRealtime()
        store.checkpoint(current)
        return output
    }

    private fun leaveForeground(now: Long): List<ScopedProductEvent> {
        var current = checkpoint ?: return emptyList()
        current = accrueForeground(current).copy(
            lastEventAtEpochMs = now,
            lastBackgroundAtEpochMs = now,
            wasForeground = false,
            foregroundStartedAtEpochMs = 0L,
        )
        checkpoint = current
        foregroundStartedElapsedMs = null
        store.checkpoint(current)
        return listOf(scoped(
            current,
            sessionEvent(ProductEventName.SESSION_CHECKPOINT, current, ProductEventReason.BACKGROUND),
        ))
    }

    private fun rotateSession(
        reason: ProductEventReason,
        now: Long,
        nextAccount: String?,
    ): List<ScopedProductEvent> {
        val output = mutableListOf<ScopedProductEvent>()
        checkpoint?.let { output += finishSession(accrueForeground(it), reason) }
        val next = newCheckpoint(now, nextAccount, appForeground)
        checkpoint = next
        foregroundStartedElapsedMs = if (next.wasForeground) SystemClock.elapsedRealtime() else null
        store.checkpoint(next)
        output += scoped(next, sessionEvent(ProductEventName.SESSION_START, next, reason))
        return output
    }

    private fun finishSession(
        current: EngagementCheckpoint,
        reason: ProductEventReason,
    ): ScopedProductEvent = scoped(
        current,
        sessionEvent(ProductEventName.SESSION_SUMMARY, current, reason),
    )

    private fun newCheckpoint(
        now: Long,
        account: String?,
        foreground: Boolean,
    ): EngagementCheckpoint = EngagementCheckpoint(
        sessionId = UUID.randomUUID().toString(),
        accountId = account,
        startedAtEpochMs = now,
        lastEventAtEpochMs = now,
        lastBackgroundAtEpochMs = 0L,
        activeForegroundMs = 0L,
        wasForeground = foreground,
        foregroundStartedAtEpochMs = if (foreground) now else 0L,
    )

    private fun accrueForeground(current: EngagementCheckpoint): EngagementCheckpoint {
        val started = foregroundStartedElapsedMs ?: return current
        val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
        return current.copy(activeForegroundMs = current.activeForegroundMs + elapsed)
    }

    private fun recoverPriorProcessIfNeeded() {
        if (recoveredPriorProcess) return
        recoveredPriorProcess = true
        val current = checkpoint ?: return
        if (!current.wasForeground) return
        // A killed process cannot report its final interval. Use its last durable
        // behavior timestamp as a conservative lower bound without trusting a
        // wall-clock interval longer than the session timeout.
        val recovered = (current.lastEventAtEpochMs - current.foregroundStartedAtEpochMs)
            .coerceIn(0L, SESSION_TIMEOUT_MS)
        checkpoint = current.copy(
            activeForegroundMs = current.activeForegroundMs + recovered,
            wasForeground = false,
            foregroundStartedAtEpochMs = 0L,
        )
        store.checkpoint(checkNotNull(checkpoint))
    }

    private fun sessionEvent(
        name: String,
        current: EngagementCheckpoint,
        reason: ProductEventReason,
    ) = ProductEvent(
        name = name,
        surface = if (name == ProductEventName.SESSION_CHECKPOINT) {
            ProductSurface.BACKGROUND
        } else {
            ProductSurface.APP
        },
        reason = reason,
        durationMs = current.activeForegroundMs,
    )

    private fun scoped(
        current: EngagementCheckpoint,
        event: ProductEvent,
        dedupeKey: String? = null,
    ) = ScopedProductEvent(current.sessionId, current.accountId, event, dedupeKey)

    private fun dedupeKey(current: EngagementCheckpoint, event: ProductEvent): String? {
        if (event.name !in DEDUPED_EVENTS || event.contentId == null) return null
        return "${current.sessionId}:${event.name}:${event.surface}:${event.contentId}"
    }

    private fun currentAccountId(): String? {
        // Session restoration deliberately runs off the first-frame path. Until
        // it resolves, preserve the checkpoint's principal instead of creating
        // a brief anonymous session on every signed-in cold start.
        if (!runCatching(identityReady).getOrDefault(false)) return checkpoint?.accountId
        return runCatching(accountId).getOrNull()
    }

    private companion object {
        const val L1_CAPACITY = 512
        const val L2_CAPACITY = 5_000
        const val SESSION_TIMEOUT_MS = 30L * 60L * 1_000L
        val DEDUPED_EVENTS = setOf(
            ProductEventName.POST_IMPRESSION,
            ProductEventName.POST_DETAIL_VIEW,
            ProductEventName.COMMENTS_VIEW,
            ProductEventName.COMMUNITY_VIEW,
            ProductEventName.COMMUNITY_POST_VIEW,
            ProductEventName.AD_IMPRESSION,
            ProductEventName.AD_DETAIL_VIEW,
        )
    }
}

private data class EngagementCheckpoint(
    val sessionId: String,
    val accountId: String?,
    val startedAtEpochMs: Long,
    val lastEventAtEpochMs: Long,
    val lastBackgroundAtEpochMs: Long,
    val activeForegroundMs: Long,
    val wasForeground: Boolean,
    val foregroundStartedAtEpochMs: Long,
)

private data class ScopedProductEvent(
    val sessionId: String,
    val accountId: String?,
    val event: ProductEvent,
    val dedupeKey: String?,
)

/** App-private random installation identity and recoverable session checkpoint. */
private class ProductIdentityStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    val installationId: String = preferences.getString(INSTALLATION_ID, null)
        ?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
        ?: UUID.randomUUID().toString().also {
            // Synchronous once per install so the id survives an immediate
            // process kill; a storage failure degrades to a new id next launch.
            preferences.edit().putString(INSTALLATION_ID, it).commit()
        }

    fun checkpoint(): EngagementCheckpoint? {
        val sessionId = preferences.getString(SESSION_ID, null) ?: return null
        if (runCatching { UUID.fromString(sessionId) }.isFailure) return null
        return EngagementCheckpoint(
            sessionId = sessionId,
            accountId = preferences.getString(ACCOUNT_ID, null),
            startedAtEpochMs = preferences.getLong(STARTED_AT, 0L),
            lastEventAtEpochMs = preferences.getLong(LAST_EVENT_AT, 0L),
            lastBackgroundAtEpochMs = preferences.getLong(LAST_BACKGROUND_AT, 0L),
            activeForegroundMs = preferences.getLong(ACTIVE_MS, 0L),
            wasForeground = preferences.getBoolean(WAS_FOREGROUND, false),
            foregroundStartedAtEpochMs = preferences.getLong(FOREGROUND_STARTED_AT, 0L),
        )
    }

    fun checkpoint(value: EngagementCheckpoint) {
        preferences.edit()
            .putString(SESSION_ID, value.sessionId)
            .apply {
                if (value.accountId == null) remove(ACCOUNT_ID) else putString(ACCOUNT_ID, value.accountId)
            }
            .putLong(STARTED_AT, value.startedAtEpochMs)
            .putLong(LAST_EVENT_AT, value.lastEventAtEpochMs)
            .putLong(LAST_BACKGROUND_AT, value.lastBackgroundAtEpochMs)
            .putLong(ACTIVE_MS, value.activeForegroundMs)
            .putBoolean(WAS_FOREGROUND, value.wasForeground)
            .putLong(FOREGROUND_STARTED_AT, value.foregroundStartedAtEpochMs)
            .apply()
    }

    fun clearCheckpoint() {
        preferences.edit()
            .remove(SESSION_ID)
            .remove(ACCOUNT_ID)
            .remove(STARTED_AT)
            .remove(LAST_EVENT_AT)
            .remove(LAST_BACKGROUND_AT)
            .remove(ACTIVE_MS)
            .remove(WAS_FOREGROUND)
            .remove(FOREGROUND_STARTED_AT)
            .apply()
    }

    private companion object {
        const val PREFERENCES = "product_analytics_v1"
        const val INSTALLATION_ID = "installation_id"
        const val SESSION_ID = "session_id"
        const val ACCOUNT_ID = "account_id"
        const val STARTED_AT = "started_at"
        const val LAST_EVENT_AT = "last_event_at"
        const val LAST_BACKGROUND_AT = "last_background_at"
        const val ACTIVE_MS = "active_ms"
        const val WAS_FOREGROUND = "was_foreground"
        const val FOREGROUND_STARTED_AT = "foreground_started_at"
    }
}

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
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = false }

    override suspend fun doWork(): Result {
        val dao = AppDatabase.get(applicationContext).productAnalyticsOutboxDao()
        var attemptedIds: List<String> = emptyList()
        return try {
            repeat(MAX_BATCHES_PER_RUN) {
                val scope = dao.oldest() ?: return Result.success()
                val pending = dao.oldestForScope(
                    scope.sessionId,
                    scope.installationId,
                    scope.accountId,
                    BATCH_SIZE,
                )
                attemptedIds = pending.map { it.id }
                val decoded = pending.mapNotNull { row ->
                    runCatching { json.decodeFromString<ProductEvent>(row.payloadJson) }.getOrNull()
                }
                if (decoded.isEmpty()) {
                    dao.delete(pending.map { it.id })
                    return@repeat
                }
                BackendGraph.client(applicationContext).sendProductAnalyticsBatch(
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
            }
            if (dao.count() > 0) ProductAnalyticsUploadScheduler.enqueue(applicationContext)
            Result.success()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: BackendHttpException) {
            if (error.status in 400..499 && error.status != 408 && error.status != 429) {
                if (attemptedIds.isNotEmpty()) dao.delete(attemptedIds)
                else dao.oldest()?.let { dao.delete(listOf(it.id)) }
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
        const val MAX_BATCHES_PER_RUN = 10
    }
}
