package dev.readthat.ui

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.SystemClock

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import coil3.imageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import dev.readthat.domain.CellUi
import dev.readthat.domain.AdLaunchContext
import dev.readthat.domain.AdMediaKind
import dev.readthat.domain.ImageMediaUi
import dev.readthat.domain.NormalFeedMediaContext
import dev.readthat.domain.toPostTransitionPreview
import dev.readthat.core.ui.markdown.MarkdownText
import dev.readthat.core.ui.brand.ReadThatLogo
import dev.readthat.core.ui.typography.ReadThatTextStyles
import dev.readthat.shared.AppSettings
import dev.readthat.shared.VideoPlaybackPolicy
import dev.readthat.shared.videoPosterCacheKey
import dev.readthat.playback.AdaptiveVideoPlayer
import dev.readthat.playback.AdaptiveVideoSource
import dev.readthat.playback.VideoPlaybackCoordinator
import dev.readthat.playback.VideoPlaybackRole
import dev.readthat.playback.rememberVideoPlaybackPolicy
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.absoluteValue
import dev.readthat.observability.PerformanceMetric
import dev.readthat.observability.PerformanceSurface
import dev.readthat.observability.PerformanceTelemetry
import dev.readthat.observability.ProductAnalytics
import dev.readthat.observability.ProductContentType
import dev.readthat.observability.ProductEvent
import dev.readthat.observability.ProductEventName
import dev.readthat.observability.ProductSurface
import dev.readthat.observability.performanceTimer
import kotlinx.coroutines.launch
import dev.readthat.shared.PostTransitionPreview

/** The only signed-in account data the feed chrome needs to render. */
@Immutable
data class FeedAccountHeader(
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val updatedAt: Long = 0,
)

/**
 * The feed.
 *
 * ### What Paging 3 removed from this file
 *
 * The previous version carried a `derivedStateOf` over `LazyListState` that
 * compared the last visible index against a `PREFETCH_THRESHOLD` and called
 * `loadMoreIfNeeded()`. All of that is gone: `PagingConfig.prefetchDistance`
 * expresses the same intent declaratively, and Paging drives it from the
 * `PagingSource` rather than from scroll geometry the UI has to re-measure.
 *
 * What is left is the part Paging cannot decide for you — **which load state
 * gets which pixels**:
 *
 * | Load state           | Treatment                                  |
 * |----------------------|--------------------------------------------|
 * | refresh + empty list | layout-matched skeleton                    |
 * | refresh + cached rows| nothing — show the stale rows, not a blank |
 * | append               | footer spinner                             |
 * | append error         | inline retry row, feed stays usable        |
 *
 * The refresh-with-cached-rows row is the one worth defending out loud: because
 * Room is the source of truth, a failed refresh still has a feed to show. Only
 * a *cold* failure is a full-screen error.
 */
