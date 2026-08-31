package dev.readthat.feed.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.readthat.core.ui.typography.ReadThatTextStyles
import dev.readthat.domain.AdLaunchContext
import dev.readthat.domain.AdMediaItemUi
import dev.readthat.domain.AdMediaKind
import dev.readthat.domain.CellUi
import dev.readthat.domain.RelatedPostUi
import dev.readthat.domain.launchContext
import dev.readthat.observability.ProductAnalytics
import dev.readthat.observability.ProductContentType
import dev.readthat.observability.ProductEvent
import dev.readthat.observability.ProductEventName
import dev.readthat.observability.ProductEventReason
import dev.readthat.observability.ProductSurface
import dev.readthat.shared.videoPosterCacheKey
import kotlin.time.TimeSource

enum class PromotedPlaybackState { Idle, Buffering, Ready, Playing, Paused, Ended, Error }

data class PromotedPlaybackSnapshot(
    val state: PromotedPlaybackState,
    val positionMs: Long,
    val durationMs: Long?,
) {
    val completionPercent: Double?
        get() = durationMs?.takeIf { it > 0L }?.let { duration ->
            (positionMs * 100.0 / duration).coerceIn(0.0, 100.0)
        }
}

typealias PromotedImageRenderer = @Composable (
    url: String,
    cacheKey: String,
    contentDescription: String?,
    videoPreview: Boolean,
    modifier: Modifier,
) -> Unit

typealias PromotedVideoRenderer = @Composable (
    media: AdMediaItemUi,
    muted: Boolean,
    onFirstFrame: () -> Unit,
    onPlaybackState: (PromotedPlaybackSnapshot) -> Unit,
    modifier: Modifier,
) -> Unit

/** One promoted-content icon system compiled into both platform renderers. */
data class PromotedFeedIcons(
    val more: ImageVector? = Icons.Default.MoreVert,
    val play: ImageVector? = Icons.Default.PlayArrow,
    val replay: ImageVector? = Icons.Default.Replay,
    val sparkle: ImageVector? = Icons.Default.AutoAwesome,
    val info: ImageVector? = Icons.Default.Info,
    val volumeOff: ImageVector? = Icons.AutoMirrored.Filled.VolumeOff,
    val volumeUp: ImageVector? = Icons.AutoMirrored.Filled.VolumeUp,
    val upvote: ImageVector? = Icons.Outlined.ArrowUpward,
    val downvote: ImageVector? = Icons.Outlined.ArrowDownward,
    val comments: ImageVector? = Icons.Outlined.ChatBubbleOutline,
    val share: ImageVector? = Icons.Outlined.Share,
)

/**
 * Canonical promoted-content renderer for every Compose host.
 *
 * Layout, click semantics and telemetry are common. Hosts inject only decoded images, their native
 * adaptive player and navigation/share actions. Iconography is feature-owned common code.
 */
@Composable
fun PromotedFeedCell(
    item: CellUi,
    companionMedia: CellUi.AdMedia?,
    playInline: Boolean,
    imageRenderer: PromotedImageRenderer,
    videoRenderer: PromotedVideoRenderer,
    initialRenderedFirstFrame: (AdMediaItemUi) -> Boolean,
    onOpenProfile: (String) -> Unit,
    onOpenAd: (AdLaunchContext) -> Unit,
    onRelatedPost: (String) -> Unit,
    onShare: ((String) -> Unit)? = null,
    onMutedChanged: (AdMediaItemUi, Boolean) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    productSurface: ProductSurface = ProductSurface.FEED,
    icons: PromotedFeedIcons = PromotedFeedIcons(),
) {
    when (item) {
        is CellUi.AdHeader -> PromotedHeader(
            item,
            imageRenderer,
            onOpenProfile,
            modifier,
            productSurface,
            icons,
        )
        is CellUi.AdTitle -> PromotedTitle(item, modifier)
        is CellUi.AdMedia -> PromotedMedia(
            item,
            playInline,
            imageRenderer,
            videoRenderer,
            initialRenderedFirstFrame,
            onOpenAd,
            onMutedChanged,
            modifier,
            productSurface,
            icons,
        )
        is CellUi.AdSummary -> PromotedSummary(item, modifier, icons)
        is CellUi.AdRelatedPosts -> PromotedRelatedPosts(
            item,
            onRelatedPost,
            modifier,
            productSurface,
            icons,
        )
        is CellUi.AdActionBar -> PromotedActionBar(
            item,
            companionMedia,
            onOpenAd,
            onShare,
            modifier,
            productSurface,
            icons,
        )
        else -> error("PromotedFeedCell cannot render ${item::class.simpleName}")
    }
}

