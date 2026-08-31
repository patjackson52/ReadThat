package dev.readthat.media.ui

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.readthat.playback.AdaptiveVideoPlayer
import dev.readthat.playback.AdaptiveVideoSource
import dev.readthat.playback.VideoPlaybackCoordinator
import dev.readthat.playback.VideoPlaybackRole
import dev.readthat.playback.VideoPlaybackSnapshot
import dev.readthat.playback.VideoPlaybackState
import dev.readthat.playback.rememberVideoPlaybackPolicy
import dev.readthat.shared.AppSettings
import dev.readthat.shared.PostMedia

@Composable
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
actual fun PlatformMediaLifecycle(onMemoryPressure: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnMemoryPressure = rememberUpdatedState(onMemoryPressure)
    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> VideoPlaybackCoordinator.setAppForeground(true)
                Lifecycle.Event.ON_STOP -> VideoPlaybackCoordinator.setAppForeground(false)
                else -> Unit
            }
        }
        val callbacks = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            override fun onLowMemory() {
                currentOnMemoryPressure.value()
                VideoPlaybackCoordinator.trimMemory()
            }

            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                    currentOnMemoryPressure.value()
                    VideoPlaybackCoordinator.trimMemory()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        context.registerComponentCallbacks(callbacks)
        VideoPlaybackCoordinator.setAppForeground(
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
        )
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            context.unregisterComponentCallbacks(callbacks)
            VideoPlaybackCoordinator.setAppForeground(false)
        }
    }
}

@Composable
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
    val policy = rememberVideoPlaybackPolicy(settings)
    val urls = remember(media.hlsUrl, media.fallbackUrl, media.url) { media.secureVideoUrls() }
    val primaryUrl = urls.primary ?: return
    val playWhenReady = shouldRequestNativePlayback(
        playRequested = autoplay,
        autoplayAllowed = policy.autoplay,
        userInitiatedPlayback = userInitiatedPlayback,
    )
    val currentOnPlaybackState = rememberUpdatedState(onPlaybackState)
    var playerAcquired by remember(primaryUrl, role) { mutableStateOf(false) }
    // Do not acquire a decoder or source while autoplay is suppressed. MediaFeed begins acquiring
    // after an explicit Play and retains that lease across a later manual pause.
    if (!shouldAcquireNativePlayer(role, playWhenReady, userInitiatedPlayback, playerAcquired)) {
        LaunchedEffect(primaryUrl, role) {
            currentOnPlaybackState.value(PlatformPlaybackSnapshot(
                state = PlatformPlaybackState.Paused,
                positionMs = 0L,
                durationMs = media.durationSeconds?.times(1_000L),
            ))
        }
        return
    }
    SideEffect { playerAcquired = true }
    val source = remember(primaryUrl, urls.fallback, media.cacheKey) {
        AdaptiveVideoSource(primaryUrl, urls.fallback, media.cacheKey)
    }
    AdaptiveVideoPlayer(
        source = source,
        policy = policy,
        autoplay = playWhenReady,
        muted = muted,
        showControls = showControls,
        role = role.toAndroidRole(),
        continueExistingPlayback = continueExistingPlayback,
        modifier = modifier,
        onFirstFrame = onFirstFrame,
        onPlaybackState = { currentOnPlaybackState.value(it.toPlatformSnapshot()) },
    )
    LaunchedEffect(source, replayRequest) {
        if (replayRequest > 0) {
            withFrameNanos { }
            VideoPlaybackCoordinator.replay(source)
        }
    }
    LaunchedEffect(source, seekRequest) {
        seekRequest?.let { request ->
            withFrameNanos { }
            VideoPlaybackCoordinator.seekTo(source, request.positionMs)
        }
    }
}

actual fun platformVideoPlaybackIdentity(media: PostMedia): String? {
    val urls = media.secureVideoUrls()
    return media.cacheKey?.takeIf(String::isNotBlank) ?: urls.primary
}

actual fun platformVideoHasRenderedFirstFrame(media: PostMedia): Boolean {
    val urls = media.secureVideoUrls()
    val primary = urls.primary ?: return false
    return VideoPlaybackCoordinator.hasRendered(
        AdaptiveVideoSource(primary, urls.fallback, media.cacheKey),
    )
}

actual val platformVideoRequiresPosterOverlayUntilFirstFrame: Boolean = false

@Composable
actual fun rememberPlatformVideoPlaybackPolicy(settings: AppSettings) =
    rememberVideoPlaybackPolicy(settings)

@Composable
actual fun PlatformVideoPreloadWindow(
    media: List<PostMedia>,
    focusIndex: Int,
    settings: AppSettings,
    role: PlatformVideoRole,
) {
    val context = LocalContext.current.applicationContext
    val policy = rememberVideoPlaybackPolicy(settings)
    val owner = remember { Any() }
    val indexedSources = remember(media) {
        media.mapIndexedNotNull { index, item ->
            val urls = item.secureVideoUrls()
            AdaptiveVideoSource(urls.primary, urls.fallback, item.cacheKey)
                .takeIf { it.hlsUrl != null || it.fallbackUrl != null }
                ?.let { index to it }
        }
    }
    val sources = indexedSources.map { it.second }
    val sourceFocus = indexedSources.indexOfFirst { (originalIndex) -> originalIndex >= focusIndex }
        .takeIf { it >= 0 } ?: indexedSources.lastIndex
    LaunchedEffect(context, sources, sourceFocus, policy, role) {
        if (sources.isEmpty() || !policy.allowPrefetch) {
            VideoPlaybackCoordinator.clearPreloadWindow(owner)
        } else {
            VideoPlaybackCoordinator.updatePreloadWindow(
                context,
                owner,
                role.toAndroidRole(),
                sources,
                sourceFocus.coerceIn(sources.indices),
                policy,
            )
        }
    }
    DisposableEffect(owner) {
        onDispose { VideoPlaybackCoordinator.clearPreloadWindow(owner) }
    }
}

private fun PlatformVideoRole.toAndroidRole(): VideoPlaybackRole = when (this) {
    PlatformVideoRole.Feed -> VideoPlaybackRole.Feed
    PlatformVideoRole.AdFeed -> VideoPlaybackRole.Feed
    PlatformVideoRole.MediaFeed -> VideoPlaybackRole.MediaFeed
    PlatformVideoRole.Detail -> VideoPlaybackRole.Detail
    PlatformVideoRole.AdDetail -> VideoPlaybackRole.AdDetail
}

private fun VideoPlaybackSnapshot.toPlatformSnapshot() = PlatformPlaybackSnapshot(
    state = when (state) {
        VideoPlaybackState.Idle -> PlatformPlaybackState.Idle
        VideoPlaybackState.Buffering -> PlatformPlaybackState.Buffering
        VideoPlaybackState.Ready -> PlatformPlaybackState.Ready
        VideoPlaybackState.Playing -> PlatformPlaybackState.Playing
        VideoPlaybackState.Paused -> PlatformPlaybackState.Paused
        VideoPlaybackState.Ended -> PlatformPlaybackState.Ended
        VideoPlaybackState.Error -> PlatformPlaybackState.Error
    },
    positionMs = positionMs,
    durationMs = durationMs,
)
