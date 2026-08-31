package dev.readthat.mediafeed.ui

import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.size.Precision
import dev.readthat.mediafeed.domain.MediaFeedItem
import dev.readthat.mediafeed.domain.MediaFeedMedia
import dev.readthat.client.SharedMediaFeedViewModel
import dev.readthat.observability.PerformanceMetric
import dev.readthat.observability.PerformanceTimer
import dev.readthat.observability.PerformanceSurface
import dev.readthat.observability.PerformanceTelemetry
import dev.readthat.observability.ProductAnalytics
import dev.readthat.observability.ProductContentType
import dev.readthat.observability.ProductEvent
import dev.readthat.observability.ProductEventName
import dev.readthat.observability.ProductSurface
import dev.readthat.observability.performanceTimer
import dev.readthat.playback.AdaptiveVideoPlayer
import dev.readthat.playback.AdaptiveVideoSource
import dev.readthat.playback.VideoPlaybackCoordinator
import dev.readthat.playback.VideoPlaybackRole
import dev.readthat.playback.rememberVideoPlaybackPolicy
import dev.readthat.shared.AppSettings
import dev.readthat.shared.videoPosterCacheKey
import kotlinx.coroutines.delay

@Composable
fun LegacyMediaFeedScreen(
    viewModel: SharedMediaFeedViewModel,
    settings: AppSettings,
    onClose: () -> Unit,
    onOpenDetails: (MediaFeedItem) -> Unit,
    onOpenCommunity: (String) -> Unit,
    onOpenUser: (String) -> Unit,
    interactionTimer: PerformanceTimer? = null,
    modifier: Modifier = Modifier,
) {
    val items = viewModel.feed.collectAsLazyPagingItems()
    val navigationItems by viewModel.navigationItems.collectAsStateWithLifecycle()
    val pageCount = maxOf(1, items.itemCount, navigationItems.size)
    val restoredPage = remember { viewModel.restoredPage }
    val immediateInitialPage = restoredPage.takeIf { it in navigationItems.indices } ?: 0
    val pagerState = rememberPagerState(initialPage = immediateInitialPage) {
        maxOf(1, items.itemCount, navigationItems.size)
    }
    val context = LocalContext.current.applicationContext
    val videoPolicy = rememberVideoPlaybackPolicy(settings)
    val preloadOwner = remember { Any() }
    val imageLoader = remember(context) { context.imageLoader }
    val prefetchWidth = remember(context) { context.resources.displayMetrics.widthPixels }
    val prefetchHeight = remember(context) { context.resources.displayMetrics.heightPixels }
    val imageRequests = remember { mutableMapOf<String, Disposable>() }
    val tti = remember(interactionTimer) { interactionTimer ?: performanceTimer() }
    var ttiReported by remember { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }
    var restorationApplied by remember { mutableStateOf(restoredPage == immediateInitialPage) }

    // A normal-feed launch can paint any absolute page before Paging has loaded
    // around the anchor. The bounds guard is required: peek/get reject an empty snapshot.
    fun itemAt(index: Int): MediaFeedItem? = mediaFeedItemAt(
        pagedItem = if (index in 0 until items.itemCount) items.peek(index) else null,
        navigationItems = navigationItems,
        index = index,
    )

    fun loadItemAt(index: Int): MediaFeedItem? {
        val paged = if (index in 0 until items.itemCount) items[index] else null
        return paged ?: navigationItems.getOrNull(index)
    }

    val currentItem = itemAt(pagerState.currentPage)
    val currentCommunity = currentItem
        ?.subreddit
        ?.removePrefix("r/")
        ?.takeIf(String::isNotBlank)

    LaunchedEffect(Unit) { PerformanceTelemetry.enterSurface(PerformanceSurface.MEDIA) }
    LaunchedEffect(items.itemCount, restoredPage) {
        if (!restorationApplied && items.itemCount > 0) {
            pagerState.scrollToPage(restoredPage.coerceAtMost(items.itemCount - 1))
            restorationApplied = true
        }
    }
    LaunchedEffect(items.itemSnapshotList, pagerState.currentPage) {
        val current = pagerState.currentPage
        if (
            navigationItems.isNotEmpty() &&
            current in 0 until items.itemCount &&
            items.peek(current) != null
        ) {
            // The navigation snapshot exists only to bridge the first Room frame.
            // Releasing it prevents an arbitrarily long normal-feed session from
            // remaining duplicated in the destination ViewModel.
            viewModel.releaseNavigationFallback()
        }
    }
    LaunchedEffect(items.itemCount) {
        if (!ttiReported && itemAt(pagerState.currentPage) != null) {
            withFrameNanos { }
            ttiReported = true
            PerformanceTelemetry.duration(
                PerformanceMetric.MEDIA_FEED_TTI,
                tti,
                surface = PerformanceSurface.MEDIA,
                attributes = mapOf("cache_tier" to if (items.itemCount > 0) "room" else "navigation_seed"),
            )
        }
    }
    LaunchedEffect(pagerState.currentPage, restorationApplied) {
        if (!restorationApplied) return@LaunchedEffect
        viewModel.setCurrentPage(pagerState.currentPage)
        val item = itemAt(pagerState.currentPage) ?: return@LaunchedEffect
        delay(600)
        if (pagerState.currentPage < pageCount && itemAt(pagerState.currentPage)?.postId == item.postId) {
            ProductAnalytics.record(ProductEvent(
                name = ProductEventName.POST_IMPRESSION,
                surface = ProductSurface.MEDIA,
                contentId = item.postId,
                contentType = ProductContentType.POST,
                position = pagerState.currentPage,
            ))
        }
    }
    LaunchedEffect(pagerState.currentPage, items.itemCount, navigationItems, videoPolicy) {
        // Keep ranks relative to the loaded feed, not to a sliding six-page slice. Re-numbering
        // that slice on every swipe made DefaultPreloadManager's current index disagree with the
        // already-warmed MediaSource and caused an avoidable BUFFERING transition at handoff.
        val videoPlan = mediaFeedVideoPreloadPlan(pageCount, pagerState.currentPage, ::itemAt)
        if (videoPlan == null || !videoPolicy.allowPrefetch) {
            VideoPlaybackCoordinator.clearPreloadWindow(preloadOwner)
        } else {
            VideoPlaybackCoordinator.updatePreloadWindow(
                context = context,
                owner = preloadOwner,
                role = VideoPlaybackRole.MediaFeed,
                sources = videoPlan.sources,
                focusIndex = videoPlan.focusIndex,
                policy = videoPolicy,
            )
        }

        val desired = ((pagerState.currentPage - 1).coerceAtLeast(0)..
            (pagerState.currentPage + 2).coerceAtMost(maxOf(0, items.itemCount - 1)))
            .mapNotNull(::itemAt)
            .flatMap { item ->
                item.allMedia.mapNotNull { media ->
                    media.prefetchModel()?.let { model -> Triple(item, media, model) }
                }
            }
        val desiredKeys = desired.mapTo(mutableSetOf()) { (item, media, _) ->
            media.prefetchKey(item.postId)
        }
        (imageRequests.keys - desiredKeys).forEach { key -> imageRequests.remove(key)?.dispose() }
        desired.forEach { (item, media, model) ->
            val key = media.prefetchKey(item.postId)
            if (key !in imageRequests) {
                imageRequests[key] = imageLoader.enqueue(
                    ImageRequest.Builder(context)
                        .data(model)
                        .memoryCacheKey(key)
                        .diskCacheKey(key)
                        .size(prefetchWidth, prefetchHeight)
                        .precision(Precision.INEXACT)
                        .build(),
                )
            }
        }
    }
    DisposableEffect(Unit) {
        val enteredAt = SystemClock.elapsedRealtime()
        onDispose {
            VideoPlaybackCoordinator.clearPreloadWindow(preloadOwner)
            imageRequests.values.forEach(Disposable::dispose)
            imageRequests.clear()
            ProductAnalytics.record(ProductEvent(
                name = ProductEventName.MEDIA_FEED_TIME_SPENT,
                surface = ProductSurface.MEDIA,
                durationMs = (SystemClock.elapsedRealtime() - enteredAt).coerceAtLeast(0),
            ))
        }
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            // A slow drag commits after half a page; fling velocity can still advance a shorter
            // gesture. Every release settles on exactly one item with a critically damped spring.
            flingBehavior = PagerDefaults.flingBehavior(
                state = pagerState,
                pagerSnapDistance = PagerSnapDistance.atMost(1),
                snapPositionalThreshold = 0.5f,
                snapAnimationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ),
            key = { index -> itemAt(index)?.postId ?: "media-placeholder-$index" },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = loadItemAt(page)
            if (item != null) {
                MediaPage(
                    item = item,
                    active = page == pagerState.currentPage,
                    videoPolicy = videoPolicy,
                    chromeVisible = chromeVisible,
                    onToggleChrome = { chromeVisible = !chromeVisible },
                    onOpenDetails = {
                        onOpenDetails(item)
                    },
                    onOpenUser = { onOpenUser(item.author.removePrefix("u/")) },
                )
            }
        }

        if (chromeVisible) {
            MediaFeedTopBar(
                community = currentCommunity,
                avatarUrl = currentItem?.communityAvatarUrl,
                onClose = onClose,
                onOpenCommunity = { community -> onOpenCommunity(community) },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

internal fun mediaFeedItemAt(
    pagedItem: MediaFeedItem?,
    navigationItems: List<MediaFeedItem>,
    index: Int,
): MediaFeedItem? = pagedItem ?: navigationItems.getOrNull(index)

internal data class MediaFeedVideoPreloadPlan(
    val sources: List<AdaptiveVideoSource>,
    val focusIndex: Int,
)

internal fun mediaFeedVideoPreloadPlan(
    pageCount: Int,
    currentPage: Int,
    itemAt: (Int) -> MediaFeedItem?,
): MediaFeedVideoPreloadPlan? {
    val videos = (0 until pageCount).mapNotNull { page ->
        itemAt(page)?.takeIf { it.media.isVideo }?.let { page to it.media.videoSource(it.postId) }
    }
    if (videos.isEmpty()) return null
    val focus = videos.indexOfFirst { (page) -> page >= currentPage }
        .takeIf { it >= 0 } ?: videos.lastIndex
    return MediaFeedVideoPreloadPlan(videos.map { it.second }, focus)
}

@Composable
private fun MediaPage(
    item: MediaFeedItem,
    active: Boolean,
    videoPolicy: dev.readthat.shared.VideoPlaybackPolicy,
    chromeVisible: Boolean,
    onToggleChrome: () -> Unit,
    onOpenDetails: () -> Unit,
    onOpenUser: () -> Unit,
) {
    val videoSource = remember(
        item.postId,
        item.media.cacheKey,
        item.media.hlsUrl,
        item.media.fallbackUrl,
        item.media.url,
    ) { item.media.videoSource(item.postId) }
    var renderedFirstFrame by remember(item.postId, videoSource, active) {
        mutableStateOf(active && VideoPlaybackCoordinator.hasRendered(videoSource))
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val mediaModifier = Modifier.fillMaxSize().clickable(
            onClickLabel = if (chromeVisible) "Hide post details" else "Show post details",
            onClick = onToggleChrome,
        )
        if (item.media.isVideo) {
            if (active) {
                AdaptiveVideoPlayer(
                    source = videoSource,
                    policy = videoPolicy,
                    autoplay = true,
                    muted = false,
                    showControls = false,
                    role = VideoPlaybackRole.MediaFeed,
                    continueExistingPlayback = true,
                    onFirstFrame = { renderedFirstFrame = true },
                    modifier = Modifier.fillMaxSize(),
                )
                // PlayerView owns Android touch dispatch even with controls disabled.
                Box(mediaModifier)
            }
            if (!active || !renderedFirstFrame) item.media.posterUrl?.let { preview ->
                MediaImage(
                    item.postId,
                    item.media,
                    preview,
                    mediaModifier,
                    zoomable = false,
                    requestCacheKey = item.media.prefetchKey(item.postId),
                )
            }
        } else {
            if (item.allMedia.size > 1) {
                MediaImageCarousel(item, mediaModifier)
            } else {
                item.media.zoomUrl?.let {
                    MediaImage(item.postId, item.media, it, mediaModifier, zoomable = true)
                } ?: item.media.url?.let {
                    MediaImage(item.postId, item.media, it, mediaModifier, zoomable = true)
                }
            }
        }

        if (chromeVisible) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 8.dp, vertical = 6.dp)) {
                Spacer(Modifier.weight(1f))
                Surface(
                    color = Color.Black.copy(alpha = .42f),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "u/${item.author.removePrefix("u/")}",
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable(onClickLabel = "Open user profile", onClick = onOpenUser),
                        )
                        Text(
                            item.title,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clickable(
                                onClickLabel = "Open post and comments",
                                onClick = onOpenDetails,
                            ),
                        )
                        item.body?.takeIf(String::isNotBlank)?.let { body ->
                            Text(
                                body,
                                color = Color.White.copy(alpha = .92f),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().clickable(
                                    onClickLabel = "Open post text and comments",
                                    onClick = onOpenDetails,
                                ),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.KeyboardArrowUp, null, tint = Color.White, modifier = Modifier.size(22.dp))
                                Text(item.score.toString(), color = Color.White)
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable(onClickLabel = "Open comments", onClick = onOpenDetails),
                            ) {
                                Icon(Icons.Outlined.ChatBubbleOutline, null, tint = Color.White, modifier = Modifier.size(19.dp))
                                Text(" ${item.commentCount}", color = Color.White)
                            }
                            if (item.postedAgo.isNotBlank()) Text(item.postedAgo, color = Color.White.copy(alpha = .8f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaImageCarousel(item: MediaFeedItem, modifier: Modifier) {
    val pagerState = rememberPagerState(pageCount = { item.allMedia.size })
    Box(Modifier.fillMaxSize()) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val media = item.allMedia[page]
            val url = media.zoomUrl ?: media.url
            if (url != null) {
                MediaImage(item.postId, media, url, modifier, zoomable = true)
            }
        }
        Surface(
            color = Color.Black.copy(alpha = .68f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .safeDrawingPadding()
                .padding(top = 54.dp, end = 12.dp),
        ) {
            Text(
                "${pagerState.currentPage + 1}/${item.allMedia.size}",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun MediaFeedTopBar(
    community: String?,
    avatarUrl: String?,
    onClose: () -> Unit,
    onOpenCommunity: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val avatarUrls = remember { mutableStateMapOf<String, String?>() }
    LaunchedEffect(community, avatarUrl) {
        community?.let { avatarUrls[it] = avatarUrl }
    }
    Box(
        modifier
            .fillMaxWidth()
            .background(Color.Black)
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(Icons.Default.Close, "Close media feed", tint = Color.White)
        }
        Crossfade(
            targetState = community,
            animationSpec = tween(durationMillis = 220),
            label = "media-community-title",
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp),
        ) { targetCommunity ->
            targetCommunity?.let { name ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable(
                            onClickLabel = "Open community",
                            onClick = { onOpenCommunity(name) },
                        )
                        .padding(vertical = 6.dp),
                ) {
                    MediaCommunityAvatar(
                        community = name,
                        avatarUrl = if (name == community) avatarUrl else avatarUrls[name],
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = "r/$name",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    // Mirrors avatar + gap so the text remains at the exact viewport center.
                    Spacer(Modifier.size(36.dp))
                }
            }
        }
        IconButton(onClick = {}, modifier = Modifier.align(Alignment.CenterEnd)) {
            Icon(Icons.Outlined.MoreVert, "More options", tint = Color.White)
        }
    }
}

@Composable
private fun MediaCommunityAvatar(community: String, avatarUrl: String?) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .semantics { contentDescription = "r/$community community avatar" },
        contentAlignment = Alignment.Center,
    ) {
        if (!avatarUrl.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(avatarUrl)
                    .memoryCacheKey("community-avatar:$avatarUrl")
                    .diskCacheKey("community-avatar:$avatarUrl")
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = community.take(1).uppercase(),
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun MediaImage(
    postId: String,
    media: MediaFeedMedia,
    url: String,
    modifier: Modifier,
    zoomable: Boolean,
    requestCacheKey: String = media.prefetchKey(postId),
) {
    val mediaKey = media.mediaId ?: media.cacheKey ?: url
    var scale by remember(postId, mediaKey) { mutableFloatStateOf(1f) }
    var offset by remember(postId, mediaKey) { mutableStateOf(Offset.Zero) }
    val zoomModifier = if (!zoomable) Modifier else Modifier.pointerInput(postId, mediaKey) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } >= 2) {
                    val nextScale = (scale * event.calculateZoom()).coerceIn(1f, 4f)
                    offset = if (nextScale == 1f) Offset.Zero else offset + event.calculatePan()
                    scale = nextScale
                    event.changes.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(url)
            .memoryCacheKey(requestCacheKey)
            .diskCacheKey(requestCacheKey)
            .build(),
        contentDescription = media.altText,
        contentScale = ContentScale.Fit,
        modifier = modifier.then(zoomModifier).graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationX = offset.x
            translationY = offset.y
        }.semantics { contentDescription = media.altText },
    )
}

private fun dev.readthat.mediafeed.domain.MediaFeedMedia.videoSource(postId: String) = AdaptiveVideoSource(
    hlsUrl = hlsUrl,
    fallbackUrl = fallbackUrl ?: url,
    cacheKey = cacheKey ?: mediaId?.let { "video:$it" } ?: "post:$postId",
)

internal fun dev.readthat.mediafeed.domain.MediaFeedMedia.prefetchModel(): String? =
    if (isVideo) posterUrl else zoomUrl ?: url

internal fun dev.readthat.mediafeed.domain.MediaFeedMedia.prefetchKey(postId: String): String =
    if (isVideo) videoPosterCacheKey(cacheKey ?: "post:$postId", posterUrl)
    else cacheKey ?: mediaId?.let { "image:$it" } ?: "post:$postId"