@Composable
@OptIn(FlowPreview::class)
fun FeedScreen(
    viewModel: FeedViewModel,
    onPostClick: (postId: String, preview: PostTransitionPreview) -> Unit,
    onCommunityClick: (communityName: String) -> Unit,
    onProfileClick: (username: String) -> Unit = {},
    /** Media opens the immersive pager; title/body/action taps keep opening detail. */
    onMediaClick: ((context: NormalFeedMediaContext) -> Unit)? = null,
    /** Promoted media opens the hybrid continued-playback media + landing-page detail. */
    onAdClick: ((context: AdLaunchContext) -> Unit)? = null,
    onSearch: () -> Unit = {},
    onOpenNavigation: () -> Unit = {},
    accountHeader: FeedAccountHeader? = null,
    onAccountClick: () -> Unit = {},
    showHeader: Boolean = true,
    settings: AppSettings = AppSettings(),
    onFirstContentRendered: (cacheTier: String) -> Unit = {},
    modifier: Modifier = Modifier,
    /** Applied to the first row of each post so the app shell can retain its screen position. */
    postPositionModifierFor: @Composable (postId: String) -> Modifier = { Modifier },
    /** Optional destination chrome that scrolls as the first row above SDUI groups. */
    listHeader: (@Composable () -> Unit)? = null,
    performanceSurface: PerformanceSurface = PerformanceSurface.FEED,
    productSurface: ProductSurface = ProductSurface.FEED,
    postOpenEventName: String? = null,
) {
    val items = viewModel.feed.collectAsLazyPagingItems()
    val dropped by viewModel.droppedCellTypes.collectAsStateWithLifecycle()
    val initialCacheTier by viewModel.initialCacheTier.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val videoPolicy = rememberVideoPlaybackPolicy(settings)
    var resharePostId by remember { mutableStateOf<String?>(null) }
    var targetCommunity by remember { mutableStateOf("") }
    var reshareError by remember { mutableStateOf<String?>(null) }
    var userInitiatedRefresh by remember { mutableStateOf(false) }
    var firstContentReported by remember { mutableStateOf(false) }
    val interactionScope = rememberCoroutineScope()
    val adIdByGroup = items.itemSnapshotList.items
        .filterIsInstance<CellUi.AdHeader>()
        .associate { it.key.substringBefore('/') to it.adId }

    LaunchedEffect(performanceSurface) { PerformanceTelemetry.enterSurface(performanceSurface) }
    LaunchedEffect(items.itemCount, initialCacheTier) {
        val cacheTier = initialCacheTier
        if (!firstContentReported && items.itemCount > 0 && cacheTier != null) {
            // Stop after pixels containing the first real feed unit are eligible
            // for presentation, not merely when Room invalidates Paging.
            withFrameNanos { }
            firstContentReported = true
            onFirstContentRendered(cacheTier)
        }
    }

    fun interaction(type: String, action: () -> Unit) {
        val timer = performanceTimer()
        action()
        interactionScope.launch {
            withFrameNanos { }
            PerformanceTelemetry.duration(
                PerformanceMetric.INTERACTION_TO_NEXT_FRAME,
                timer,
                surface = performanceSurface,
                attributes = mapOf("interaction_type" to type),
            )
        }
    }

    // DWELL-GATED PREFETCH — the feed side of the comments-TTI story. A post that
    // stays visible for the debounce window gets its 8-count comment tree fetched
    // ahead of the tap. snapshotFlow reads scroll state without recomposing this
    // scope; debounce is the dwell gate so a fast fling prefetches nothing.
    // Reddit's published bill for this behavior: ~40k extra req/s across platforms.
    LaunchedEffect(listState, adIdByGroup) {
        val activeAdViews = mutableMapOf<String, Long>()
        fun finishAdView(adId: String, now: Long) {
            val started = activeAdViews.remove(adId) ?: return
            ProductAnalytics.record(ProductEvent(
                name = ProductEventName.AD_VIEW_TIME,
                surface = productSurface,
                contentId = adId,
                contentType = ProductContentType.AD,
                durationMs = (now - started).coerceAtLeast(0L),
            ))
        }
        try {
            snapshotFlow {
            val layout = listState.layoutInfo
            val viewportSize = layout.viewportEndOffset - layout.viewportStartOffset
            layout.visibleItemsInfo
                .mapNotNull { item ->
                    val key = item.key as? String ?: return@mapNotNull null
                    val groupId = key.substringBefore('/')
                    val cellId = key.substringAfter('/', "")
                    if (
                        key.startsWith("__") ||
                        cellId == "actions" ||
                        // A tiny header crossing the viewport must not count as
                        // a promoted impression. Gate ads on their media stage.
                        (groupId in adIdByGroup && cellId != "media")
                    ) {
                        return@mapNotNull null
                    }
                    val visiblePixels = (
                        minOf(item.offset + item.size, layout.viewportEndOffset) -
                            maxOf(item.offset, layout.viewportStartOffset)
                        ).coerceAtLeast(0)
                    val maximumVisible = minOf(item.size, viewportSize)
                    groupId.takeIf {
                        it.isNotBlank() && maximumVisible > 0 && visiblePixels * 2 >= maximumVisible
                    }
                }
                .toSet()
            }
                .debounce(600)
                .collect { groupIds ->
                val now = SystemClock.elapsedRealtime()
                val visibleAds = groupIds.mapNotNullTo(mutableSetOf()) { adIdByGroup[it] }
                (activeAdViews.keys - visibleAds).forEach { finishAdView(it, now) }
                visibleAds.forEach { adId ->
                    if (activeAdViews.putIfAbsent(adId, now) == null) {
                        ProductAnalytics.record(ProductEvent(
                            name = ProductEventName.AD_IMPRESSION,
                            surface = productSurface,
                            contentId = adId,
                            contentType = ProductContentType.AD,
                        ))
                    }
                }
                val postIds = groupIds.filterNot { it in adIdByGroup }.toSet()
                viewModel.prefetchComments(postIds)
                postIds.forEach { postId ->
                    ProductAnalytics.record(ProductEvent(
                        name = ProductEventName.POST_IMPRESSION,
                        surface = productSurface,
                        contentId = postId,
                        contentType = ProductContentType.POST,
                    ))
                }
            }
        } finally {
            val now = SystemClock.elapsedRealtime()
            activeAdViews.keys.toList().forEach { finishAdView(it, now) }
        }
    }

    val refresh = items.loadState.refresh
    LaunchedEffect(refresh) {
        if (refresh !is LoadState.Loading) userInitiatedRefresh = false
    }

    // The app-shell Scaffold owns system bars and bottom navigation. This feature Scaffold exists
    // only for local layout structure; consuming system bars here would double the top inset.
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (showHeader) {
                HomeHeader(
                    onSearch = onSearch,
                    onOpenNavigation = onOpenNavigation,
                    account = accountHeader,
                    onAccountClick = { interaction("open_profile", onAccountClick) },
                )
            }
            Box(Modifier.fillMaxSize()) {
            when {
                refresh is LoadState.Loading && items.itemCount == 0 ->
                    FeedSkeleton(Modifier.fillMaxSize(), listHeader)

                // Cold failure only. With rows on disk we fall through and render
                // them — an offline launch shows the last feed, not an error page.
                refresh is LoadState.Error && items.itemCount == 0 ->
                    Column(Modifier.fillMaxSize()) {
                        listHeader?.invoke()
                        Box(Modifier.weight(1f)) {
                            ErrorState(
                                refresh.error.message ?: "Could not load the feed",
                            ) {
                                viewModel.markErrorRetry()
                                items.retry()
                            }
                        }
                    }

                else -> PullToRefreshBox(
                    // Automatic stale-cache and app-active refreshes are silent.
                    // Only a gesture the user initiated owns refresh chrome.
                    isRefreshing = userInitiatedRefresh && refresh is LoadState.Loading,
                    onRefresh = {
                        userInitiatedRefresh = true
                        viewModel.markUserRefresh()
                        items.refresh()
                    },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    FeedList(
                        items,
                        dropped,
                        listState,
                        { id, value -> interaction("vote") { viewModel.vote(id, value) } },
                        { id -> interaction("open_detail") {
                            postOpenEventName?.let { eventName ->
                                ProductAnalytics.record(ProductEvent(
                                    name = eventName,
                                    surface = productSurface,
                                    contentId = id,
                                    contentType = ProductContentType.POST,
                                ))
                            }
                            onPostClick(id, items.transitionPreview(id))
                        } },
                        { id -> interaction("open_media") {
                            val preview = items.transitionPreview(id)
                            if (onMediaClick == null) {
                                onPostClick(id, preview)
                            } else {
                                interactionScope.launch {
                                    onMediaClick(viewModel.mediaLaunchContext(id, preview))
                                }
                            }
                        } },
                        { communityName -> interaction("open_community") {
                            onCommunityClick(communityName)
                        } },
                        { username -> interaction("open_ad_profile") {
                            onProfileClick(username)
                        } },
                        onAdOpen = { ad -> interaction("open_ad") {
                            if (onAdClick != null) onAdClick(ad)
                        } },
                        onRelatedPost = { postId -> interaction("open_ad_related") {
                            onPostClick(postId, items.transitionPreview(postId))
                        } },
                        onRetry = {
                            viewModel.markErrorRetry()
                            items.retry()
                        },
                        onReshare = { resharePostId = it; targetCommunity = ""; reshareError = null },
                        onShare = { postId ->
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "Check out this post: $postId")
                            }, "Share post"))
                        },
                        postPositionModifierFor = postPositionModifierFor,
                        videoPolicy = videoPolicy,
                        listHeader = listHeader,
                    )
                }
            }
            }
        }
    }
    resharePostId?.let { postId ->
        AlertDialog(
            onDismissRequest = { resharePostId = null },
            title = { Text("Reshare to a community") },
            text = {
                Column {
                    OutlinedTextField(
                        value = targetCommunity,
                        onValueChange = { targetCommunity = it; reshareError = null },
                        label = { Text("r/community") },
                        singleLine = true,
                    )
                    reshareError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = targetCommunity.trim().removePrefix("r/").length >= 3,
                    onClick = {
                        viewModel.reshare(postId, targetCommunity) { error ->
                            if (error == null) resharePostId = null else reshareError = error
                        }
                    },
                ) { Text("Reshare") }
            },
            dismissButton = { TextButton(onClick = { resharePostId = null }) { Text("Cancel") } },
        )
    }
}

