package dev.readthat.client

import dev.readthat.data.db.AppDatabase
import dev.readthat.data.db.PendingProductAnalyticsEventEntity
import dev.readthat.observability.ProductAnalytics
import dev.readthat.observability.ProductAnalyticsBatch
import dev.readthat.observability.ProductAnalyticsRecorder
import dev.readthat.observability.ProductEvent
import dev.readthat.observability.ProductEventName
import dev.readthat.observability.ProductEventReason
import dev.readthat.observability.ProductSurface
import dev.readthat.shared.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** App lifecycle seam used by the shared ViewModel without exposing either platform runtime. */
interface ProductAnalyticsLifecycle {
    fun onForeground()
    fun onBackground()
}

internal object NoOpProductAnalyticsLifecycle : ProductAnalyticsLifecycle {
    override fun onForeground() = Unit
    override fun onBackground() = Unit
}

/** App-private persistence for the installation id and recoverable engagement checkpoint. */
internal interface ProductAnalyticsStateStore {
    val installationId: String
    fun readCheckpoint(): String?
    fun writeCheckpoint(value: String?)
}

/**
 * Shared L1 channel -> Room L2 -> unified HTTPS exporter for product behavior events.
 *
 * The foreground session survives process death and short background transitions. Each queued row
 * captures the account at event time; upload authenticates only if that exact account is active.
 */
