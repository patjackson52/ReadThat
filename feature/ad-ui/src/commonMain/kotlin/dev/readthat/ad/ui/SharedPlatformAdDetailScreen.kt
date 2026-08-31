package dev.readthat.ad.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import dev.readthat.domain.AdLaunchContext
import dev.readthat.domain.AdMediaKind
import dev.readthat.image.ui.PlatformImage
import dev.readthat.image.ui.PlatformImageByteLoader
import dev.readthat.image.ui.PlatformImageKind
import dev.readthat.image.ui.PlatformImageRequest
import dev.readthat.media.ui.PlatformPlaybackSnapshot
import dev.readthat.media.ui.PlatformPlaybackState
import dev.readthat.media.ui.PlatformVideoPlayer
import dev.readthat.media.ui.PlatformVideoRole
import dev.readthat.shared.AppSettings
import dev.readthat.shared.PostMedia

/** Native image/video edge for the canonical promoted-content detail screen. */
@Composable
fun SharedPlatformAdDetailScreen(
    ad: AdLaunchContext,
    settings: AppSettings,
    onClose: () -> Unit,
    imageByteLoader: PlatformImageByteLoader? = null,
    initialMuted: Boolean = true,
    onMutedChanged: (Boolean) -> Unit = {},
    icons: AdDetailIcons = AdDetailIcons(),
    modifier: Modifier = Modifier,
) {
    SharedAdDetailScreen(
        ad = ad,
        onClose = onClose,
        initialMuted = initialMuted,
        onMutedChanged = onMutedChanged,
        videoRenderer = { videoAd, muted, replayRequest, onFirstFrame, onPlaybackState, videoModifier ->
            PlatformVideoPlayer(
                media = videoAd.toAdPostMedia(),
                settings = settings,
                autoplay = true,
                muted = muted,
                showControls = false,
                role = PlatformVideoRole.AdDetail,
                continueExistingPlayback = true,
                replayRequest = replayRequest,
                onFirstFrame = onFirstFrame,
                onPlaybackState = { onPlaybackState(it.toAdPlaybackSnapshot()) },
                modifier = videoModifier,
            )
        },
        imageRenderer = { url, cacheKey, videoPreview, description, imageModifier ->
            PlatformImage(
                request = PlatformImageRequest(
                    url = url,
                    cacheKey = cacheKey,
                    kind = if (videoPreview) {
                        PlatformImageKind.VideoPreview
                    } else {
                        PlatformImageKind.Still
                    },
                ),
                byteLoader = imageByteLoader,
                contentDescription = description,
                contentScale = ContentScale.Crop,
                modifier = imageModifier,
            )
        },
        landingRenderer = { landingAd, landingModifier ->
            PlatformAdLanding(landingAd, landingModifier)
        },
        icons = icons,
        modifier = modifier,
    )
}

internal fun AdLaunchContext.toAdPostMedia() = PostMedia(
    placeholderColor = placeholderColor,
    aspectRatio = aspectRatio,
    isVideo = kind == AdMediaKind.Video,
    url = imageUrl,
    altText = altText,
    hlsUrl = hlsUrl,
    posterUrl = posterUrl,
    fallbackUrl = fallbackUrl,
    cacheKey = cacheKey,
)

private fun PlatformPlaybackSnapshot.toAdPlaybackSnapshot() = AdPlaybackSnapshot(
    state = when (state) {
        PlatformPlaybackState.Idle -> AdPlaybackState.Idle
        PlatformPlaybackState.Buffering -> AdPlaybackState.Buffering
        PlatformPlaybackState.Ready -> AdPlaybackState.Ready
        PlatformPlaybackState.Playing -> AdPlaybackState.Playing
        PlatformPlaybackState.Paused -> AdPlaybackState.Paused
        PlatformPlaybackState.Ended -> AdPlaybackState.Ended
        PlatformPlaybackState.Error -> AdPlaybackState.Error
    },
    positionMs = positionMs,
    durationMs = durationMs,
)
