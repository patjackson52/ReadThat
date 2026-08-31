package dev.readthat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import dev.readthat.domain.CellUi
import dev.readthat.client.SharedFeedViewModel
import dev.readthat.domain.AdLaunchContext
import dev.readthat.domain.AdMediaItemUi
import dev.readthat.domain.NormalFeedMediaContext
import dev.readthat.domain.toPostTransitionPreview
import dev.readthat.feed.ui.SharedFeedCellRenderer
import dev.readthat.feed.ui.SharedFeedAccount
import dev.readthat.feed.ui.SharedFeedInteraction
import dev.readthat.feed.ui.SharedFeedScreen
import dev.readthat.feed.ui.SharedFeedVisibleItem
import dev.readthat.feed.ui.SharedFeedMediaPreloadWindow
import dev.readthat.feed.ui.SharedPlatformHomeFeedHeader
import dev.readthat.feed.ui.feedMediaPrefetchCatalog
import dev.readthat.feed.ui.rememberSharedFeedInteractionRecorder
import dev.readthat.feed.ui.selectSharedFeedVideo
import dev.readthat.media.ui.rememberPlatformVideoPlaybackPolicy
import dev.readthat.shared.AppSettings
import dev.readthat.shared.VideoPlaybackPolicy
import dev.readthat.playback.AdaptiveVideoSource
import dev.readthat.playback.VideoPlaybackCoordinator
import dev.readthat.observability.PerformanceSurface
import dev.readthat.observability.PerformanceTelemetry
import dev.readthat.observability.ProductAnalytics
import dev.readthat.observability.ProductContentType
import dev.readthat.observability.ProductEvent
import dev.readthat.observability.ProductSurface
import kotlinx.coroutines.launch
import dev.readthat.shared.PostTransitionPreview

/** Compatibility name retained while the mature Android shell remains compiled as reference. */
typealias FeedAccountHeader = SharedFeedAccount

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
fun FeedScreen(
    viewModel: SharedFeedViewModel,
    onPostClick: (postId: String, preview: PostTransitionPreview) -> Unit,
    onCommunityClick: (communityName: String) -> Unit,
    onProfileClick: (username: String) -> Unit = {},
    /** Media opens the immersive pager; title/body/action taps keep opening detail. */
    onMediaClick: ((context: NormalFeedMediaContext) -> Unit)? = null,
    /** Promoted media opens the hybrid continued-playback media + landing-page detail. */
    onAdClick: ((context: AdLaunchContext) -> Unit)? = null,
    /** Native presentation remains a host capability; the feed supplies canonical post context. */
    onSharePost: (postId: String, title: String) -> Unit,
    onSharePromoted: (url: String) -> Unit,
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
    val initialCacheTier by viewModel.initialCacheTier.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val videoPolicy = rememberPlatformVideoPlaybackPolicy(settings)
    val interactionScope = rememberCoroutineScope()
    val interactionRecorder = rememberSharedFeedInteractionRecorder(performanceSurface)

    LaunchedEffect(performanceSurface) { PerformanceTelemetry.enterSurface(performanceSurface) }

    fun interaction(type: SharedFeedInteraction, action: () -> Unit) =
        interactionRecorder.record(type, action)

    // The app-shell Scaffold owns system bars and bottom navigation. This feature Scaffold exists
    // only for local layout structure; consuming system bars here would double the top inset.
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            FeedList(
                items,
                listState,
                { id, value -> interaction(SharedFeedInteraction.Vote) { viewModel.vote(id, value) } },
                { id -> interaction(SharedFeedInteraction.OpenDetail) {
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
                { id -> interaction(SharedFeedInteraction.OpenMedia) {
                    val preview = items.transitionPreview(id)
                    if (onMediaClick == null) {
                        onPostClick(id, preview)
                    } else {
                        interactionScope.launch {
                            onMediaClick(viewModel.mediaLaunchContext(id, preview))
                        }
                    }
                } },
                { communityName -> interaction(SharedFeedInteraction.OpenCommunity) {
                    onCommunityClick(communityName)
                } },
                { username -> interaction(SharedFeedInteraction.OpenAdProfile) {
                    onProfileClick(username)
                } },
                onAdOpen = { ad -> interaction(SharedFeedInteraction.OpenAd) {
                    if (onAdClick != null) onAdClick(ad)
                } },
                onRelatedPost = { postId -> interaction(SharedFeedInteraction.OpenAdRelated) {
                    onPostClick(postId, items.transitionPreview(postId))
                } },
                onRetry = {
                    viewModel.markErrorRetry()
                },
                onUserRefresh = viewModel::markUserRefresh,
                initialCacheTier = initialCacheTier,
                onFirstContentRendered = onFirstContentRendered,
                onPrefetchComments = viewModel::prefetchComments,
                onReshare = { postId, community, onComplete ->
                    interaction(SharedFeedInteraction.Reshare) {
                        viewModel.reshare(postId, community, onComplete)
                    }
                },
                onShare = { postId, title ->
                    interaction(SharedFeedInteraction.Share) {
                        onSharePost(postId, title)
                    }
                },
                onSharePromoted = { url ->
                    interaction(SharedFeedInteraction.Share) { onSharePromoted(url) }
                },
                postPositionModifierFor = postPositionModifierFor,
                videoPolicy = videoPolicy,
                settings = settings,
                header = if (showHeader) {
                    {
                        SharedPlatformHomeFeedHeader(
                            onSearch = onSearch,
                            onOpenNavigation = onOpenNavigation,
                            account = accountHeader,
                            onAccountClick = {
                                interaction(SharedFeedInteraction.OpenProfile, onAccountClick)
                            },
                        )
                    }
                } else {
                    null
                },
                listHeader = listHeader,
                productSurface = productSurface,
            )
        }
    }
}

