package dev.readthat.media.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitViewController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.readthat.shared.AppSettings
import dev.readthat.shared.ConnectionKind
import dev.readthat.shared.DeviceTier
import dev.readthat.shared.PostMedia
import dev.readthat.shared.VideoPlaybackPolicy
import dev.readthat.shared.VideoPolicyResolver
import dev.readthat.shared.deviceTierForPhysicalMemory
import dev.readthat.observability.PerformanceMetric
import dev.readthat.observability.PerformanceSurface
import dev.readthat.observability.PerformanceTelemetry
import dev.readthat.observability.PerformanceTimer
import dev.readthat.observability.ProductAnalytics
import dev.readthat.observability.ProductContentType
import dev.readthat.observability.ProductEvent
import dev.readthat.observability.ProductEventName
import dev.readthat.observability.ProductEventReason
import dev.readthat.observability.ProductSurface
import dev.readthat.observability.performanceTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.AVURLAssetAllowsConstrainedNetworkAccessKey
import platform.AVFoundation.AVURLAssetAllowsExpensiveNetworkAccessKey
import platform.AVFoundation.cancelLoading
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.loadValuesAsynchronouslyForKeys
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.setAutomaticallyWaitsToMinimizeStalling
import platform.AVFoundation.setCanUseNetworkResourcesForLiveStreamingWhilePaused
import platform.AVFoundation.setMuted
import platform.AVFoundation.setPreferredForwardBufferDuration
import platform.AVFoundation.setPreferredPeakBitRate
import platform.AVFoundation.timeControlStatus
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVKit.AVPlayerViewController
import platform.Foundation.NSURL
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Network.nw_interface_type_cellular
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_constrained
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.Network.nw_path_uses_interface_type
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import platform.darwin.NSObjectProtocol
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeGetSeconds
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidReceiveMemoryWarningNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import kotlin.time.TimeMark
import kotlin.time.TimeSource

@Composable
@OptIn(ExperimentalForeignApi::class)
actual fun PlatformMediaLifecycle(onMemoryPressure: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnMemoryPressure = rememberUpdatedState(onMemoryPressure)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> IosVideoPlaybackCoordinator.setAppForeground(true)
                Lifecycle.Event.ON_STOP -> IosVideoPlaybackCoordinator.setAppForeground(false)
                else -> Unit
            }
        }
        val center = NSNotificationCenter.defaultCenter
        val memoryObserver = center.addObserverForName(
            UIApplicationDidReceiveMemoryWarningNotification,
            null,
            NSOperationQueue.mainQueue,
        ) {
            currentOnMemoryPressure.value()
            IosVideoPlaybackCoordinator.trimMemory()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        IosVideoPlaybackCoordinator.setAppForeground(
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
        )
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            center.removeObserver(memoryObserver)
            IosVideoPlaybackCoordinator.setAppForeground(false)
        }
    }
}

