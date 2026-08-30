package dev.readthat.ui

import android.os.SystemClock
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dev.readthat.observability.ProductAnalytics
import dev.readthat.observability.ProductContentType
import dev.readthat.observability.ProductEvent
import dev.readthat.observability.ProductEventName
import dev.readthat.observability.ProductEventReason
import dev.readthat.observability.ProductSurface
import dev.readthat.playback.AdaptiveVideoPlayer
import dev.readthat.playback.AdaptiveVideoSource
import dev.readthat.playback.VideoPlaybackCoordinator
import dev.readthat.playback.VideoPlaybackRole
import dev.readthat.playback.VideoPlaybackSnapshot
import dev.readthat.playback.VideoPlaybackState
import dev.readthat.domain.AdLaunchContext
import dev.readthat.domain.AdMediaItemUi
import dev.readthat.domain.AdMediaKind
import dev.readthat.domain.CellUi
import dev.readthat.domain.RelatedPostUi
import dev.readthat.domain.launchContext
import dev.readthat.core.ui.typography.ReadThatTextStyles
import dev.readthat.shared.VideoPlaybackPolicy
import dev.readthat.shared.videoPosterCacheKey

@Composable
internal fun AdHeaderCell(
    item: CellUi.AdHeader,
    onOpenProfile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val username = item.author.removePrefix("u/")
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.clickable(
                onClickLabel = "Open u/$username profile",
                onClick = {
                    ProductAnalytics.record(ProductEvent(
                        name = ProductEventName.AD_CLICK,
                        surface = ProductSurface.FEED,
                        contentId = item.adId,
                        contentType = ProductContentType.AD,
                    ))
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
                if (item.avatarUrl != null) {
                    AsyncImage(
                        model = item.avatarUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            username.take(1).uppercase(),
                            fontWeight = FontWeight.Black,
                        )
                    }
                }
            }
            Text(
                text = "u/$username",
                modifier = Modifier.padding(start = 8.dp),
                style = ReadThatTextStyles.feedMetadata,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = item.label,
            modifier = Modifier.padding(start = 6.dp),
            style = ReadThatTextStyles.feedMetadata,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Icon(Icons.Default.MoreVert, "Ad options", Modifier.size(22.dp))
    }
}