@Composable
private fun FeedList(
    items: LazyPagingItems<CellUi>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onVote: (String, Int) -> Unit,
    onOpen: (String) -> Unit,
    onOpenMedia: (String) -> Unit,
    onOpenCommunity: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onAdOpen: (AdLaunchContext) -> Unit,
    onRelatedPost: (String) -> Unit,
    onRetry: () -> Unit,
    onUserRefresh: () -> Unit,
    initialCacheTier: String?,
    onFirstContentRendered: (String) -> Unit,
    onPrefetchComments: (Set<String>) -> Unit,
    onReshare: (String, String, (String?) -> Unit) -> Unit,
    onShare: (postId: String, title: String) -> Unit,
    onSharePromoted: (String) -> Unit,
    postPositionModifierFor: @Composable (postId: String) -> Modifier,
    videoPolicy: VideoPlaybackPolicy,
    settings: AppSettings,
    header: (@Composable () -> Unit)?,
    listHeader: (@Composable () -> Unit)?,
    productSurface: ProductSurface,
) {
    val snapshotItems = items.itemSnapshotList.items
    val prefetchCatalog = remember(snapshotItems) { feedMediaPrefetchCatalog(snapshotItems) }
    val firstVisibleIndex = remember { mutableStateOf(listState.firstVisibleItemIndex) }
    val prefetchPlan = prefetchCatalog.plan(firstVisibleIndex.value)
    SharedFeedMediaPreloadWindow(
        plan = prefetchPlan,
        settings = settings,
        videoPolicy = videoPolicy,
    )

    fun updatePrefetchWindow(firstVisible: Int) {
        firstVisibleIndex.value = firstVisible
    }
    SharedFeedScreen(
        items = items,
        initialCacheTier = initialCacheTier,
        explicitlyOffline = false,
        autoplayEnabled = videoPolicy.autoplay,
        onUserRefresh = onUserRefresh,
        onRetry = onRetry,
        onFirstContentRendered = onFirstContentRendered,
        onFirstVisibleItemChanged = ::updatePrefetchWindow,
        onPrefetchComments = onPrefetchComments,
        onReshare = onReshare,
        itemContent = { item, adMedia, postTitle, playInline, requestReshare ->
            CellRenderer(
                item,
                onVote,
                onOpen,
                onOpenMedia,
                onOpenCommunity,
                onOpenProfile,
                onAdOpen,
                onRelatedPost,
                requestReshare,
                { postId -> onShare(postId, postTitle) },
                onSharePromoted,
                postPositionModifierFor,
                playInline = playInline,
                settings = settings,
                adMedia = adMedia,
                productSurface = productSurface,
            )
        },
        listState = listState,
        header = header,
        listHeader = listHeader,
        productSurface = productSurface,
    )
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
    onSharePromoted: (String) -> Unit,
    postPositionModifierFor: @Composable (postId: String) -> Modifier,
    playInline: Boolean,
    settings: AppSettings,
    adMedia: CellUi.AdMedia?,
    productSurface: ProductSurface,
) {
    // Composite keys are "groupId/cellId"; the group id IS the post id. Content
    // cells tap through to post detail; the action bar keeps its own tap targets.
    val postId = item.key.substringBefore('/')
    SharedFeedCellRenderer(
        item = item,
        playInline = playInline,
        settings = settings,
        adMedia = adMedia,
        onOpenPost = onOpen,
        onOpenMedia = onOpenMedia,
        onOpenCommunity = onOpenCommunity,
        onVote = onVote,
        onReshare = onReshare,
        onShare = onShare,
        onOpenAdProfile = onOpenProfile,
        onOpenAd = onAdOpen,
        onRelatedPost = onRelatedPost,
        onShareAd = adMedia?.let { onSharePromoted },
        onPromotedMutedChanged = { media, muted ->
            VideoPlaybackCoordinator.setMuted(media.toAdaptiveVideoSource(), muted)
        },
        modifier = if (item is CellUi.Metadata || item is CellUi.AdHeader) {
            postPositionModifierFor(postId)
        } else {
            Modifier
        },
        productSurface = productSurface,
    )
}

private fun AdMediaItemUi.toAdaptiveVideoSource() = AdaptiveVideoSource(
    hlsUrl,
    fallbackUrl,
    cacheKey,
)

/** A rendered frame is reusable only while this cell owns the same active media source. */
internal fun retainRenderedVideoFrame(
    playInline: Boolean,
    coordinatorHasRenderedSource: Boolean,
): Boolean = playInline && coordinatorHasRenderedSource

/** Small pure seam for deterministic autoplay ownership under fast scrolling. */
internal typealias FeedVisibleItem = SharedFeedVisibleItem

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
): String? = selectSharedFeedVideo(
    visibleItems,
    videoKeys,
    viewportStart,
    viewportEnd,
)

private fun LazyPagingItems<CellUi>.transitionPreview(postId: String): PostTransitionPreview {
    val group = itemSnapshotList.items.filter { it.key.substringBefore('/') == postId }
    return group.toPostTransitionPreview(postId)
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