/** AVPlayer keeps native HLS, QUIC/HTTP-3, FairPlay and route handling on iOS. */
@Composable
@OptIn(ExperimentalForeignApi::class)
actual fun PlatformVideoPlayer(
    media: PostMedia,
    settings: AppSettings,
    autoplay: Boolean,
    userInitiatedPlayback: Boolean,
    muted: Boolean,
    showControls: Boolean,
    role: PlatformVideoRole,
    continueExistingPlayback: Boolean,
    replayRequest: Int,
    seekRequest: PlatformVideoSeekRequest?,
    modifier: Modifier,
    onFirstFrame: () -> Unit,
    onPlaybackState: (PlatformPlaybackSnapshot) -> Unit,
) {
    val urls = remember(media.hlsUrl, media.fallbackUrl, media.url) { media.secureVideoUrls() }
    val url = urls.primary ?: return
    val policy = rememberPlatformVideoPlaybackPolicy(settings)
    val playWhenReady = shouldRequestNativePlayback(
        playRequested = autoplay,
        autoplayAllowed = policy.autoplay,
        userInitiatedPlayback = userInitiatedPlayback,
    )
    val currentOnPlaybackState = rememberUpdatedState(onPlaybackState)
    var playerAcquired by remember(url, role) { mutableStateOf(false) }
    // Avoid constructing AVPlayerItem while autoplay is suppressed. MediaFeed begins acquiring
    // after explicit Play and retains its lease across a later manual pause.
    if (!shouldAcquireNativePlayer(role, playWhenReady, userInitiatedPlayback, playerAcquired)) {
        LaunchedEffect(url, role) {
            currentOnPlaybackState.value(PlatformPlaybackSnapshot(
                state = PlatformPlaybackState.Paused,
                positionMs = 0L,
                durationMs = media.durationSeconds?.times(1_000L),
            ))
        }
        return
    }
    SideEffect { playerAcquired = true }
    val owner = remember { Any() }
    val controller = remember {
        AVPlayerViewController().apply { player = IosVideoPlaybackCoordinator.player }
    }
    val firstFrameTimer = remember(url) { performanceTimer() }
    val playbackAnalytics = remember(url, role, media.cacheKey, media.durationSeconds) {
        IosPlaybackAnalytics(
            contentId = media.cacheKey,
            surface = role.productSurface,
            mediaDurationMs = media.durationSeconds?.times(1_000L),
        )
    }
    var reportedFirstFrame by remember(url) { mutableStateOf(false) }
    var rebufferTimer by remember(owner, url) { mutableStateOf<PerformanceTimer?>(null) }
    LaunchedEffect(
        owner,
        url,
        urls.fallback,
        policy,
        playWhenReady,
        muted,
        role,
        continueExistingPlayback,
        playbackAnalytics,
    ) {
        IosVideoPlaybackCoordinator.attach(
            owner,
            url,
            urls.fallback,
            policy,
            role,
            playWhenReady,
            muted,
            continueExistingPlayback,
            durationHintMs = media.durationSeconds?.times(1_000L),
            onPlaybackSnapshot = { snapshot ->
                val playbackState = snapshot.state
                if (playbackState == PlatformPlaybackState.Buffering && reportedFirstFrame) {
                    if (rebufferTimer == null) rebufferTimer = performanceTimer()
                } else if (playbackState == PlatformPlaybackState.Playing) {
                    rebufferTimer?.let { timer ->
                        PerformanceTelemetry.duration(
                            PerformanceMetric.VIDEO_REBUFFER,
                            timer,
                            surface = role.performanceSurface,
                            attributes = mapOf("content_kind" to "video"),
                        )
                    }
                    rebufferTimer = null
                } else {
                    rebufferTimer = null
                }
                playbackAnalytics.update(
                    state = playbackState,
                    positionMs = snapshot.positionMs,
                )
                currentOnPlaybackState.value(snapshot)
            },
            onTerminalFailure = {
                val positionMs = IosVideoPlaybackCoordinator.player.positionMillis()
                val durationMs = IosVideoPlaybackCoordinator.player.durationMillis()
                    ?: media.durationSeconds?.times(1_000L)
                playbackAnalytics.finish(ProductEventReason.ERROR, positionMs)
                currentOnPlaybackState.value(PlatformPlaybackSnapshot(
                    PlatformPlaybackState.Error,
                    positionMs,
                    durationMs,
                ))
            },
        )
    }
    LaunchedEffect(owner, url, replayRequest) {
        if (replayRequest > 0) {
            withFrameNanos { }
            IosVideoPlaybackCoordinator.replay(owner, url)
        }
    }
    LaunchedEffect(owner, url, seekRequest) {
        seekRequest?.let { request ->
            // Let an autoplay-disabled manual seek acquire and attach AVPlayer first.
            withFrameNanos { }
            playbackAnalytics.seek(request.positionMs)
            IosVideoPlaybackCoordinator.seekTo(owner, url, request.positionMs)
        }
    }
    SideEffect { controller.showsPlaybackControls = showControls }
    LaunchedEffect(controller, owner, url) {
        while (!reportedFirstFrame) {
            // Suspend without a timer while a higher-priority surface owns the process player.
            IosVideoPlaybackCoordinator.awaitOwnership(owner, url)
            withFrameNanos { }
            if (IosVideoPlaybackCoordinator.owns(owner, url) && controller.readyForDisplay) {
                reportedFirstFrame = true
                IosVideoPlaybackCoordinator.markRendered(url)
                PerformanceTelemetry.duration(
                    PerformanceMetric.VIDEO_TIME_TO_FIRST_FRAME,
                    firstFrameTimer,
                    surface = when (role) {
                        PlatformVideoRole.Feed -> PerformanceSurface.FEED
                        PlatformVideoRole.AdFeed -> PerformanceSurface.FEED
                        PlatformVideoRole.MediaFeed -> PerformanceSurface.MEDIA
                        PlatformVideoRole.Detail -> PerformanceSurface.DETAIL
                        PlatformVideoRole.AdDetail -> PerformanceSurface.DETAIL
                    },
                    attributes = mapOf(
                        "content_kind" to "video",
                        "phase" to role.name.lowercase(),
                    ),
                )
                onFirstFrame()
                break
            }
        }
    }
    DisposableEffect(controller, owner, url, playbackAnalytics) {
        val center = NSNotificationCenter.defaultCenter
        val resignObserver = center.addObserverForName(
            UIApplicationWillResignActiveNotification,
            null,
            NSOperationQueue.mainQueue,
        ) {
            if (IosVideoPlaybackCoordinator.owns(owner, url)) {
                playbackAnalytics.finish(
                    ProductEventReason.PAUSE,
                    IosVideoPlaybackCoordinator.player.positionMillis(),
                )
                IosVideoPlaybackCoordinator.pause(owner)
            }
        }
        val activeObserver = center.addObserverForName(
            UIApplicationDidBecomeActiveNotification,
            null,
            NSOperationQueue.mainQueue,
        ) {
            IosVideoPlaybackCoordinator.resume(owner)
        }
        onDispose {
            center.removeObserver(resignObserver)
            center.removeObserver(activeObserver)
            playbackAnalytics.finish(
                ProductEventReason.SURFACE_CHANGE,
                IosVideoPlaybackCoordinator.player.positionMillis()
                    .takeIf { IosVideoPlaybackCoordinator.owns(owner, url) },
            )
            controller.player = null
            IosVideoPlaybackCoordinator.detach(owner)
        }
    }
    UIKitViewController(
        factory = { controller.apply { player = IosVideoPlaybackCoordinator.player } },
        update = {
            it.player = IosVideoPlaybackCoordinator.player
            it.showsPlaybackControls = showControls
        },
        onRelease = { it.player = null },
        modifier = modifier,
    )
}

