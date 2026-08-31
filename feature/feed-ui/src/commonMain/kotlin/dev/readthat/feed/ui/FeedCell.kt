package dev.readthat.feed.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.readthat.domain.CellUi
import dev.readthat.domain.ImageMediaUi

typealias FeedCellMediaImageRenderer = @Composable (
    media: CellUi.Media,
    url: String,
    cacheKey: String,
    videoPreview: Boolean,
    modifier: Modifier,
) -> Unit

typealias FeedCellMediaVideoRenderer = @Composable (
    media: CellUi.Media,
    onFirstFrame: () -> Unit,
    modifier: Modifier,
) -> Unit

/**
 * Canonical SDUI cell dispatch shared by both application hosts.
 *
 * Platform code supplies native image/video engines. Promoted cells are delegated to the common
 * [PromotedFeedCell]; everything else—including tap ownership, geometry and actions—is identical.
 */
@Composable
fun FeedCell(
    item: CellUi,
    playInline: Boolean,
    onOpenPost: (String) -> Unit,
    onOpenMedia: (String) -> Unit,
    onOpenCommunity: (String) -> Unit,
    onVote: (String, Int) -> Unit,
    onReshare: (String) -> Unit,
    onShare: (String) -> Unit,
    playbackIdentity: (CellUi.Media) -> Any?,
    initialRenderedFirstFrame: (CellUi.Media) -> Boolean,
    mediaImageRenderer: FeedCellMediaImageRenderer,
    mediaVideoRenderer: FeedCellMediaVideoRenderer,
    carouselImageRenderer: @Composable (ImageMediaUi, String, Modifier) -> Unit,
    playIndicator: @Composable (Modifier) -> Unit,
    metadataTrailing: @Composable (CellUi.Metadata) -> Unit = {},
    adRenderer: @Composable (CellUi, Modifier) -> Unit,
    modifier: Modifier = Modifier,
    actionIcons: FeedActionIcons = FeedActionIcons(),
) {
    val groupId = item.key.substringBefore('/')
    when (item) {
        is CellUi.Metadata -> FeedMetadataCell(
            item = item,
            onOpenCommunity = onOpenCommunity,
            modifier = modifier,
        ) { metadataTrailing(item) }
        is CellUi.Title -> Box(modifier.clickable { onOpenPost(groupId) }) { FeedTitleCell(item) }
        is CellUi.Text -> Box(modifier.clickable { onOpenPost(groupId) }) { FeedTextCell(item) }
        is CellUi.Media -> FeedMediaCell(
            item = item,
            playInline = playInline,
            playbackIdentity = playbackIdentity(item),
            initialRenderedFirstFrame = initialRenderedFirstFrame(item),
            onOpenMedia = { onOpenMedia(groupId) },
            imageRenderer = { url, cacheKey, videoPreview, imageModifier ->
                mediaImageRenderer(item, url, cacheKey, videoPreview, imageModifier)
            },
            videoRenderer = { onFirstFrame, videoModifier ->
                mediaVideoRenderer(item, onFirstFrame, videoModifier)
            },
            playIndicator = playIndicator,
            modifier = modifier,
        )
        is CellUi.ImageCarousel -> FeedImageCarouselCell(
            item = item,
            onOpenMedia = { onOpenMedia(groupId) },
            imageRenderer = carouselImageRenderer,
            modifier = modifier,
        )
        is CellUi.ActionBar -> FeedActionBarCell(
            item = item,
            onVote = onVote,
            onOpenComments = onOpenPost,
            onReshare = onReshare,
            onShare = onShare,
            modifier = modifier,
            icons = actionIcons,
        )
        is CellUi.Announcement -> FeedAnnouncementCell(item, modifier)
        is CellUi.Link -> Box(modifier.clickable { onOpenPost(groupId) }) { FeedLinkCell(item) }
        is CellUi.GroupDivider -> HorizontalDivider(
            modifier = modifier,
            thickness = 6.dp,
            color = MaterialTheme.colorScheme.surfaceVariant,
        )
        is CellUi.AdHeader,
        is CellUi.AdTitle,
        is CellUi.AdMedia,
        is CellUi.AdSummary,
        is CellUi.AdRelatedPosts,
        is CellUi.AdActionBar,
        -> adRenderer(item, modifier)
    }
}
