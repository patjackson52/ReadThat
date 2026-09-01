package dev.readthat.compose

/** Shared application coordinator, independent of the Android/iOS binary hosts. */

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import dev.readthat.navigation.AppDestination
import dev.readthat.navigation.AppNavigationPolicy
import dev.readthat.navigation.PlatformBackGestureBridge
import dev.readthat.navigation.toAppDestination
import dev.readthat.ad.ui.SharedPlatformAdDetailScreen
import dev.readthat.client.CreateMode
import dev.readthat.client.DetailState
import dev.readthat.client.FeedCard
import dev.readthat.client.ReadThatUiState
import dev.readthat.client.ReadThatViewModel
import dev.readthat.creation.ui.SharedCreateCommunityScreen
import dev.readthat.creation.ui.SharedCreatePostScreen
import dev.readthat.creation.ui.SharedPendingCommunityScreen
import dev.readthat.creation.ui.SharedPendingPostScreen
import dev.readthat.community.ui.SharedCommunityDrawer
import dev.readthat.community.ui.SharedCommunityDiscoveryScreen
import dev.readthat.community.ui.SharedPlatformCommunityDetailHeader
import dev.readthat.community.ui.SharedCommunityDetailTelemetry
import dev.readthat.auth.ui.SharedAuthScreen as CommonAuthScreen
import dev.readthat.core.ui.theme.ReadThatTheme
import dev.readthat.domain.AdLaunchContext
import dev.readthat.domain.CellUi
import dev.readthat.domain.toPostTransitionPreview
import dev.readthat.feed.ui.SharedFeedCellRenderer
import dev.readthat.feed.ui.SharedFeedMediaPreloadWindow
import dev.readthat.feed.ui.SharedFeedAccount
import dev.readthat.feed.ui.SharedFeedInteraction
import dev.readthat.feed.ui.SharedFeedInteractionRecorder
import dev.readthat.feed.ui.SharedFeedScreen
import dev.readthat.feed.ui.SharedFeedVisibleItem
import dev.readthat.feed.ui.SharedPlatformHomeFeedHeader
import dev.readthat.feed.ui.feedMediaPrefetchCatalog
import dev.readthat.feed.ui.rememberSharedFeedInteractionRecorder
import dev.readthat.image.ui.PlatformImageByteLoader
import dev.readthat.image.ui.clearPlatformImageMemoryCache
import dev.readthat.feed.ui.selectSharedFeedVideo
import dev.readthat.detail.ui.DetailPresentation
import dev.readthat.detail.ui.SharedPostDetailMediaGallery
import dev.readthat.detail.ui.SharedPlatformPostDetailScreen
import dev.readthat.detail.ui.toDetailCommunityHeader
import dev.readthat.detail.ui.toDetailUiState
import dev.readthat.deeplink.DeepLinkInbox
import dev.readthat.deeplink.ReadThatDeepLink
import dev.readthat.deeplink.ReadThatDeepLinks
import dev.readthat.mediafeed.ui.SharedPlatformMediaFeedRoute
import dev.readthat.media.ui.PlatformMediaLifecycle
import dev.readthat.media.acquisition.ui.rememberPlatformCameraLauncher
import dev.readthat.media.acquisition.ui.rememberPlatformMediaPickerLauncher
import dev.readthat.sharing.SharePayloads
import dev.readthat.sharing.ui.rememberPlatformShareAction
import dev.readthat.sharing.ui.rememberPlatformSharePayloadAction
import dev.readthat.media.ui.platformVideoHasRenderedFirstFrame
import dev.readthat.media.ui.platformVideoPlaybackIdentity
import dev.readthat.media.ui.rememberPlatformVideoPlaybackPolicy
import dev.readthat.search.ui.SharedPlatformSearchRoute
import dev.readthat.shell.ui.SharedActivityScreen
import dev.readthat.shell.ui.SharedBottomNavigation
import dev.readthat.shell.ui.SharedStartupShell
import dev.readthat.settings.ui.SharedSettingsScreen
import dev.readthat.profile.ui.ProfileEditorUiState
import dev.readthat.profile.ui.SharedAvatarPickerButton
import dev.readthat.profile.ui.SharedPlatformEditProfileScreen
import dev.readthat.profile.ui.SharedPlatformOwnProfileScreen
import dev.readthat.profile.ui.SharedPlatformPublicProfileScreen
import dev.readthat.observability.PerformanceSurface
import dev.readthat.observability.PerformanceTelemetry
import dev.readthat.observability.PerformanceMetric
import dev.readthat.observability.ProductAnalytics
import dev.readthat.observability.ProductContentType
import dev.readthat.observability.ProductEvent
import dev.readthat.observability.ProductEventName
import dev.readthat.observability.ProductSurface
import dev.readthat.observability.performanceTimer
import dev.readthat.shared.PostKind
import dev.readthat.shared.LocalPostMedia
import dev.readthat.shared.SessionState
import dev.readthat.shared.UserProfile
import kotlinx.coroutines.launch
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap

