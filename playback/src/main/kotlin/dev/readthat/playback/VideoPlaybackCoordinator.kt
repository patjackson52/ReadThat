package dev.readthat.playback

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.MediaSourceFactorySupplier
import androidx.media3.exoplayer.source.preload.PreCacheHelper
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import androidx.media3.ui.PlayerView
import dev.readthat.networking.UnifiedTransport
import dev.readthat.observability.PerformanceMetric
import dev.readthat.observability.PerformanceTelemetry
import dev.readthat.observability.PerformanceTimer
import dev.readthat.observability.ProductAnalytics
import dev.readthat.observability.ProductContentType
import dev.readthat.observability.ProductEvent
import dev.readthat.observability.ProductEventName
import dev.readthat.observability.ProductEventReason
import dev.readthat.observability.ProductSurface
import dev.readthat.observability.performanceTimer
import dev.readthat.shared.VideoPlaybackPolicy
import java.net.URI
import kotlin.coroutines.resume
import kotlin.math.absoluteValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class VideoPlaybackRole(internal val priority: Int) {
    Feed(1),
    MediaFeed(2),
    Detail(3),
    AdDetail(4),
}

internal fun videoRepeatMode(role: VideoPlaybackRole): Int =
    if (role == VideoPlaybackRole.MediaFeed) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF

enum class VideoPlaybackState { Idle, Buffering, Ready, Playing, Paused, Ended, Error }

data class VideoPlaybackSnapshot(
    val state: VideoPlaybackState,
    val positionMs: Long,
    val durationMs: Long?,
) {
    val completionPercent: Double?
        get() = durationMs?.takeIf { it > 0L }?.let {
            (positionMs * 100.0 / it).coerceIn(0.0, 100.0)
        }
}

/** Pure policy seam; covered without constructing codecs or Android views. */
internal enum class VideoPreloadTier { None, Source, Tracks, Loaded }

internal fun videoPreloadTier(distance: Int, playbackActive: Boolean): VideoPreloadTier = when {
    !playbackActive && distance == 0 -> VideoPreloadTier.Loaded
    playbackActive && distance == 0 -> VideoPreloadTier.None
    distance == 1 -> VideoPreloadTier.Loaded
    distance == -1 -> VideoPreloadTier.Loaded
    distance.absoluteValue == 2 -> VideoPreloadTier.Tracks
    distance.absoluteValue <= 4 -> VideoPreloadTier.Source
    else -> VideoPreloadTier.None
}

/** Maps an occurrence-based viewport index onto Media3's stable-key-deduplicated source list. */
internal fun deduplicatedVideoFocusIndex(
    sources: List<AdaptiveVideoSource>,
    requestedFocusIndex: Int,
): Int {
    if (sources.isEmpty()) return -1
    val requestedKey = sources[requestedFocusIndex.coerceIn(sources.indices)].stableKey
    return sources.distinctBy(AdaptiveVideoSource::stableKey)
        .indexOfFirst { it.stableKey == requestedKey }
}

/**
 * A playing feed-to-detail handoff keeps controls available on tap, but must not let PlayerView's
 * default auto-show policy flash them when the already-playing player is attached to its new view.
 */
internal fun suppressVideoControllerAutoShow(
    showControls: Boolean,
    continueExistingPlayback: Boolean,
    sameMedia: Boolean,
    playWhenReady: Boolean,
    playbackEnded: Boolean,
): Boolean = showControls &&
    continueExistingPlayback &&
    sameMedia &&
    playWhenReady &&
    !playbackEnded

/** A progress publisher only runs while one foreground surface owns actively playing media. */
internal fun shouldPublishVideoProgress(
    hasWinner: Boolean,
    appForeground: Boolean,
    isPlaying: Boolean,
): Boolean = hasWinner && appForeground && isPlaying

/**
 * Process-scoped playback owner.
 *
 * There is exactly one ExoPlayer/decoder for the feed + detail surface. Feed
 * cells and detail temporarily own PlayerViews, while [PlayerView.switchTargetView]
 * moves the player atomically between them. The player is created lazily only
 * when a video actually becomes active; preloading does not create a second one.
 */
@OptIn(UnstableApi::class)
object VideoPlaybackCoordinator {
    private var session: PlaybackSession? = null
    private var sessionInitializationStarted = false
    private var appForeground = true
    private val pendingOperations = ArrayDeque<(PlaybackSession) -> Unit>()

    @MainThread
    fun attach(
        context: Context,
        owner: Any,
        source: AdaptiveVideoSource,
        policy: VideoPlaybackPolicy,
        role: VideoPlaybackRole,
        autoplay: Boolean,
        muted: Boolean,
        showControls: Boolean,
        continueExistingPlayback: Boolean,
        lifecycleStarted: Boolean,
        onFirstFrame: () -> Unit,
        onPlaybackState: (VideoPlaybackSnapshot) -> Unit,
        onError: (PlaybackException) -> Unit,
    ) {
        requireMainThread()
        withSession(context, policy) { playbackSession ->
            playbackSession.attach(
                owner,
                PlaybackRequest(
                    source = source,
                    policy = policy,
                    role = role,
                    autoplay = autoplay,
                    muted = muted,
                    showControls = showControls,
                    continueExistingPlayback = continueExistingPlayback,
                    lifecycleStarted = lifecycleStarted,
                    onFirstFrame = onFirstFrame,
                    onPlaybackState = onPlaybackState,
                    onError = onError,
                ),
            )
        }
    }