@Composable
internal fun AdTitleCell(item: CellUi.AdTitle) {
    Text(
        text = item.text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        style = ReadThatTextStyles.feedTitle,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
internal fun AdMediaCell(
    item: CellUi.AdMedia,
    playInline: Boolean,
    videoPolicy: VideoPlaybackPolicy,
    onOpen: (AdLaunchContext) -> Unit,
) {
    if (item.items.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { item.items.size })
    var initialPageReported by remember(item.key) { mutableStateOf(false) }

    LaunchedEffect(pagerState.currentPage) {
        if (initialPageReported) {
            ProductAnalytics.record(ProductEvent(
                name = ProductEventName.AD_CAROUSEL_SWIPE,
                surface = ProductSurface.FEED,
                contentId = item.adId,
                contentType = ProductContentType.AD,
                position = pagerState.currentPage,
            ))
        } else {
            initialPageReported = true
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(14.dp))
                .aspectRatio(item.items.first().aspectRatio)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                AdMediaPage(
                    ad = item,
                    media = item.items[page],
                    page = page,
                    playInline = playInline && page == pagerState.currentPage,
                    videoPolicy = videoPolicy,
                    onOpen = onOpen,
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
                        color = Color.White,
                        style = ReadThatTextStyles.feedMetadata,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
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
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = ReadThatTextStyles.feedMetadata,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Surface(
                modifier = Modifier.clickable {
                    ProductAnalytics.record(ProductEvent(
                        name = ProductEventName.AD_CTA_CLICK,
                        surface = ProductSurface.FEED,
                        contentId = item.adId,
                        contentType = ProductContentType.AD,
                        position = selected,
                    ))
                    onOpen(item.launchContext(selected))
                },
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(24.dp),
            ) {
                Text(
                    item.ctaLabel,
                    modifier = Modifier.padding(horizontal = 15.dp, vertical = 9.dp),
                    fontWeight = FontWeight.Bold,
                    style = ReadThatTextStyles.feedSupporting,
                )
            }
        }
    }
}

@Composable
private fun AdMediaPage(
    ad: CellUi.AdMedia,
    media: AdMediaItemUi,
    page: Int,
    playInline: Boolean,
    videoPolicy: VideoPlaybackPolicy,
    onOpen: (AdLaunchContext) -> Unit,
) {
    val source = remember(media.cacheKey, media.hlsUrl, media.fallbackUrl) {
        AdaptiveVideoSource(media.hlsUrl, media.fallbackUrl, media.cacheKey)
    }
    var playback by remember(media.creativeId) {
        mutableStateOf(VideoPlaybackSnapshot(VideoPlaybackState.Idle, 0L, null))
    }
    var muted by remember(media.creativeId) { mutableStateOf(true) }
    var watchStartedAt by remember(media.creativeId) { mutableLongStateOf(0L) }
    var watchStartedPosition by remember(media.creativeId) { mutableLongStateOf(0L) }
    var priorState by remember(media.creativeId) { mutableStateOf(VideoPlaybackState.Idle) }
    var renderedFirstFrame by remember(media.creativeId, playInline) {
        mutableStateOf(playInline && VideoPlaybackCoordinator.hasRendered(source))
    }
    // Funnel events stay keyed to the stable ad id. The carousel page is kept
    // in `position`; creative ids remain playback/cache/navigation identities.
    val analyticsId = ad.adId

    fun finishWatch(snapshot: VideoPlaybackSnapshot, reason: ProductEventReason) {
        if (watchStartedAt == 0L) return
        val duration = (SystemClock.elapsedRealtime() - watchStartedAt).coerceAtLeast(0L)
        watchStartedAt = 0L
        if (duration < 100L) return
        ProductAnalytics.record(ProductEvent(
            name = ProductEventName.AD_VIDEO_WATCH,
            surface = ProductSurface.FEED,
            contentId = analyticsId,
            contentType = ProductContentType.AD,
            reason = reason,
            durationMs = duration,
            position = watchStartedPosition.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            completionPercent = snapshot.completionPercent,
        ))
    }

    fun playbackChanged(snapshot: VideoPlaybackSnapshot) {
        val old = priorState
        playback = snapshot
        priorState = snapshot.state
        if (snapshot.state == VideoPlaybackState.Playing && old != VideoPlaybackState.Playing) {
            watchStartedAt = SystemClock.elapsedRealtime()
            watchStartedPosition = snapshot.positionMs
            ProductAnalytics.record(ProductEvent(
                name = ProductEventName.AD_VIDEO_PLAY,
                surface = ProductSurface.FEED,
                contentId = analyticsId,
                contentType = ProductContentType.AD,
                position = snapshot.positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            ))
        } else if (old == VideoPlaybackState.Playing && snapshot.state != VideoPlaybackState.Playing) {
            finishWatch(
                snapshot,
                if (snapshot.state == VideoPlaybackState.Ended) {
                    ProductEventReason.ENDED
                } else {
                    ProductEventReason.PAUSE
                },
            )
        }
        if (snapshot.state == VideoPlaybackState.Ended && old != VideoPlaybackState.Ended) {
            ProductAnalytics.record(ProductEvent(
                name = ProductEventName.AD_VIDEO_COMPLETE,
                surface = ProductSurface.FEED,
                contentId = analyticsId,
                contentType = ProductContentType.AD,
                reason = ProductEventReason.ENDED,
                completionPercent = 100.0,
            ))
        }
    }

    DisposableEffect(media.creativeId) {
        onDispose { finishWatch(playback, ProductEventReason.SURFACE_CHANGE) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics { contentDescription = media.altText },
    ) {
        if (media.kind == AdMediaKind.Image) {
            media.imageUrl?.let {
                AsyncImage(
                    model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                        .data(it)
                        .memoryCacheKey(media.cacheKey)
                        .diskCacheKey(media.cacheKey)
                        .build(),
                    contentDescription = media.altText,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            if (playInline && (media.hlsUrl != null || media.fallbackUrl != null)) {
                AdaptiveVideoPlayer(
                    source = source,
                    policy = videoPolicy,
                    autoplay = true,
                    muted = muted,
                    showControls = false,
                    role = VideoPlaybackRole.Feed,
                    onFirstFrame = { renderedFirstFrame = true },
                    onPlaybackState = ::playbackChanged,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (!playInline || !renderedFirstFrame) {
                media.posterUrl?.let { poster ->
                    val posterKey = videoPosterCacheKey(media.cacheKey, media.posterUrl)
                    AsyncImage(
                        model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                            .data(poster)
                            .memoryCacheKey(posterKey)
                            .diskCacheKey(posterKey)
                            .build(),
                        contentDescription = media.altText,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (!playInline) {
                Icon(
                    Icons.Default.PlayArrow,
                    "Play ad video",
                    Modifier.align(Alignment.Center).size(58.dp),
                    tint = Color.White.copy(alpha = .94f),
                )
            }
        }

        if (playback.state == VideoPlaybackState.Ended) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .46f)))
            Column(
                Modifier.align(Alignment.Center),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AdOverlayAction(Icons.Default.Replay, "REPLAY VIDEO") {
                    ProductAnalytics.record(ProductEvent(
                        name = ProductEventName.AD_CLICK,
                        surface = ProductSurface.FEED,
                        contentId = ad.adId,
                        contentType = ProductContentType.AD,
                        position = page,
                    ))
                    onOpen(ad.launchContext(page, restartAtBeginning = true))
                }
                AdOverlayAction(Icons.Default.AutoAwesome, ad.ctaLabel.uppercase()) {
                    ProductAnalytics.record(ProductEvent(
                        name = ProductEventName.AD_CTA_CLICK,
                        surface = ProductSurface.FEED,
                        contentId = ad.adId,
                        contentType = ProductContentType.AD,
                        position = page,
                    ))
                    onOpen(ad.launchContext(page))
                }
            }
        } else {
            Box(
                Modifier.fillMaxSize().clickable {
                    ProductAnalytics.record(ProductEvent(
                        name = ProductEventName.AD_CLICK,
                        surface = ProductSurface.FEED,
                        contentId = ad.adId,
                        contentType = ProductContentType.AD,
                        position = page,
                    ))
                    onOpen(ad.launchContext(page))
                },
            )
        }

        if (media.kind == AdMediaKind.Video) {
            Surface(
                color = Color.Black.copy(alpha = .72f),
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .size(36.dp)
                    .clickable {
                        muted = !muted
                        VideoPlaybackCoordinator.setMuted(source, muted)
                    },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        if (muted) "Unmute ad video" else "Mute ad video",
                        Modifier.size(20.dp),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun AdOverlayAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.clickable(onClick = onClick).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = Color.Transparent,
            border = BorderStroke(2.dp, Color.White),
            modifier = Modifier.size(48.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(25.dp), tint = Color.White)
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
internal fun AdSummaryCell(item: CellUi.AdSummary) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            item.text,
            modifier = Modifier.weight(1f),
            style = ReadThatTextStyles.feedBody,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            Icons.Default.AutoAwesome,
            item.disclosureLabel,
            Modifier.padding(start = 8.dp).size(21.dp),
            tint = Color(0xFF008675),
        )
    }
}

@Composable
internal fun AdRelatedPostsCell(
    item: CellUi.AdRelatedPosts,
    onPost: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(item.posts, key = RelatedPostUi::postId) { post ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .width(260.dp)
                        .height(78.dp)
                        .clickable {
                            ProductAnalytics.record(ProductEvent(
                                name = ProductEventName.AD_RELATED_CLICK,
                                surface = ProductSurface.FEED,
                                contentId = item.adId,
                                contentType = ProductContentType.AD,
                            ))
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
                            "r/${post.subreddit.removePrefix("r/")} · ${compactAdCount(post.score)} upvotes",
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
            Icon(Icons.Default.Info, null, Modifier.size(18.dp))
            Text(
                item.disclosureLabel,
                modifier = Modifier.padding(start = 7.dp),
                style = ReadThatTextStyles.feedMetadata,
            )
        }
    }
}

@Composable
internal fun AdActionBarCell(item: CellUi.AdActionBar) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AdActionPill {
            Icon(Icons.Outlined.ArrowUpward, "Upvote ad", Modifier.size(19.dp))
            Text(
                "Vote",
                fontWeight = FontWeight.Bold,
                style = ReadThatTextStyles.feedAction,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Box(Modifier.width(1.dp).height(22.dp).background(MaterialTheme.colorScheme.outlineVariant))
            Icon(Icons.Outlined.ArrowDownward, "Downvote ad", Modifier.padding(start = 8.dp).size(19.dp))
        }
        AdActionPill {
            Icon(Icons.Outlined.ChatBubbleOutline, "Ad comments", Modifier.size(19.dp))
            Text(
                item.commentCount.toString(),
                style = ReadThatTextStyles.feedAction,
                modifier = Modifier.padding(start = 7.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        AdActionPill { Icon(Icons.Outlined.Share, "Share ad", Modifier.size(20.dp)) }
    }
}

@Composable
private fun AdActionPill(content: @Composable RowScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

internal fun CellUi.AdMedia.videoSources(): List<AdaptiveVideoSource> = items.mapNotNull { media ->
    media.takeIf { it.kind == AdMediaKind.Video && (it.hlsUrl != null || it.fallbackUrl != null) }
        ?.let { AdaptiveVideoSource(it.hlsUrl, it.fallbackUrl, it.cacheKey) }
}

private fun compactAdCount(value: Int): String = when {
    value >= 1_000_000 -> "${value / 100_000 / 10.0}M"
    value >= 1_000 -> "${value / 100 / 10.0}k"
    else -> value.toString()
}