@Composable
fun ReadThatApp(
    viewModel: ReadThatViewModel,
    deepLinks: DeepLinkInbox? = null,
    backGestures: PlatformBackGestureBridge? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pendingDeepLinkState = deepLinks?.pending?.collectAsStateWithLifecycle()
    PlatformMediaLifecycle {
        clearPlatformImageMemoryCache()
        StagedDecodedImageCache.clear()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onForeground()
                Lifecycle.Event.ON_STOP -> viewModel.onBackground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            viewModel.onForeground()
        }
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.session, pendingDeepLinkState?.value) {
        pendingDeepLinkState?.value?.let { target ->
            when (state.session) {
                SessionState.Restoring -> Unit
                SessionState.SignedOut -> viewModel.navigate(target.toAppDestination())
                is SessionState.SignedIn -> {
                    viewModel.navigate(target.toAppDestination())
                    deepLinks.consume(target)
                }
            }
        }
    }
    val platformUriHandler = LocalUriHandler.current
    val readThatUriHandler = object : UriHandler {
        override fun openUri(uri: String) {
            val target = ReadThatDeepLinks.parse(uri)
            if (target == null) {
                platformUriHandler.openUri(uri)
            } else if (state.session == SessionState.SignedOut && deepLinks != null) {
                deepLinks.offer(target)
            } else {
                viewModel.navigate(target.toAppDestination())
            }
        }
    }
    ReadThatTheme(state.settings.darkTheme) {
        // Paint the platform-independent root explicitly. Android's launch-window coral and
        // UIKit's default white must never leak through transparent Compose destinations.
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            CompositionLocalProvider(LocalUriHandler provides readThatUriHandler) {
                when (state.session) {
                    SessionState.Restoring -> SharedStartupShell()
                    SessionState.SignedOut -> pendingDeepLinkState?.value?.let { target ->
                        val destination = (state.destination as? AppDestination.PostDetail)
                            ?.takeIf { it.postId == target.postId }
                            ?: target.toAppDestination()
                        SignedOutDeepLinkedPost(
                            state = state,
                            destination = destination,
                            pendingTarget = target,
                            viewModel = viewModel,
                            deepLinks = deepLinks,
                            backGestures = backGestures,
                        )
                    } ?: AuthScreen(viewModel)
                    is SessionState.SignedIn -> AppScaffold(state, viewModel, backGestures)
                }
            }
        }
    }
}

@Composable
private fun SignedOutDeepLinkedPost(
    state: ReadThatUiState,
    destination: AppDestination.PostDetail,
    pendingTarget: ReadThatDeepLink,
    viewModel: ReadThatViewModel,
    deepLinks: DeepLinkInbox,
    backGestures: PlatformBackGestureBridge?,
) {
    PlatformSystemBackHandler(enabled = true, backGestures = backGestures) {
        deepLinks.consume(pendingTarget)
        viewModel.navigate(AppDestination.Feed)
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        DetailScreen(
            state = state,
            postId = destination.postId,
            viewModel = viewModel,
            modifier = Modifier.windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
            ),
            onBack = {
                deepLinks.consume(pendingTarget)
                viewModel.navigate(AppDestination.Feed)
            },
            onContinueThread = { parentId ->
                deepLinks.offer(ReadThatDeepLink.Comment(destination.postId, parentId))
            },
        )
    }
}