    @MainThread
    fun detach(owner: Any) {
        requireMainThread()
        withExistingOrInitializingSession { it.detach(owner) }
    }

    @MainThread
    fun bindView(
        context: Context,
        policy: VideoPlaybackPolicy,
        owner: Any,
        view: PlayerView,
    ) {
        requireMainThread()
        withSession(context, policy) { it.bindView(owner, view) }
    }

    @MainThread
    fun unbindView(owner: Any, view: PlayerView) {
        requireMainThread()
        withExistingOrInitializingSession { it.unbindView(owner, view) }
    }

    @MainThread
    fun setLifecycleStarted(owner: Any, started: Boolean) {
        requireMainThread()
        withExistingOrInitializingSession { it.setLifecycleStarted(owner, started) }
    }

    @MainThread
    fun hasRendered(source: AdaptiveVideoSource): Boolean {
        requireMainThread()
        return session?.renderedMediaKey == source.stableKey
    }

    /**
     * Tracks a small window around the next likely video. Media3 loads 3 seconds
     * for the next item, 1 second for the previous item, and metadata/tracks for
     * two more. Distant Paging items cost no network or decoder memory.
     */
    @MainThread
    fun updatePreloadWindow(
        context: Context,
        owner: Any,
        role: VideoPlaybackRole,
        sources: List<AdaptiveVideoSource>,
        focusIndex: Int,
        policy: VideoPlaybackPolicy,
    ) {
        requireMainThread()
        withSession(context, policy) {
            it.updatePreloadWindow(owner, role, sources, focusIndex, policy.allowPrefetch)
        }
    }

    @MainThread
    fun clearPreloadWindow(owner: Any) {
        requireMainThread()
        withExistingOrInitializingSession { it.clearPreloadWindow(owner) }
    }

    /** Restarts only if [source] still owns the process player. */
    @MainThread
    fun replay(source: AdaptiveVideoSource) {
        requireMainThread()
        withExistingOrInitializingSession { it.replay(source) }
    }

    /** Seeks only if [source] still owns the process player; playback intent is preserved. */
    @MainThread
    fun seekTo(source: AdaptiveVideoSource, positionMs: Long) {
        requireMainThread()
        withExistingOrInitializingSession { it.seekTo(source, positionMs) }
    }

    /** Changes audio only if [source] is still the active item. */
    @MainThread
    fun setMuted(source: AdaptiveVideoSource, muted: Boolean) {
        requireMainThread()
        withExistingOrInitializingSession { it.setMuted(source, muted) }
    }

    /** Current audio state for a same-media surface handoff, or null if inactive. */
    @MainThread
    fun isMuted(source: AdaptiveVideoSource): Boolean? {
        requireMainThread()
        return session?.isMuted(source)
    }

    /** Process lifecycle is a second gate beyond an individual Compose destination. */
    @MainThread
    fun setAppForeground(foreground: Boolean) {
        requireMainThread()
        appForeground = foreground
        withExistingOrInitializingSession { it.setAppForeground(foreground) }
    }

    /** Release the decoder and Activity-backed target view under system memory pressure. */
    @MainThread
    fun trimMemory() {
        requireMainThread()
        withExistingOrInitializingSession { it.releasePlayerForMemoryPressure() }
    }

    /** Test/debug assertion: zero before first active video, otherwise exactly one. */
    val createdPlayerCount: Int
        @MainThread get() = if (session?.hasPlayer == true) 1 else 0

    /**
     * SimpleCache scans and opens its index during construction, which Media3
     * explicitly documents as potentially slow. Queue the short-lived UI
     * operations while one process-wide cache initializes on IO, then create
     * the looper-bound preloader/player session and replay them on main.
     */
    private fun withSession(
        context: Context,
        policy: VideoPlaybackPolicy,
        operation: (PlaybackSession) -> Unit,
    ) {
        session?.let {
            operation(it)
            return
        }
        pendingOperations.addLast(operation)
        if (sessionInitializationStarted) return
        sessionInitializationStarted = true
        val appContext = context.applicationContext
        VideoEngine.loadCache(appContext, policy.cacheBytes) { cache ->
            val created = session ?: PlaybackSession(appContext, policy, cache).also {
                session = it
                it.setAppForeground(appForeground)
            }
            sessionInitializationStarted = false
            while (pendingOperations.isNotEmpty()) pendingOperations.removeFirst()(created)
        }
    }

    private fun withExistingOrInitializingSession(operation: (PlaybackSession) -> Unit) {
        session?.let {
            operation(it)
            return
        }
        if (sessionInitializationStarted) pendingOperations.addLast(operation)
    }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "VideoPlaybackCoordinator must be called on the main thread"
        }
    }
}

private data class PlaybackRequest(
    val source: AdaptiveVideoSource,
    val policy: VideoPlaybackPolicy,
    val role: VideoPlaybackRole,
    val autoplay: Boolean,
    val muted: Boolean,
    val showControls: Boolean,
    val continueExistingPlayback: Boolean,
    val lifecycleStarted: Boolean,
    val onFirstFrame: () -> Unit,
    val onPlaybackState: (VideoPlaybackSnapshot) -> Unit,
    val onError: (PlaybackException) -> Unit,
    val sequence: Long = 0,
    val resumeAfterLifecycle: Boolean = false,
)

