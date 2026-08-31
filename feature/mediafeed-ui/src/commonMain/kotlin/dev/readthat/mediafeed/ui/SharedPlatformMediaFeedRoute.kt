package dev.readthat.mediafeed.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.paging.compose.LazyPagingItems
import dev.readthat.image.ui.PlatformImage
import dev.readthat.image.ui.PlatformImageByteLoader
import dev.readthat.image.ui.PlatformImageKind
import dev.readthat.image.ui.PlatformImagePreloadWindow
import dev.readthat.image.ui.PlatformImageRequest
import dev.readthat.media.ui.PlatformVideoPlayer
import dev.readthat.media.ui.PlatformVideoPreloadWindow
import dev.readthat.media.ui.PlatformVideoRole
import dev.readthat.media.ui.PlatformPlaybackSnapshot
import dev.readthat.media.ui.PlatformPlaybackState
import dev.readthat.media.ui.PlatformVideoSeekRequest
import dev.readthat.media.ui.platformVideoHasRenderedFirstFrame
import dev.readthat.mediafeed.domain.MediaFeedItem
import dev.readthat.mediafeed.domain.MediaFeedMedia
import dev.readthat.observability.PerformanceTimer
import dev.readthat.shared.AppSettings
import dev.readthat.shared.PostMedia

/**
 * Complete platform-media adapter around the canonical immersive route.
 *
 * Hosts retain Paging/ViewModel ownership and native actions. Stable media identity, decoded-image
 * requests, player roles, first-frame reuse, and both native preload windows remain identical on
 * Android and iOS.
 */
@Composable
fun SharedPlatformMediaFeedRoute(
    items: LazyPagingItems<MediaFeedItem>,
    navigationItems: List<MediaFeedItem>,
    restoredPage: Int,
    onCurrentPageChanged: (Int) -> Unit,
    onNavigationHydrated: () -> Unit,
    onClose: () -> Unit,
    onOpenDetails: (MediaFeedItem) -> Unit,
    onOpenCommunity: (String) -> Unit,
    onOpenUser: (String) -> Unit,
    onVote: (postId: String, value: Int) -> Unit,
    onShare: (MediaFeedItem) -> Unit,
    settings: AppSettings,
    initialCacheTier: String?,
    imageByteLoader: PlatformImageByteLoader? = null,
    interactionTimer: PerformanceTimer? = null,
    modifier: Modifier = Modifier,
) {
    SharedMediaFeedRoute(
        items = items,
        navigationItems = navigationItems,
        restoredPage = restoredPage,
        onCurrentPageChanged = onCurrentPageChanged,
        onNavigationHydrated = onNavigationHydrated,
        onClose = onClose,
        onOpenDetails = onOpenDetails,
        onOpenCommunity = onOpenCommunity,
        onOpenUser = onOpenUser,
        onVote = onVote,
        onShare = onShare,
        imageRenderer = { url, cacheKey, videoPreview, description, contentScale, imageModifier ->
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
                contentScale = contentScale,
                backgroundColor = Color.Black,
                modifier = imageModifier,
            )
        },
        videoRenderer = {
            item,
            media,
            request,
            onFirstFrame,
            onPlaybackState,
            videoModifier ->
            PlatformVideoPlayer(
                media = media.toStablePostMedia(item.postId),
                settings = settings,
                autoplay = request.playRequested,
                userInitiatedPlayback = request.userInitiatedPlayback,
                muted = request.muted,
                showControls = false,
                role = PlatformVideoRole.MediaFeed,
                continueExistingPlayback = true,
                replayRequest = request.replayRequest,
                seekRequest = request.seekRequest?.let {
                    PlatformVideoSeekRequest(it.requestId, it.positionMs)
                },
                onFirstFrame = onFirstFrame,
                onPlaybackState = { onPlaybackState(it.toMediaFeedPlaybackSnapshot()) },
                modifier = videoModifier,
            )
        },
        preloader = { plan ->
            SharedMediaFeedPreloader(plan, settings, imageByteLoader)
        },
        hasRenderedVideoFrame = { item, media ->
            platformVideoHasRenderedFirstFrame(media.toStablePostMedia(item.postId))
        },
        initialCacheTier = initialCacheTier,
        interactionTimer = interactionTimer,
        modifier = modifier,
    )
}

@Composable
private fun SharedMediaFeedPreloader(
    plan: MediaFeedPreloadPlan,
    settings: AppSettings,
    imageByteLoader: PlatformImageByteLoader?,
) {
    PlatformVideoPreloadWindow(
        media = remember(plan.videos) {
            plan.videos.map { it.media.toStablePostMedia(it.postId) }
        },
        focusIndex = plan.videoFocusIndex.coerceAtLeast(0),
        settings = settings,
        role = PlatformVideoRole.MediaFeed,
    )
    PlatformImagePreloadWindow(
        requests = remember(plan.images) {
            plan.images.map { request ->
                PlatformImageRequest(
                    url = request.url,
                    cacheKey = request.cacheKey,
                    kind = if (request.videoPreview) {
                        PlatformImageKind.VideoPreview
                    } else {
                        PlatformImageKind.Still
                    },
                )
            }
        },
        byteLoader = imageByteLoader,
    )
}

internal fun MediaFeedMedia.toStablePostMedia(postId: String): PostMedia = toPostMedia().copy(
    cacheKey = cacheKey?.takeIf(String::isNotBlank)
        ?: mediaId?.let { "video:$it" }
        ?: "post:$postId",
)

internal fun PlatformPlaybackSnapshot.toMediaFeedPlaybackSnapshot() = MediaFeedPlaybackSnapshot(
    state = when (state) {
        PlatformPlaybackState.Idle -> MediaFeedPlaybackState.Idle
        PlatformPlaybackState.Buffering -> MediaFeedPlaybackState.Buffering
        PlatformPlaybackState.Ready -> MediaFeedPlaybackState.Ready
        PlatformPlaybackState.Playing -> MediaFeedPlaybackState.Playing
        PlatformPlaybackState.Paused -> MediaFeedPlaybackState.Paused
        PlatformPlaybackState.Ended -> MediaFeedPlaybackState.Ended
        PlatformPlaybackState.Error -> MediaFeedPlaybackState.Error
    },
    positionMs = positionMs,
    durationMs = durationMs,
)
