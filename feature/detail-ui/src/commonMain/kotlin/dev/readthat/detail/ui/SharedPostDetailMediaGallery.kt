package dev.readthat.detail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.readthat.image.ui.PlatformImage
import dev.readthat.image.ui.PlatformImageByteLoader
import dev.readthat.image.ui.PlatformImageKind
import dev.readthat.image.ui.PlatformImageRequest
import dev.readthat.media.ui.PlatformVideoPlayer
import dev.readthat.media.ui.PlatformVideoPreloadWindow
import dev.readthat.media.ui.PlatformVideoRole
import dev.readthat.media.ui.platformVideoHasRenderedFirstFrame
import dev.readthat.media.ui.platformVideoRequiresPosterOverlayUntilFirstFrame
import dev.readthat.shared.AppSettings
import dev.readthat.shared.PostMedia
import dev.readthat.shared.videoPosterCacheKey
import kotlin.math.abs

/**
 * Shared post-detail gallery backed by platform-native image and video engines.
 *
 * The window prewarms only nearby videos. Media3 retains its native PlayerView layering, while the
 * iOS actual keeps a decoded poster above AVPlayer until its first displayable frame.
 */
@Composable
fun SharedPostDetailMediaGallery(
    mediaItems: List<PostMedia>,
    stableCacheKey: String,
    settings: AppSettings,
    imageByteLoader: PlatformImageByteLoader? = null,
    playIcon: ImageVector? = null,
    onOpenMediaFeed: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (mediaItems.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { mediaItems.size })
    val current = mediaItems[pagerState.currentPage.coerceIn(mediaItems.indices)]
    val videoMedia = remember(mediaItems, stableCacheKey) {
        mediaItems.mapIndexedNotNull { index, media ->
            if (!media.isVideo) null else index to media.withStableCacheKey(stableCacheKey, index)
        }
    }
    val videoFocus = videoMedia.indices.minByOrNull { index ->
        abs(videoMedia[index].first - pagerState.currentPage)
    } ?: 0
    PlatformVideoPreloadWindow(
        media = videoMedia.map { it.second },
        focusIndex = videoFocus,
        settings = settings,
        role = PlatformVideoRole.Detail,
    )
    Box(
        modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(detailMediaAspectRatio(current))
            .background(
                if (platformVideoRequiresPosterOverlayUntilFirstFrame) {
                    Color.Black
                } else {
                    Color(current.placeholderColor)
                },
            ),
        contentAlignment = Alignment.BottomEnd,
    ) {
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val media = mediaItems[page].withStableCacheKey(stableCacheKey, page)
            SharedPostDetailMediaPage(
                media = media,
                settings = settings,
                playVideo = page == pagerState.currentPage,
                imageByteLoader = imageByteLoader,
                playIcon = playIcon,
            )
        }
        if (mediaItems.size > 1) {
            Surface(
                color = Color.Black.copy(alpha = .66f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
            ) {
                Text(
                    "${pagerState.currentPage + 1}/${mediaItems.size}",
                    Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        if (onOpenMediaFeed != null) {
            Surface(
                onClick = onOpenMediaFeed,
                color = Color.Black.copy(alpha = .66f),
                contentColor = Color.White,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(
                        top = if (mediaItems.size > 1) 44.dp else 10.dp,
                        end = 10.dp,
                    ),
            ) {
                Text(
                    if (mediaItems.size > 1) "Open gallery" else "Open media feed",
                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun SharedPostDetailMediaPage(
    media: PostMedia,
    settings: AppSettings,
    playVideo: Boolean,
    imageByteLoader: PlatformImageByteLoader?,
    playIcon: ImageVector?,
) {
    var renderedFirstFrame by remember(media.cacheKey, playVideo) {
        mutableStateOf(playVideo && platformVideoHasRenderedFirstFrame(media))
    }
    Box(
        Modifier.fillMaxSize().background(Color(media.placeholderColor)),
        contentAlignment = Alignment.BottomEnd,
    ) {
        if (media.isVideo) {
            if (!platformVideoRequiresPosterOverlayUntilFirstFrame) {
                DetailPoster(media, imageByteLoader)
            }
            if (playVideo && media.hasPlayableVideoSource) {
                PlatformVideoPlayer(
                    media = media,
                    settings = settings,
                    autoplay = false,
                    muted = false,
                    showControls = true,
                    role = PlatformVideoRole.Detail,
                    continueExistingPlayback = true,
                    modifier = Modifier.fillMaxSize(),
                    onFirstFrame = { renderedFirstFrame = true },
                )
            }
            if (
                platformVideoRequiresPosterOverlayUntilFirstFrame &&
                (!playVideo || !renderedFirstFrame)
            ) {
                DetailPoster(media, imageByteLoader)
                if (playIcon != null) {
                    Icon(
                        playIcon,
                        "Play video",
                        Modifier.align(Alignment.Center).size(54.dp),
                        tint = Color.White,
                    )
                }
            }
        } else {
            detailStillImageRequest(media)?.let { request ->
                PlatformImage(
                    request = request,
                    byteLoader = imageByteLoader,
                    contentDescription = media.altText,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        media.durationSeconds?.let { seconds ->
            Surface(
                color = Color.Black.copy(alpha = .6f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.padding(8.dp),
            ) {
                Text(
                    durationLabel(seconds),
                    Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun DetailPoster(media: PostMedia, imageByteLoader: PlatformImageByteLoader?) {
    media.posterUrl?.let { poster ->
        PlatformImage(
            request = PlatformImageRequest(
                url = poster,
                cacheKey = videoPosterCacheKey(requireNotNull(media.cacheKey), poster),
                kind = PlatformImageKind.VideoPreview,
            ),
            byteLoader = imageByteLoader,
            contentDescription = media.altText,
            contentScale = if (platformVideoRequiresPosterOverlayUntilFirstFrame) {
                ContentScale.Fit
            } else {
                ContentScale.Crop
            },
            backgroundColor = if (platformVideoRequiresPosterOverlayUntilFirstFrame) Color.Black else null,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private val PostMedia.hasPlayableVideoSource: Boolean
    get() = hlsUrl != null || fallbackUrl != null || url != null

internal fun PostMedia.withStableCacheKey(parentKey: String, page: Int): PostMedia =
    if (cacheKey.isNullOrBlank()) copy(cacheKey = "$parentKey:$page") else this

/**
 * Keeps the transition preview on the exact feed cache identity. A suffix here would bypass the
 * warm decoded/compressed entry and retry an often-expired signed feed URL while fresh detail data
 * is loading. Once that data arrives its variant-specific URL and key naturally replace this request.
 */
internal fun detailStillImageRequest(media: PostMedia): PlatformImageRequest? {
    val url = media.zoomUrl ?: media.url ?: return null
    val cacheKey = media.cacheKey?.takeIf(String::isNotBlank) ?: return null
    return PlatformImageRequest(url = url, cacheKey = cacheKey)
}

internal fun durationLabel(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

private fun detailMediaAspectRatio(media: PostMedia): Modifier = Modifier.aspectRatio(
    media.aspectRatio.takeIf { it.isFinite() }?.coerceIn(.5f, 2.5f) ?: (16f / 9f),
)