@OptIn(UnstableApi::class)
private class FeedPreloadControl :
    TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> {
    var enabled = false
    var playbackActive = false
    var focusIndex = 0

    override fun getTargetPreloadStatus(index: Int): DefaultPreloadManager.PreloadStatus {
        if (!enabled) return DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
        return when (videoPreloadTier(index - focusIndex, playbackActive)) {
            VideoPreloadTier.Loaded -> DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(
                if (index < focusIndex) PREVIOUS_PRELOAD_MS else NEXT_PRELOAD_MS,
            )
            VideoPreloadTier.Tracks -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED
            VideoPreloadTier.Source -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_SOURCE_PREPARED
            VideoPreloadTier.None -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
        }
    }

    private companion object {
        const val NEXT_PRELOAD_MS = 3_000L
        const val PREVIOUS_PRELOAD_MS = 1_000L
    }
}

@OptIn(UnstableApi::class)
private class PlaybackSession(
    private val context: Context,
    initialPolicy: VideoPlaybackPolicy,
    cache: Cache?,
) : Player.Listener {
    private val handler = Handler(Looper.getMainLooper())
    private val preloadControl = FeedPreloadControl()
    private val cachePolicy = PlaybackCachePolicy(initialPolicy.writeCache)
    private val upstream = UnifiedTransport.mediaDataSourceFactory(context)
    private val loadControl = DefaultLoadControl.Builder()
        // The ExoPlayer is process scoped while policy can change with network conditions.
        // Keep construction-time memory bounded; current bitrate and cache-write policy remain dynamic.
        .setBufferDurationsMs(5_000, MAX_FORWARD_BUFFER_MS, 1_000, 1_500)
        .setBackBuffer(5_000, true)
        // Bound aggregate SampleQueue memory used by nearby preloads.
        .setPlayerTargetBufferBytes(PlayerId.PRELOAD.name, 12 * MIB)
        .build()
    private val builder = DefaultPreloadManager.Builder(context, preloadControl)
        .setDataSourceFactory(upstream)
        .setLoadControl(loadControl)
        .setRenderersFactory(DefaultRenderersFactory(context))
        .apply {
            // Playback still works directly from the shared network engine if
            // disk cache initialization fails or storage is critically low.
            cache?.let {
                setCache(it)
                // Media3 immediately copies the builder's current data source/cache into a custom
                // supplier. Configure those values first so the supplier never observes null here.
                setMediaSourceFactorySupplier(
                    VideoMediaSourceFactorySupplier(context, upstream, it, cachePolicy::mayWrite),
                )
            }
        }
    private val preloadManager = builder.build()

    private var playerOrNull: ExoPlayer? = null
    private val requests = LinkedHashMap<Any, PlaybackRequest>()
    private val views = LinkedHashMap<Any, PlayerView>()
    private val preloadRequests = LinkedHashMap<Any, PreloadRequest>()
    private val tracked = LinkedHashMap<String, TrackedMedia>()
    private var sequence = 0L
    private var preloadSequence = 0L
    private var winner: Any? = null
    private var attachedView: PlayerView? = null
    private var currentSource: AdaptiveVideoSource? = null
    private var attemptedFallback = false
    private var firstFrameTimer: PerformanceTimer? = null
    private var rebufferTimer: PerformanceTimer? = null
    private var activePlayback: ActivePlayback? = null
    private var appForeground = true
    var renderedMediaKey: String? = null
        private set

    private val publishProgress = Runnable { notifyWinner() }

    val hasPlayer: Boolean get() = playerOrNull != null

    private val pauseIfIdle = Runnable {
        if (requests.isEmpty()) playerOrNull?.pause()
    }
    private val stopIfIdle = Runnable {
        if (requests.isEmpty()) {
            playerOrNull?.clearMediaItems()
            currentSource = null
            renderedMediaKey = null
            if (preloadRequests.isEmpty()) {
                preloadManager.reset()
                tracked.clear()
            }
        }
    }
    private val detachStaleView = Runnable {
        if (views.values.none { it === attachedView }) switchView(null)
    }
    private val pauseIfLifecyclePending = Runnable {
        val request = winner?.let(requests::get)
        if (request != null && !request.lifecycleStarted) playerOrNull?.pause()
    }

    fun attach(owner: Any, request: PlaybackRequest) {
        handler.removeCallbacks(pauseIfIdle)
        handler.removeCallbacks(stopIfIdle)
        val previous = requests[owner]
        val playIntentChanged = previous == null ||
            previous.source.stableKey != request.source.stableKey ||
            previous.autoplay != request.autoplay ||
            previous.lifecycleStarted != request.lifecycleStarted
        requests[owner] = request.copy(
            // Recomposition updates callbacks and policy but must not make an
            // existing same-priority owner look newer than another surface.
            sequence = previous?.sequence ?: ++sequence,
            resumeAfterLifecycle = previous?.resumeAfterLifecycle ?: false,
        )
        reconcile(applyPlayIntent = playIntentChanged)
    }

    fun detach(owner: Any) {
        val detachedView = views.remove(owner)
        requests.remove(owner)
        reconcile()
        if (detachedView != null && attachedView === detachedView && winner?.let(views::get) == null) {
            handler.postDelayed(detachStaleView, HANDOFF_GRACE_MS)
        }
        if (requests.isEmpty()) {
            // Navigation may briefly dispose the source before composing the
            // destination. Grace keeps playback and the last decoded frame alive.
            handler.postDelayed(pauseIfIdle, HANDOFF_GRACE_MS)
            handler.postDelayed(stopIfIdle, IDLE_MEDIA_RELEASE_MS)
        }
    }

    fun bindView(owner: Any, view: PlayerView) {
        views[owner] = view
        handler.removeCallbacks(detachStaleView)
        configureView(view, requests[owner])
        reconcile()
    }

    fun unbindView(owner: Any, view: PlayerView) {
        if (views[owner] === view) views.remove(owner)
        if (attachedView === view) {
            val replacement = winner?.let(views::get)
            if (replacement != null) switchView(replacement)
            else handler.postDelayed(detachStaleView, HANDOFF_GRACE_MS)
        }
    }

    fun setLifecycleStarted(owner: Any, started: Boolean) {
        val existing = requests[owner] ?: return
        if (existing.lifecycleStarted != started) {
            if (started) handler.removeCallbacks(pauseIfLifecyclePending)
            val shouldResume = if (!started && winner === owner) {
                playerOrNull?.playWhenReady == true
            } else {
                existing.resumeAfterLifecycle
            }
            requests[owner] = existing.copy(
                lifecycleStarted = started,
                resumeAfterLifecycle = shouldResume,
            )
            reconcile(applyPlayIntent = true)
        }
    }

    fun updatePreloadWindow(
        owner: Any,
        role: VideoPlaybackRole,
        sources: List<AdaptiveVideoSource>,
        requestedFocusIndex: Int,
        enabled: Boolean,
    ) {
        if (!enabled || sources.isEmpty()) {
            preloadRequests.remove(owner)
        } else {
            val previous = preloadRequests[owner]
            preloadRequests[owner] = PreloadRequest(
                role = role,
                sources = sources,
                focusIndex = requestedFocusIndex,
                sequence = previous?.sequence ?: ++preloadSequence,
            )
        }
        reconcilePreloads()
    }

    fun clearPreloadWindow(owner: Any) {
        preloadRequests.remove(owner)
        reconcilePreloads()
    }

    fun replay(source: AdaptiveVideoSource) {
        if (currentSource?.stableKey != source.stableKey) return
        val player = playerOrNull ?: return
        player.seekTo(0L)
        if (player.playerError != null || player.playbackState == Player.STATE_IDLE) {
            player.prepare()
        }
        player.play()
        notifyWinner()
    }

    fun seekTo(source: AdaptiveVideoSource, positionMs: Long) {
        if (currentSource?.stableKey != source.stableKey) return
        val player = playerOrNull ?: return
        player.seekTo(clampVideoSeekPosition(positionMs, player.duration))
        notifyWinner()
    }

    fun setMuted(source: AdaptiveVideoSource, muted: Boolean) {
        if (currentSource?.stableKey != source.stableKey) return
        playerOrNull?.volume = if (muted) 0f else 1f
    }

    fun isMuted(source: AdaptiveVideoSource): Boolean? =
        playerOrNull?.takeIf { currentSource?.stableKey == source.stableKey }?.volume?.let { it == 0f }

    fun setAppForeground(foreground: Boolean) {
        if (appForeground == foreground) return
        appForeground = foreground
        if (!foreground) {
            playerOrNull?.pause()
            flushPlayback(ProductEventReason.PAUSE)
            disablePreloadWindow()
            scheduleProgressPublication()
        } else {
            reconcilePreloads()
            reconcile(applyPlayIntent = true)
        }
    }

    fun releasePlayerForMemoryPressure() {
        val restoreVisiblePlayback = appForeground && requests.values.any(PlaybackRequest::lifecycleStarted)
        handler.removeCallbacks(pauseIfIdle)
        handler.removeCallbacks(stopIfIdle)
        handler.removeCallbacks(detachStaleView)
        handler.removeCallbacks(pauseIfLifecyclePending)
        handler.removeCallbacks(publishProgress)
        flushPlayback(ProductEventReason.PAUSE)
        switchView(null)
        playerOrNull?.removeListener(this)
        playerOrNull?.release()
        playerOrNull = null
        currentSource = null
        renderedMediaKey = null
        firstFrameTimer = null
        rebufferTimer = null
        preloadManager.reset()
        tracked.clear()
        preloadControl.enabled = false
        // A system trim can race Activity recreation. If the replacement owner
        // is already visible, immediately rebuild and rebind the one shared
        // player; otherwise its next lifecycle/attach transition does so.
        if (restoreVisiblePlayback) reconcile(applyPlayIntent = true)
    }

    private fun reconcilePreloads() {
        if (!appForeground) {
            disablePreloadWindow()
            return
        }
        val selected = preloadRequests.values.maxWithOrNull(
            compareBy<PreloadRequest> { it.role.priority }.thenBy(PreloadRequest::sequence),
        )
        if (selected == null) {
            disablePreloadWindow()
            return
        }
        applyPreloadWindow(selected.sources, selected.focusIndex)
    }

    private fun applyPreloadWindow(
        sources: List<AdaptiveVideoSource>,
        requestedFocusIndex: Int,
    ) {
        val playable = sources.distinctBy(AdaptiveVideoSource::stableKey)
        if (playable.isEmpty()) {
            disablePreloadWindow()
            return
        }
        val focus = deduplicatedVideoFocusIndex(sources, requestedFocusIndex)
            .coerceIn(playable.indices)
        preloadControl.enabled = true
        preloadControl.focusIndex = focus
        preloadControl.playbackActive = requests.isNotEmpty()

        val window = (focus - 2).coerceAtLeast(0)..(focus + 6).coerceAtMost(playable.lastIndex)
        val wanted = window.map { index ->
            val source = playable[index]
            TrackedMedia(source, source.toMediaItem(), index)
        }

        // A refresh may reorder ranks. Reset only while no MediaSource is in use;
        // an active player keeps its source and receives missing neighbors only.
        val rankingChanged = wanted.any { tracked[it.source.stableKey]?.ranking != it.ranking }
        if (requests.isEmpty() && currentSource == null && rankingChanged) {
            preloadManager.reset()
            tracked.clear()
        }
        val wantedKeys = wanted.mapTo(mutableSetOf(), transform = { it.source.stableKey })
        val removable = tracked.values.filter {
            it.source.stableKey !in wantedKeys && it.source.stableKey != currentSource?.stableKey
        }
        if (removable.isNotEmpty()) {
            preloadManager.removeMediaItems(removable.map(TrackedMedia::item))
            removable.forEach { tracked.remove(it.source.stableKey) }
        }
        val additions = wanted.filter { it.source.stableKey !in tracked }
        additions.forEach { tracked[it.source.stableKey] = it }
        if (additions.isNotEmpty()) {
            preloadManager.addMediaItems(additions.map(TrackedMedia::item), additions.map(TrackedMedia::ranking))
        }
        preloadManager.setCurrentPlayingIndex(focus)
    }

    private fun disablePreloadWindow() {
        preloadControl.enabled = false
        preloadManager.invalidate()
        val removable = tracked.values.filter { it.source.stableKey != currentSource?.stableKey }
        if (removable.isNotEmpty()) {
            preloadManager.removeMediaItems(removable.map(TrackedMedia::item))
            removable.forEach { tracked.remove(it.source.stableKey) }
        }
    }

    private fun reconcile(applyPlayIntent: Boolean = false) {
        val oldWinner = winner
        val next = requests.maxWithOrNull(
            compareBy<Map.Entry<Any, PlaybackRequest>> { it.value.role.priority }
                .thenBy { it.value.sequence },
        )
        winner = next?.key
        val request = next?.value
        preloadControl.playbackActive = request != null

        if (request == null || !appForeground) {
            scheduleProgressPublication()
            return
        }
        val player = player()
        val sameMedia = currentSource?.stableKey == request.source.stableKey
        val transferring = oldWinner != winner && sameMedia && request.continueExistingPlayback

        applyPolicy(player, request.policy)
        // A focused immersive-feed clip must never decay into a black ended
        // surface. Feed/detail retain ordinary one-shot playback semantics.
        player.repeatMode = videoRepeatMode(request.role)
        if (!sameMedia) switchMedia(player, request.source)

        if (!request.lifecycleStarted) {
            val handoffPending = player.playWhenReady &&
                (request.resumeAfterLifecycle ||
                    (request.continueExistingPlayback && transferring))
            if (handoffPending) {
                // Navigation may stop the outgoing feed before composing/starting detail. Keep
                // the visible shared video running across that short gap and retain its intent.
                if (transferring) {
                    winner?.let {
                        requests[it] = request.copy(resumeAfterLifecycle = true)
                    }
                }
                handler.removeCallbacks(pauseIfLifecyclePending)
                handler.postDelayed(pauseIfLifecyclePending, NAVIGATION_LIFECYCLE_GRACE_MS)
            } else {
                player.playWhenReady = false
            }
        } else {
            handler.removeCallbacks(pauseIfLifecyclePending)
            if (!transferring && (!sameMedia || oldWinner != winner || applyPlayIntent)) {
                player.playWhenReady = request.autoplay || request.resumeAfterLifecycle
                if (request.resumeAfterLifecycle) {
                    winner?.let { requests[it] = request.copy(resumeAfterLifecycle = false) }
                }
            }
        }
        // A muted feed must not suddenly make sound simply because its video
        // expanded. Preserve volume for a true same-media handoff.
        if (!transferring) player.volume = if (request.muted) 0f else 1f

        if (player.isPlaying && activePlayback?.surface != request.role.productSurface) {
            flushPlayback(ProductEventReason.SURFACE_CHANGE)
            startPlayback()
        }

        winner?.let(views::get)?.let { target ->
            val suppressController = configureView(target, request)
            switchView(target)
            // switchTargetView attaches the player after configuration. Hide once more so a
            // controller visibility update triggered by that attachment cannot reach a frame.
            if (suppressController) target.hideController()
        }
        if (renderedMediaKey == request.source.stableKey) request.onFirstFrame()
        notifyWinner()
    }

    private fun player(): ExoPlayer = playerOrNull ?: builder.buildExoPlayer().apply {
        repeatMode = Player.REPEAT_MODE_OFF
        addListener(this@PlaybackSession)
    }.also { playerOrNull = it }

    private fun switchMedia(player: ExoPlayer, source: AdaptiveVideoSource) {
        flushPlayback(ProductEventReason.MEDIA_CHANGE)
        // A new source must clear the previous clip's TextureView frame immediately. Same-source
        // navigation handoffs are handled separately by configureView after identity is known.
        attachedView?.setKeepContentOnPlayerReset(false)
        currentSource = source
        renderedMediaKey = null
        attemptedFallback = false
        firstFrameTimer = performanceTimer()
        rebufferTimer = null
        val trackedSource = tracked[source.stableKey]
        val mediaSource = trackedSource?.let { preloadManager.getMediaSource(it.item) }
        if (mediaSource != null) player.setMediaSource(mediaSource) else player.setMediaItem(source.toMediaItem())
        player.prepare()
        trackedSource?.let {
            preloadControl.focusIndex = it.ranking
            preloadManager.setCurrentPlayingIndex(it.ranking)
        }
    }

    private fun switchView(target: PlayerView?) {
        val player = playerOrNull ?: return
        if (attachedView === target) return
        PlayerView.switchTargetView(player, attachedView, target)
        attachedView = target
    }

    private fun configureView(view: PlayerView, request: PlaybackRequest?): Boolean {
        val showControls = request?.showControls == true
        val player = playerOrNull
        val suppressController = request != null && suppressVideoControllerAutoShow(
            showControls = showControls,
            continueExistingPlayback = request.continueExistingPlayback,
            sameMedia = currentSource?.stableKey == request.source.stableKey,
            playWhenReady = player?.playWhenReady == true,
            playbackEnded = player?.playbackState == Player.STATE_ENDED,
        )
        // Set the policy before enabling the controller: setUseController(true) may immediately
        // evaluate visibility against the attached player's current playing state.
        view.controllerAutoShow = showControls && !suppressController
        view.useController = showControls
        if (suppressController) view.hideController()
        // Retain content only for an already-rendered same-source handoff. Keeping content across
        // a MediaSource change leaks the previous clip's last frame into the next feed cell.
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        view.setKeepContentOnPlayerReset(
            keepContentOnPlayerReset(request?.source?.stableKey, renderedMediaKey),
        )
        return suppressController
    }

    private fun applyPolicy(player: ExoPlayer, policy: VideoPlaybackPolicy) {
        cachePolicy.writeEnabled = policy.writeCache
        val parameters = player.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(Int.MAX_VALUE, policy.maxVideoHeight)
        if (policy.preferredPeakBitrate > 0) {
            parameters.setMaxVideoBitrate(
                policy.preferredPeakBitrate.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            )
        }
        player.trackSelectionParameters = parameters.build()
    }

    override fun onRenderedFirstFrame() {
        val key = currentSource?.stableKey ?: return
        if (renderedMediaKey != key) {
            renderedMediaKey = key
            firstFrameTimer?.let { timer ->
                PerformanceTelemetry.duration(
                    PerformanceMetric.VIDEO_TIME_TO_FIRST_FRAME,
                    timer,
                    attributes = mapOf("content_kind" to "video"),
                )
            }
            firstFrameTimer = null
        }
        winner?.let(requests::get)?.onFirstFrame?.invoke()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        // Natural completion restores PlayerView's normal replay controls. BUFFERING/READY during
        // a continued handoff keeps auto-show suppressed, while manual tap-to-show remains active.
        winner?.let { owner ->
            val request = requests[owner]
            val view = views[owner]
            if (request != null && view != null) configureView(view, request)
        }
        if (renderedMediaKey != null && playbackState == Player.STATE_BUFFERING && rebufferTimer == null) {
            rebufferTimer = performanceTimer()
        } else if (playbackState == Player.STATE_READY) {
            rebufferTimer?.let { timer ->
                PerformanceTelemetry.duration(
                    PerformanceMetric.VIDEO_REBUFFER,
                    timer,
                    attributes = mapOf("content_kind" to "video"),
                )
            }
            rebufferTimer = null
        }
        if (playbackState == Player.STATE_ENDED) flushPlayback(ProductEventReason.ENDED)
        notifyWinner()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) startPlayback() else flushPlayback(ProductEventReason.PAUSE)
        notifyWinner()
    }

    override fun onPlayerError(error: PlaybackException) {
        flushPlayback(ProductEventReason.ERROR)
        val source = currentSource ?: return
        val fallback = source.fallbackUrl
        if (!attemptedFallback && fallback != null && fallback != source.hlsUrl) {
            attemptedFallback = true
            currentSource = source.copy(hlsUrl = null)
            firstFrameTimer = performanceTimer()
            player().setMediaItem(currentSource!!.toMediaItem())
            player().prepare()
        } else {
            winner?.let(requests::get)?.onError?.invoke(error)
        }
        winner?.let(requests::get)?.onPlaybackState?.invoke(playbackSnapshot(VideoPlaybackState.Error))
    }

    private fun notifyWinner() {
        winner?.let(requests::get)?.onPlaybackState?.invoke(playbackSnapshot())
        scheduleProgressPublication()
    }

    /**
     * Media3 reports state transitions but not a continuous playhead. Re-publish the existing
     * player snapshot while it is actually advancing so shared Compose transport controls stay
     * synchronized without polling from every feed cell.
     */
    private fun scheduleProgressPublication() {
        handler.removeCallbacks(publishProgress)
        if (shouldPublishVideoProgress(
                hasWinner = winner?.let(requests::containsKey) == true,
                appForeground = appForeground,
                isPlaying = playerOrNull?.isPlaying == true,
            )
        ) {
            handler.postDelayed(publishProgress, PLAYBACK_PROGRESS_INTERVAL_MS)
        }
    }

    private fun playbackSnapshot(forced: VideoPlaybackState? = null): VideoPlaybackSnapshot {
        val player = playerOrNull
        val duration = player?.duration?.takeIf { it > 0L && it != C.TIME_UNSET }
        val state = forced ?: when {
            player == null || player.playbackState == Player.STATE_IDLE -> VideoPlaybackState.Idle
            player.playbackState == Player.STATE_BUFFERING -> VideoPlaybackState.Buffering
            player.playbackState == Player.STATE_ENDED -> VideoPlaybackState.Ended
            player.isPlaying -> VideoPlaybackState.Playing
            player.playbackState == Player.STATE_READY && player.playWhenReady -> VideoPlaybackState.Ready
            player.playbackState == Player.STATE_READY -> VideoPlaybackState.Paused
            else -> VideoPlaybackState.Idle
        }
        return VideoPlaybackSnapshot(
            state = state,
            positionMs = player?.currentPosition?.coerceAtLeast(0L) ?: 0L,
            durationMs = duration,
        )
    }

    private data class TrackedMedia(
        val source: AdaptiveVideoSource,
        val item: MediaItem,
        val ranking: Int,
    )

    private data class PreloadRequest(
        val role: VideoPlaybackRole,
        val sources: List<AdaptiveVideoSource>,
        val focusIndex: Int,
        val sequence: Long,
    )

    private data class ActivePlayback(
        val contentId: String?,
        val surface: ProductSurface,
        val startedElapsedMs: Long,
        val startedPositionMs: Long,
    )

    private fun startPlayback() {
        if (activePlayback != null) return
        val player = playerOrNull ?: return
        val request = winner?.let(requests::get) ?: return
        activePlayback = ActivePlayback(
            // Never fall back to a signed URL. Server-provided media always has
            // a stable cache key; synthetic/demo media remains aggregate-only.
            contentId = currentSource?.cacheKey,
            surface = request.role.productSurface,
            startedElapsedMs = SystemClock.elapsedRealtime(),
            startedPositionMs = player.currentPosition.coerceAtLeast(0L),
        )
    }

    private fun flushPlayback(reason: ProductEventReason) {
        val active = activePlayback ?: return
        activePlayback = null
        val playedMs = (SystemClock.elapsedRealtime() - active.startedElapsedMs).coerceAtLeast(0L)
        if (playedMs < MIN_PLAYBACK_EVENT_MS) return
        val player = playerOrNull
        val mediaDuration = player?.duration?.takeIf { it > 0L && it != C.TIME_UNSET }
        val completion = mediaDuration?.let { duration ->
            ((player.currentPosition.coerceAtLeast(0L) * 100.0) / duration).coerceIn(0.0, 100.0)
        }
        ProductAnalytics.record(ProductEvent(
            name = ProductEventName.MEDIA_PLAYBACK,
            surface = active.surface,
            contentId = active.contentId,
            contentType = ProductContentType.VIDEO,
            reason = reason,
            durationMs = playedMs,
            position = active.startedPositionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            completionPercent = completion,
        ))
    }

    private companion object {
        const val MIB = 1024 * 1024
        const val MAX_FORWARD_BUFFER_MS = 20_000
        const val HANDOFF_GRACE_MS = 500L
        const val NAVIGATION_LIFECYCLE_GRACE_MS = 750L
        const val IDLE_MEDIA_RELEASE_MS = 30_000L
        const val MIN_PLAYBACK_EVENT_MS = 100L
        const val PLAYBACK_PROGRESS_INTERVAL_MS = 100L
    }
}

