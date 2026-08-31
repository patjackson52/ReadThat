package dev.readthat.feed.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import dev.readthat.domain.AdLaunchContext
import dev.readthat.domain.AdMediaItemUi
import dev.readthat.domain.AdMediaKind
import dev.readthat.domain.CellUi
import dev.readthat.image.ui.PlatformImage
import dev.readthat.image.ui.PlatformImageByteLoader
import dev.readthat.image.ui.PlatformImageKind
import dev.readthat.image.ui.PlatformImageRequest
import dev.readthat.media.ui.PlatformPlaybackSnapshot
import dev.readthat.media.ui.PlatformPlaybackState
import dev.readthat.media.ui.PlatformVideoPlayer
import dev.readthat.media.ui.PlatformVideoRole
import dev.readthat.media.ui.platformVideoHasRenderedFirstFrame
import dev.readthat.media.ui.platformVideoPlaybackIdentity
import dev.readthat.observability.ProductSurface
import dev.readthat.shared.AppSettings
import dev.readthat.shared.PostMedia

/**
 * Canonical feature-owned glyphs. Hosts may override them for previews, but production Android
 * and iOS consume the same defaults so iconography cannot drift at the application boundary.
 */
data class SharedFeedCellIcons(
    val playIndicator: ImageVector? = Icons.Default.PlayCircle,
    val metadataMore: ImageVector? = Icons.Outlined.MoreHoriz,
    val actions: FeedActionIcons = FeedActionIcons(),
    val promoted: PromotedFeedIcons = PromotedFeedIcons(),
)

/** Stable action set for the post overflow; hosts provide execution, never menu policy. */
enum class FeedPostOverflowAction {
    OpenPost,
    Reshare,
    Share,
    OpenCommunity,
}

/** Community navigation is omitted when malformed server metadata has no usable name. */
fun feedPostOverflowActions(community: String): List<FeedPostOverflowAction> = buildList {
    add(FeedPostOverflowAction.OpenPost)
    add(FeedPostOverflowAction.Reshare)
    add(FeedPostOverflowAction.Share)
    if (normalizedFeedCommunity(community).isNotEmpty()) {
        add(FeedPostOverflowAction.OpenCommunity)
    }
}

/**
 * Canonical feed-cell platform adapter for every application host.
 *
 * Geometry, media mapping, poster/frame hand-off, promoted playback and cache requests live here.
 * [PlatformImage] and [PlatformVideoPlayer] retain native Coil/Media3 and iOS decode/AVPlayer
 * implementations, while [imageByteLoader] keeps iOS image traffic on the shared pooled client.
 */
@Composable
fun SharedFeedCellRenderer(
    item: CellUi,
    playInline: Boolean,
    settings: AppSettings,
    adMedia: CellUi.AdMedia?,
    onOpenPost: (String) -> Unit,
    onOpenMedia: (String) -> Unit,
    onOpenCommunity: (String) -> Unit,
    onVote: (String, Int) -> Unit,
    onReshare: (String) -> Unit,
    onShare: (String) -> Unit,
    onOpenAdProfile: (String) -> Unit,
    onOpenAd: (AdLaunchContext) -> Unit,
    onRelatedPost: (String) -> Unit,
    imageByteLoader: PlatformImageByteLoader? = null,
    onShareAd: ((String) -> Unit)? = null,
    onPromotedMutedChanged: (AdMediaItemUi, Boolean) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    productSurface: ProductSurface = ProductSurface.FEED,
    icons: SharedFeedCellIcons = SharedFeedCellIcons(),
) {
    FeedCell(
        item = item,
        playInline = playInline,
        onOpenPost = onOpenPost,
        onOpenMedia = onOpenMedia,
        onOpenCommunity = onOpenCommunity,
        onVote = onVote,
        onReshare = onReshare,
        onShare = onShare,
        playbackIdentity = { media ->
            platformVideoPlaybackIdentity(media.toFeedPostMedia())
        },
        initialRenderedFirstFrame = { media ->
            playInline && platformVideoHasRenderedFirstFrame(media.toFeedPostMedia())
        },
        mediaImageRenderer = { media, url, cacheKey, videoPreview, imageModifier ->
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
                contentDescription = media.altText,
                contentScale = if (videoPreview) ContentScale.Fit else ContentScale.Crop,
                modifier = imageModifier,
            )
        },
        mediaVideoRenderer = { media, onFirstFrame, playerModifier ->
            PlatformVideoPlayer(
                media = media.toFeedPostMedia(),
                settings = settings,
                autoplay = true,
                muted = true,
                showControls = false,
                role = PlatformVideoRole.Feed,
                continueExistingPlayback = true,
                onFirstFrame = onFirstFrame,
                modifier = playerModifier,
            )
        },
        carouselImageRenderer = { image, cacheKey, imageModifier ->
            PlatformImage(
                request = PlatformImageRequest(
                    url = requireNotNull(image.sourceUrl),
                    cacheKey = cacheKey,
                ),
                byteLoader = imageByteLoader,
                contentDescription = image.altText,
                contentScale = ContentScale.Crop,
                modifier = imageModifier,
            )
        },
        playIndicator = { indicatorModifier ->
            SharedFeedGlyph(
                icon = icons.playIndicator,
                fallback = "▶",
                contentDescription = "Play video",
                modifier = indicatorModifier,
                tint = Color.White.copy(alpha = .9f),
            )
        },
        metadataTrailing = { metadata ->
            FeedPostOverflow(
                postId = metadata.key.substringBefore('/'),
                community = metadata.subreddit,
                icon = icons.metadataMore,
                onOpenPost = onOpenPost,
                onReshare = onReshare,
                onShare = onShare,
                onOpenCommunity = onOpenCommunity,
            )
        },
        adRenderer = { adCell, adModifier ->
            PromotedFeedCell(
                item = adCell,
                companionMedia = adMedia,
                playInline = playInline,
                imageRenderer = { url, cacheKey, description, videoPreview, imageModifier ->
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
                        contentScale = if (videoPreview) ContentScale.Fit else ContentScale.Crop,
                        modifier = imageModifier,
                    )
                },
                videoRenderer = { media, muted, onFirstFrame, onPlaybackState, videoModifier ->
                    PlatformVideoPlayer(
                        media = media.toFeedPostMedia(),
                        settings = settings,
                        autoplay = true,
                        muted = muted,
                        showControls = false,
                        role = PlatformVideoRole.AdFeed,
                        continueExistingPlayback = true,
                        onFirstFrame = onFirstFrame,
                        onPlaybackState = { onPlaybackState(it.toPromotedPlaybackSnapshot()) },
                        modifier = videoModifier,
                    )
                },
                initialRenderedFirstFrame = { media ->
                    platformVideoHasRenderedFirstFrame(media.toFeedPostMedia())
                },
                onOpenProfile = onOpenAdProfile,
                onOpenAd = onOpenAd,
                onRelatedPost = onRelatedPost,
                onShare = onShareAd,
                onMutedChanged = onPromotedMutedChanged,
                modifier = adModifier,
                productSurface = productSurface,
                icons = icons.promoted,
            )
        },
        modifier = modifier,
        actionIcons = icons.actions,
    )
}

