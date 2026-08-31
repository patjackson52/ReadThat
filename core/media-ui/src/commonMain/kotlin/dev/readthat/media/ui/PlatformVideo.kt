package dev.readthat.media.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.readthat.networking.TransportSecurityPolicy
import dev.readthat.shared.AppSettings
import dev.readthat.shared.PostMedia
import dev.readthat.shared.VideoPlaybackPolicy

enum class PlatformVideoRole(internal val priority: Int) {
    Feed(1),
    AdFeed(1),
    MediaFeed(2),
    Detail(3),
    AdDetail(4),
}

enum class PlatformPlaybackState { Idle, Buffering, Ready, Playing, Paused, Ended, Error }

data class PlatformPlaybackSnapshot(
    val state: PlatformPlaybackState,
    val positionMs: Long,
    val durationMs: Long?,
) {
    val completionPercent: Double?
        get() = durationMs?.takeIf { it > 0L }?.let { duration ->
            (positionMs * 100.0 / duration).coerceIn(0.0, 100.0)
        }
}

/**
 * Edge-triggered seek command for the process-scoped native player.
 *
 * [requestId] distinguishes two deliberate seeks to the same position; carrying a command instead
 * of mutable player state keeps the common transport chrome independent from AVPlayer/Media3.
 */
data class PlatformVideoSeekRequest(
    val requestId: Long,
    val positionMs: Long,
) {
    init {
        require(requestId >= 0L)
        require(positionMs >= 0L)
    }
}

/** Gates speculative native media work with host lifecycle and releases bounded caches on pressure. */
@Composable
expect fun PlatformMediaLifecycle(onMemoryPressure: () -> Unit)

internal data class PlatformVideoUrls(
    val primary: String?,
    val fallback: String?,
)

/** Native media stacks still receive the same HTTPS-only boundary as shared HTTP requests. */
internal fun PostMedia.secureVideoUrls(
    security: TransportSecurityPolicy = TransportSecurityPolicy(),
): PlatformVideoUrls {
    val hls = hlsUrl?.takeIf(security::permits)
    val fallback = (fallbackUrl ?: url)?.takeIf(security::permits)
    val primary = hls ?: fallback
    return PlatformVideoUrls(
        primary = primary,
        fallback = fallback?.takeUnless { it == primary },
    )
}

@Composable
expect fun PlatformVideoPlayer(
    media: PostMedia,
    settings: AppSettings,
    autoplay: Boolean = settings.autoplayVideo,
    userInitiatedPlayback: Boolean = false,
    muted: Boolean = false,
    showControls: Boolean = true,
    role: PlatformVideoRole = PlatformVideoRole.Detail,
    continueExistingPlayback: Boolean = false,
    replayRequest: Int = 0,
    seekRequest: PlatformVideoSeekRequest? = null,
    modifier: Modifier = Modifier,
    onFirstFrame: () -> Unit = {},
    onPlaybackState: (PlatformPlaybackSnapshot) -> Unit = {},
)

/**
 * Autoplay policy gates speculative playback, never an explicit user request.
 *
 * Manual playback still uses the resolved native buffering/bitrate policy and HTTPS-only source;
 * it merely bypasses the preference/network decision that prevents unsolicited autoplay.
 */
fun shouldRequestNativePlayback(
    playRequested: Boolean,
    autoplayAllowed: Boolean,
    userInitiatedPlayback: Boolean,
): Boolean = playRequested && (autoplayAllowed || userInitiatedPlayback)

/** Avoids decoder/network acquisition until a surface can play or has an active manual session. */
fun shouldAcquireNativePlayer(
    role: PlatformVideoRole,
    playWhenReady: Boolean,
    userInitiatedPlayback: Boolean,
    alreadyAcquired: Boolean = false,
): Boolean = alreadyAcquired || when (role) {
    PlatformVideoRole.Feed, PlatformVideoRole.AdFeed -> playWhenReady
    PlatformVideoRole.MediaFeed -> playWhenReady || userInitiatedPlayback
    PlatformVideoRole.Detail, PlatformVideoRole.AdDetail -> true
}

/** Native buffers retain surface-specific bounds while honoring the shared data-saver ceiling. */
internal fun preferredForwardBufferSeconds(
    role: PlatformVideoRole,
    policy: VideoPlaybackPolicy,
): Double = minOf(
    when (role) {
        PlatformVideoRole.Feed, PlatformVideoRole.AdFeed -> 8
        PlatformVideoRole.MediaFeed -> 20
        PlatformVideoRole.Detail, PlatformVideoRole.AdDetail -> 30
    },
    policy.forwardBufferSeconds,
).coerceAtLeast(1).toDouble()

/** Stable native-player source identity used to retain a rendered frame across host recomposition. */
expect fun platformVideoPlaybackIdentity(media: PostMedia): String?

/** True only while the process player still owns a rendered frame for this exact source. */
expect fun platformVideoHasRenderedFirstFrame(media: PostMedia): Boolean

/**
 * UIKit video interop is opaque until AVPlayer reports a displayable frame, so iOS keeps the
 * decoded poster above the native view during that hand-off. Android's PlayerView owns its poster
 * and controls and must remain the top hit-test target.
 */
expect val platformVideoRequiresPosterOverlayUntilFirstFrame: Boolean

/** One platform-fact-aware policy used by feed autoplay and every speculative media tier. */
@Composable
expect fun rememberPlatformVideoPlaybackPolicy(settings: AppSettings): VideoPlaybackPolicy

/** Keeps only a small platform-native HLS window warm around the likely next item. */
@Composable
expect fun PlatformVideoPreloadWindow(
    media: List<PostMedia>,
    focusIndex: Int,
    settings: AppSettings,
    role: PlatformVideoRole = PlatformVideoRole.Feed,
)