@Composable
private fun AppScaffold(
    state: ReadThatUiState,
    viewModel: ReadThatViewModel,
    backGestures: PlatformBackGestureBridge?,
) {
    val showBottom = AppNavigationPolicy.showsBottomNavigation(state.destination)
    val snackbar = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val destinationStateHolder = rememberSaveableStateHolder()
    val destinationStateRegistry = remember { BoundedDestinationStateRegistry() }
    val destinationStateKey = state.destination.saveableStateKey()
    val communityDrawerState by viewModel.sharedCommunityDrawerState.collectAsStateWithLifecycle()
    val drawerScope = rememberCoroutineScope()
    val immersiveDestination = AppNavigationPolicy.isImmersive(state.destination)
    PlatformSystemBackHandler(
        enabled = state.destination.handlesPlatformSystemBack(),
        backGestures = backGestures,
        onBack = viewModel::back,
    )
    LaunchedEffect(state.destination) {
        destinationStateRegistry.touch(state.destination).forEach(destinationStateHolder::removeState)
        PerformanceTelemetry.enterSurface(state.destination.performanceSurface())
        when (val destination = state.destination) {
            is AppDestination.PostDetail -> ProductAnalytics.record(ProductEvent(
                name = ProductEventName.POST_DETAIL_VIEW,
                surface = ProductSurface.DETAIL,
                contentId = destination.postId,
                contentType = ProductContentType.POST,
            ))
            is AppDestination.AdDetail -> ProductAnalytics.record(ProductEvent(
                name = ProductEventName.AD_DETAIL_VIEW,
                surface = ProductSurface.AD_DETAIL,
                contentId = destination.ad.adId,
                contentType = ProductContentType.AD,
            ))
            else -> Unit
        }
    }
    val commentsAnalyticsPostId = when (val destination = state.destination) {
        is AppDestination.PostDetail -> destination.postId
        is AppDestination.Media -> state.mediaFeed.commentsPostId
        else -> null
    }
    val commentsAnalyticsSurface = if (state.destination is AppDestination.Media) {
        ProductSurface.MEDIA
    } else {
        ProductSurface.COMMENTS
    }
    LaunchedEffect(commentsAnalyticsPostId) {
        val postId = commentsAnalyticsPostId ?: return@LaunchedEffect
        if (state.destination is AppDestination.Media) {
            ProductAnalytics.record(ProductEvent(
                name = ProductEventName.POST_DETAIL_VIEW,
                surface = ProductSurface.MEDIA,
                contentId = postId,
                contentType = ProductContentType.POST,
            ))
        }
    }
    val commentsReadyForAnalytics = commentsAnalyticsPostId != null &&
        state.detail.postId == commentsAnalyticsPostId && state.detail.comments != null
    LaunchedEffect(commentsAnalyticsPostId, commentsReadyForAnalytics) {
        val postId = commentsAnalyticsPostId ?: return@LaunchedEffect
        if (commentsReadyForAnalytics) {
            ProductAnalytics.record(ProductEvent(
                name = ProductEventName.COMMENTS_VIEW,
                surface = commentsAnalyticsSurface,
                contentId = postId,
                contentType = ProductContentType.POST,
            ))
        }
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }
    LaunchedEffect(drawerState.currentValue) {
        if (drawerState.currentValue == DrawerValue.Open) viewModel.onCommunityDrawerOpened()
    }
    fun closeDrawerAnd(action: () -> Unit) {
        drawerScope.launch {
            drawerState.close()
            action()
        }
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = AppNavigationPolicy.allowsCommunityDrawer(state.destination),
        drawerContent = {
            SharedCommunityDrawer(
                state = communityDrawerState,
                onCreateCommunity = {
                    closeDrawerAnd {
                        viewModel.navigate(AppDestination.CreateCommunity)
                    }
                },
                onBrowse = { closeDrawerAnd { viewModel.navigate(AppDestination.Communities) } },
                onCommunity = { name ->
                    AppNavigationPolicy.communityDestination(name)?.let { destination ->
                        closeDrawerAnd { viewModel.navigate(destination) }
                    }
                },
                onSeeAll = viewModel::showAllRecentCommunities,
                onShowDrawer = viewModel::showCommunityDrawerHome,
                onToggleCommunities = viewModel::toggleDrawerCommunities,
                onRetry = viewModel::retryCommunityDrawer,
                onRemoveRecent = viewModel::removeRecentCommunity,
                onClearRecent = viewModel::clearRecentCommunities,
            )
        },
    ) {
      Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        // Platform hosts render edge-to-edge; shared UI applies safe areas exactly once.
        contentWindowInsets = when {
            immersiveDestination -> WindowInsets(0, 0, 0, 0)
            // SharedPostDetailScreen owns the bottom inset inside its fixed composer bar,
            // matching the Android edge-to-edge host. Keep top/cutout handling here.
            state.destination is AppDestination.PostDetail -> WindowInsets.safeDrawing.only(
                WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
            )
            else -> WindowInsets.safeDrawing
        },
        bottomBar = {
            if (showBottom) {
                SharedBottomNavigation(
                    selected = state.destination,
                    onNavigate = viewModel::navigate,
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            destinationStateHolder.SaveableStateProvider(destinationStateKey) {
                when (val destination = state.destination) {
                    AppDestination.Feed -> FeedScreen(
                        state,
                        viewModel,
                        onOpenNavigation = { drawerScope.launch { drawerState.open() } },
                    )
                    AppDestination.Activity -> SharedActivityScreen()
                    AppDestination.Search -> SharedSearchScreen(viewModel)
                    AppDestination.Communities -> CommunitiesScreen(viewModel)
                    is AppDestination.CreatePost, AppDestination.CreateCommunity -> CreateScreen(state, viewModel)
                    AppDestination.Profile -> ProfileScreen(state, viewModel)
                    AppDestination.Settings -> SettingsScreen(state, viewModel)
                    AppDestination.EditProfile -> EditProfileScreen(state, viewModel)
                    is AppDestination.PostDetail -> DetailScreen(state, destination.postId, viewModel)
                    is AppDestination.Community -> CommunityScreen(state, destination.name, viewModel)
                    is AppDestination.Media -> MediaScreen(state, destination.postId, viewModel)
                    is AppDestination.AdDetail -> AdDetailScreen(destination.ad, state, viewModel)
                    is AppDestination.PublicProfile -> PublicProfileScreen(state, viewModel)
                    is AppDestination.PendingPost -> PendingPostScreen(state, viewModel)
                    is AppDestination.PendingCommunity -> PendingCommunityScreen(state, viewModel)
                }
            }
        }
      }
    }
}

@Composable
private fun AuthScreen(viewModel: ReadThatViewModel) {
    val authState by viewModel.sharedAuthState.collectAsStateWithLifecycle()
    CommonAuthScreen(
        state = authState,
        onMode = viewModel::setAuthMode,
        onUsername = viewModel::setAuthUsername,
        onDisplayName = viewModel::setAuthDisplayName,
        onPassword = viewModel::setAuthPassword,
        onTogglePassword = viewModel::toggleAuthPasswordVisibility,
        onSubmit = viewModel::submitAuth,
        onClearMessage = viewModel::clearAuthMessage,
        modifier = Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
    )
}

@Composable
@OptIn(FlowPreview::class, ExperimentalMaterial3Api::class)
private fun FeedScreen(
    state: ReadThatUiState,
    viewModel: ReadThatViewModel,
    onOpenNavigation: () -> Unit,
) {
    val user = (state.session as? SessionState.SignedIn)?.user
    val listState = rememberLazyListState()
    val items = viewModel.pagedFeedCells.collectAsLazyPagingItems()
    val feedCells = items.itemSnapshotList.items
    val feedCards = remember(feedCells) { feedCardsFromCells(feedCells) }
    val prefetchCatalog = remember(feedCells) { feedMediaPrefetchCatalog(feedCells) }
    var firstVisibleIndex by remember { mutableStateOf(0) }
    val prefetchPlan = remember(prefetchCatalog, firstVisibleIndex) {
        prefetchCatalog.plan(firstVisibleIndex)
    }
    val imageByteLoader = rememberPlatformImageByteLoader(viewModel)
    val videoPolicy = rememberPlatformVideoPlaybackPolicy(state.settings)
    val homeTti = remember { performanceTimer() }
    val interactionRecorder = rememberSharedFeedInteractionRecorder(PerformanceSurface.FEED)
    LaunchedEffect(feedCards) {
        viewModel.updateFeedPresentationWindow(feedCards)
    }
    SharedFeedMediaPreloadWindow(
        plan = prefetchPlan,
        settings = state.settings,
        videoPolicy = videoPolicy,
        imageByteLoader = imageByteLoader,
    )
    SharedFeedScreen(
        items = items,
        initialCacheTier = state.feed.initialCacheTier,
        explicitlyOffline = state.feed.isOffline,
        autoplayEnabled = videoPolicy.autoplay,
        onUserRefresh = viewModel::markUserFeedRefresh,
        onRetry = viewModel::markFeedErrorRetry,
        onFirstContentRendered = { cacheTier ->
            PerformanceTelemetry.duration(
                PerformanceMetric.HOME_TTI,
                homeTti,
                PerformanceSurface.FEED,
                attributes = mapOf("cache_tier" to cacheTier),
            )
        },
        onFirstVisibleItemChanged = { index ->
            firstVisibleIndex = index
        },
        onPrefetchComments = viewModel::prefetchComments,
        onReshare = viewModel::reshareFromFeed,
        header = {
            HomeHeader(
                account = user,
                viewModel = viewModel,
                onOpenNavigation = onOpenNavigation,
                onSearch = { viewModel.navigate(AppDestination.Search) },
                onAccountClick = {
                    interactionRecorder.record(SharedFeedInteraction.OpenProfile) {
                        viewModel.navigate(AppDestination.Profile)
                    }
                },
            )
        },
        itemContent = { cell, adMedia, postTitle, playInline, requestReshare ->
            FeedCellView(
                cell = cell,
                state = state,
                viewModel = viewModel,
                playInlineVideo = playInline,
                adMedia = adMedia,
                postTitle = postTitle,
                interactionRecorder = interactionRecorder,
                onReshare = requestReshare,
            )
        },
        modifier = Modifier.fillMaxSize(),
        listState = listState,
        productSurface = ProductSurface.FEED,
    )
}

internal fun feedCardsFromCells(cells: List<CellUi>): List<FeedCard> {
    val grouped = linkedMapOf<String, MutableList<CellUi>>()
    cells.forEach { cell -> grouped.getOrPut(cell.feedGroupId()) { mutableListOf() } += cell }
    return grouped.map { (id, group) -> FeedCard(id, group, group.toPostTransitionPreview(id)) }
}

internal fun CellUi.feedGroupId(): String = key.substringBefore('/')

internal typealias FeedVisibleItem = SharedFeedVisibleItem

internal fun selectActiveFeedVideo(
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

@Composable
private fun HomeHeader(
    account: UserProfile?,
    viewModel: ReadThatViewModel,
    onOpenNavigation: () -> Unit,
    onSearch: () -> Unit,
    onAccountClick: () -> Unit,
) {
    SharedPlatformHomeFeedHeader(
        account = account?.let {
            SharedFeedAccount(it.username, it.displayName, it.avatarUrl, it.updatedAt)
        },
        onOpenNavigation = onOpenNavigation,
        onSearch = onSearch,
        onAccountClick = onAccountClick,
        imageByteLoader = rememberPlatformImageByteLoader(viewModel),
    )
}

@Composable
private fun FeedCellView(
    cell: CellUi,
    state: ReadThatUiState,
    viewModel: ReadThatViewModel,
    playInlineVideo: Boolean,
    adMedia: CellUi.AdMedia? = null,
    postTitle: String = "ReadThat post",
    openEventName: String? = null,
    productSurface: ProductSurface = ProductSurface.FEED,
    interactionRecorder: SharedFeedInteractionRecorder,
    onReshare: (String) -> Unit = {},
) {
    val postId = cell.feedGroupId()
    fun openPost() {
        interactionRecorder.record(SharedFeedInteraction.OpenDetail) {
            openEventName?.let { eventName ->
                ProductAnalytics.record(ProductEvent(
                    name = eventName,
                    surface = productSurface,
                    contentId = postId,
                    contentType = ProductContentType.POST,
                ))
            }
            viewModel.navigate(AppDestination.PostDetail(postId))
        }
    }
    val share = rememberPlatformShareAction(viewModel.sharePayload(postId, postTitle))
    val sharePayload = rememberPlatformSharePayloadAction()
    val promotedSharePayload = remember(adMedia) {
        adMedia?.let { SharePayloads.linkOrNull(it.destinationUrl, "Promoted link") }
    }
    val imageByteLoader = rememberPlatformImageByteLoader(viewModel)
    SharedFeedCellRenderer(
        item = cell,
        playInline = playInlineVideo,
        settings = state.settings,
        adMedia = adMedia,
        onOpenPost = { openPost() },
        onOpenMedia = { media ->
            interactionRecorder.record(SharedFeedInteraction.OpenMedia) {
                viewModel.openMedia(media)
            }
        },
        onOpenCommunity = { community ->
            interactionRecorder.record(SharedFeedInteraction.OpenCommunity) {
                viewModel.openCommunity(community)
            }
        },
        onVote = { _, value ->
            interactionRecorder.record(SharedFeedInteraction.Vote) {
                viewModel.vote(postId, value)
            }
        },
        onReshare = { id ->
            interactionRecorder.record(SharedFeedInteraction.Reshare) { onReshare(id) }
        },
        onShare = {
            interactionRecorder.record(SharedFeedInteraction.Share) { share() }
        },
        onOpenAdProfile = { username ->
            interactionRecorder.record(SharedFeedInteraction.OpenAdProfile) {
                viewModel.navigate(AppDestination.PublicProfile(username))
            }
        },
        onOpenAd = { ad ->
            interactionRecorder.record(SharedFeedInteraction.OpenAd) {
                viewModel.navigate(AppDestination.AdDetail(ad))
            }
        },
        onRelatedPost = { relatedPostId ->
            interactionRecorder.record(SharedFeedInteraction.OpenAdRelated) {
                viewModel.navigate(AppDestination.PostDetail(relatedPostId))
            }
        },
        imageByteLoader = imageByteLoader,
        onShareAd = promotedSharePayload?.let { payload ->
            { _ ->
                interactionRecorder.record(SharedFeedInteraction.Share) {
                    sharePayload(payload)
                }
            }
        },
        productSurface = productSurface,
    )
}
private fun String.toComposeColor(fallback: Color): Color {
    val hex = trim().removePrefix("#")
    val value = hex.toLongOrNull(16) ?: return fallback
    return when (hex.length) {
        6 -> Color(0xFF000000L or value)
        8 -> Color(value)
        else -> fallback
    }
}

@Composable
private fun DetailScreen(
    state: ReadThatUiState,
    postId: String,
    viewModel: ReadThatViewModel,
    modifier: Modifier = Modifier,
    presentation: DetailPresentation = DetailPresentation.FullScreen,
    onBack: () -> Unit = viewModel::back,
    onContinueThread: (String) -> Unit = { parentId ->
        viewModel.navigate(AppDestination.PostDetail(postId, rootCommentId = parentId))
    },
) {
    val canMutate = state.session is SessionState.SignedIn
    val detail = state.detail.takeIf { it.postId == postId } ?: DetailState(postId = postId)
    val sharePost = rememberPlatformShareAction(
        viewModel.sharePayload(postId, detail.post?.title ?: "ReadThat post"),
    )
    val render by viewModel.detailRender.collectAsStateWithLifecycle()
    val imageByteLoader = rememberPlatformImageByteLoader(viewModel)
    val commentsTti = remember(postId) { performanceTimer() }
    var commentsTtiReported by remember(postId) { mutableStateOf(false) }
    LaunchedEffect(postId, detail.comments != null, detail.refreshingComments, detail.error) {
        val terminalError = !detail.refreshingComments && detail.error != null
        if (!commentsTtiReported && (detail.comments != null || terminalError)) {
            withFrameNanos { }
            commentsTtiReported = true
            PerformanceTelemetry.duration(
                PerformanceMetric.COMMENTS_TTI,
                commentsTti,
                if (presentation == DetailPresentation.MediaBottomSheet) {
                    PerformanceSurface.MEDIA
                } else {
                    PerformanceSurface.DETAIL
                },
                attributes = mapOf(
                    "cache_tier" to if (detail.comments != null) "room" else "error_state",
                    "phase" to if (detail.comments != null) "initial_comments" else "error_state",
                ),
            )
        }
    }
    SharedPlatformPostDetailScreen(
        state = detail.toDetailUiState(render, canMutate),
        onContinueThread = onContinueThread,
        onCommunityClick = { community ->
            viewModel.openCommunity(community)
        },
        onJoinCommunity = { viewModel.setDetailCommunityJoined(true) },
        onBack = onBack,
        onToggleComment = viewModel::toggleCommentCollapsed,
        onLoadMore = { cursorId -> viewModel.loadMoreComments(postId, cursorId) },
        onViewport = { first, last -> viewModel.onCommentsViewport(postId, first, last) },
        onVoteComment = { commentId, value -> viewModel.voteComment(postId, commentId, value) },
        onVotePost = { value -> viewModel.vote(postId, value) },
        onCreateComment = { parentId, body ->
            viewModel.replyTo(parentId)
            viewModel.setCommentDraft(body)
            viewModel.submitComment(postId)
        },
        onClearError = viewModel::clearDetailError,
        mediaRenderer = { mediaItems, stableCacheKey, mediaModifier ->
            SharedPostDetailMediaGallery(
                mediaItems = mediaItems,
                stableCacheKey = stableCacheKey,
                settings = state.settings,
                imageByteLoader = imageByteLoader,
                playIcon = Icons.Default.PlayArrow,
                onOpenMediaFeed = { viewModel.navigate(AppDestination.Media(postId)) },
                modifier = mediaModifier,
            )
        },
        imageByteLoader = imageByteLoader,
        onSharePost = sharePost,
        onResharePost = if (canMutate) {
            { community ->
                viewModel.setReshareTarget(community)
                viewModel.reshare(postId)
            }
        } else null,
        modifier = modifier,
        communityHeader = detail.toDetailCommunityHeader(),
        presentation = presentation,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaScreen(state: ReadThatUiState, routeAnchorPostId: String, viewModel: ReadThatViewModel) {
    val items = viewModel.pagedMediaFeed.collectAsLazyPagingItems()
    val navigationItems by viewModel.mediaFeedNavigationItems.collectAsStateWithLifecycle()
    val initialCacheTier by viewModel.mediaFeedInitialCacheTier.collectAsStateWithLifecycle()
    val restoredPage = remember(routeAnchorPostId) { viewModel.mediaFeedRestoredPage }
    val share = rememberPlatformSharePayloadAction()

    val imageByteLoader = rememberPlatformImageByteLoader(viewModel)
    SharedPlatformMediaFeedRoute(
        items = items,
        navigationItems = navigationItems,
        restoredPage = restoredPage,
        onCurrentPageChanged = viewModel::setMediaFeedCurrentPage,
        onNavigationHydrated = viewModel::releaseMediaFeedNavigationFallback,
        onClose = viewModel::back,
        onOpenDetails = viewModel::openMediaComments,
        onOpenCommunity = viewModel::openCommunity,
        onOpenUser = { viewModel.navigate(AppDestination.PublicProfile(it)) },
        onVote = viewModel::voteMediaFeed,
        onShare = { item -> share(viewModel.sharePayload(item.postId, item.title)) },
        settings = state.settings,
        imageByteLoader = imageByteLoader,
        initialCacheTier = initialCacheTier,
        modifier = Modifier.fillMaxSize(),
    )

    state.mediaFeed.commentsPostId?.let { postId ->
        ModalBottomSheet(
            onDismissRequest = viewModel::closeMediaComments,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            DetailScreen(
                state = state,
                postId = postId,
                viewModel = viewModel,
                modifier = Modifier.fillMaxWidth().fillMaxHeight(.88f),
                presentation = DetailPresentation.MediaBottomSheet,
                onBack = viewModel::closeMediaComments,
            )
        }
    }
}

@Composable
private fun AdDetailScreen(
    ad: AdLaunchContext,
    state: ReadThatUiState,
    viewModel: ReadThatViewModel,
) {
    val imageByteLoader = rememberPlatformImageByteLoader(viewModel)
    SharedPlatformAdDetailScreen(
        ad = ad,
        settings = state.settings,
        onClose = viewModel::back,
        imageByteLoader = imageByteLoader,
    )
}

@Composable
private fun SharedSearchScreen(viewModel: ReadThatViewModel) {
    val state by viewModel.sharedSearchState.collectAsStateWithLifecycle()
    val pagedResults = viewModel.pagedSearchResults.collectAsLazyPagingItems()
    val imageByteLoader = rememberPlatformImageByteLoader(viewModel)
    SharedPlatformSearchRoute(
        state = state,
        pagedResults = pagedResults,
        onQueryChanged = viewModel::onSharedSearchQueryChanged,
        onSubmit = viewModel::submitSharedSearch,
        onClearQuery = viewModel::clearSharedSearchQuery,
        onBack = viewModel::back,
        onSelectType = viewModel::selectSharedSearchType,
        onSelectSort = viewModel::selectSharedSearchSort,
        onSelectTime = viewModel::selectSharedSearchTime,
        onToggleSafe = viewModel::toggleSharedSafeSearch,
        onDeleteRecent = viewModel::deleteSharedRecentSearch,
        onClearRecent = viewModel::clearSharedRecentSearches,
        onRetryAll = viewModel::retrySharedAllSearch,
        onPost = { postId -> viewModel.navigate(AppDestination.PostDetail(postId)) },
        onComment = { postId, commentId ->
            viewModel.navigate(AppDestination.PostDetail(postId, focusCommentId = commentId))
        },
        onCommunity = viewModel::openCommunity,
        onProfile = { username -> viewModel.navigate(AppDestination.PublicProfile(username)) },
        imageByteLoader = imageByteLoader,
    )
}

@Composable
private fun CommunitiesScreen(viewModel: ReadThatViewModel) {
    val discovery by viewModel.sharedCommunityDiscoveryState.collectAsStateWithLifecycle()
    SharedCommunityDiscoveryScreen(
        state = discovery,
        onBack = viewModel::back,
        onSearch = { viewModel.navigate(AppDestination.Search) },
        onCommunity = viewModel::openCommunity,
        onTrendingPost = { postId -> viewModel.navigate(AppDestination.PostDetail(postId)) },
        onCreateCommunity = {
            viewModel.navigate(AppDestination.CreateCommunity)
        },
        onRefresh = viewModel::refreshCommunityDiscovery,
    )
}

@Composable
private fun CreateScreen(state: ReadThatUiState, viewModel: ReadThatViewModel) {
    val creation = state.create
    if (creation.mode == CreateMode.Community) {
        SharedCreateCommunityScreen(
            state = creation,
            onBack = viewModel::back,
            onSubmit = viewModel::submitCreate,
            onNameChanged = viewModel::setCommunityName,
            onDisplayNameChanged = viewModel::setCommunityDisplayName,
            onDescriptionChanged = viewModel::setCommunityDescription,
            onAccessChanged = viewModel::setCommunityAccess,
        )
        return
    }
    val pickImages = rememberPlatformMediaPickerLauncher(
        kind = PostKind.Image,
        onPicked = viewModel::addPickedMedia,
        onError = viewModel::reportCreateError,
    )
    val pickVideo = rememberPlatformMediaPickerLauncher(
        kind = PostKind.Video,
        onPicked = viewModel::addPickedMedia,
        onError = viewModel::reportCreateError,
    )
    val takePhoto = rememberPlatformCameraLauncher(
        onPicked = viewModel::addPickedMedia,
        onError = viewModel::reportCreateError,
    )
    SharedCreatePostScreen(
        state = creation,
        onBack = viewModel::back,
        onSubmit = viewModel::submitCreate,
        onCommunityChanged = viewModel::setPostCommunity,
        onTitleChanged = viewModel::setPostTitle,
        onBodyChanged = viewModel::setPostBody,
        onLinkChanged = viewModel::setPostLink,
        onKindChanged = viewModel::setPostKind,
        onFlairChanged = viewModel::setPostFlair,
        onRemoveMedia = viewModel::removePickedMedia,
        onRefreshCommunities = viewModel::refreshCreationCommunities,
        onPickImages = pickImages,
        onPickVideo = pickVideo,
        onTakePhoto = takePhoto,
        stagedMediaRenderer = { media, kind, modifier ->
            if (kind == PostKind.Image) {
                StagedImage(viewModel, media, modifier)
            } else {
                Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PlayArrow, "Selected video", tint = Color.White, modifier = Modifier.size(54.dp))
                }
            }
        },
    )
}

@Composable
private fun PendingPostScreen(state: ReadThatUiState, viewModel: ReadThatViewModel) {
    SharedPendingPostScreen(
        state = state.creationStatus,
        onBack = viewModel::back,
        onRetry = viewModel::retryPendingCreation,
        onCommitted = { viewModel.navigate(AppDestination.PostDetail(it)) },
    )
}

@Composable
private fun PendingCommunityScreen(state: ReadThatUiState, viewModel: ReadThatViewModel) {
    SharedPendingCommunityScreen(
        state = state.creationStatus,
        onBack = viewModel::back,
        onRetry = viewModel::retryPendingCreation,
        onCreatePost = { community ->
            viewModel.navigate(AppDestination.CreatePost(community))
        },
        onOpenCommunity = viewModel::openCommunity,
    )
}

@Composable
@OptIn(FlowPreview::class)
private fun CommunityScreen(state: ReadThatUiState, name: String, viewModel: ReadThatViewModel) {
    val communityState by viewModel.sharedCommunityDetailState.collectAsStateWithLifecycle()
    val community = communityState.detail
    val interactionRecorder = rememberSharedFeedInteractionRecorder(PerformanceSurface.COMMUNITY)
    val listState = rememberLazyListState()
    val items = viewModel.pagedCommunityFeedCells.collectAsLazyPagingItems()
    val feedCells = items.itemSnapshotList.items
    val feedCards = remember(feedCells) { feedCardsFromCells(feedCells) }
    val refresh = items.loadState.refresh
    val append = items.loadState.append
    val hasCachedRows = items.itemCount > 0
    val showingOfflineRows = communityState.offline ||
        (hasCachedRows && (refresh is LoadState.Error || append is LoadState.Error))
    val prefetchCatalog = remember(feedCells) { feedMediaPrefetchCatalog(feedCells) }
    var firstVisibleIndex by remember { mutableStateOf(0) }
    val prefetchPlan = remember(prefetchCatalog, firstVisibleIndex) {
        prefetchCatalog.plan(firstVisibleIndex)
    }
    val imageByteLoader = rememberPlatformImageByteLoader(viewModel)
    val videoPolicy = rememberPlatformVideoPlaybackPolicy(state.settings)
    LaunchedEffect(feedCards) { viewModel.updateCommunityPresentationWindow(feedCards) }
    SharedCommunityDetailTelemetry(
        state = communityState,
        communityName = name,
        hasFeedContent = hasCachedRows,
        feedLoadFailed = !hasCachedRows && (refresh is LoadState.Error || append is LoadState.Error),
    )
    SharedFeedMediaPreloadWindow(
        plan = prefetchPlan,
        settings = state.settings,
        videoPolicy = videoPolicy,
        imageByteLoader = imageByteLoader,
    )
    SharedFeedScreen(
        items = items,
        initialCacheTier = communityState.initialCacheTier,
        explicitlyOffline = communityState.offline,
        autoplayEnabled = videoPolicy.autoplay,
        onUserRefresh = {
            viewModel.markUserCommunityFeedRefresh(name)
            viewModel.refreshCommunity()
        },
        onRetry = { viewModel.markCommunityFeedErrorRetry(name) },
        onFirstVisibleItemChanged = { index ->
            firstVisibleIndex = index
        },
        onFirstContentRendered = {},
        onPrefetchComments = viewModel::prefetchComments,
        onReshare = viewModel::reshareFromFeed,
        itemContent = { cell, adMedia, postTitle, playInline, requestReshare ->
            FeedCellView(
                cell = cell,
                state = state,
                viewModel = viewModel,
                playInlineVideo = playInline,
                adMedia = adMedia,
                postTitle = postTitle,
                openEventName = ProductEventName.COMMUNITY_POST_VIEW,
                productSurface = ProductSurface.COMMUNITY,
                interactionRecorder = interactionRecorder,
                onReshare = requestReshare,
            )
        },
        modifier = Modifier.fillMaxSize(),
        listState = listState,
        listHeader = {
            SharedPlatformCommunityDetailHeader(
                state = communityState.copy(offline = showingOfflineRows),
                communityName = name,
                onBack = viewModel::back,
                onSearch = { viewModel.navigate(AppDestination.Search) },
                onToggleMembership = {
                    community?.let { viewModel.setCommunityJoined(!it.isJoined) }
                },
                onRetry = viewModel::refreshCommunity,
                onCreatePost = {
                    viewModel.navigate(AppDestination.CreatePost(community?.name ?: name))
                },
                imageByteLoader = rememberPlatformImageByteLoader(viewModel),
            )
        },
        productSurface = ProductSurface.COMMUNITY,
    )
}

@Composable
private fun ProfileScreen(state: ReadThatUiState, viewModel: ReadThatViewModel) {
    val user = (state.session as? SessionState.SignedIn)?.user ?: return
    SharedPlatformOwnProfileScreen(
        user = user,
        onEdit = { viewModel.navigate(AppDestination.EditProfile) },
        onSettings = { viewModel.navigate(AppDestination.Settings) },
        imageByteLoader = rememberPlatformImageByteLoader(viewModel),
    )
}

@Composable
private fun EditProfileScreen(state: ReadThatUiState, viewModel: ReadThatViewModel) {
    val user = (state.session as? SessionState.SignedIn)?.user ?: return
    val editor = state.profile
    SharedPlatformEditProfileScreen(
        user = user,
        state = ProfileEditorUiState(
            displayName = editor.displayName,
            bio = editor.bio,
            saving = editor.saving,
            hasAvatar = editor.avatar != null || (!editor.removeAvatar && user.avatarUrl != null),
            error = editor.error,
        ),
        onBack = viewModel::back,
        onSave = viewModel::saveProfile,
        onDisplayNameChanged = viewModel::setProfileDisplayName,
        onBioChanged = viewModel::setProfileBio,
        onRemoveAvatar = viewModel::removeProfileAvatar,
        avatarUrl = user.avatarUrl.takeUnless { editor.removeAvatar },
        localPreviewRenderer = editor.avatar?.let { stagedAvatar ->
            { modifier -> StagedImage(viewModel, stagedAvatar, modifier) }
        },
        imageByteLoader = rememberPlatformImageByteLoader(viewModel),
        avatarPicker = { enabled, hasAvatar ->
            SharedAvatarPickerButton(
                enabled = enabled,
                hasAvatar = hasAvatar,
                onPicked = viewModel::setProfileAvatar,
                onError = viewModel::reportProfileError,
            )
        },
    )
}

@Composable
private fun PublicProfileScreen(state: ReadThatUiState, viewModel: ReadThatViewModel) {
    SharedPlatformPublicProfileScreen(
        user = state.profile.publicProfile,
        loading = state.profile.loading,
        error = state.profile.error,
        onBack = viewModel::back,
        onRetry = viewModel::retryPublicProfile,
        imageByteLoader = rememberPlatformImageByteLoader(viewModel),
    )
}

@Composable
private fun SettingsScreen(state: ReadThatUiState, viewModel: ReadThatViewModel) {
    val user = (state.session as? SessionState.SignedIn)?.user ?: return
    val settingsState by viewModel.sharedSettingsState.collectAsStateWithLifecycle()
    SharedSettingsScreen(
        user = user,
        state = settingsState,
        onBack = viewModel::back,
        onEditProfile = { viewModel.navigate(AppDestination.EditProfile) },
        onCreateCommunity = {
            viewModel.navigate(AppDestination.CreateCommunity)
        },
        onPreferenceChanged = viewModel::setSettingPreference,
        onLogout = viewModel::logout,
        onClearError = viewModel::clearSettingsError,
    )
}

internal fun accountAgeLabel(createdAt: Long, nowMillis: Long): String {
    if (createdAt <= 0L || nowMillis < createdAt) return "New"
    val days = (nowMillis - createdAt) / 86_400_000L
    return when {
        days < 1L -> "Today"
        days < 30L -> "${days}d"
        days < 365L -> "${days / 30L}mo"
        else -> "${days / 365L}y"
    }
}

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun StagedImage(viewModel: ReadThatViewModel, media: LocalPostMedia, modifier: Modifier) {
    val decodedKey = "staged:${media.localPath}:${media.byteSize}"
    var bitmap by remember(decodedKey) { mutableStateOf(StagedDecodedImageCache.get(decodedKey)) }
    var failed by remember(decodedKey) { mutableStateOf(false) }
    var retryRequest by remember(decodedKey) { mutableStateOf(0) }
    LaunchedEffect(decodedKey, retryRequest) {
        if (bitmap == null) {
            failed = false
            bitmap = try {
                val bytes = viewModel.loadStagedMediaBytes(media)
                withContext(Dispatchers.Default) { bytes.decodeToImageBitmap() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }?.also { StagedDecodedImageCache.put(decodedKey, it) }
            failed = bitmap == null
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: if (failed) {
                TextButton(onClick = { retryRequest += 1 }) { Text("Retry preview") }
            } else {
                CircularProgressIndicator(Modifier.size(28.dp))
            }
    }
}

@Composable
private fun rememberPlatformImageByteLoader(
    viewModel: ReadThatViewModel,
): PlatformImageByteLoader = remember(viewModel) {
    PlatformImageByteLoader { request ->
        viewModel.loadMediaBytes(request.url, request.cacheKey, request.videoPreview)
    }
}

/** Staged local previews do not enter the network image pipeline. */
private object StagedDecodedImageCache {
    private const val MAX_BYTES = 64L * 1_048_576L
    private val entries = LinkedHashMap<String, ImageBitmap>()
    private var sizeBytes = 0L

    fun get(key: String): ImageBitmap? = entries.remove(key)?.also { entries[key] = it }

    fun put(key: String, bitmap: ImageBitmap) {
        entries.remove(key)?.let { sizeBytes -= it.estimatedBytes }
        entries[key] = bitmap
        sizeBytes += bitmap.estimatedBytes
        while (sizeBytes > MAX_BYTES && entries.isNotEmpty()) {
            val eldest = entries.entries.first()
            entries.remove(eldest.key)
            sizeBytes -= eldest.value.estimatedBytes
        }
    }

    fun clear() {
        entries.clear()
        sizeBytes = 0L
    }

    private val ImageBitmap.estimatedBytes: Long
        get() = width.toLong() * height.toLong() * 4L
}