@Composable
private fun FeedPostOverflow(
    postId: String,
    community: String,
    icon: ImageVector?,
    onOpenPost: (String) -> Unit,
    onReshare: (String) -> Unit,
    onShare: (String) -> Unit,
    onOpenCommunity: (String) -> Unit,
) {
    var expanded by remember(postId) { mutableStateOf(false) }
    val normalizedCommunity = normalizedFeedCommunity(community)
    fun run(action: () -> Unit) {
        expanded = false
        action()
    }

    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(40.dp),
        ) {
            SharedFeedGlyph(
                icon = icon,
                fallback = "⋯",
                contentDescription = "Post options",
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            feedPostOverflowActions(community).forEach { action ->
                val label = when (action) {
                    FeedPostOverflowAction.OpenPost -> "Open post & comments"
                    FeedPostOverflowAction.Reshare -> "Reshare"
                    FeedPostOverflowAction.Share -> "Share post"
                    FeedPostOverflowAction.OpenCommunity -> "Open r/$normalizedCommunity"
                }
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        run {
                            when (action) {
                                FeedPostOverflowAction.OpenPost -> onOpenPost(postId)
                                FeedPostOverflowAction.Reshare -> onReshare(postId)
                                FeedPostOverflowAction.Share -> onShare(postId)
                                FeedPostOverflowAction.OpenCommunity ->
                                    onOpenCommunity(normalizedCommunity)
                            }
                        }
                    },
                )
            }
        }
    }
}

private fun normalizedFeedCommunity(value: String): String = value.trim().removePrefix("r/")

@Composable
private fun SharedFeedGlyph(
    icon: ImageVector?,
    fallback: String,
    contentDescription: String,
    modifier: Modifier,
    tint: Color = Color.Unspecified,
) {
    if (icon != null) {
        Icon(icon, contentDescription, modifier, tint = tint)
    } else {
        Text(fallback, modifier = modifier, color = tint)
    }
}

internal fun CellUi.Media.toFeedPostMedia() = PostMedia(
    placeholderColor = placeholderColor,
    aspectRatio = aspectRatio,
    isVideo = video != null,
    durationSeconds = durationSeconds,
    url = sourceUrl,
    altText = altText,
    hlsUrl = video?.hlsUrl,
    dashUrl = video?.dashUrl,
    posterUrl = video?.posterUrl,
    fallbackUrl = video?.fallbackUrl,
    deliveryStatus = video?.deliveryStatus ?: "not_applicable",
    processingProgress = video?.processingProgress ?: 0,
    cacheKey = cacheKey ?: "post:${key.substringBefore('/')}",
)

internal fun AdMediaItemUi.toFeedPostMedia() = PostMedia(
    placeholderColor = placeholderColor,
    aspectRatio = aspectRatio,
    isVideo = kind == AdMediaKind.Video,
    durationSeconds = durationSeconds,
    url = imageUrl,
    altText = altText,
    hlsUrl = hlsUrl,
    dashUrl = dashUrl,
    posterUrl = posterUrl,
    fallbackUrl = fallbackUrl,
    cacheKey = cacheKey,
)

private fun PlatformPlaybackSnapshot.toPromotedPlaybackSnapshot() = PromotedPlaybackSnapshot(
    state = when (state) {
        PlatformPlaybackState.Idle -> PromotedPlaybackState.Idle
        PlatformPlaybackState.Buffering -> PromotedPlaybackState.Buffering
        PlatformPlaybackState.Ready -> PromotedPlaybackState.Ready
        PlatformPlaybackState.Playing -> PromotedPlaybackState.Playing
        PlatformPlaybackState.Paused -> PromotedPlaybackState.Paused
        PlatformPlaybackState.Ended -> PromotedPlaybackState.Ended
        PlatformPlaybackState.Error -> PromotedPlaybackState.Error
    },
    positionMs = positionMs,
    durationMs = durationMs,
)