internal fun clampVideoSeekPosition(requestedMs: Long, durationMs: Long): Long {
    val nonNegative = requestedMs.coerceAtLeast(0L)
    return if (durationMs == C.TIME_UNSET || durationMs <= 0L) {
        nonNegative
    } else {
        nonNegative.coerceAtMost(durationMs)
    }
}

internal fun keepContentOnPlayerReset(requestSourceKey: String?, renderedMediaKey: String?): Boolean =
    requestSourceKey != null && requestSourceKey == renderedMediaKey

private val VideoPlaybackRole.productSurface: ProductSurface
    get() = when (this) {
        VideoPlaybackRole.Feed -> ProductSurface.FEED
        VideoPlaybackRole.MediaFeed -> ProductSurface.MEDIA
        VideoPlaybackRole.Detail -> ProductSurface.DETAIL
        VideoPlaybackRole.AdDetail -> ProductSurface.AD_DETAIL
    }

/** A fresh MediaSource.Factory per Media3 player/preloader, backed by one LRU. */
@OptIn(UnstableApi::class)
private class VideoMediaSourceFactorySupplier(
    private val context: Context,
    upstream: DataSource.Factory,
    cache: Cache,
    private val mayWrite: () -> Boolean,
) : MediaSourceFactorySupplier {
    private var upstreamFactory = upstream
    private var cacheInstance = cache

    override fun get() = DefaultMediaSourceFactory(context).setDataSourceFactory(
        VideoEngine.playbackDataSourceFactory(upstreamFactory, cacheInstance, mayWrite),
    )

    override fun setCache(cache: Cache?): MediaSourceFactorySupplier = apply {
        // The Java API is nullable and may send null while a builder is only partially
        // configured. Retain the valid constructor fallback until a real cache is supplied.
        if (cache != null) cacheInstance = cache
    }

    override fun setDataSourceFactory(factory: DataSource.Factory?): MediaSourceFactorySupplier = apply {
        if (factory != null) upstreamFactory = factory
    }
}

