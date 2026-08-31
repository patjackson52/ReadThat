package dev.readthat.feed.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.readthat.core.ui.typography.ReadThatTextStyles
import dev.readthat.domain.CellUi
import dev.readthat.domain.ImageMediaUi
import dev.readthat.shared.feedImageCacheKey
import dev.readthat.shared.feedVideoPosterCacheKey

typealias FeedImageRenderer = @Composable (
    url: String,
    cacheKey: String,
    videoPreview: Boolean,
    modifier: Modifier,
) -> Unit

typealias FeedVideoRenderer = @Composable (
    onFirstFrame: () -> Unit,
    modifier: Modifier,
) -> Unit

@Composable
fun FeedMediaCell(
    item: CellUi.Media,
    playInline: Boolean,
    playbackIdentity: Any?,
    initialRenderedFirstFrame: Boolean,
    onOpenMedia: () -> Unit,
    imageRenderer: FeedImageRenderer,
    videoRenderer: FeedVideoRenderer,
    playIndicator: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    var renderedFirstFrame by remember(item.key, playbackIdentity, playInline) {
        mutableStateOf(playInline && initialRenderedFirstFrame)
    }
    Box(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .aspectRatio(safeFeedAspectRatio(item.aspectRatio))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics { contentDescription = item.altText },
        contentAlignment = Alignment.BottomEnd,
    ) {
        val video = item.video
        if (video == null) {
            item.sourceUrl?.let { url ->
                imageRenderer(url, item.feedImageCacheKey(), false, Modifier.fillMaxSize())
            }
        } else {
            if (playInline && (video.hlsUrl != null || video.fallbackUrl != null || item.sourceUrl != null)) {
                videoRenderer({ renderedFirstFrame = true }, Modifier.fillMaxSize())
            }
            if (shouldShowFirstFramePreview(playInline, renderedFirstFrame)) {
                video.posterUrl?.let { preview ->
                    imageRenderer(
                        preview,
                        item.feedVideoPosterCacheKey(),
                        true,
                        Modifier.fillMaxSize(),
                    )
                }
            }
            if (!playInline) {
                playIndicator(Modifier.align(Alignment.Center).size(54.dp))
            }
            if (video.deliveryStatus == "waiting" || video.deliveryStatus == "processing") {
                Surface(
                    color = Color.Black.copy(alpha = .68f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                ) {
                    Text(
                        "Processing ${video.processingProgress}%",
                        Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        item.durationLabel?.let { duration ->
            Surface(
                color = Color.Black.copy(alpha = .64f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            ) {
                Text(
                    duration,
                    Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        // Native player views own pointer dispatch. This final Compose surface remains above
        // Media3/AVPlayer so every media stage has one consistent immersive-feed action.
        Box(
            Modifier.fillMaxSize().clickable(
                onClickLabel = "Open immersive media feed",
                onClick = onOpenMedia,
            ),
        )
    }
}

@Composable
fun FeedImageCarouselCell(
    item: CellUi.ImageCarousel,
    onOpenMedia: () -> Unit,
    imageRenderer: @Composable (ImageMediaUi, String, Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (item.items.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { item.items.size })
    Box(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .aspectRatio(safeFeedAspectRatio(item.items.first().aspectRatio))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val image = item.items[page]
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        onClickLabel = "Open immersive media feed",
                        onClick = onOpenMedia,
                    )
                    .semantics { contentDescription = image.altText },
            ) {
                image.sourceUrl?.let {
                    imageRenderer(image, image.feedImageCacheKey(item.key, page), Modifier.fillMaxSize())
                }
            }
        }
        Surface(
            color = Color.Black.copy(alpha = .68f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
        ) {
            Text(
                "${pagerState.currentPage + 1}/${item.items.size}",
                color = Color.White,
                style = ReadThatTextStyles.feedMetadata,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

internal fun safeFeedAspectRatio(value: Float): Float =
    value.takeIf { it.isFinite() }?.coerceIn(.5f, 2.5f) ?: (16f / 9f)

internal fun shouldShowFirstFramePreview(playInline: Boolean, renderedFirstFrame: Boolean): Boolean =
    !playInline || !renderedFirstFrame