@Composable
@OptIn(FlowPreview::class)
private fun FeedList(
    items: LazyPagingItems<CellUi>,
    dropped: Map<String, Int>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onVote: (String, Int) -> Unit,
    onOpen: (String) -> Unit,
    onOpenMedia: (String) -> Unit,
    onOpenCommunity: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onAdOpen: (AdLaunchContext) -> Unit,
    onRelatedPost: (String) -> Unit,
    onRetry: () -> Unit,
    onReshare: (String) -> Unit,
    onShare: (String) -> Unit,
    postPositionModifierFor: @Composable (postId: String) -> Modifier,
    videoPolicy: VideoPlaybackPolicy,
    listHeader: (@Composable () -> Unit)?,
) {
    val context = LocalContext.current.applicationContext
    val snapshotItems = items.itemSnapshotList.items
    val videoEntries = remember(snapshotItems) {
        snapshotItems.flatMapIndexed { index, item ->
            when (item) {
                is CellUi.Media -> item.takeIf { it.video != null }?.let { media -> listOf(
                    FeedVideoEntry(
                    cellIndex = index,
                    cellKey = media.key,
                    source = media.toAdaptiveVideoSource(),
                    posterUrl = media.video?.posterUrl,
                    posterCacheKey = media.videoPosterCacheKey(),
                    ),
                ) } ?: emptyList()
                is CellUi.AdMedia -> item.videoSources().map { source ->
                    val adMedia = item.items.first { it.cacheKey == source.cacheKey }
                    FeedVideoEntry(
                        cellIndex = index,
                        cellKey = item.key,
                        source = source,
                        posterUrl = adMedia.posterUrl,
                        posterCacheKey = videoPosterCacheKey(adMedia.cacheKey, adMedia.posterUrl),
                    )
                }
                else -> emptyList()
            }
        }
    }
    val imageEntries = remember(snapshotItems) { feedImageEntries(snapshotItems) }
    val imageLoader = remember(context) { context.imageLoader }
    val prefetchWidth = remember(context) { context.resources.displayMetrics.widthPixels }
    val prefetchHeight = remember(context) { context.resources.displayMetrics.heightPixels }
    val videoPreloadOwner = remember { Any() }
    val posterRequests = remember { mutableMapOf<String, coil3.request.Disposable>() }
    val imageRequests = remember { mutableMapOf<String, coil3.request.Disposable>() }
    var activeVideoKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(listState, videoPolicy.autoplay, videoEntries) {
        val videoKeys = videoEntries.mapTo(HashSet(), FeedVideoEntry::cellKey)
        snapshotFlow {
            val layout = listState.layoutInfo
            selectActiveVideoKey(
                visibleItems = layout.visibleItemsInfo.mapNotNull { item ->
                    (item.key as? String)?.let { FeedVisibleItem(it, item.offset, item.size) }
                },
                videoKeys = videoKeys,
                viewportStart = layout.viewportStartOffset,
                viewportEnd = layout.viewportEndOffset,
            )
        }
            .debounce(150)
            .distinctUntilChanged()
            .collect { selectedKey ->
                activeVideoKey = selectedKey.takeIf { videoPolicy.autoplay }
            }
    }
    // Start the likely next video before any of its pixels enter the viewport.
    // DefaultPreloadManager then keeps only the small adjacent window hot.
    LaunchedEffect(listState, videoEntries, videoPolicy) {
        if (videoEntries.isEmpty() || !videoPolicy.allowPrefetch) {
            VideoPlaybackCoordinator.clearPreloadWindow(videoPreloadOwner)
            return@LaunchedEffect
        }
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { firstVisible ->
                val focus = videoEntries.indexOfFirst { it.cellIndex >= firstVisible }
                    .takeIf { it >= 0 } ?: videoEntries.lastIndex
                VideoPlaybackCoordinator.updatePreloadWindow(
                    context = context,
                    owner = videoPreloadOwner,
                    role = VideoPlaybackRole.Feed,
                    sources = videoEntries.map(FeedVideoEntry::source),
                    focusIndex = focus,
                    policy = videoPolicy,
                )
            }
    }
    // Warm the same nearby window into Coil's L1/L2 before LazyColumn composes those rows. The
    // server poster is a small static JPEG, so this is cheaper and more deterministic than
    // seeking/decrypting an HLS segment merely to manufacture a local frame while scrolling.
    LaunchedEffect(listState, videoEntries) {
        if (videoEntries.isEmpty()) {
            posterRequests.values.forEach(coil3.request.Disposable::dispose)
            posterRequests.clear()
            return@LaunchedEffect
        }
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { firstVisible ->
                val focus = videoEntries.indexOfFirst { it.cellIndex >= firstVisible }
                    .takeIf { it >= 0 } ?: videoEntries.lastIndex
                val window = (focus - 2).coerceAtLeast(0)..(focus + 6).coerceAtMost(videoEntries.lastIndex)
                val desiredKeys = window.mapTo(mutableSetOf()) { videoEntries[it].posterCacheKey }
                (posterRequests.keys - desiredKeys).forEach { key ->
                    posterRequests.remove(key)?.dispose()
                }
                window.forEach { index ->
                    val entry = videoEntries[index]
                    val poster = entry.posterUrl ?: return@forEach
                    if (entry.posterCacheKey !in posterRequests) {
                        posterRequests[entry.posterCacheKey] = imageLoader.enqueue(
                            ImageRequest.Builder(context)
                                .data(poster)
                                .memoryCacheKey(entry.posterCacheKey)
                                .diskCacheKey(entry.posterCacheKey)
                                .size(prefetchWidth, prefetchHeight)
                                .precision(Precision.INEXACT)
                                .build(),
                        )
                    }
                }
        }
    }
    // Paging prefetches feed data, but it cannot warm Coil by itself. Keep decoded images and
    // carousel pages around the viewport in the same L1/L2 cache used by AsyncImage so a newly
    // composed row does not expose its server placeholder while the first request starts.
    LaunchedEffect(listState, imageEntries) {
        if (imageEntries.isEmpty()) {
            imageRequests.values.forEach(coil3.request.Disposable::dispose)
            imageRequests.clear()
            return@LaunchedEffect
        }
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { firstVisible ->
                val focus = imageEntries.indexOfFirst { it.cellIndex >= firstVisible }
                    .takeIf { it >= 0 } ?: imageEntries.lastIndex
                val window = (focus - 3).coerceAtLeast(0)..
                    (focus + 12).coerceAtMost(imageEntries.lastIndex)
                val desiredKeys = window.mapTo(mutableSetOf()) { imageEntries[it].cacheKey }
                (imageRequests.keys - desiredKeys).forEach { key ->
                    imageRequests.remove(key)?.dispose()
                }
                window.forEach { index ->
                    val entry = imageEntries[index]
                    if (entry.cacheKey !in imageRequests) {
                        imageRequests[entry.cacheKey] = imageLoader.enqueue(
                            ImageRequest.Builder(context)
                                .data(entry.url)
                                .memoryCacheKey(entry.cacheKey)
                                .diskCacheKey(entry.cacheKey)
                                .size(prefetchWidth, prefetchHeight)
                                .precision(Precision.INEXACT)
                                .build(),
                        )
                    }
                }
            }
    }
    DisposableEffect(Unit) {
        onDispose {
            VideoPlaybackCoordinator.clearPreloadWindow(videoPreloadOwner)
            posterRequests.values.forEach(coil3.request.Disposable::dispose)
            posterRequests.clear()
            imageRequests.values.forEach(coil3.request.Disposable::dispose)
            imageRequests.clear()
        }
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {

        listHeader?.let { header ->
            item(key = "__feed_header__", contentType = "feed_header") { header() }
        }

        // Forward-compatibility and prefetch metrics remain side channels. They
        // should never occupy feed pixels in a production-shaped client.

        // itemKey      -> stable identity across page loads: correct scroll
        //                 restoration and minimal recomposition.
        // itemContentType -> lets Compose reuse a composition across cells of the
        //                 same shape. It matters far more under SDUI than in a
        //                 fixed feed, because one list holds seven cell types.
        items(
            count = items.itemCount,
            key = items.itemKey { it.key },
            contentType = items.itemContentType { it::class.simpleName },
        ) { index ->
            // Null only when placeholders are enabled; they are not, so this is
            // defensive rather than a real branch.
            items[index]?.let {
                CellRenderer(
                    it, onVote, onOpen, onOpenMedia, onOpenCommunity, onOpenProfile, onAdOpen, onRelatedPost,
                    onReshare, onShare,
                    postPositionModifierFor,
                    playInline = it.key == activeVideoKey,
                    videoPolicy = videoPolicy,
                )
            }
        }

        when (val append = items.loadState.append) {
            is LoadState.Loading -> item(key = "__append_spinner__") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }

            is LoadState.Error -> item(key = "__append_error__") {
                AppendError(append.error.message ?: "Could not load more") {
                    onRetry()
                }
            }

            else -> Unit
        }
    }
}