@Composable
private fun PromotedHeader(
    item: CellUi.AdHeader,
    imageRenderer: PromotedImageRenderer,
    onOpenProfile: (String) -> Unit,
    modifier: Modifier,
    productSurface: ProductSurface,
    icons: PromotedFeedIcons,
) {
    val username = item.author.removePrefix("u/")
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.clickable(
                onClickLabel = "Open u/$username profile",
                onClick = {
                    recordAdEvent(ProductEventName.AD_CLICK, item.adId, productSurface)
                    onOpenProfile(username)
                },
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(24.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                item.avatarUrl?.let { avatarUrl ->
                    imageRenderer(
                        avatarUrl,
                        "ad-avatar:${item.adId}",
                        null,
                        false,
                        Modifier.fillMaxSize(),
                    )
                } ?: Box(contentAlignment = Alignment.Center) {
                    Text(username.take(1).uppercase(), fontWeight = FontWeight.Black)
                }
            }
            Text(
                "u/$username",
                Modifier.padding(start = 8.dp),
                style = ReadThatTextStyles.feedMetadata,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            item.label,
            Modifier.padding(start = 6.dp),
            style = ReadThatTextStyles.feedMetadata,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        PromotedGlyph(icons.more, "⋮", "Ad options", Modifier.size(22.dp))
    }
}

@Composable
private fun PromotedTitle(item: CellUi.AdTitle, modifier: Modifier) {
    Text(
        item.text,
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        style = ReadThatTextStyles.feedTitle,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun PromotedMedia(
    item: CellUi.AdMedia,
    playInline: Boolean,
    imageRenderer: PromotedImageRenderer,
    videoRenderer: PromotedVideoRenderer,
    initialRenderedFirstFrame: (AdMediaItemUi) -> Boolean,
    onOpenAd: (AdLaunchContext) -> Unit,
    onMutedChanged: (AdMediaItemUi, Boolean) -> Unit,
    modifier: Modifier,
    productSurface: ProductSurface,
    icons: PromotedFeedIcons,
) {
    if (item.items.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { item.items.size })
    var initialPageReported by remember(item.key) { mutableStateOf(false) }
    LaunchedEffect(pagerState.currentPage) {
        if (initialPageReported) {
            recordAdEvent(
                ProductEventName.AD_CAROUSEL_SWIPE,
                item.adId,
                productSurface,
                pagerState.currentPage,
            )
        } else {
            initialPageReported = true
        }
    }

    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(14.dp))
                .aspectRatio(item.items.first().aspectRatio.coerceIn(0.5f, 2.5f))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            HorizontalPager(pagerState, Modifier.fillMaxSize()) { page ->
                PromotedMediaPage(
                    ad = item,
                    media = item.items[page],
                    page = page,
                    playInline = playInline && page == pagerState.currentPage,
                    imageRenderer = imageRenderer,
                    videoRenderer = videoRenderer,
                    initialRenderedFirstFrame = initialRenderedFirstFrame,
                    onOpenAd = onOpenAd,
                    onMutedChanged = onMutedChanged,
                    productSurface = productSurface,
                    icons = icons,
                )
            }
            if (item.items.size > 1) {
                Surface(
                    color = Color.Black.copy(alpha = .68f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                ) {
                    Text(
                        "${pagerState.currentPage + 1}/${item.items.size}",
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        color = Color.White,
                        style = ReadThatTextStyles.feedMetadata,
                    )
                }
            }
        }

        val selected = pagerState.currentPage.coerceIn(item.items.indices)
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                item.displayDomain,
                Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = ReadThatTextStyles.feedMetadata,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Surface(
                modifier = Modifier.clickable {
                    recordAdEvent(ProductEventName.AD_CTA_CLICK, item.adId, productSurface, selected)
                    onOpenAd(item.launchContext(selected))
                },
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(24.dp),
            ) {
                Text(
                    item.ctaLabel,
                    Modifier.padding(horizontal = 15.dp, vertical = 9.dp),
                    fontWeight = FontWeight.Bold,
                    style = ReadThatTextStyles.feedSupporting,
                )
            }
        }
    }
}

