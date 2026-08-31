package dev.readthat.feed.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import dev.readthat.image.ui.PlatformImage
import dev.readthat.image.ui.PlatformImageByteLoader
import dev.readthat.image.ui.PlatformImageKind
import dev.readthat.image.ui.PlatformImagePreloadWindow
import dev.readthat.image.ui.PlatformImageRequest
import dev.readthat.media.ui.PlatformVideoPreloadWindow
import dev.readthat.media.ui.PlatformVideoRole
import dev.readthat.shared.AppSettings
import dev.readthat.shared.PostMedia
import dev.readthat.shared.VideoPlaybackPolicy

/** Home-feed chrome with the common HTTPS/stable-key account avatar adapter installed. */
@Composable
fun SharedPlatformHomeFeedHeader(
    account: SharedFeedAccount?,
    onOpenNavigation: () -> Unit,
    onSearch: () -> Unit,
    onAccountClick: () -> Unit,
    imageByteLoader: PlatformImageByteLoader? = null,
    modifier: Modifier = Modifier,
) {
    SharedHomeFeedHeader(
        account = account,
        onOpenNavigation = onOpenNavigation,
        onSearch = onSearch,
        onAccountClick = onAccountClick,
        avatarRenderer = { url, cacheKey, avatarModifier ->
            PlatformImage(
                request = feedAccountAvatarRequest(url, cacheKey),
                byteLoader = imageByteLoader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = avatarModifier,
            )
        },
        modifier = modifier,
    )
}

/**
 * One shared feed prefetch executor for both hosts. Video posters follow the same resolved network
 * policy as native HLS preloading; still images remain available for the offline-first feed.
 */
@Composable
fun SharedFeedMediaPreloadWindow(
    plan: FeedMediaPrefetchPlan,
    settings: AppSettings,
    videoPolicy: VideoPlaybackPolicy,
    imageByteLoader: PlatformImageByteLoader? = null,
    role: PlatformVideoRole = PlatformVideoRole.Feed,
) {
    val videoMedia = remember(plan.videos) { plan.videos.map(FeedVideoPrefetchRequest::toPostMedia) }
    val imageRequests = remember(plan, videoPolicy.allowPrefetch) {
        plan.platformImageRequests(includeVideoPosters = videoPolicy.allowPrefetch)
    }
    PlatformVideoPreloadWindow(
        media = videoMedia,
        focusIndex = plan.videoFocusIndex,
        settings = settings,
        role = role,
    )
    PlatformImagePreloadWindow(
        requests = imageRequests,
        byteLoader = imageByteLoader,
    )
}

internal fun feedAccountAvatarRequest(
    url: String,
    cacheKey: String,
) = PlatformImageRequest(
    url = url,
    cacheKey = cacheKey,
    kind = PlatformImageKind.Avatar,
)

internal fun FeedMediaPrefetchPlan.platformImageRequests(
    includeVideoPosters: Boolean,
): List<PlatformImageRequest> = (
    if (includeVideoPosters) decodedImages else stillImages
).map { request ->
    PlatformImageRequest(
        url = request.url,
        cacheKey = request.cacheKey,
        kind = if (request.videoPreview) PlatformImageKind.VideoPreview else PlatformImageKind.Still,
    )
}

private fun FeedVideoPrefetchRequest.toPostMedia() = PostMedia(
    placeholderColor = placeholderColor,
    aspectRatio = aspectRatio,
    isVideo = true,
    durationSeconds = durationSeconds,
    url = url,
    altText = altText,
    hlsUrl = hlsUrl,
    dashUrl = dashUrl,
    posterUrl = posterUrl,
    fallbackUrl = fallbackUrl,
    deliveryStatus = deliveryStatus,
    processingProgress = processingProgress,
    cacheKey = cacheKey,
)