@Composable
private fun FeedSkeleton(
    modifier: Modifier = Modifier,
    listHeader: (@Composable () -> Unit)? = null,
) {
    LazyColumn(modifier) {
        listHeader?.let { header ->
            item(key = "__feed_header_skeleton__", contentType = "feed_header") { header() }
        }
        items(3, key = { "__feed_skeleton_$it" }) { index ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(if (index == 0) 0.42f else 0.34f).height(12.dp),
                ) {}
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(0.88f).height(22.dp),
                ) {}
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 7f),
                ) {}
            }
        }
    }
}

@Composable
private fun AppendError(message: String, onRetry: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clickable(onClick = onRetry),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Text(
            "Retry",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun CellRenderer(
    item: CellUi,
    onVote: (String, Int) -> Unit,
    onOpen: (String) -> Unit,
    onOpenMedia: (String) -> Unit,
    onOpenCommunity: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onAdOpen: (AdLaunchContext) -> Unit,
    onRelatedPost: (String) -> Unit,
    onReshare: (String) -> Unit,
    onShare: (String) -> Unit,
    postPositionModifierFor: @Composable (postId: String) -> Modifier,
    playInline: Boolean,
    videoPolicy: VideoPlaybackPolicy,
) {
    // Composite keys are "groupId/cellId"; the group id IS the post id. Content
    // cells tap through to post detail; the action bar keeps its own tap targets.
    val postId = item.key.substringBefore('/')
    val open = Modifier.clickable { onOpen(postId) }
    when (item) {
        is CellUi.Metadata -> MetadataCell(item, onOpenCommunity, postPositionModifierFor(postId))
        is CellUi.Title -> Box(open) { TitleCell(item) }
        is CellUi.Text -> Box(open) { TextCell(item) }
        is CellUi.Media -> Box {
            MediaCell(item, playInline, videoPolicy)
            // AndroidView/PlayerView owns its touch stream even when feed controls are hidden.
            // Keep a Compose hit target above it so an active video opens detail just like its
            // poster, title, and other server-driven content cells.
            Box(
                Modifier
                    .matchParentSize()
                    .semantics { contentDescription = "Open media feed" }
                    .clickable(onClickLabel = "Open immersive media feed") { onOpenMedia(postId) },
            )
        }
        is CellUi.ImageCarousel -> ImageCarouselCell(item, onOpenMedia = { onOpenMedia(postId) })
        is CellUi.AdHeader -> AdHeaderCell(item, onOpenProfile, postPositionModifierFor(postId))
        is CellUi.AdTitle -> AdTitleCell(item)
        is CellUi.AdMedia -> AdMediaCell(item, playInline, videoPolicy, onAdOpen)
        is CellUi.AdSummary -> AdSummaryCell(item)
        is CellUi.AdRelatedPosts -> AdRelatedPostsCell(item, onRelatedPost)
        is CellUi.AdActionBar -> AdActionBarCell(item)
        is CellUi.Link -> Box(open) { LinkCell(item) }
        is CellUi.ActionBar -> ActionBarCell(item, onVote, onOpen, onReshare, onShare)
        is CellUi.Announcement -> AnnouncementCell(item)
        is CellUi.GroupDivider -> HorizontalDivider(thickness = 6.dp, color = MaterialTheme.colorScheme.surfaceVariant)
    }
}

@Composable
private fun ImageCarouselCell(
    item: CellUi.ImageCarousel,
    onOpenMedia: () -> Unit,
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
            .aspectRatio(item.items.first().aspectRatio)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val image = item.items[page]
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(
                        onClickLabel = "Open immersive media feed",
                        onClick = onOpenMedia,
                    )
                    .semantics { contentDescription = image.altText },
            ) {
                image.sourceUrl?.let { url ->
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(url)
                            .memoryCacheKey(image.feedImageCacheKey(item.key, page))
                            .diskCacheKey(image.feedImageCacheKey(item.key, page))
                            .build(),
                        contentDescription = image.altText,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
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

@Composable
private fun LinkCell(item: CellUi.Link, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(item.domain, style = ReadThatTextStyles.feedSupporting)
            Text(
                item.url,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = ReadThatTextStyles.feedMetadata,
            )
        }
    }
}

@Composable
private fun MetadataCell(
    item: CellUi.Metadata,
    onOpenCommunity: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val communityName = item.subreddit.trim().removePrefix("r/")
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .clickable(
                    enabled = communityName.isNotBlank(),
                    onClickLabel = "Open r/$communityName",
                ) { onOpenCommunity(communityName) },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(28.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    communityName.take(1).uppercase(),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = ReadThatTextStyles.feedMetadata,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                item.line,
                style = ReadThatTextStyles.feedMetadata,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.Outlined.MoreHoriz, "Post options", Modifier.size(20.dp))
        if (item.pinned) {
            Text(
                "PINNED",
                style = ReadThatTextStyles.feedMetadata,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun TitleCell(item: CellUi.Title, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(
            item.text,
            style = ReadThatTextStyles.feedTitle,
            fontWeight = FontWeight.Bold,
        )
        item.flair?.let { flair ->
            Surface(
                color = flair.backgroundColor.feedColor(MaterialTheme.colorScheme.surfaceVariant),
                contentColor = flair.textColor.feedColor(MaterialTheme.colorScheme.onSurface),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                Text(
                    flair.text,
                    style = ReadThatTextStyles.feedMetadata,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

private fun String.feedColor(fallback: Color): Color = runCatching {
    Color(AndroidColor.parseColor(this))
}.getOrDefault(fallback)

@Composable
private fun TextCell(item: CellUi.Text, modifier: Modifier = Modifier) {
    MarkdownText(
        markdown = item.body,
        style = ReadThatTextStyles.feedBody,
        maxLines = item.maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun MediaCell(
    item: CellUi.Media,
    playInline: Boolean,
    videoPolicy: VideoPlaybackPolicy,
    modifier: Modifier = Modifier,
) {
    val videoSource = remember(
        item.key,
        item.cacheKey,
        item.video?.hlsUrl,
        item.video?.fallbackUrl,
        item.sourceUrl,
    ) { item.toAdaptiveVideoSource() }
    // A LazyColumn cell can remain composed while ownership moves to another video. Reset the
    // handoff gate synchronously whenever inline playback is reacquired; retaining the old `true`
    // would remove the poster one composition before the newly selected media renders. The only
    // safe exception is a same-media feed/detail handoff whose decoded frame is still current.
    val videoSourceIdentity = videoSource.cacheKey ?: videoSource.hlsUrl ?: videoSource.fallbackUrl
    var renderedFirstFrame by remember(item.key, videoSourceIdentity, playInline) {
        mutableStateOf(
            retainRenderedVideoFrame(
                playInline = playInline,
                coordinatorHasRenderedSource = VideoPlaybackCoordinator.hasRendered(videoSource),
            ),
        )
    }
    Box(
        modifier
            .fillMaxWidth()
            // The shared-bounds owner is the complete post column, not only the inset media.
            // Paint its gutters before padding so the detail reveal curtain can never show
            // through transparent pixels while the row is lifted into the transition overlay.
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .aspectRatio(item.aspectRatio)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            // Accessibility on a generic, server-described cell: alt text has to come
            // from the server, because the client no longer knows what it is showing.
            .semantics { contentDescription = item.altText },
        contentAlignment = Alignment.BottomEnd,
    ) {
        item.sourceUrl?.takeIf { item.video == null }?.let { url ->
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(url)
                    .memoryCacheKey(item.feedImageCacheKey())
                    .diskCacheKey(item.feedImageCacheKey())
                    .build(),
                contentDescription = item.altText,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        val video = item.video
        if (video != null && playInline && (video.hlsUrl != null || video.fallbackUrl != null)) {
            AdaptiveVideoPlayer(
                source = videoSource,
                policy = videoPolicy,
                autoplay = true,
                muted = true,
                showControls = false,
                role = VideoPlaybackRole.Feed,
                onFirstFrame = { renderedFirstFrame = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Keep the cached poster above TextureView's opaque pre-render surface. Removing it only
        // from Media3's rendered-first-frame callback prevents a black flash even on a cache miss.
        if (video != null && (!playInline || !renderedFirstFrame)) {
            video.posterUrl?.let { poster ->
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(poster)
                        .memoryCacheKey(item.videoPosterCacheKey())
                        .diskCacheKey(item.videoPosterCacheKey())
                        .build(),
                    contentDescription = item.altText,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (video != null && (!playInline || !renderedFirstFrame)) {
            Icon(
                Icons.Default.PlayCircle,
                "Play video",
                Modifier.align(Alignment.Center).size(54.dp),
                tint = Color.White.copy(alpha = .9f),
            )
        }
        if (video != null && video.deliveryStatus in setOf("waiting", "processing")) {
            Surface(
                color = Color.Black.copy(alpha = 0.68f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Text(
                    "Processing ${video.processingProgress}%",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                )
            }
        }
        item.durationLabel?.let { label ->
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.padding(8.dp),
            ) {
                Text(
                    label,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** A previously rendered frame is reusable only while this cell owns the same active media. */
internal fun retainRenderedVideoFrame(
    playInline: Boolean,
    coordinatorHasRenderedSource: Boolean,
): Boolean = playInline && coordinatorHasRenderedSource

private data class FeedVideoEntry(
    val cellIndex: Int,
    val cellKey: String,
    val source: AdaptiveVideoSource,
    val posterUrl: String?,
    val posterCacheKey: String,
)

/** Small pure seam for deterministic autoplay ownership under fast scrolling. */
internal data class FeedVisibleItem(
    val key: String,
    val offset: Int,
    val size: Int,
)

/**
 * Selects one video only after at least half of its maximum possible viewport
 * exposure is visible. The largest visible area wins; center distance breaks a
 * tie. This prevents a departing sliver from holding the sole decoder while a
 * fully visible, already-preloaded video waits below it.
 */
internal fun selectActiveVideoKey(
    visibleItems: List<FeedVisibleItem>,
    videoKeys: Set<String>,
    viewportStart: Int,
    viewportEnd: Int,
): String? {
    val viewportSize = viewportEnd - viewportStart
    if (viewportSize <= 0 || videoKeys.isEmpty()) return null
    val viewportCenter = viewportStart + viewportSize / 2
    var bestKey: String? = null
    var bestVisiblePixels = -1
    var bestCenterDistance = Int.MAX_VALUE
    for (item in visibleItems) {
        if (item.key !in videoKeys || item.size <= 0) continue
        val visiblePixels = (
            minOf(item.offset + item.size, viewportEnd) -
                maxOf(item.offset, viewportStart)
            ).coerceAtLeast(0)
        val maximumVisiblePixels = minOf(item.size, viewportSize)
        if (visiblePixels * 2 < maximumVisiblePixels) continue
        val centerDistance = (item.offset + item.size / 2 - viewportCenter).absoluteValue
        if (
            visiblePixels > bestVisiblePixels ||
            (visiblePixels == bestVisiblePixels && centerDistance < bestCenterDistance)
        ) {
            bestKey = item.key
            bestVisiblePixels = visiblePixels
            bestCenterDistance = centerDistance
        }
    }
    return bestKey
}

private fun CellUi.Media.toAdaptiveVideoSource() = AdaptiveVideoSource(
    hlsUrl = video?.hlsUrl,
    fallbackUrl = video?.fallbackUrl ?: sourceUrl,
    cacheKey = cacheKey ?: "post:${key.substringBefore('/')}",
)

private fun CellUi.Media.videoPosterCacheKey(): String =
    videoPosterCacheKey(
        mediaKey = cacheKey ?: "post:${key.substringBefore('/')}",
        posterUrl = video?.posterUrl,
    )

private fun LazyPagingItems<CellUi>.transitionPreview(postId: String): PostTransitionPreview {
    val group = itemSnapshotList.items.filter { it.key.substringBefore('/') == postId }
    return group.toPostTransitionPreview(postId)
}

@Composable
private fun ActionBarCell(
    item: CellUi.ActionBar,
    onVote: (String, Int) -> Unit,
    onOpen: (String) -> Unit,
    onReshare: (String) -> Unit,
    onShare: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = Color.Transparent,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.ArrowUpward,
                    "Upvote",
                    Modifier.clickable { onVote(item.itemId, 1) }.padding(8.dp).size(19.dp),
                    tint = if (item.viewerVote == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Text(item.scoreLabel, fontWeight = FontWeight.Bold, style = ReadThatTextStyles.feedAction)
                Icon(
                    Icons.Outlined.ArrowDownward,
                    "Downvote",
                    Modifier.clickable { onVote(item.itemId, -1) }.padding(8.dp).size(19.dp),
                    tint = if (item.viewerVote == -1) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        ActionPill(
            Icons.Outlined.ChatBubbleOutline,
            item.commentLabel,
            "Comments, ${item.commentLabel}",
        ) { onOpen(item.itemId) }
        ActionPill(Icons.Outlined.Repeat, "", "Reshare") { onReshare(item.itemId) }
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        ActionPill(Icons.Outlined.Share, "", "Share") { onShare(item.itemId) }
    }
}

@Composable
private fun ActionPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    accessibilityLabel: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = Color.Transparent,
        modifier = Modifier
            .clickable(onClick = onClick)
            .semantics { contentDescription = accessibilityLabel },
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp))
            if (label.isNotBlank()) Text(" $label", style = ReadThatTextStyles.feedAction, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun HomeHeader(
    onSearch: () -> Unit,
    onOpenNavigation: () -> Unit,
    account: FeedAccountHeader?,
    onAccountClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconButton(onClick = onOpenNavigation, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Menu, "Open community menu", Modifier.size(28.dp))
        }
        ReadThatLogo(
            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)),
            contentDescription = "ReadThat",
        )
        Row(
            Modifier.weight(1f).height(46.dp)
                .clickable(onClick = onSearch)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(24.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp))
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("  Find anything", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AccountButton(account, onAccountClick)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun AccountButton(
    account: FeedAccountHeader?,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val avatarRequest = remember(account?.username, account?.updatedAt, account?.avatarUrl) {
        account?.avatarUrl?.takeIf(String::isNotBlank)?.let { avatarUrl ->
            ImageRequest.Builder(context)
                .data(avatarUrl)
                .memoryCacheKey("feed-account-avatar:${account.username}:${account.updatedAt}")
                .diskCacheKey("feed-account-avatar:${account.username}:${account.updatedAt}")
                .build()
        }
    }
    val username = account?.username.orEmpty().trim().removePrefix("u/")
    val fallback = account?.displayName
        ?.trim()
        ?.firstOrNull()
        ?.uppercase()
        ?: username.firstOrNull()?.uppercase()
        ?: "U"

    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color(0xFF86A8F7))
            .clickable(
                enabled = account != null,
                onClickLabel = if (username.isBlank()) null else "Open u/$username profile",
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                if (username.isNotBlank()) contentDescription = "u/$username profile"
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            fallback,
            color = Color(0xFF0B1416),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Black,
        )
        if (avatarRequest != null) {
            AsyncImage(
                model = avatarRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun AnnouncementCell(item: CellUi.Announcement) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(item.text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DroppedBanner(dropped: Map<String, Int>) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Forward-compatibility demo",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "The server sent cell types this build does not understand. They were " +
                        "skipped and counted rather than crashing the feed:",
                style = MaterialTheme.typography.bodySmall,
            )
            dropped.forEach { (type, count) ->
                Text("• $type × $count", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Text(
            "Tap to retry",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .padding(top = 12.dp)
                .clickable(onClick = onRetry),
        )
    }
}

internal data class FeedImageEntry(
    val cellIndex: Int,
    val url: String,
    val cacheKey: String,
)

/** Every feed bitmap request, using the exact cache key consumed by its renderer. */
internal fun feedImageEntries(items: List<CellUi>): List<FeedImageEntry> = buildList {
    items.forEachIndexed { cellIndex, item ->
        when (item) {
            is CellUi.Media -> if (item.video == null) {
                item.sourceUrl?.let { add(FeedImageEntry(cellIndex, it, item.feedImageCacheKey())) }
            }
            is CellUi.ImageCarousel -> item.items.forEachIndexed { page, image ->
                image.sourceUrl?.let {
                    add(FeedImageEntry(cellIndex, it, image.feedImageCacheKey(item.key, page)))
                }
            }
            is CellUi.AdMedia -> item.items.forEach { media ->
                when (media.kind) {
                    AdMediaKind.Image -> media.imageUrl?.let {
                        add(FeedImageEntry(cellIndex, it, media.cacheKey))
                    }
                    // Video posters have their own window and lifecycle above.
                    AdMediaKind.Video -> Unit
                }
            }
            else -> Unit
        }
    }
}

internal fun CellUi.Media.feedImageCacheKey(): String = cacheKey ?: "feed-image:$key"

internal fun ImageMediaUi.feedImageCacheKey(parentKey: String, page: Int): String =
    cacheKey ?: mediaId?.let { "image:$it" } ?: "feed-carousel:$parentKey:$page"