class ProductAnalyticsExporter internal constructor(
    private val client: ReadThatClient,
    database: AppDatabase,
    private val store: ProductAnalyticsStateStore,
    private val platform: String,
    private val appVersion: String,
    private val buildType: String,
) : ProductAnalyticsRecorder, ProductAnalyticsLifecycle {
    private val dao = database.productAnalyticsOutboxDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commands = Channel<Command>(
        capacity = L1_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val json = Json { explicitNulls = false; ignoreUnknownKeys = false }
    private val flushMutex = Mutex()
    private var checkpoint = store.readCheckpoint()?.let { encoded ->
        runCatching { json.decodeFromString<EngagementCheckpoint>(encoded) }.getOrNull()
    }
    private var foregroundStartedElapsedMs: Long? = null
    private var recoveredPriorProcess = false
    private var appForeground = false
    private var nextFlushAfterMillis = 0L
    private var failureBackoffMillis = INITIAL_BACKOFF_MILLIS

    init {
        scope.launch {
            for (command in commands) {
                val rows = when (command) {
                    is Command.Event -> recordNow(command.event)
                    is Command.Foreground -> enterForeground(command.now, command.elapsed)
                    is Command.Background -> leaveForeground(command.now, command.elapsed)
                    Command.IdentityChanged -> identityChangedNow()
                }
                persist(rows)
                if (command is Command.Background) flush()
            }
        }
    }

    fun install() {
        ProductAnalytics.install(this)
        scope.launch {
            client.session
                .map { state ->
                    Identity(
                        ready = state !is SessionState.Restoring,
                        accountId = (state as? SessionState.SignedIn)?.user?.id,
                    )
                }
                .distinctUntilChanged()
                .collect { commands.send(Command.IdentityChanged) }
        }
        scope.launch { flush() }
    }

    override fun record(event: ProductEvent) {
        event.sanitizedForProductAnalytics()?.let { commands.trySend(Command.Event(it)) }
    }

    override fun onForeground() {
        commands.trySend(Command.Foreground(platformEpochMillis(), platformElapsedRealtimeMillis()))
    }

    override fun onBackground() {
        commands.trySend(Command.Background(platformEpochMillis(), platformElapsedRealtimeMillis()))
    }

    suspend fun flush() = flushMutex.withLock {
        if (!client.enabled) return@withLock
        val now = platformEpochMillis()
        if (now < nextFlushAfterMillis) return@withLock
        var attemptedIds: List<String> = emptyList()
        try {
            for (batchIndex in 0 until MAX_BATCHES_PER_FLUSH) {
                val scopeRow = dao.oldest() ?: break
                val pending = dao.oldestForScope(
                    scopeRow.sessionId,
                    scopeRow.installationId,
                    scopeRow.accountId,
                    BATCH_SIZE,
                )
                if (pending.isEmpty()) break
                attemptedIds = pending.map(PendingProductAnalyticsEventEntity::id)
                val events = pending.mapNotNull { row ->
                    runCatching { json.decodeFromString<ProductEvent>(row.payloadJson) }
                        .getOrNull()
                        ?.sanitizedForProductAnalytics()
                }
                if (events.isEmpty()) {
                    dao.delete(attemptedIds)
                    attemptedIds = emptyList()
                    continue
                }
                client.sendProductAnalyticsBatch(
                    ProductAnalyticsBatch(
                        platform = platform,
                        appVersion = appVersion,
                        buildType = buildType,
                        installationId = scopeRow.installationId,
                        sessionId = scopeRow.sessionId,
                        events = events,
                    ),
                    expectedAccountId = scopeRow.accountId,
                )
                dao.delete(attemptedIds)
                attemptedIds = emptyList()
            }
            nextFlushAfterMillis = 0L
            failureBackoffMillis = INITIAL_BACKOFF_MILLIS
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ReadThatHttpException) {
            val permanent = error.status in 400..499 && error.status !in setOf(408, 429)
            if (permanent) {
                if (attemptedIds.isNotEmpty()) dao.delete(attemptedIds)
                else dao.oldest()?.let { row -> dao.delete(listOf(row.id)) }
                nextFlushAfterMillis = 0L
                failureBackoffMillis = INITIAL_BACKOFF_MILLIS
            } else {
                scheduleRetry(now)
            }
        } catch (_: Throwable) {
            scheduleRetry(now)
        }
    }

    private suspend fun persist(events: List<ScopedProductEvent>) {
        if (events.isEmpty()) return
        events.forEach { scoped ->
            dao.insert(PendingProductAnalyticsEventEntity(
                id = platformMutationId("product").substringAfter(':'),
                installationId = store.installationId,
                sessionId = scoped.sessionId,
                accountId = scoped.accountId,
                payloadJson = json.encodeToString(scoped.event),
                dedupeKey = scoped.dedupeKey,
                createdAt = scoped.event.recordedAtEpochMs,
            ))
        }
        dao.trimToNewest(L2_CAPACITY)
        if (dao.count() >= FLUSH_THRESHOLD) scope.launch { flush() }
    }

    private fun recordNow(event: ProductEvent): List<ScopedProductEvent> {
        val now = event.recordedAtEpochMs
        val output = mutableListOf<ScopedProductEvent>()
        var current = checkpoint
        val nextAccount = currentAccountId()
        if (current == null) {
            current = newCheckpoint(now, nextAccount, appForeground)
            output += scoped(
                current,
                sessionEvent(ProductEventName.SESSION_START, current, ProductEventReason.COLD_START, now),
            )
        } else if (current.accountId != nextAccount && identity().ready) {
            output += rotateSession(ProductEventReason.IDENTITY_CHANGE, now, nextAccount, platformElapsedRealtimeMillis())
            current = checkNotNull(checkpoint)
        }
        current = current.copy(lastEventAtEpochMs = maxOf(current.lastEventAtEpochMs, now))
        saveCheckpoint(current)
        output += scoped(current, event, dedupeKey(current, event))
        return output
    }

    private fun enterForeground(now: Long, elapsed: Long): List<ScopedProductEvent> {
        if (appForeground) return emptyList()
        appForeground = true
        recoverPriorProcessIfNeeded()
        val nextAccount = currentAccountId()
        val existing = checkpoint
        val inactivityAt = existing?.lastBackgroundAtEpochMs?.takeIf { it > 0L }
            ?: existing?.lastEventAtEpochMs
        val timedOut = inactivityAt != null && now - inactivityAt >= SESSION_TIMEOUT_MS
        val output = mutableListOf<ScopedProductEvent>()
        if (existing != null && (timedOut || (identity().ready && existing.accountId != nextAccount))) {
            output += finishSession(
                existing,
                if (timedOut) ProductEventReason.TIMEOUT else ProductEventReason.IDENTITY_CHANGE,
                now,
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
                    now,
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
                sessionEvent(ProductEventName.SESSION_FOREGROUND, current, ProductEventReason.FOREGROUND, now),
            )
        }
        foregroundStartedElapsedMs = elapsed
        saveCheckpoint(current)
        return output
    }

    private fun leaveForeground(now: Long, elapsed: Long): List<ScopedProductEvent> {
        if (!appForeground) return emptyList()
        appForeground = false
        var current = checkpoint ?: return emptyList()
        current = accrueForeground(current, elapsed).copy(
            lastEventAtEpochMs = now,
            lastBackgroundAtEpochMs = now,
            wasForeground = false,
            foregroundStartedAtEpochMs = 0L,
        )
        foregroundStartedElapsedMs = null
        saveCheckpoint(current)
        return listOf(scoped(
            current,
            sessionEvent(ProductEventName.SESSION_CHECKPOINT, current, ProductEventReason.BACKGROUND, now),
        ))
    }

    private fun identityChangedNow(): List<ScopedProductEvent> {
        if (!identity().ready) return emptyList()
        val current = checkpoint ?: return emptyList()
        val nextAccount = identity().accountId
        if (current.accountId == nextAccount) return emptyList()
        return if (appForeground) {
            rotateSession(
                ProductEventReason.IDENTITY_CHANGE,
                platformEpochMillis(),
                nextAccount,
                platformElapsedRealtimeMillis(),
            )
        } else {
            checkpoint = null
            foregroundStartedElapsedMs = null
            store.writeCheckpoint(null)
            listOf(finishSession(current, ProductEventReason.IDENTITY_CHANGE, platformEpochMillis()))
        }
    }

    private fun rotateSession(
        reason: ProductEventReason,
        now: Long,
        nextAccount: String?,
        elapsed: Long,
    ): List<ScopedProductEvent> {
        val output = mutableListOf<ScopedProductEvent>()
        checkpoint?.let { output += finishSession(accrueForeground(it, elapsed), reason, now) }
        val next = newCheckpoint(now, nextAccount, appForeground)
        foregroundStartedElapsedMs = elapsed.takeIf { appForeground }
        saveCheckpoint(next)
        output += scoped(next, sessionEvent(ProductEventName.SESSION_START, next, reason, now))
        return output
    }

    private fun recoverPriorProcessIfNeeded() {
        if (recoveredPriorProcess) return
        recoveredPriorProcess = true
        val current = checkpoint ?: return
        if (!current.wasForeground) return
        val recovered = (current.lastEventAtEpochMs - current.foregroundStartedAtEpochMs)
            .coerceIn(0L, SESSION_TIMEOUT_MS)
        saveCheckpoint(current.copy(
            activeForegroundMs = current.activeForegroundMs + recovered,
            wasForeground = false,
            foregroundStartedAtEpochMs = 0L,
        ))
    }

    private fun newCheckpoint(now: Long, accountId: String?, foreground: Boolean) = EngagementCheckpoint(
        sessionId = platformMutationId("session").substringAfter(':'),
        accountId = accountId,
        startedAtEpochMs = now,
        lastEventAtEpochMs = now,
        lastBackgroundAtEpochMs = 0L,
        activeForegroundMs = 0L,
        wasForeground = foreground,
        foregroundStartedAtEpochMs = now.takeIf { foreground } ?: 0L,
    )

    private fun accrueForeground(current: EngagementCheckpoint, elapsed: Long): EngagementCheckpoint {
        val started = foregroundStartedElapsedMs ?: return current
        return current.copy(activeForegroundMs = current.activeForegroundMs + (elapsed - started).coerceAtLeast(0L))
    }

    private fun saveCheckpoint(value: EngagementCheckpoint) {
        checkpoint = value
        store.writeCheckpoint(json.encodeToString(value))
    }

    private fun finishSession(
        current: EngagementCheckpoint,
        reason: ProductEventReason,
        now: Long,
    ) = scoped(current, sessionEvent(ProductEventName.SESSION_SUMMARY, current, reason, now))

    private fun sessionEvent(
        name: String,
        current: EngagementCheckpoint,
        reason: ProductEventReason,
        now: Long,
    ) = ProductEvent(
        name = name,
        surface = if (name == ProductEventName.SESSION_CHECKPOINT) ProductSurface.BACKGROUND else ProductSurface.APP,
        recordedAtEpochMs = now,
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

    private fun identity(): Identity {
        val state = client.session.value
        return Identity(
            ready = state !is SessionState.Restoring,
            accountId = (state as? SessionState.SignedIn)?.user?.id,
        )
    }

    private fun currentAccountId(): String? = identity().let { value ->
        if (value.ready) value.accountId else checkpoint?.accountId
    }

    private fun scheduleRetry(now: Long) {
        nextFlushAfterMillis = now + failureBackoffMillis
        failureBackoffMillis = (failureBackoffMillis * 2).coerceAtMost(MAX_BACKOFF_MILLIS)
    }

    private sealed interface Command {
        data class Event(val event: ProductEvent) : Command
        data class Foreground(val now: Long, val elapsed: Long) : Command
        data class Background(val now: Long, val elapsed: Long) : Command
        data object IdentityChanged : Command
    }

    private data class Identity(val ready: Boolean, val accountId: String?)

    private companion object {
        const val L1_CAPACITY = 512
        const val L2_CAPACITY = 5_000
        const val FLUSH_THRESHOLD = 20
        const val BATCH_SIZE = 50
        const val MAX_BATCHES_PER_FLUSH = 10
        const val SESSION_TIMEOUT_MS = 30L * 60L * 1_000L
        const val INITIAL_BACKOFF_MILLIS = 60_000L
        const val MAX_BACKOFF_MILLIS = 30L * 60_000L
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

@Serializable
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

private const val MAX_PRODUCT_DURATION_MS = 7L * 24L * 60L * 60L * 1_000L
private const val MAX_PRODUCT_POSITION = 24 * 60 * 60 * 1_000
private val PRODUCT_CONTENT_ID = Regex("^[A-Za-z0-9._:-]{1,160}$")
private val ALLOWED_PRODUCT_EVENT_NAMES = setOf(
    ProductEventName.SESSION_START,
    ProductEventName.SESSION_FOREGROUND,
    ProductEventName.SESSION_CHECKPOINT,
    ProductEventName.SESSION_SUMMARY,
    ProductEventName.POST_IMPRESSION,
    ProductEventName.POST_DETAIL_VIEW,
    ProductEventName.COMMENTS_VIEW,
    ProductEventName.COMMENT_CREATE,
    ProductEventName.MEDIA_PLAYBACK,
    ProductEventName.MEDIA_FEED_TIME_SPENT,
    ProductEventName.COMMUNITY_VIEW,
    ProductEventName.COMMUNITY_TIME_SPENT,
    ProductEventName.COMMUNITY_JOIN,
    ProductEventName.COMMUNITY_LEAVE,
    ProductEventName.COMMUNITY_POST_VIEW,
    ProductEventName.AD_IMPRESSION,
    ProductEventName.AD_VIEW_TIME,
    ProductEventName.AD_CLICK,
    ProductEventName.AD_CTA_CLICK,
    ProductEventName.AD_CAROUSEL_SWIPE,
    ProductEventName.AD_RELATED_CLICK,
    ProductEventName.AD_VIDEO_PLAY,
    ProductEventName.AD_VIDEO_WATCH,
    ProductEventName.AD_VIDEO_COMPLETE,
    ProductEventName.AD_DETAIL_VIEW,
    ProductEventName.AD_LANDING_LOAD,
)

/** Mirrors the backend's bounded schema before an event can enter durable storage. */
fun ProductEvent.sanitizedForProductAnalytics(): ProductEvent? {
    if (name !in ALLOWED_PRODUCT_EVENT_NAMES || recordedAtEpochMs <= 0L) return null
    return copy(
        contentId = contentId?.takeIf(PRODUCT_CONTENT_ID::matches),
        durationMs = durationMs?.coerceIn(0L, MAX_PRODUCT_DURATION_MS),
        position = position?.coerceIn(0, MAX_PRODUCT_POSITION),
        completionPercent = completionPercent?.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0),
    )
}