actual fun platformVideoPlaybackIdentity(media: PostMedia): String? {
    val urls = media.secureVideoUrls()
    return media.cacheKey?.takeIf(String::isNotBlank) ?: urls.primary
}

actual fun platformVideoHasRenderedFirstFrame(media: PostMedia): Boolean {
    val url = media.secureVideoUrls().primary ?: return false
    return IosVideoPlaybackCoordinator.hasRendered(url)
}

actual val platformVideoRequiresPosterOverlayUntilFirstFrame: Boolean = true

/** Mirrors Media3's playback-session events without replacing AVPlayer's native HLS stack. */
private class IosPlaybackAnalytics(
    private val contentId: String?,
    private val surface: ProductSurface,
    private val mediaDurationMs: Long?,
) {
    private var segmentStartedAt: TimeMark? = null
    private var sessionActive = false
    private var accumulatedPlayedMs = 0L
    private var startedPositionMs = 0L
    private var lastPositionMs = 0L

    fun update(state: PlatformPlaybackState, positionMs: Long) {
        val isPlaying = state == PlatformPlaybackState.Playing
        if (isPlaying && sessionActive && positionMs + LOOP_RESET_TOLERANCE_MS < lastPositionMs) {
            finish(ProductEventReason.ENDED, lastPositionMs)
        }
        if (isPlaying) {
            if (!sessionActive) {
                sessionActive = true
                accumulatedPlayedMs = 0L
                startedPositionMs = positionMs
            }
            if (segmentStartedAt == null) segmentStartedAt = TimeSource.Monotonic.markNow()
        } else if (state == PlatformPlaybackState.Buffering) {
            closePlayingSegment()
        } else if (sessionActive) {
            val reason = if (
                mediaDurationMs != null && positionMs >= mediaDurationMs - ENDED_TOLERANCE_MS
            ) ProductEventReason.ENDED else ProductEventReason.PAUSE
            finish(reason, positionMs)
        }
        lastPositionMs = positionMs
    }

    fun finish(reason: ProductEventReason, positionMs: Long? = null) {
        if (!sessionActive) return
        closePlayingSegment()
        sessionActive = false
        val endingPositionMs = positionMs ?: lastPositionMs
        val playedMs = accumulatedPlayedMs.coerceAtLeast(0L)
        accumulatedPlayedMs = 0L
        if (playedMs < MIN_PLAYBACK_EVENT_MS) return
        ProductAnalytics.record(ProductEvent(
            name = ProductEventName.MEDIA_PLAYBACK,
            surface = surface,
            contentId = contentId,
            contentType = ProductContentType.VIDEO,
            reason = reason,
            durationMs = playedMs,
            position = startedPositionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            completionPercent = mediaDurationMs
                ?.takeIf { it > 0L }
                ?.let { duration -> (endingPositionMs * 100.0 / duration).coerceIn(0.0, 100.0) },
        ))
    }

    /** A deliberate backward scrub is not a loop completion. */
    fun seek(positionMs: Long) {
        closePlayingSegment()
        lastPositionMs = positionMs.coerceAtLeast(0L)
    }

    private fun closePlayingSegment() {
        val mark = segmentStartedAt ?: return
        segmentStartedAt = null
        accumulatedPlayedMs += mark.elapsedNow().inWholeMilliseconds.coerceAtLeast(0L)
    }

    private companion object {
        const val MIN_PLAYBACK_EVENT_MS = 100L
        const val LOOP_RESET_TOLERANCE_MS = 1_000L
        const val ENDED_TOLERANCE_MS = 500L
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun AVPlayer.positionMillis(): Long = CMTimeGetSeconds(currentTime())
    .takeIf { it.isFinite() && it >= 0.0 }
    ?.times(1_000.0)
    ?.toLong()
    ?: 0L

@OptIn(ExperimentalForeignApi::class)
private fun AVPlayer.durationMillis(): Long? = currentItem()
    ?.duration
    ?.let(::CMTimeGetSeconds)
    ?.takeIf { it.isFinite() && it > 0.0 }
    ?.times(1_000.0)
    ?.toLong()

private val PlatformVideoRole.productSurface: ProductSurface
    get() = when (this) {
        PlatformVideoRole.Feed -> ProductSurface.FEED
        PlatformVideoRole.AdFeed -> ProductSurface.FEED
        PlatformVideoRole.MediaFeed -> ProductSurface.MEDIA
        PlatformVideoRole.Detail -> ProductSurface.DETAIL
        PlatformVideoRole.AdDetail -> ProductSurface.AD_DETAIL
    }

private val PlatformVideoRole.performanceSurface: PerformanceSurface
    get() = when (this) {
        PlatformVideoRole.Feed -> PerformanceSurface.FEED
        PlatformVideoRole.AdFeed -> PerformanceSurface.FEED
        PlatformVideoRole.MediaFeed -> PerformanceSurface.MEDIA
        PlatformVideoRole.Detail -> PerformanceSurface.DETAIL
        PlatformVideoRole.AdDetail -> PerformanceSurface.DETAIL
}

@Composable
@OptIn(ExperimentalForeignApi::class)
actual fun PlatformVideoPreloadWindow(
    media: List<PostMedia>,
    focusIndex: Int,
    settings: AppSettings,
    role: PlatformVideoRole,
) {
    val policy = rememberPlatformVideoPlaybackPolicy(settings)
    val owner = remember { Any() }
    val urls = remember(media, focusIndex, policy.allowPrefetch) {
        if (!policy.allowPrefetch || media.isEmpty()) emptyList() else {
            val focus = focusIndex.coerceIn(media.indices)
            ((focus - 1).coerceAtLeast(0)..(focus + 2).coerceAtMost(media.lastIndex))
                .mapNotNull { index ->
                    media[index].secureVideoUrls().primary
                }
                .distinct()
        }
    }
    LaunchedEffect(owner, urls, role) {
        IosVideoPlaybackCoordinator.updatePreloadWindow(owner, urls, role)
    }
    DisposableEffect(owner) {
        onDispose { IosVideoPlaybackCoordinator.clearPreloadWindow(owner) }
    }
}

/**
 * A progress publisher exists only while the foreground process player has an owner and an
 * active play intent. Paused/ended surfaces receive an edge snapshot without retaining a timer.
 */
internal fun shouldPublishIosPlaybackProgress(
    hasOwner: Boolean,
    appForeground: Boolean,
    playbackRequested: Boolean,
    state: PlatformPlaybackState,
): Boolean = hasOwner && appForeground && playbackRequested &&
    state != PlatformPlaybackState.Ended && state != PlatformPlaybackState.Error

/** One native decoder/player is transferred between feed and media surfaces. */
@OptIn(ExperimentalForeignApi::class)
private object IosVideoPlaybackCoordinator {
    private var playerOrNull: AVPlayer? = null
    val player: AVPlayer get() = playerOrNull ?: AVPlayer().also { playerOrNull = it }
    private var appForeground = true
    private val playbackRequests = LinkedHashMap<Any, PlaybackRequest>()
    private val preloadRequests = LinkedHashMap<Any, PreloadRequest>()
    private var playbackSequence = 0L
    private var preloadSequence = 0L
    private var currentOwner: Any? = null
    private val currentOwnerState = MutableStateFlow<Any?>(null)
    private var currentPrimaryUrl: String? = null
    private var currentUrl: String? = null
    private var attemptedFallback = false
    private var renderedPrimaryUrl: String? = null
    /** The exact AVURLAsset warmed by prefetch is handed to AVPlayerItem; no duplicate manifest load. */
    private val preloadedAssets = mutableMapOf<String, AVURLAsset>()
    private var loopObserver: NSObjectProtocol? = null
    private var failureObserver: NSObjectProtocol? = null
    private val snapshotScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var snapshotJob: Job? = null

    fun attach(
        owner: Any,
        primaryUrl: String,
        fallbackUrl: String?,
        policy: VideoPlaybackPolicy,
        role: PlatformVideoRole,
        autoplay: Boolean,
        muted: Boolean,
        continueExistingPlayback: Boolean,
        durationHintMs: Long?,
        onPlaybackSnapshot: (PlatformPlaybackSnapshot) -> Unit,
        onTerminalFailure: () -> Unit,
    ) {
        val previous = playbackRequests[owner]
        playbackRequests[owner] = PlaybackRequest(
            primaryUrl = primaryUrl,
            fallbackUrl = fallbackUrl,
            policy = policy,
            role = role,
            autoplay = autoplay,
            muted = muted,
            continueExistingPlayback = continueExistingPlayback,
            durationHintMs = durationHintMs,
            onPlaybackSnapshot = onPlaybackSnapshot,
            onTerminalFailure = onTerminalFailure,
            sequence = previous?.sequence ?: ++playbackSequence,
        )
        reconcilePlayback()
    }

    fun owns(owner: Any, url: String): Boolean =
        currentOwner === owner && playbackRequests[owner]?.primaryUrl == url

    suspend fun awaitOwnership(owner: Any, url: String) {
        currentOwnerState.first { selected ->
            selected === owner && playbackRequests[owner]?.primaryUrl == url
        }
    }

    fun markRendered(primaryUrl: String) {
        if (currentPrimaryUrl == primaryUrl) renderedPrimaryUrl = primaryUrl
    }

    fun hasRendered(primaryUrl: String): Boolean =
        currentPrimaryUrl == primaryUrl && renderedPrimaryUrl == primaryUrl

    fun pause(owner: Any) {
        if (currentOwner === owner) {
            player.pause()
            notifyCurrentPlayback()
        }
    }

    fun resume(owner: Any) {
        if (currentOwner === owner) {
            playbackRequests[owner]?.let(::applyPlaybackIntent)
            notifyCurrentPlayback()
        }
    }

    fun setAppForeground(foreground: Boolean) {
        if (appForeground == foreground) return
        appForeground = foreground
        if (foreground) {
            reconcilePreloads()
            reconcilePlayback()
        } else {
            playerOrNull?.pause()
            applyPreloadWindow(emptyList())
            notifyCurrentPlayback()
        }
    }

    fun trimMemory() {
        val retained = currentUrl.takeIf { currentOwner != null }
        preloadedAssets.keys.toList().forEach { url ->
            if (url != retained) preloadedAssets.remove(url)?.cancelLoading()
        }
    }

    fun replay(owner: Any, url: String) {
        if (!owns(owner, url)) return
        val request = playbackRequests[owner] ?: return
        if (player.currentItem()?.status == AVPlayerItemStatusFailed) {
            preloadedAssets.remove(request.primaryUrl)?.cancelLoading()
            attemptedFallback = false
            currentUrl = request.primaryUrl
            renderedPrimaryUrl = null
            player.replaceCurrentItemWithPlayerItem(AVPlayerItem(createAsset(request.primaryUrl)))
            configureCurrentItem(owner, request)
        }
        player.seekToTime(CMTimeMake(value = 0, timescale = 1))
        player.play()
        notifyCurrentPlayback()
    }

    fun seekTo(owner: Any, url: String, positionMs: Long) {
        if (!owns(owner, url)) return
        player.seekToTime(CMTimeMake(value = positionMs.coerceAtLeast(0L), timescale = 1_000))
        notifyCurrentPlayback()
    }

    fun detach(owner: Any) {
        val wasCurrent = currentOwner === owner
        playbackRequests.remove(owner)
        if (wasCurrent) {
            setCurrentOwner(null)
            reconcilePlayback()
        }
    }

    private fun reconcilePlayback() {
        val selected = playbackRequests.entries.maxWithOrNull(
            compareBy<Map.Entry<Any, PlaybackRequest>> { it.value.role.priority }
                .thenBy { it.value.sequence },
        )
        if (selected == null) {
            setCurrentOwner(null)
            playerOrNull?.pause()
            loopObserver?.let(NSNotificationCenter.defaultCenter::removeObserver)
            loopObserver = null
            failureObserver?.let(NSNotificationCenter.defaultCenter::removeObserver)
            failureObserver = null
            stopSnapshotPublication()
            return
        }
        val ownerChanged = currentOwner !== selected.key
        setCurrentOwner(selected.key)
        val request = selected.value
        prepare(selected.key, request, ownerChanged)
        applyPlaybackIntent(request)
        notifyCurrentPlayback()
    }

    private fun applyPlaybackIntent(request: PlaybackRequest) {
        player.setMuted(request.muted)
        if (appForeground && request.autoplay) player.play() else player.pause()
    }

    private fun setCurrentOwner(owner: Any?) {
        currentOwner = owner
        currentOwnerState.value = owner
    }

    private fun prepare(owner: Any, request: PlaybackRequest, ownerChanged: Boolean) {
        val url = request.primaryUrl
        val primaryChanged = currentPrimaryUrl != url
        if (primaryChanged) {
            currentPrimaryUrl = url
            attemptedFallback = false
        }
        if (primaryChanged || currentUrl == null || (ownerChanged && !request.continueExistingPlayback)) {
            val asset = preloadedAssets[url] ?: createAsset(url)
            player.replaceCurrentItemWithPlayerItem(AVPlayerItem(asset))
            currentUrl = url
            renderedPrimaryUrl = null
        }
        configureCurrentItem(owner, request)
    }

    private fun configureCurrentItem(owner: Any, request: PlaybackRequest) {
        val role = request.role
        val policy = request.policy
        loopObserver?.let(NSNotificationCenter.defaultCenter::removeObserver)
        loopObserver = if (role == PlatformVideoRole.MediaFeed) {
            NSNotificationCenter.defaultCenter.addObserverForName(
                AVPlayerItemDidPlayToEndTimeNotification,
                player.currentItem(),
                NSOperationQueue.mainQueue,
            ) {
                player.seekToTime(CMTimeMake(value = 0, timescale = 1))
                player.play()
                notifyCurrentPlayback()
            }
        } else null
        failureObserver?.let(NSNotificationCenter.defaultCenter::removeObserver)
        failureObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            AVPlayerItemFailedToPlayToEndTimeNotification,
            player.currentItem(),
            NSOperationQueue.mainQueue,
        ) {
            attemptFallback(owner, request)
        }
        player.setAutomaticallyWaitsToMinimizeStalling(true)
        player.currentItem()?.setPreferredForwardBufferDuration(
            preferredForwardBufferSeconds(role, policy),
        )
        player.currentItem()?.setPreferredPeakBitRate(policy.preferredPeakBitrate.toDouble())
        player.currentItem()?.setCanUseNetworkResourcesForLiveStreamingWhilePaused(false)
    }

    private fun attemptFallback(owner: Any, request: PlaybackRequest) {
        val fallback = request.fallbackUrl
        if (currentOwner !== owner || attemptedFallback || fallback == null || fallback == currentUrl) {
            player.pause()
            if (currentOwner === owner) {
                stopSnapshotPublication()
                request.onTerminalFailure()
            }
            return
        }
        val shouldResume = player.timeControlStatus == AVPlayerTimeControlStatusPlaying ||
            player.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
        attemptedFallback = true
        currentUrl = fallback
        player.replaceCurrentItemWithPlayerItem(AVPlayerItem(createAsset(fallback)))
        configureCurrentItem(owner, request)
        if (shouldResume) player.play() else applyPlaybackIntent(request)
        notifyCurrentPlayback()
    }

    /**
     * AVPlayer state is sampled once per process player, not once per composed feed cell. This
     * covers playhead movement and time-control transitions while keeping paused surfaces idle.
     */
    private fun notifyCurrentPlayback() {
        val owner = currentOwner
        val request = owner?.let(playbackRequests::get)
        if (owner == null || request == null) {
            stopSnapshotPublication()
            return
        }
        val positionMs = player.positionMillis()
        val durationMs = player.durationMillis() ?: request.durationHintMs
        val state = when {
            player.timeControlStatus == AVPlayerTimeControlStatusPlaying ->
                PlatformPlaybackState.Playing
            player.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate ->
                PlatformPlaybackState.Buffering
            durationMs != null && positionMs >= durationMs - ENDED_TOLERANCE_MS ->
                PlatformPlaybackState.Ended
            else -> PlatformPlaybackState.Paused
        }
        request.onPlaybackSnapshot(PlatformPlaybackSnapshot(state, positionMs, durationMs))
        if (shouldPublishIosPlaybackProgress(
                hasOwner = true,
                appForeground = appForeground,
                playbackRequested = request.autoplay,
                state = state,
            )
        ) {
            startSnapshotPublication()
        } else {
            stopSnapshotPublication()
        }
    }

    private fun startSnapshotPublication() {
        if (snapshotJob?.isActive == true) return
        snapshotJob = snapshotScope.launch {
            while (isActive) {
                delay(SNAPSHOT_INTERVAL_MS)
                notifyCurrentPlayback()
            }
        }
    }

    private fun stopSnapshotPublication() {
        snapshotJob?.cancel()
        snapshotJob = null
    }

    fun updatePreloadWindow(owner: Any, urls: List<String>, role: PlatformVideoRole) {
        val previous = preloadRequests[owner]
        if (urls.isEmpty()) {
            preloadRequests.remove(owner)
        } else {
            preloadRequests[owner] = PreloadRequest(
                urls = urls,
                role = role,
                sequence = previous?.sequence ?: ++preloadSequence,
            )
        }
        reconcilePreloads()
    }

    fun clearPreloadWindow(owner: Any) {
        preloadRequests.remove(owner)
        reconcilePreloads()
    }

    private fun reconcilePreloads() {
        if (!appForeground) {
            applyPreloadWindow(emptyList())
            return
        }
        val selected = preloadRequests.values.maxWithOrNull(
            compareBy<PreloadRequest> { it.role.priority }.thenBy { it.sequence },
        )
        applyPreloadWindow(selected?.urls.orEmpty())
    }

    private fun applyPreloadWindow(urls: List<String>) {
        val desired = urls.toSet()
        (preloadedAssets.keys - desired).forEach { staleUrl ->
            if (staleUrl != currentUrl) preloadedAssets.remove(staleUrl)?.cancelLoading()
        }
        urls.forEach { url ->
            if (url !in preloadedAssets) {
                preloadedAssets[url] = createAsset(url).also { asset ->
                    asset.loadValuesAsynchronouslyForKeys(listOf("playable")) { }
                }
            }
        }
    }

    private fun createAsset(url: String) = AVURLAsset(
        NSURL(string = url),
        mapOf(
            // The resolver decides whether to create preloads at all. Once selected, manual
            // playback must remain possible if the path changes to cellular or Low Data Mode.
            AVURLAssetAllowsExpensiveNetworkAccessKey to true,
            AVURLAssetAllowsConstrainedNetworkAccessKey to true,
        ),
    )

    private data class PlaybackRequest(
        val primaryUrl: String,
        val fallbackUrl: String?,
        val policy: VideoPlaybackPolicy,
        val role: PlatformVideoRole,
        val autoplay: Boolean,
        val muted: Boolean,
        val continueExistingPlayback: Boolean,
        val durationHintMs: Long?,
        val onPlaybackSnapshot: (PlatformPlaybackSnapshot) -> Unit,
        val onTerminalFailure: () -> Unit,
        val sequence: Long,
    )

    private data class PreloadRequest(
        val urls: List<String>,
        val role: PlatformVideoRole,
        val sequence: Long,
    )

    private const val SNAPSHOT_INTERVAL_MS = 100L
    private const val ENDED_TOLERANCE_MS = 500L
}

private data class IosNetworkSnapshot(
    val connection: ConnectionKind,
    val dataSaverEnabled: Boolean,
)

@Composable
@OptIn(ExperimentalForeignApi::class)
actual fun rememberPlatformVideoPlaybackPolicy(settings: AppSettings): VideoPlaybackPolicy {
    var network by remember {
        mutableStateOf(IosNetworkSnapshot(ConnectionKind.Offline, dataSaverEnabled = false))
    }
    DisposableEffect(Unit) {
        val monitor = nw_path_monitor_create()
        val queue = dispatch_queue_create("dev.readthat.video-network-policy", null)
        nw_path_monitor_set_update_handler(monitor) { path ->
            if (path == null) return@nw_path_monitor_set_update_handler
            val online = nw_path_get_status(path) == nw_path_status_satisfied
            val expensive = nw_path_is_expensive(path) ||
                nw_path_uses_interface_type(path, nw_interface_type_cellular)
            val constrained = nw_path_is_constrained(path)
            val next = IosNetworkSnapshot(
                connection = when {
                    !online -> ConnectionKind.Offline
                    expensive -> ConnectionKind.Metered
                    else -> ConnectionKind.Unmetered
                },
                dataSaverEnabled = constrained,
            )
            dispatch_async(dispatch_get_main_queue()) { network = next }
        }
        nw_path_monitor_set_queue(monitor, queue)
        nw_path_monitor_start(monitor)
        onDispose { nw_path_monitor_cancel(monitor) }
    }
    val deviceFacts = remember { currentIosVideoDeviceFacts() }
    return remember(settings, network, deviceFacts) {
        VideoPolicyResolver.resolve(
            settings = settings,
            connection = network.connection,
            dataSaverEnabled = network.dataSaverEnabled ||
                (settings.reduceDataOnMetered && network.connection == ConnectionKind.Metered),
            deviceTier = deviceFacts.deviceTier,
            availableCacheBytes = deviceFacts.availableCacheBytes,
        )
    }
}

private data class IosVideoDeviceFacts(
    val deviceTier: DeviceTier,
    val availableCacheBytes: Long,
)

@OptIn(ExperimentalForeignApi::class)
private fun currentIosVideoDeviceFacts(): IosVideoDeviceFacts {
    val physicalMemory = NSProcessInfo.processInfo.physicalMemory.toLong()
    val cacheRoot = NSSearchPathForDirectoriesInDomains(
        NSCachesDirectory,
        NSUserDomainMask,
        true,
    ).firstOrNull() as? String
    val availableBytes = cacheRoot
        ?.let { NSFileManager.defaultManager.attributesOfFileSystemForPath(it, null) }
        ?.get(NSFileSystemFreeSize)
        ?.let { it as? NSNumber }
        ?.longLongValue
        ?.coerceAtLeast(0L)
        ?: DEFAULT_AVAILABLE_CACHE_BYTES
    return IosVideoDeviceFacts(
        deviceTier = deviceTierForPhysicalMemory(physicalMemory),
        availableCacheBytes = availableBytes,
    )
}

private const val DEFAULT_AVAILABLE_CACHE_BYTES = 512L * 1_048_576L
