package dev.readthat.mediafeed.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import dev.readthat.mediafeed.domain.MediaFeedItem
import dev.readthat.mediafeed.domain.MediaFeedMedia
import dev.readthat.observability.PerformanceMetric
import dev.readthat.observability.PerformanceSurface
import dev.readthat.observability.PerformanceTelemetry
import dev.readthat.observability.PerformanceTimer
import dev.readthat.observability.ProductAnalytics
import dev.readthat.observability.ProductContentType
import dev.readthat.observability.ProductEvent
import dev.readthat.observability.ProductEventName
import dev.readthat.observability.ProductSurface
import dev.readthat.observability.performanceTimer
import dev.readthat.shared.videoPosterCacheKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

typealias MediaFeedImageRenderer = @Composable (
    url: String,
    cacheKey: String,
    videoPreview: Boolean,
    contentDescription: String,
    contentScale: ContentScale,
    modifier: Modifier,
) -> Unit

typealias MediaFeedVideoRenderer = @Composable (
    item: MediaFeedItem,
    media: MediaFeedMedia,
    request: MediaFeedVideoPlaybackRequest,
    onFirstFrame: () -> Unit,
    onPlaybackState: (MediaFeedPlaybackSnapshot) -> Unit,
    modifier: Modifier,
) -> Unit

typealias MediaFeedPreloader = @Composable (MediaFeedPreloadPlan) -> Unit

data class MediaFeedVideoPreloadEntry(
    val page: Int,
    val mediaIndex: Int,
    val postId: String,
    val media: MediaFeedMedia,
)

data class MediaFeedImagePreloadRequest(
    val url: String,
    val cacheKey: String,
    val videoPreview: Boolean,
)

data class MediaFeedPreloadPlan(
    val videos: List<MediaFeedVideoPreloadEntry>,
    val videoFocusIndex: Int,
    val images: List<MediaFeedImagePreloadRequest>,
)

enum class MediaFeedPlaybackState { Idle, Buffering, Ready, Playing, Paused, Ended, Error }

data class MediaFeedPlaybackSnapshot(
    val state: MediaFeedPlaybackState,
    val positionMs: Long,
    val durationMs: Long?,
)

data class MediaFeedSeekRequest(
    val requestId: Long,
    val positionMs: Long,
)

data class MediaFeedVideoPlaybackRequest(
    val playRequested: Boolean,
    val userInitiatedPlayback: Boolean,
    val muted: Boolean,
    val replayRequest: Int,
    val seekRequest: MediaFeedSeekRequest?,
)

enum class MediaFeedPrimaryPlaybackAction { Play, Pause, Replay }

fun mediaFeedPrimaryPlaybackAction(
    playbackState: MediaFeedPlaybackState,
    playRequested: Boolean,
): MediaFeedPrimaryPlaybackAction = when {
    playbackState == MediaFeedPlaybackState.Ended || playbackState == MediaFeedPlaybackState.Error ->
        MediaFeedPrimaryPlaybackAction.Replay
    playbackState == MediaFeedPlaybackState.Paused || !playRequested ->
        MediaFeedPrimaryPlaybackAction.Play
    else -> MediaFeedPrimaryPlaybackAction.Pause
}

/** Bounded interaction dimension; never emit post ids, URLs, or community names here. */
enum class MediaFeedInteraction(val telemetryValue: String) {
    ToggleChrome("toggle_chrome"),
    Play("play"),
    Pause("pause"),
    Replay("replay"),
    Mute("mute"),
    Unmute("unmute"),
    Seek("seek"),
    OpenMenu("open_menu"),
    OpenDetail("open_detail"),
    OpenCommunity("open_community"),
    OpenProfile("open_profile"),
    Vote("vote"),
    Share("share"),
    Close("close"),
}

fun MediaFeedInteraction.telemetryAttributes(): Map<String, String> =
    mapOf("interaction_type" to telemetryValue)