private class PlaybackCachePolicy(@Volatile var writeEnabled: Boolean) {
    fun mayWrite(): Boolean = writeEnabled
}

/** WorkManager entry point: one startup video, first two seconds, disk only. */
@OptIn(UnstableApi::class)
object VideoStartupPrefetcher {
    const val STARTUP_DURATION_MS = 2_000L
    const val MAX_STARTUP_VIDEOS = 1

    suspend fun prefetch(
        context: Context,
        sources: List<AdaptiveVideoSource>,
        cacheBytes: Long,
    ): Int {
        var completed = 0
        for (source in sources.distinctBy(AdaptiveVideoSource::stableKey).take(MAX_STARTUP_VIDEOS)) {
            val ok = withTimeoutOrNull(PREFETCH_TIMEOUT_MS) {
                preCacheOne(context.applicationContext, source, cacheBytes)
            } ?: false
            if (ok) completed++
        }
        return completed
    }

    private suspend fun preCacheOne(
        context: Context,
        source: AdaptiveVideoSource,
        cacheBytes: Long,
    ): Boolean {
        val cache = VideoEngine.cache(context, cacheBytes) ?: return false
        return suspendCancellableCoroutine { continuation ->
            val handler = VideoEngine.preCacheHandler()
            var helper: PreCacheHelper? = null
            continuation.invokeOnCancellation {
                handler.post {
                    helper?.stop()
                    helper?.release(false)
                    helper = null
                }
            }
            handler.post {
                if (!continuation.isActive) return@post
                fun finish(result: Boolean) {
                    helper?.release(false)
                    helper = null
                    if (continuation.isActive) continuation.resume(result)
                }
                val listener = object : PreCacheHelper.Listener {
                    override fun onPreCacheCompleted(mediaItem: MediaItem) = finish(true)
                    override fun onPrepareError(mediaItem: MediaItem, error: java.io.IOException) = finish(false)
                    override fun onDownloadError(mediaItem: MediaItem, error: java.io.IOException) = finish(false)
                }
                try {
                    val factory = PreCacheHelper.Factory(
                        cache,
                        UnifiedTransport.mediaDataSourceFactory(context),
                        DefaultRenderersFactory(context),
                        handler.looper,
                    ).setListener(listener)
                    helper = factory.create(source.toMediaItem())
                    helper?.preCache(0L, STARTUP_DURATION_MS)
                } catch (_: Exception) {
                    finish(false)
                }
            }
        }
    }