@Composable
private fun PromotedMediaPage(
    ad: CellUi.AdMedia,
    media: AdMediaItemUi,
    page: Int,
    playInline: Boolean,
    imageRenderer: PromotedImageRenderer,
    videoRenderer: PromotedVideoRenderer,
    initialRenderedFirstFrame: (AdMediaItemUi) -> Boolean,
    onOpenAd: (AdLaunchContext) -> Unit,
    onMutedChanged: (AdMediaItemUi, Boolean) -> Unit,
    productSurface: ProductSurface,
    icons: PromotedFeedIcons,
) {
    var playback by remember(media.creativeId) {
        mutableStateOf(PromotedPlaybackSnapshot(PromotedPlaybackState.Idle, 0L, null))
    }
    var muted by remember(media.creativeId) { mutableStateOf(true) }
    var renderedFirstFrame by remember(media.creativeId, playInline) {
        mutableStateOf(playInline && initialRenderedFirstFrame(media))
    }
    val playbackAnalytics = rememberPromotedPlaybackAnalytics(
        adId = ad.adId,
        creativeId = media.creativeId,
        surface = productSurface,
    )

    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics { contentDescription = media.altText },
    ) {
        if (media.kind == AdMediaKind.Image) {
            media.imageUrl?.let { url ->
                imageRenderer(url, media.cacheKey, media.altText, false, Modifier.fillMaxSize())
            }
        } else {
            if (playInline && (media.hlsUrl != null || media.fallbackUrl != null)) {
                videoRenderer(
                    media,
                    muted,
                    { renderedFirstFrame = true },
                    { snapshot ->
                        playback = snapshot
                        playbackAnalytics(snapshot)
                    },
                    Modifier.fillMaxSize(),
                )
            }
            if (!playInline || !renderedFirstFrame) {
                media.posterUrl?.let { preview ->
                    imageRenderer(
                        preview,
                        videoPosterCacheKey(media.cacheKey, media.posterUrl),
                        media.altText,
                        true,
                        Modifier.fillMaxSize(),
                    )
                }
            }
            if (!playInline) {
                PromotedGlyph(
                    icons.play,
                    "▶",
                    "Play ad video",
                    Modifier.align(Alignment.Center).size(58.dp),
                    Color.White.copy(alpha = .94f),
                )
            }
        }

        if (playback.state == PromotedPlaybackState.Ended) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .46f)))
            Column(
                Modifier.align(Alignment.Center),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PromotedOverlayAction(icons.replay, "↻", "REPLAY VIDEO") {
                    recordAdEvent(ProductEventName.AD_CLICK, ad.adId, productSurface, page)
                    onOpenAd(ad.launchContext(page, restartAtBeginning = true))
                }
                PromotedOverlayAction(icons.sparkle, "✦", ad.ctaLabel.uppercase()) {
                    recordAdEvent(ProductEventName.AD_CTA_CLICK, ad.adId, productSurface, page)
                    onOpenAd(ad.launchContext(page))
                }
            }
        } else {
            Box(
                Modifier.fillMaxSize().clickable(onClickLabel = "Open promoted content") {
                    recordAdEvent(ProductEventName.AD_CLICK, ad.adId, productSurface, page)
                    onOpenAd(ad.launchContext(page))
                },
            )
        }

        if (media.kind == AdMediaKind.Video) {
            Surface(
                color = Color.Black.copy(alpha = .72f),
                shape = CircleShape,
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp).size(36.dp)
                    .clickable {
                        muted = !muted
                        onMutedChanged(media, muted)
                    },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    PromotedGlyph(
                        if (muted) icons.volumeOff else icons.volumeUp,
                        if (muted) "🔇" else "🔊",
                        if (muted) "Unmute ad video" else "Mute ad video",
                        Modifier.size(20.dp),
                        Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun PromotedOverlayAction(
    icon: ImageVector?,
    fallback: String,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.clickable(onClickLabel = label, onClick = onClick).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = Color.Transparent,
            border = BorderStroke(2.dp, Color.White),
            modifier = Modifier.size(48.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                PromotedGlyph(icon, fallback, null, Modifier.size(25.dp), Color.White)
            }
        }
        Text(
            label,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun PromotedSummary(
    item: CellUi.AdSummary,
    modifier: Modifier,
    icons: PromotedFeedIcons,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            item.text,
            Modifier.weight(1f),
            style = ReadThatTextStyles.feedBody,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        PromotedGlyph(
            icons.sparkle,
            "✦",
            item.disclosureLabel,
            Modifier.padding(start = 8.dp).size(21.dp),
            Color(0xFF008675),
        )
    }
}

@Composable
private fun PromotedRelatedPosts(
    item: CellUi.AdRelatedPosts,
    onPost: (String) -> Unit,
    modifier: Modifier,
    productSurface: ProductSurface,
    icons: PromotedFeedIcons,
) {
    Column(modifier.fillMaxWidth()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(item.posts, key = RelatedPostUi::postId) { post ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.width(260.dp).height(78.dp).clickable {
                        recordAdEvent(ProductEventName.AD_RELATED_CLICK, item.adId, productSurface)
                        onPost(post.postId)
                    },
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                        Text(
                            post.title,
                            style = ReadThatTextStyles.feedSupporting,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "r/${post.subreddit.removePrefix("r/")} · ${compactPromotedCount(post.score)} upvotes",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = ReadThatTextStyles.feedMetadata,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PromotedGlyph(icons.info, "ⓘ", null, Modifier.size(18.dp))
            Text(
                item.disclosureLabel,
                Modifier.padding(start = 7.dp),
                style = ReadThatTextStyles.feedMetadata,
            )
        }
    }
}

@Composable
private fun PromotedActionBar(
    item: CellUi.AdActionBar,
    companionMedia: CellUi.AdMedia?,
    onOpenAd: (AdLaunchContext) -> Unit,
    onShare: ((String) -> Unit)?,
    modifier: Modifier,
    productSurface: ProductSurface,
    icons: PromotedFeedIcons,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PromotedActionPill {
            PromotedGlyph(icons.upvote, "▲", "Upvote ad", Modifier.size(19.dp))
            Text(
                "Vote",
                Modifier.padding(horizontal = 8.dp),
                fontWeight = FontWeight.Bold,
                style = ReadThatTextStyles.feedAction,
            )
            Box(Modifier.width(1.dp).height(22.dp).background(MaterialTheme.colorScheme.outlineVariant))
            PromotedGlyph(
                icons.downvote,
                "▼",
                "Downvote ad",
                Modifier.padding(start = 8.dp).size(19.dp),
            )
        }
        PromotedActionPill(onClick = companionMedia?.let { media ->
            {
                recordAdEvent(ProductEventName.AD_CLICK, item.adId, productSurface, 0)
                onOpenAd(media.launchContext(0))
            }
        }) {
            PromotedGlyph(icons.comments, "○", "Ad comments", Modifier.size(19.dp))
            Text(
                item.commentCount.toString(),
                Modifier.padding(start = 7.dp),
                style = ReadThatTextStyles.feedAction,
            )
        }
        Spacer(Modifier.weight(1f))
        PromotedActionPill(onClick = if (companionMedia != null && onShare != null) {
            { onShare(companionMedia.destinationUrl) }
        } else null) {
            PromotedGlyph(icons.share, "↗", "Share ad", Modifier.size(20.dp))
        }
    }
}

@Composable
private fun PromotedActionPill(
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
private fun PromotedGlyph(
    icon: ImageVector?,
    fallback: String,
    accessibilityLabel: String?,
    modifier: Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    if (icon != null) {
        Icon(icon, accessibilityLabel, modifier, tint = tint)
    } else {
        Text(
            fallback,
            modifier.then(if (accessibilityLabel == null) Modifier else Modifier.semantics {
                contentDescription = accessibilityLabel
            }),
            color = tint,
            style = ReadThatTextStyles.feedAction,
            fontWeight = FontWeight.Bold,
        )
    }
}

internal fun compactPromotedCount(value: Int): String = when {
    value >= 1_000_000 -> "${value / 100_000 / 10.0}M"
    value >= 1_000 -> "${value / 100 / 10.0}k"
    else -> value.toString()
}

private fun recordAdEvent(
    name: String,
    adId: String,
    surface: ProductSurface,
    position: Int? = null,
) {
    ProductAnalytics.record(ProductEvent(
        name = name,
        surface = surface,
        contentId = adId,
        contentType = ProductContentType.AD,
        position = position,
    ))
}

@Composable
private fun rememberPromotedPlaybackAnalytics(
    adId: String,
    creativeId: String,
    surface: ProductSurface,
): (PromotedPlaybackSnapshot) -> Unit {
    val origin = remember { TimeSource.Monotonic.markNow() }
    val analytics = remember(adId, creativeId, surface) {
        PromotedPlaybackAnalytics(adId, surface, { origin.elapsedNow().inWholeMilliseconds })
    }
    DisposableEffect(analytics) {
        onDispose { analytics.finish(ProductEventReason.SURFACE_CHANGE) }
    }
    return remember(analytics) { analytics::update }
}

internal class PromotedPlaybackAnalytics(
    private val adId: String,
    private val surface: ProductSurface,
    private val elapsedMillis: () -> Long,
    private val record: (ProductEvent) -> Unit = ProductAnalytics::record,
) {
    private var prior = PromotedPlaybackSnapshot(PromotedPlaybackState.Idle, 0L, null)
    private var watchStartedAt: Long? = null
    private var watchStartedPosition = 0L
    private var completed = false

    fun update(snapshot: PromotedPlaybackSnapshot) {
        if (snapshot.state == PromotedPlaybackState.Playing && prior.state != PromotedPlaybackState.Playing) {
            if (prior.state == PromotedPlaybackState.Ended) completed = false
            watchStartedAt = elapsedMillis()
            watchStartedPosition = snapshot.positionMs
            record(ProductEvent(
                name = ProductEventName.AD_VIDEO_PLAY,
                surface = surface,
                contentId = adId,
                contentType = ProductContentType.AD,
                position = snapshot.positionMs.coerceToEventPosition(),
            ))
        } else if (
            prior.state == PromotedPlaybackState.Playing &&
            snapshot.state != PromotedPlaybackState.Playing
        ) {
            finish(
                if (snapshot.state == PromotedPlaybackState.Ended) {
                    ProductEventReason.ENDED
                } else {
                    ProductEventReason.PAUSE
                },
                snapshot,
            )
        }
        if (snapshot.state == PromotedPlaybackState.Ended && !completed) {
            completed = true
            record(ProductEvent(
                name = ProductEventName.AD_VIDEO_COMPLETE,
                surface = surface,
                contentId = adId,
                contentType = ProductContentType.AD,
                reason = ProductEventReason.ENDED,
                completionPercent = 100.0,
            ))
        }
        prior = snapshot
    }

    fun finish(reason: ProductEventReason, snapshot: PromotedPlaybackSnapshot = prior) {
        val startedAt = watchStartedAt ?: return
        watchStartedAt = null
        val playedMs = (elapsedMillis() - startedAt).coerceAtLeast(0L)
        if (playedMs < 100L) return
        record(ProductEvent(
            name = ProductEventName.AD_VIDEO_WATCH,
            surface = surface,
            contentId = adId,
            contentType = ProductContentType.AD,
            reason = reason,
            durationMs = playedMs,
            position = watchStartedPosition.coerceToEventPosition(),
            completionPercent = snapshot.completionPercent,
        ))
    }
}

private fun Long.coerceToEventPosition(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