@Stable
private class MediaFeedInteractionRecorder(
    private val scope: CoroutineScope,
) {
    fun record(interaction: MediaFeedInteraction, action: () -> Unit) {
        val timer = performanceTimer()
        action()
        scope.launch {
            withFrameNanos { }
            PerformanceTelemetry.duration(
                PerformanceMetric.INTERACTION_TO_NEXT_FRAME,
                timer,
                PerformanceSurface.MEDIA,
                attributes = interaction.telemetryAttributes(),
            )
        }
    }
}

@Composable
private fun rememberMediaFeedInteractionRecorder(): MediaFeedInteractionRecorder {
    val scope = rememberCoroutineScope()
    return remember(scope) { MediaFeedInteractionRecorder(scope) }
}

/**
 * Canonical immersive media presentation shared by Android and iOS.
 *
 * Paging geometry, launch-snapshot fallback, exact one-page flings, galleries, zoom, chrome,
 * actions, impressions and preload planning are common. Hosts inject only their native image
 * decoder, player, preload coordinator and share action.
 */
@Composable
fun SharedMediaFeedRoute(
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
    imageRenderer: MediaFeedImageRenderer,
    videoRenderer: MediaFeedVideoRenderer,
    preloader: MediaFeedPreloader,
    hasRenderedVideoFrame: (MediaFeedItem, MediaFeedMedia) -> Boolean,
    initialCacheTier: String?,
    interactionTimer: PerformanceTimer? = null,
    modifier: Modifier = Modifier,
) {
    val pageCount = maxOf(1, items.itemCount, navigationItems.size)
    val immediateInitialPage = restoredPage.takeIf { it in navigationItems.indices } ?: 0
    val pagerState = rememberPagerState(initialPage = immediateInitialPage) {
        maxOf(1, items.itemCount, navigationItems.size)
    }
    val selectedMedia = remember { mutableStateMapOf<String, Int>() }
    val interactions = rememberMediaFeedInteractionRecorder()
    val tti = remember(interactionTimer) { interactionTimer ?: performanceTimer() }
    var ttiReported by remember { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }
    var restorationApplied by remember { mutableStateOf(restoredPage == immediateInitialPage) }

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
    val currentCommunity = currentItem?.subreddit?.removePrefix("r/")?.takeIf(String::isNotBlank)
    val preloadPlan = mediaFeedPreloadPlan(
        items = (0 until pageCount).map(::itemAt),
        currentPage = pagerState.currentPage,
        selectedMedia = selectedMedia,
    )
    preloader(preloadPlan)

    LaunchedEffect(Unit) { PerformanceTelemetry.enterSurface(PerformanceSurface.MEDIA) }
    LaunchedEffect(items.itemCount, restoredPage) {
        if (!restorationApplied && items.itemCount > 0) {
            pagerState.scrollToPage(restoredPage.coerceAtMost(items.itemCount - 1))
            restorationApplied = true
        }
    }
    LaunchedEffect(items.itemSnapshotList, pagerState.currentPage) {
        val page = pagerState.currentPage
        if (navigationItems.isNotEmpty() && page in 0 until items.itemCount && items.peek(page) != null) {
            onNavigationHydrated()
        }
    }
    LaunchedEffect(items.itemCount, navigationItems.size, items.loadState.refresh, initialCacheTier) {
        val refreshFailed = items.loadState.refresh is LoadState.Error
        val cacheTier = initialCacheTier ?: "error_state".takeIf { refreshFailed }
        val contentReady = itemAt(pagerState.currentPage) != null && cacheTier != null
        if (!ttiReported && (contentReady || refreshFailed)) {
            withFrameNanos { }
            ttiReported = true
            PerformanceTelemetry.duration(
                PerformanceMetric.MEDIA_FEED_TTI,
                tti,
                PerformanceSurface.MEDIA,
                attributes = mapOf(
                    "cache_tier" to (cacheTier ?: "error_state"),
                ),
            )
        }
    }
    LaunchedEffect(pagerState.currentPage, restorationApplied, pageCount) {
        if (!restorationApplied) return@LaunchedEffect
        val page = pagerState.currentPage
        onCurrentPageChanged(page)
        val item = itemAt(page) ?: return@LaunchedEffect
        delay(IMPRESSION_DWELL_MILLIS)
        if (pagerState.currentPage == page && itemAt(page)?.postId == item.postId) {
            ProductAnalytics.record(ProductEvent(
                name = ProductEventName.POST_IMPRESSION,
                surface = ProductSurface.MEDIA,
                contentId = item.postId,
                contentType = ProductContentType.POST,
                position = page,
            ))
        }
    }
    DisposableEffect(Unit) {
        val enteredAt = TimeSource.Monotonic.markNow()
        onDispose {
            ProductAnalytics.record(ProductEvent(
                name = ProductEventName.MEDIA_FEED_TIME_SPENT,
                surface = ProductSurface.MEDIA,
                durationMs = enteredAt.elapsedNow().inWholeMilliseconds.coerceAtLeast(0L),
            ))
        }
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        when {
            currentItem == null && items.loadState.refresh is LoadState.Loading ->
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            currentItem == null && items.loadState.refresh is LoadState.Error -> MediaFeedError(
                message = (items.loadState.refresh as LoadState.Error).error.message ?: "Unable to load media",
                onRetry = items::retry,
                modifier = Modifier.align(Alignment.Center),
            )
            currentItem == null && items.itemCount == 0 && navigationItems.isEmpty() ->
                Text("No media available", Modifier.align(Alignment.Center), color = Color.White)
            else -> VerticalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                flingBehavior = PagerDefaults.flingBehavior(
                    state = pagerState,
                    pagerSnapDistance = PagerSnapDistance.atMost(1),
                    snapPositionalThreshold = .5f,
                    snapAnimationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                ),
                key = { index -> itemAt(index)?.postId ?: "media-placeholder-$index" },
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                loadItemAt(page)?.let { item ->
                    SharedMediaPage(
                        item = item,
                        active = page == pagerState.currentPage,
                        selectedIndex = selectedMedia[item.postId] ?: 0,
                        chromeVisible = chromeVisible,
                        onSelected = { selectedMedia[item.postId] = it },
                        onToggleChrome = {
                            interactions.record(MediaFeedInteraction.ToggleChrome) {
                                chromeVisible = !chromeVisible
                            }
                        },
                        onOpenDetails = {
                            interactions.record(MediaFeedInteraction.OpenDetail) { onOpenDetails(item) }
                        },
                        onOpenUser = {
                            interactions.record(MediaFeedInteraction.OpenProfile) {
                                onOpenUser(item.author.removePrefix("u/"))
                            }
                        },
                        onVote = { value ->
                            interactions.record(MediaFeedInteraction.Vote) { onVote(item.postId, value) }
                        },
                        onShare = {
                            interactions.record(MediaFeedInteraction.Share) { onShare(item) }
                        },
                        interactions = interactions,
                        imageRenderer = imageRenderer,
                        videoRenderer = videoRenderer,
                        hasRenderedVideoFrame = hasRenderedVideoFrame,
                    )
                }
            }
        }

        if (chromeVisible) {
            MediaFeedTopBar(
                community = currentCommunity,
                avatarUrl = currentItem?.communityAvatarUrl,
                currentItem = currentItem,
                onClose = { interactions.record(MediaFeedInteraction.Close, onClose) },
                onOpenDetails = {
                    currentItem?.let { item ->
                        interactions.record(MediaFeedInteraction.OpenDetail) { onOpenDetails(item) }
                    }
                },
                onShare = {
                    currentItem?.let { item ->
                        interactions.record(MediaFeedInteraction.Share) { onShare(item) }
                    }
                },
                onOpenCommunity = { community ->
                    interactions.record(MediaFeedInteraction.OpenCommunity) {
                        onOpenCommunity(community)
                    }
                },
                interactions = interactions,
                imageRenderer = imageRenderer,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

fun mediaFeedItemAt(
    pagedItem: MediaFeedItem?,
    navigationItems: List<MediaFeedItem>,
    index: Int,
): MediaFeedItem? = pagedItem ?: navigationItems.getOrNull(index)

fun mediaFeedPreloadPlan(
    items: List<MediaFeedItem?>,
    currentPage: Int,
    selectedMedia: Map<String, Int>,
): MediaFeedPreloadPlan {
    val videos = items.flatMapIndexed { page, item ->
        item?.allMedia.orEmpty().mapIndexedNotNull { mediaIndex, media ->
            media.takeIf(MediaFeedMedia::isVideo)?.let {
                MediaFeedVideoPreloadEntry(page, mediaIndex, item!!.postId, it)
            }
        }
    }
    val selectedIndex = items.getOrNull(currentPage)?.let { selectedMedia[it.postId] } ?: 0
    val exactFocus = videos.indexOfFirst { it.page == currentPage && it.mediaIndex == selectedIndex }
    val videoFocus = exactFocus.takeIf { it >= 0 }
        ?: videos.indexOfFirst { it.page >= currentPage }.takeIf { it >= 0 }
        ?: videos.lastIndex
    val imageRange = if (items.isEmpty()) IntRange.EMPTY else {
        (currentPage - 1).coerceAtLeast(0)..(currentPage + 2).coerceAtMost(items.lastIndex)
    }
    val images = imageRange.flatMap { page ->
        val item = items.getOrNull(page) ?: return@flatMap emptyList()
        item.allMedia.mapNotNull { media ->
            media.prefetchUrl()?.let { url ->
                MediaFeedImagePreloadRequest(
                    url = url,
                    cacheKey = media.mediaFeedImageCacheKey(item.postId),
                    videoPreview = media.isVideo,
                )
            }
        }
    }.distinctBy(MediaFeedImagePreloadRequest::cacheKey)
    return MediaFeedPreloadPlan(videos, videoFocus, images)
}

fun MediaFeedMedia.mediaFeedImageCacheKey(postId: String): String = if (isVideo) {
    videoPosterCacheKey(cacheKey ?: mediaId?.let { "video:$it" } ?: "post:$postId", posterUrl)
} else {
    cacheKey ?: mediaId?.let { "image:$it" } ?: "post:$postId"
}

private fun MediaFeedMedia.prefetchUrl(): String? = if (isVideo) posterUrl else zoomUrl ?: url ?: posterUrl

@Composable
private fun SharedMediaPage(
    item: MediaFeedItem,
    active: Boolean,
    selectedIndex: Int,
    chromeVisible: Boolean,
    onSelected: (Int) -> Unit,
    onToggleChrome: () -> Unit,
    onOpenDetails: () -> Unit,
    onOpenUser: () -> Unit,
    onVote: (Int) -> Unit,
    onShare: () -> Unit,
    interactions: MediaFeedInteractionRecorder,
    imageRenderer: MediaFeedImageRenderer,
    videoRenderer: MediaFeedVideoRenderer,
    hasRenderedVideoFrame: (MediaFeedItem, MediaFeedMedia) -> Boolean,
) {
    val mediaItems = item.allMedia
    val horizontalPager = rememberPagerState(
        initialPage = selectedIndex.coerceIn(mediaItems.indices),
        pageCount = mediaItems::size,
    )
    LaunchedEffect(horizontalPager.currentPage) { onSelected(horizontalPager.currentPage) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        HorizontalPager(
            state = horizontalPager,
            beyondViewportPageCount = 1,
            key = { page -> mediaItems[page].mediaId ?: mediaItems[page].cacheKey ?: page },
            modifier = Modifier.fillMaxSize(),
        ) { mediaIndex ->
            val media = mediaItems[mediaIndex]
            SharedMedia(
                item = item,
                mediaIndex = mediaIndex,
                media = media,
                active = active && horizontalPager.currentPage == mediaIndex,
                chromeVisible = chromeVisible,
                onToggleChrome = onToggleChrome,
                onOpenDetails = onOpenDetails,
                onOpenUser = onOpenUser,
                onVote = onVote,
                onShare = onShare,
                interactions = interactions,
                imageRenderer = imageRenderer,
                videoRenderer = videoRenderer,
                hasRenderedVideoFrame = hasRenderedVideoFrame,
            )
        }

        if (mediaItems.size > 1) {
            Surface(
                color = Color.Black.copy(alpha = .68f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding()
                    .padding(top = 54.dp, end = 12.dp),
            ) {
                Text(
                    "${horizontalPager.currentPage + 1}/${mediaItems.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }

    }
}

@Composable
private fun SharedMedia(
    item: MediaFeedItem,
    mediaIndex: Int,
    media: MediaFeedMedia,
    active: Boolean,
    chromeVisible: Boolean,
    onToggleChrome: () -> Unit,
    onOpenDetails: () -> Unit,
    onOpenUser: () -> Unit,
    onVote: (Int) -> Unit,
    onShare: () -> Unit,
    interactions: MediaFeedInteractionRecorder,
    imageRenderer: MediaFeedImageRenderer,
    videoRenderer: MediaFeedVideoRenderer,
    hasRenderedVideoFrame: (MediaFeedItem, MediaFeedMedia) -> Boolean,
) {
    val stableKey = media.cacheKey ?: media.mediaId ?: "${item.postId}:$mediaIndex"
    var renderedFirstFrame by remember(item.postId, stableKey, active) {
        mutableStateOf(active && hasRenderedVideoFrame(item, media))
    }
    var playRequested by remember(item.postId, stableKey) { mutableStateOf(true) }
    var userInitiatedPlayback by remember(item.postId, stableKey) { mutableStateOf(false) }
    var muted by remember(item.postId, stableKey) { mutableStateOf(false) }
    var replayRequest by remember(item.postId, stableKey) { mutableStateOf(0) }
    var seekRequestId by remember(item.postId, stableKey) { mutableStateOf(0L) }
    var seekRequest by remember(item.postId, stableKey) { mutableStateOf<MediaFeedSeekRequest?>(null) }
    var playback by remember(item.postId, stableKey) {
        mutableStateOf(MediaFeedPlaybackSnapshot(MediaFeedPlaybackState.Idle, 0L, null))
    }
    val gestureSurface = Modifier.fillMaxSize().clickable(
        onClickLabel = "Show or hide post details",
        onClick = onToggleChrome,
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (media.isVideo) {
            if (active) {
                videoRenderer(
                    item,
                    media,
                    MediaFeedVideoPlaybackRequest(
                        playRequested = playRequested,
                        userInitiatedPlayback = userInitiatedPlayback,
                        muted = muted,
                        replayRequest = replayRequest,
                        seekRequest = seekRequest,
                    ),
                    { renderedFirstFrame = true },
                    { playback = it },
                    Modifier.fillMaxSize(),
                )
            }
            if (!active || !renderedFirstFrame) media.posterUrl?.let { preview ->
                imageRenderer(
                    preview,
                    media.mediaFeedImageCacheKey(item.postId),
                    true,
                    media.altText,
                    ContentScale.Fit,
                    Modifier.fillMaxSize().background(Color.Black),
                )
            }
            Box(gestureSurface)
        } else {
            val url = media.zoomUrl ?: media.url ?: media.posterUrl
            if (url != null) ZoomableMediaImage(
                postId = item.postId,
                stableKey = stableKey,
                url = url,
                cacheKey = media.mediaFeedImageCacheKey(item.postId),
                contentDescription = media.altText,
                onToggleChrome = onToggleChrome,
                imageRenderer = imageRenderer,
            )
        }
        if (active && chromeVisible) {
            val displayedPlayback = playback.copy(
                durationMs = playback.durationMs ?: media.durationSeconds?.times(1_000L),
            )
            MediaFeedBottomChrome(
                item = item,
                onOpenDetails = onOpenDetails,
                onOpenUser = onOpenUser,
                onVote = onVote,
                onShare = onShare,
                playback = displayedPlayback.takeIf { media.isVideo },
                playRequested = playRequested,
                muted = muted,
                onPrimaryAction = { action ->
                    val interaction = when (action) {
                        MediaFeedPrimaryPlaybackAction.Play -> MediaFeedInteraction.Play
                        MediaFeedPrimaryPlaybackAction.Pause -> MediaFeedInteraction.Pause
                        MediaFeedPrimaryPlaybackAction.Replay -> MediaFeedInteraction.Replay
                    }
                    interactions.record(interaction) {
                        when (action) {
                            MediaFeedPrimaryPlaybackAction.Play -> {
                                userInitiatedPlayback = true
                                playRequested = true
                            }
                            MediaFeedPrimaryPlaybackAction.Pause -> {
                                // Keep the leased player/frame/buffer attached while paused.
                                userInitiatedPlayback = true
                                playRequested = false
                            }
                            MediaFeedPrimaryPlaybackAction.Replay -> {
                                userInitiatedPlayback = true
                                playRequested = true
                                replayRequest += 1
                            }
                        }
                    }
                },
                onSeek = { positionMs ->
                    interactions.record(MediaFeedInteraction.Seek) {
                        // A seek while policy-paused acquires the native player without starting it.
                        if (playback.state == MediaFeedPlaybackState.Paused) playRequested = false
                        userInitiatedPlayback = true
                        seekRequestId += 1L
                        seekRequest = MediaFeedSeekRequest(seekRequestId, positionMs)
                    }
                },
                onToggleMuted = {
                    interactions.record(
                        if (muted) MediaFeedInteraction.Unmute else MediaFeedInteraction.Mute,
                    ) { muted = !muted }
                },
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }
}

@Composable
private fun MediaFeedVideoControls(
    playback: MediaFeedPlaybackSnapshot,
    playRequested: Boolean,
    muted: Boolean,
    onPrimaryAction: (MediaFeedPrimaryPlaybackAction) -> Unit,
    onSeek: (Long) -> Unit,
    onToggleMuted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primaryAction = mediaFeedPrimaryPlaybackAction(playback.state, playRequested)
    val primaryIcon = when (primaryAction) {
        MediaFeedPrimaryPlaybackAction.Play -> Icons.Default.PlayArrow
        MediaFeedPrimaryPlaybackAction.Pause -> Icons.Default.Pause
        MediaFeedPrimaryPlaybackAction.Replay -> Icons.Default.Replay
    }
    val primaryLabel = when (primaryAction) {
        MediaFeedPrimaryPlaybackAction.Play -> "Play video"
        MediaFeedPrimaryPlaybackAction.Pause -> "Pause video"
        MediaFeedPrimaryPlaybackAction.Replay -> "Replay video"
    }
    val durationMs = playback.durationMs?.takeIf { it > 0L }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableStateOf(mediaFeedPlaybackFraction(playback.positionMs, durationMs)) }
    LaunchedEffect(playback.positionMs, durationMs, scrubbing) {
        if (!scrubbing) scrubFraction = mediaFeedPlaybackFraction(playback.positionMs, durationMs)
    }
    val displayedPosition = if (scrubbing && durationMs != null) {
        mediaFeedSeekPosition(scrubFraction, durationMs)
    } else {
        playback.positionMs
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { onPrimaryAction(primaryAction) },
            modifier = Modifier.size(40.dp),
        ) {
            if (playback.state == MediaFeedPlaybackState.Buffering && playRequested) {
                CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Icon(primaryIcon, primaryLabel, Modifier.size(24.dp), tint = Color.White)
            }
        }
        Slider(
            value = scrubFraction,
            onValueChange = {
                scrubbing = true
                scrubFraction = it.coerceIn(0f, 1f)
            },
            onValueChangeFinished = {
                durationMs?.let { onSeek(mediaFeedSeekPosition(scrubFraction, it)) }
                scrubbing = false
            },
            enabled = durationMs != null,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = .5f),
                disabledThumbColor = Color.White.copy(alpha = .65f),
                disabledActiveTrackColor = Color.White.copy(alpha = .45f),
                disabledInactiveTrackColor = Color.White.copy(alpha = .28f),
            ),
            modifier = Modifier.weight(1f).semantics { contentDescription = "Video position" },
        )
        Text(
            mediaFeedTimeLabel(displayedPosition),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        IconButton(
            onClick = onToggleMuted,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                if (muted) "Unmute video" else "Mute video",
                Modifier.size(22.dp),
                tint = Color.White,
            )
        }
    }
}

fun mediaFeedPlaybackFraction(positionMs: Long, durationMs: Long?): Float = durationMs
    ?.takeIf { it > 0L }
    ?.let { (positionMs.coerceIn(0L, it).toDouble() / it).toFloat() }
    ?: 0f

fun mediaFeedSeekPosition(fraction: Float, durationMs: Long): Long =
    (fraction.coerceIn(0f, 1f) * durationMs.coerceAtLeast(0L)).toLong()

fun mediaFeedTimeLabel(positionMs: Long): String {
    val totalSeconds = positionMs.coerceAtLeast(0L) / 1_000L
    val seconds = totalSeconds % 60L
    val minutes = (totalSeconds / 60L) % 60L
    val hours = totalSeconds / 3_600L
    return if (hours > 0L) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

@Composable
private fun ZoomableMediaImage(
    postId: String,
    stableKey: String,
    url: String,
    cacheKey: String,
    contentDescription: String,
    onToggleChrome: () -> Unit,
    imageRenderer: MediaFeedImageRenderer,
) {
    var scale by remember(postId, stableKey) { mutableStateOf(1f) }
    var offset by remember(postId, stableKey) { mutableStateOf(Offset.Zero) }
    imageRenderer(
        url,
        cacheKey,
        false,
        contentDescription,
        ContentScale.Fit,
        Modifier.fillMaxSize()
            .background(Color.Black)
            .pointerInput(postId, stableKey) {
                detectTapGestures(
                    onTap = { onToggleChrome() },
                    onDoubleTap = {
                        scale = if (scale > 1f) 1f else 2.5f
                        offset = Offset.Zero
                    },
                )
            }
            .pointerInput(postId, stableKey) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pan = event.calculatePan()
                        val pinching = event.changes.count { it.pressed } >= 2
                        val panning = scale > 1f && pan != Offset.Zero
                        if (pinching || panning) {
                            val previousScale = scale
                            val nextScale = (previousScale * if (pinching) event.calculateZoom() else 1f)
                                .takeIf(Float::isFinite)?.coerceIn(1f, 4f) ?: previousScale
                            if (nextScale <= 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                val ratio = nextScale / previousScale
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val focal = event.calculateCentroid() - center
                                scale = nextScale
                                offset = constrainMediaOffset(
                                    (offset - focal) * ratio + focal + pan,
                                    nextScale,
                                    size.width,
                                    size.height,
                                )
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}

fun constrainMediaOffset(
    proposed: Offset,
    scale: Float,
    viewportWidth: Int,
    viewportHeight: Int,
): Offset {
    if (scale <= 1f || viewportWidth <= 0 || viewportHeight <= 0) return Offset.Zero
    val maxX = viewportWidth * (scale - 1f) / 2f
    val maxY = viewportHeight * (scale - 1f) / 2f
    return Offset(proposed.x.coerceIn(-maxX, maxX), proposed.y.coerceIn(-maxY, maxY))
}

@Composable
private fun MediaFeedBottomChrome(
    item: MediaFeedItem,
    onOpenDetails: () -> Unit,
    onOpenUser: () -> Unit,
    onVote: (Int) -> Unit,
    onShare: () -> Unit,
    playback: MediaFeedPlaybackSnapshot? = null,
    playRequested: Boolean = false,
    muted: Boolean = false,
    onPrimaryAction: ((MediaFeedPrimaryPlaybackAction) -> Unit)? = null,
    onSeek: ((Long) -> Unit)? = null,
    onToggleMuted: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Surface(
            color = Color.Black.copy(alpha = .48f),
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
                if (
                    playback != null &&
                    onPrimaryAction != null &&
                    onSeek != null &&
                    onToggleMuted != null
                ) {
                    MediaFeedVideoControls(
                        playback = playback,
                        playRequested = playRequested,
                        muted = muted,
                        onPrimaryAction = onPrimaryAction,
                        onSeek = onSeek,
                        onToggleMuted = onToggleMuted,
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier.clickable(onClickLabel = "Upvote") {
                            onVote(if (item.viewerVote == 1) 0 else 1)
                        }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.KeyboardArrowUp,
                            null,
                            tint = if (item.viewerVote == 1) MaterialTheme.colorScheme.primary else Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(item.score.toString(), color = Color.White)
                    }
                    Icon(
                        Icons.Outlined.KeyboardArrowDown,
                        "Downvote",
                        tint = if (item.viewerVote == -1) MaterialTheme.colorScheme.primary else Color.White,
                        modifier = Modifier.size(28.dp).clickable {
                            onVote(if (item.viewerVote == -1) 0 else -1)
                        }.padding(3.dp),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(onClickLabel = "Open comments", onClick = onOpenDetails),
                    ) {
                        Text("○", color = Color.White, fontWeight = FontWeight.Bold)
                        Text(" ${item.commentCount}", color = Color.White)
                    }
                    item.postedAgo.takeIf(String::isNotBlank)?.let {
                        Text(it, color = Color.White.copy(alpha = .8f))
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onShare) { Icon(Icons.Default.Share, "Share post", tint = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun MediaFeedTopBar(
    community: String?,
    avatarUrl: String?,
    currentItem: MediaFeedItem?,
    onClose: () -> Unit,
    onOpenDetails: () -> Unit,
    onShare: () -> Unit,
    onOpenCommunity: (String) -> Unit,
    interactions: MediaFeedInteractionRecorder,
    imageRenderer: MediaFeedImageRenderer,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember(currentItem?.postId) { mutableStateOf(false) }
    Box(
        modifier.fillMaxWidth().background(Color.Black).statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(Icons.Default.Close, "Close media feed", tint = Color.White)
        }
        Crossfade(
            targetState = community,
            animationSpec = tween(220),
            label = "media-community-title",
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp),
        ) { targetCommunity ->
            targetCommunity?.let { name ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(
                        onClickLabel = "Open community",
                        onClick = { onOpenCommunity(name) },
                    ).padding(vertical = 6.dp),
                ) {
                    CommunityAvatar(name, avatarUrl, imageRenderer)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "r/$name",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.size(36.dp))
                }
            }
        }
        Box(modifier = Modifier.align(Alignment.CenterEnd)) {
            IconButton(
                enabled = currentItem != null,
                onClick = {
                    interactions.record(MediaFeedInteraction.OpenMenu) { menuExpanded = true }
                },
            ) {
                Icon(Icons.Default.MoreVert, "More options", tint = Color.White)
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("Open post & comments") },
                    onClick = {
                        menuExpanded = false
                        onOpenDetails()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Share post") },
                    onClick = {
                        menuExpanded = false
                        onShare()
                    },
                )
                community?.let { name ->
                    DropdownMenuItem(
                        text = { Text("Open r/$name") },
                        onClick = {
                            menuExpanded = false
                            onOpenCommunity(name)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CommunityAvatar(
    community: String,
    avatarUrl: String?,
    imageRenderer: MediaFeedImageRenderer,
) {
    Box(
        Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
            .semantics { contentDescription = "r/$community community avatar" },
        contentAlignment = Alignment.Center,
    ) {
        if (!avatarUrl.isNullOrBlank()) {
            imageRenderer(
                avatarUrl,
                "community-avatar:$avatarUrl",
                false,
                "",
                ContentScale.Crop,
                Modifier.fillMaxSize(),
            )
        } else {
            Text(
                community.take(1).uppercase(),
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun MediaFeedError(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = Color.White, textAlign = TextAlign.Center)
        Button(onClick = onRetry, modifier = Modifier.padding(top = 12.dp)) { Text("Retry") }
    }
}

private const val IMPRESSION_DWELL_MILLIS = 600L