    private const val PREFETCH_TIMEOUT_MS = 20_000L
}

@OptIn(UnstableApi::class)
private object VideoEngine {
    private val lock = Any()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var cacheDeferred: Deferred<SimpleCache?>? = null
    private val backgroundPreCacheHandler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        HandlerThread("video-precache", Process.THREAD_PRIORITY_BACKGROUND).run {
            start()
            Handler(looper)
        }
    }

    fun preCacheHandler(): Handler = backgroundPreCacheHandler

    fun loadCache(context: Context, maxBytes: Long, onReady: (Cache?) -> Unit) {
        val deferred = deferredCache(context, maxBytes)
        ioScope.launch {
            val cache = deferred.await()
            withContext(Dispatchers.Main.immediate) { onReady(cache) }
        }
    }

    suspend fun cache(context: Context, maxBytes: Long): SimpleCache? =
        deferredCache(context, maxBytes).await()

    private fun deferredCache(context: Context, maxBytes: Long): Deferred<SimpleCache?> =
        cacheDeferred ?: synchronized(lock) {
            cacheDeferred ?: ioScope.async {
                if (maxBytes <= 0L) return@async null
                runCatching {
                    // SimpleCache construction can scan the directory and must
                    // stay off the main thread. The first caller fixes the one
                    // process-wide LRU capacity for this process.
                    SimpleCache(
                        context.applicationContext.cacheDir.resolve("hls-segments"),
                        LeastRecentlyUsedCacheEvictor(maxBytes),
                        StandaloneDatabaseProvider(context.applicationContext),
                    )
                }.getOrNull()
            }.also { cacheDeferred = it }
    }

    fun playbackDataSourceFactory(
        upstream: DataSource.Factory,
        cache: Cache,
        mayWrite: () -> Boolean,
    ): DataSource.Factory {
        val readWrite = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val readOnly = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return DataSource.Factory {
            ManifestBypassDataSource(
                upstream.createDataSource(),
                if (mayWrite()) readWrite.createDataSource() else readOnly.createDataSource(),
            )
        }
    }
}

/** Dynamic Cloudflare manifests bypass disk; immutable media segments use LRU. */
@OptIn(UnstableApi::class)
private class ManifestBypassDataSource(
    private val upstream: DataSource,
    private val cached: DataSource,
) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
        cached.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val path = dataSpec.uri.path.orEmpty()
        active = if (path.endsWith(".m3u8", true) || path.endsWith(".mpd", true)) upstream else cached
        return requireNotNull(active).open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri() = active?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders.orEmpty()
    override fun close() {
        active?.close()
        active = null
    }
}

internal val AdaptiveVideoSource.stableKey: String
    get() = cacheKey ?: hlsUrl ?: fallbackUrl.orEmpty()

internal fun AdaptiveVideoSource.toMediaItem(): MediaItem {
    val url = hlsUrl ?: fallbackUrl ?: error("AdaptiveVideoSource has no playable URL")
    return MediaItem.Builder()
        .setMediaId(stableKey)
        .setUri(url)
        .apply {
            if (URI(url).path.endsWith(".m3u8", ignoreCase = true)) {
                setMimeType(MimeTypes.APPLICATION_M3U8)
            }
        }
        .build()
}
