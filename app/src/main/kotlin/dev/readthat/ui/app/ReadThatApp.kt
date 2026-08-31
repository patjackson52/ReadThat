package dev.readthat.ui.app

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.material3.rememberDrawerState
import dev.readthat.core.ui.typography.ReadThatTextStyles
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.core.view.WindowCompat
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.readthat.comments.ui.PostDetailChromeColor
import dev.readthat.comments.ui.PostDetailScreen
import dev.readthat.comments.ui.PostDetailPresentation
import dev.readthat.comments.ui.PostDetailToolbarHeight
import dev.readthat.communitydetail.ui.SharedCommunityDetailHeader
import dev.readthat.communitydetail.ui.SharedCommunityDetailTelemetry
import dev.readthat.BuildConfig
import dev.readthat.client.AndroidReadThatClientConfiguration
import dev.readthat.client.AndroidReadThatClientRegistry
import dev.readthat.client.SharedFeedViewModel
import dev.readthat.client.SharedDetailViewModel
import dev.readthat.client.SharedMediaFeedScope
import dev.readthat.client.SharedMediaFeedViewModel
import dev.readthat.client.SharedProfileViewModel
import dev.readthat.client.CreateMode
import dev.readthat.client.SharedCreationOutcome
import dev.readthat.client.SharedCreationViewModel
import dev.readthat.client.SharedSettingsViewModel
import dev.readthat.client.SharedCommunityDrawerViewModel
import dev.readthat.client.SharedCommunityDetailViewModel
import dev.readthat.client.SharedCommunityDiscoveryViewModel
import dev.readthat.client.SharedAuthState
import dev.readthat.client.toSharedMediaFeedLaunchContext
import dev.readthat.data.db.AppDatabase
import dev.readthat.data.sync.FeedSyncScheduler
import dev.readthat.data.sync.PostUploadScheduler
import dev.readthat.data.sync.SubredditCreationScheduler
import dev.readthat.shared.SessionState
import dev.readthat.shared.UserProfile
import dev.readthat.shared.PostTransitionPreview
import dev.readthat.shared.PostHeader
import dev.readthat.domain.NormalFeedMediaContext
import dev.readthat.navigation.AppDestination
import dev.readthat.navigation.AppNavigationPolicy
import dev.readthat.navigation.toAppDestination
import dev.readthat.deeplink.DeepLinkInbox
import dev.readthat.deeplink.ReadThatDeepLink
import dev.readthat.deeplink.ReadThatDeepLinks
import dev.readthat.ui.FeedScreen
import dev.readthat.feed.ui.SharedFeedAccount as FeedAccountHeader
import dev.readthat.auth.ui.SharedAuthScreen
import dev.readthat.ui.ads.AdDetailScreen
import dev.readthat.ui.create.SharedAndroidCreateCommunityScreen
import dev.readthat.ui.create.SharedAndroidCreatePostScreen
import dev.readthat.ui.create.SharedAndroidPendingCommunityScreen
import dev.readthat.ui.create.SharedAndroidPendingPostScreen
import dev.readthat.profile.ui.EditProfileScreen
import dev.readthat.profile.ui.ProfileScreen
import dev.readthat.profile.ui.PublicProfileScreen
import dev.readthat.mediafeed.domain.MediaFeedItem
import dev.readthat.mediafeed.ui.MediaFeedScreen
import dev.readthat.search.ui.SearchScreen
import dev.readthat.search.ui.SearchViewModel
import dev.readthat.sharing.SharePayloads
import dev.readthat.sharing.ui.rememberPlatformSharePayloadAction
import dev.readthat.settings.ui.SharedSettingsScreen
import dev.readthat.shell.ui.SharedActivityScreen
import dev.readthat.shell.ui.SharedBottomNavigation
import dev.readthat.shell.ui.SharedStartupShell
import dev.readthat.community.ui.SharedCommunityDrawer
import dev.readthat.community.ui.SharedCommunityDiscoveryScreen
import dev.readthat.core.ui.theme.ReadThatTheme
import dev.readthat.observability.AndroidPerformanceSession
import dev.readthat.observability.PerformanceSurface
import dev.readthat.observability.PerformanceTimer
import dev.readthat.observability.performanceTimer
import dev.readthat.observability.ProductEventName
import dev.readthat.observability.ProductSurface
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun ReadThatApp(
    appViewModel: AppViewModel = viewModel(),
    performanceSession: AndroidPerformanceSession? = null,
    deepLinks: DeepLinkInbox? = null,
) {
    val state by appViewModel.uiState.collectAsStateWithLifecycle()
    val authState by appViewModel.authState.collectAsStateWithLifecycle()
    val pendingDeepLinkState = deepLinks?.pending?.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val platformUriHandler = LocalUriHandler.current
    val inboxUriHandler = object : UriHandler {
        override fun openUri(uri: String) {
            val target = ReadThatDeepLinks.parse(uri)
            if (target == null || deepLinks == null) platformUriHandler.openUri(uri)
            else deepLinks.offer(target)
        }
    }
    LaunchedEffect(appViewModel) {
        appViewModel.messages.collectLatest { snackbar.showSnackbar(it) }
    }

    ReadThatTheme(state.settings.darkTheme) {
        CompositionLocalProvider(LocalUriHandler provides inboxUriHandler) {
            Surface(Modifier.fillMaxSize()) {
                when (val session = state.session) {
                    SessionState.Restoring -> SharedStartupShell()
                    SessionState.SignedOut -> pendingDeepLinkState?.value?.let { target ->
                        SignedOutDeepLinkedPost(
                            target = target,
                            settings = state.settings,
                            onBack = { deepLinks?.consume(target) },
                            onContinueThread = { commentId ->
                                deepLinks?.offer(ReadThatDeepLink.Comment(target.postId, commentId))
                            },
                        )
                    } ?: SignedOutApp(appViewModel, authState, snackbar)
                    is SessionState.SignedIn -> SignedInApp(
                        appViewModel,
                        session.user,
                        snackbar,
                        performanceSession,
                        pendingDeepLinkState?.value,
                        deepLinks?.let { inbox -> { target -> inbox.consume(target) } },
                    )
                }
            }
        }
    }
}

/** Static first-frame shell: never block launch pixels on disk or network. */
/** Mature Android startup implementation retained compiled as a migration reference. */
@Suppress("unused")
@Composable
private fun LegacyStartupShell() {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Text("Home", modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
        repeat(3) {
            Surface(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .fillMaxWidth()
                    .height(116.dp),
                color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            ) {}
        }
        Spacer(Modifier.weight(1f))
        NavigationBar {
            listOf(Icons.Default.Home, Icons.Default.Add, Icons.Default.Notifications, Icons.Default.Person)
                .forEach { icon ->
                    NavigationBarItem(
                        selected = icon == Icons.Default.Home,
                        onClick = {},
                        icon = { Icon(icon, null) },
                    )
                }
        }
    }
}

@Composable
private fun SignedOutApp(
    viewModel: AppViewModel,
    auth: SharedAuthState,
    snackbar: SnackbarHostState,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        SharedAuthScreen(
            state = auth,
            onMode = viewModel::setAuthMode,
            onUsername = viewModel::updateUsername,
            onDisplayName = viewModel::updateDisplayName,
            onPassword = viewModel::updatePassword,
            onTogglePassword = viewModel::togglePasswordVisibility,
            onSubmit = viewModel::submitAuth,
            onClearMessage = viewModel::clearAuthMessage,
            modifier = Modifier.fillMaxSize().padding(padding)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding(),
        )
    }
}

@Composable
private fun SignedOutDeepLinkedPost(
    target: ReadThatDeepLink,
    settings: dev.readthat.shared.AppSettings,
    onBack: () -> Unit,
    onContinueThread: (String) -> Unit,
) {
    val focusCommentId = (target as? ReadThatDeepLink.Comment)?.commentId
    val detailViewModel = detailViewModel(
        accountId = dev.readthat.data.db.CacheScope.DEFAULT_ACCOUNT_ID,
        postId = target.postId,
        focusedCommentId = focusCommentId,
        canMutate = false,
        keyPrefix = "anonymous-deep-link",
    )
    DetailEdgeToEdgeContent {
        PostDetailScreen(
            viewModel = detailViewModel,
            onBack = onBack,
            onContinueThread = onContinueThread,
            onCommunityClick = {},
            settings = settings,
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SignedInApp(
    appViewModel: AppViewModel,
    user: UserProfile,
    snackbar: SnackbarHostState,
    performanceSession: AndroidPerformanceSession?,
    pendingDeepLink: ReadThatDeepLink?,
    onDeepLinkConsumed: ((ReadThatDeepLink) -> Unit)?,
) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val sharedDestination = entry?.toSharedDestinationOrNull()
    val settings by appViewModel.settings.collectAsStateWithLifecycle()
    val communityDrawerViewModel = communityDrawerViewModel(user.id)
    val communityDrawerState by communityDrawerViewModel.state.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    LaunchedEffect(drawerState.currentValue) {
        if (drawerState.currentValue == DrawerValue.Open) communityDrawerViewModel.onOpened()
    }
    var transitionPreview by remember { mutableStateOf<PostTransitionPreview?>(null) }
    var mediaLaunchContext by remember { mutableStateOf<NormalFeedMediaContext?>(null) }
    var mediaFeedInteractionTimer by remember { mutableStateOf<PerformanceTimer?>(null) }
    var transitionFeedY by remember { mutableStateOf<Float?>(null) }
    var retainedBottomDestination by remember { mutableStateOf<AppDestination?>(null) }
    val visibleFeedPositions = remember { mutableMapOf<String, Float>() }
    val presentSharePayload = rememberPlatformSharePayloadAction()

    fun sharePost(postId: String, title: String) {
        presentSharePayload(appViewModel.sharePayload(postId, title))
    }

    fun sharePromoted(url: String) {
        SharePayloads.linkOrNull(url, "Promoted link")?.let(presentSharePayload)
    }

    fun reportReshare(community: String, error: String?) {
        val target = community.trim().removePrefix("r/")
        drawerScope.launch {
            snackbar.showSnackbar(error ?: "Post reshared to r/$target")
        }
    }

    fun recordFeedPosition(postId: String, y: Float) {
        visibleFeedPositions[postId] = y
        // Refresh the retained value when the returning feed is laid out. This keeps Back aligned
        // if pagination or a vote changed the selected row while detail was open.
        if (transitionPreview?.postId == postId) transitionFeedY = y
    }

    fun freezeFeedTransition(postId: String, preview: PostTransitionPreview) {
        transitionPreview = preview
        transitionFeedY = visibleFeedPositions[postId]
    }

    fun clearFeedTransition() {
        transitionPreview = null
        transitionFeedY = null
    }

    fun openCommunity(rawName: String) {
        val destination = AppNavigationPolicy.communityDestination(rawName) ?: return
        clearFeedTransition()
        nav.navigateShared(destination) { launchSingleTop = true }
    }

    fun openDeepLink(target: ReadThatDeepLink) {
        clearFeedTransition()
        performanceSession?.beginComments()
        val sharedDestination = target.toAppDestination()
        nav.navigateShared(sharedDestination) { launchSingleTop = true }
    }

    LaunchedEffect(pendingDeepLink) {
        pendingDeepLink?.let { target ->
            openDeepLink(target)
            onDeepLinkConsumed?.invoke(target)
        }
    }

    val platformUriHandler = LocalUriHandler.current
    val readThatUriHandler = object : UriHandler {
        override fun openUri(uri: String) {
            val target = ReadThatDeepLinks.parse(uri)
            if (target == null) platformUriHandler.openUri(uri) else openDeepLink(target)
        }
    }

    val useDetailStatusIcons = AppNavigationPolicy.usesDetailSystemBars(sharedDestination)
    val showBottom = AppNavigationPolicy.showsBottomNavigation(sharedDestination)
    LaunchedEffect(sharedDestination, showBottom) {
        if (showBottom) retainedBottomDestination = sharedDestination
    }
    DetailSystemBarAppearance(
        useLightStatusIcons = useDetailStatusIcons,
        darkTheme = settings.darkTheme,
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = AppNavigationPolicy.allowsCommunityDrawer(sharedDestination),
        drawerContent = {
            SharedCommunityDrawer(
                state = communityDrawerState,
                onCreateCommunity = {
                    drawerScope.launch {
                        drawerState.close()
                        communityDrawerViewModel.showDrawer()
                        nav.navigateShared(AppDestination.CreateCommunity)
                    }
                },
                onCommunity = { name ->
                    drawerScope.launch {
                        drawerState.close()
                        communityDrawerViewModel.showDrawer()
                        openCommunity(name)
                    }
                },
                onSeeAll = communityDrawerViewModel::showAllRecents,
                onShowDrawer = communityDrawerViewModel::showDrawer,
                onToggleCommunities = communityDrawerViewModel::toggleCommunities,
                onRetry = communityDrawerViewModel::retry,
                onRemoveRecent = communityDrawerViewModel::removeRecent,
                onClearRecent = communityDrawerViewModel::clearRecent,
                onBrowse = {
                    drawerScope.launch {
                        drawerState.close()
                        communityDrawerViewModel.showDrawer()
                        nav.navigateShared(AppDestination.Communities)
                    }
                },
            )
        },
    ) {
      Box(Modifier.fillMaxSize()) {
        val layoutDirection = LocalLayoutDirection.current
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                AnimatedVisibility(
                    visible = showBottom,
                    enter = slideInVertically(
                        animationSpec = tween(
                            durationMillis = POST_DETAIL_POSITION_MILLIS,
                            easing = FastOutSlowInEasing,
                        ),
                        initialOffsetY = { it },
                    ),
                    exit = slideOutVertically(
                        animationSpec = tween(
                            durationMillis = POST_DETAIL_POSITION_MILLIS,
                            easing = FastOutSlowInEasing,
                        ),
                        targetOffsetY = { it },
                    ),
                ) {
                    AppBottomBar(nav, retainedBottomDestination ?: sharedDestination)
                }
            },
            containerColor = MaterialTheme.colorScheme.background,
            // Keep the top edge in the NavHost's coordinate space. Destinations inset their
            // controls explicitly, and captured feed positions therefore share root coordinates
            // with the detail content's final position.
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        ) { padding ->
          NavHost(
                  navController = nav,
                  startDestination = HomeRoute,
                  modifier = Modifier.padding(
                      start = padding.calculateStartPadding(layoutDirection),
                      end = padding.calculateEndPadding(layoutDirection),
                      bottom = if (showBottom) padding.calculateBottomPadding() else 0.dp,
                  ),
              ) {
                composable<HomeRoute>(
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                ) {
                    TopSafeDrawingContent {
                        FeedScreen(
                            viewModel = feedViewModel(user.id),
                            onCommunityClick = ::openCommunity,
                            onProfileClick = { username ->
                                nav.navigateShared(AppDestination.PublicProfile(username))
                            },
                            onSearch = { nav.navigateShared(AppDestination.Search) },
                            onOpenNavigation = {
                                drawerScope.launch { drawerState.open() }
                            },
                            accountHeader = FeedAccountHeader(
                                username = user.username,
                                displayName = user.displayName,
                                avatarUrl = user.avatarUrl,
                                updatedAt = user.updatedAt,
                            ),
                            onAccountClick = {
                                clearFeedTransition()
                                nav.navigateShared(AppDestination.Profile) { launchSingleTop = true }
                            },
                            onPostClick = { postId, preview ->
                                freezeFeedTransition(postId, preview)
                                performanceSession?.beginComments()
                                nav.navigateShared(AppDestination.PostDetail(postId))
                            },
                            onMediaClick = { context ->
                                mediaLaunchContext = context
                                mediaFeedInteractionTimer = performanceTimer()
                                nav.navigateShared(AppDestination.Media(
                                    postId = context.anchorPostId,
                                    snapshotId = context.snapshotId,
                                ))
                            },
                            onAdClick = { ad -> nav.navigateShared(AppDestination.AdDetail(ad)) },
                            onSharePost = ::sharePost,
                            onSharePromoted = ::sharePromoted,
                            settings = settings,
                            onFirstContentRendered = { cacheTier ->
                                performanceSession?.homeInteractive(cacheTier)
                            },
                            postPositionModifierFor = { postId ->
                                Modifier.onGloballyPositioned {
                                    recordFeedPosition(postId, it.boundsInRoot().top)
                                }
                            },
                        )
                    }
                }
            composable<CommunitiesRoute> {
                val discoveryViewModel = communityDiscoveryViewModel(user.id)
                val discoveryState by discoveryViewModel.state.collectAsStateWithLifecycle()
                TopSafeDrawingContent(includeBottom = true) {
                    SharedCommunityDiscoveryScreen(
                        state = discoveryState,
                        onBack = { nav.popBackStack() },
                        onSearch = { nav.navigateShared(AppDestination.Search) },
                        onCommunity = ::openCommunity,
                        onTrendingPost = { postId ->
                            clearFeedTransition()
                            performanceSession?.beginComments()
                            nav.navigateShared(AppDestination.PostDetail(postId))
                        },
                        onCreateCommunity = { nav.navigateShared(AppDestination.CreateCommunity) },
                        onRefresh = discoveryViewModel::refresh,
                    )
                }
            }
            composable<SearchRoute> {
                TopSafeDrawingContent(includeBottom = true) {
                    SearchScreen(
                        viewModel = searchViewModel(user.id),
                        onBack = { nav.popBackStack() },
                        onPost = { postId ->
                            clearFeedTransition()
                            performanceSession?.beginComments()
                            nav.navigateShared(AppDestination.PostDetail(postId))
                        },
                        onComment = { postId, commentId ->
                            clearFeedTransition()
                            performanceSession?.beginComments()
                            nav.navigateShared(AppDestination.PostDetail(postId, focusCommentId = commentId))
                        },
                        onCommunity = ::openCommunity,
                        onProfile = { nav.navigateShared(AppDestination.PublicProfile(it)) },
                    )
                }
            }
            composable<CommunityRoute>(
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
            ) { backStack ->
                val route = backStack.toRoute<CommunityRoute>()
                LaunchedEffect(route.name) { communityDrawerViewModel.record(route.name) }
                TopSafeDrawingContent(includeBottom = true) {
                    val detailViewModel = communityDetailViewModel(user.id, route.name)
                    SharedCommunityDetailTelemetry(detailViewModel, route.name.lowercase())
                    FeedScreen(
                        viewModel = feedViewModel(
                            accountId = user.id,
                            feedId = "feed:subreddit:${route.name.lowercase()}",
                            subreddit = route.name,
                        ),
                        onCommunityClick = ::openCommunity,
                        onProfileClick = { username ->
                            nav.navigateShared(AppDestination.PublicProfile(username))
                        },
                        showHeader = false,
                        onPostClick = { postId, preview ->
                            freezeFeedTransition(postId, preview)
                            performanceSession?.beginComments()
                            nav.navigateShared(AppDestination.PostDetail(postId))
                        },
                        onMediaClick = { context ->
                            mediaLaunchContext = context
                            mediaFeedInteractionTimer = performanceTimer()
                            nav.navigateShared(AppDestination.Media(
                                postId = context.anchorPostId,
                                subreddit = route.name,
                                snapshotId = context.snapshotId,
                            ))
                        },
                        onAdClick = { ad -> nav.navigateShared(AppDestination.AdDetail(ad)) },
                        onSharePost = ::sharePost,
                        onSharePromoted = ::sharePromoted,
                        settings = settings,
                        listHeader = {
                            SharedCommunityDetailHeader(
                                viewModel = detailViewModel,
                                communityName = route.name.lowercase(),
                                onBack = { nav.popBackStack() },
                                onSearch = { nav.navigateShared(AppDestination.Search) },
                                onCreatePost = {
                                    nav.navigateShared(AppDestination.CreatePost(route.name))
                                },
                            )
                        },
                        performanceSurface = PerformanceSurface.COMMUNITY,
                        productSurface = ProductSurface.COMMUNITY,
                        postOpenEventName = ProductEventName.COMMUNITY_POST_VIEW,
                        postPositionModifierFor = { postId ->
                            Modifier.onGloballyPositioned {
                                recordFeedPosition(postId, it.boundsInRoot().top)
                            }
                        },
                    )
                }
            }
            composable<PublicProfileRoute> { backStack ->
                val route = backStack.toRoute<PublicProfileRoute>()
                val profileViewModel = profileViewModel(
                    accountId = user.id,
                    publicUsername = route.username,
                )
                val profileState by profileViewModel.state.collectAsStateWithLifecycle()
                TopSafeDrawingContent(includeBottom = true) {
                    PublicProfileScreen(
                        state = profileState,
                        onBack = { nav.popBackStack() },
                        onRetry = { profileViewModel.retryPublicProfile(route.username) },
                    )
                }
            }
            composable<CreateRoute> { backStack ->
                val route = backStack.toRoute<CreateRoute>()
                TopSafeDrawingContent(includeBottom = true) {
                    SharedAndroidCreatePostScreen(
                        viewModel = creationViewModel(user.id, initialSubreddit = route.subreddit),
                        onBack = { nav.popBackStack() },
                        onQueued = { outcome ->
                            nav.navigateShared(AppDestination.PendingPost(outcome.mutationId)) {
                                popUpTo<CreateRoute> { inclusive = true }
                            }
                        },
                    )
                }
            }
            composable<PendingPostRoute> { backStack ->
                val route = backStack.toRoute<PendingPostRoute>()
                TopSafeDrawingContent(includeBottom = true) {
                    SharedAndroidPendingPostScreen(
                        viewModel = creationViewModel(user.id, pendingPostId = route.mutationId),
                        onBack = { nav.popBackStack() },
                        onCommitted = { postId ->
                            clearFeedTransition()
                            nav.navigateShared(AppDestination.PostDetail(postId)) {
                                popUpTo<PendingPostRoute> { inclusive = true }
                            }
                        },
                    )
                }
            }
            composable<CreateCommunityRoute> {
                TopSafeDrawingContent(includeBottom = true) {
                    SharedAndroidCreateCommunityScreen(
                        viewModel = creationViewModel(user.id, mode = CreateMode.Community),
                        onBack = { nav.popBackStack() },
                        onQueued = { outcome ->
                            nav.navigateShared(AppDestination.PendingCommunity(outcome.mutationId)) {
                                popUpTo<CreateCommunityRoute> { inclusive = true }
                            }
                        },
                    )
                }
            }
            composable<CommunityCreationStatusRoute> { backStack ->
                val route = backStack.toRoute<CommunityCreationStatusRoute>()
                TopSafeDrawingContent(includeBottom = true) {
                    SharedAndroidPendingCommunityScreen(
                        viewModel = creationViewModel(user.id, pendingCommunityId = route.mutationId),
                        onBack = { nav.popBackStack() },
                        onCreatePost = { subreddit ->
                            nav.navigateShared(AppDestination.CreatePost(subreddit))
                        },
                        onOpenCommunity = ::openCommunity,
                    )
                }
            }
            composable<ActivityRoute> { TopSafeDrawingContent { SharedActivityScreen() } }
            composable<ProfileRoute> {
                TopSafeDrawingContent {
                    ProfileScreen(
                        user,
                        { nav.navigateShared(AppDestination.EditProfile) },
                        { nav.navigateShared(AppDestination.Settings) },
                    )
                }
            }
            composable<SettingsRoute> {
                val settingsViewModel = settingsViewModel(user.id)
                val settingsState by settingsViewModel.state.collectAsStateWithLifecycle()
                TopSafeDrawingContent(includeBottom = true) {
                    SharedSettingsScreen(
                        user = user,
                        state = settingsState,
                        onBack = { nav.popBackStack() },
                        onEditProfile = { nav.navigateShared(AppDestination.EditProfile) },
                        onCreateCommunity = { nav.navigateShared(AppDestination.CreateCommunity) },
                        onPreferenceChanged = settingsViewModel::setPreference,
                        onLogout = appViewModel::logout,
                        onClearError = settingsViewModel::clearError,
                    )
                }
            }
            composable<EditProfileRoute> {
                val profileViewModel = profileViewModel(accountId = user.id, editorUser = user)
                val profileState by profileViewModel.state.collectAsStateWithLifecycle()
                DisposableEffect(profileViewModel) {
                    onDispose { profileViewModel.discardEditor() }
                }
                TopSafeDrawingContent(includeBottom = true) {
                    EditProfileScreen(
                        user = user,
                        state = profileState,
                        onBack = { nav.popBackStack() },
                        onSave = {
                            profileViewModel.saveProfile { nav.popBackStack() }
                        },
                        onDisplayNameChanged = profileViewModel::setDisplayName,
                        onBioChanged = profileViewModel::setBio,
                        onAvatarSelected = profileViewModel::setAvatar,
                        onRemoveAvatar = profileViewModel::removeAvatar,
                        onError = profileViewModel::reportError,
                    )
                }
            }
                composable<AdDetailRoute>(
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None },
                ) { backStack ->
                    val route = backStack.toRoute<AdDetailRoute>()
                    AdDetailScreen(
                        ad = route.toLaunchContext(),
                        settings = settings,
                        onClose = { nav.popBackStack() },
                    )
                }
                composable<MediaFeedRoute>(
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None },
                ) { backStack ->
                    val route = backStack.toRoute<MediaFeedRoute>()
                    val sourceContext = mediaLaunchContext?.takeIf {
                        it.anchorPostId == route.postId && it.snapshotId == route.snapshotId
                    }
                    DisposableEffect(route.snapshotId) {
                        onDispose {
                            if (mediaLaunchContext?.snapshotId == route.snapshotId) {
                                mediaLaunchContext = null
                            }
                            mediaFeedInteractionTimer = null
                        }
                    }
                    LaunchedEffect(route.snapshotId, sourceContext != null) {
                        // The ViewModel factory has synchronously consumed the handoff.
                        // The route's snapshotId and Room now own restoration.
                        if (sourceContext != null && mediaLaunchContext?.snapshotId == route.snapshotId) {
                            mediaLaunchContext = null
                        }
                    }
                    var detailItem by remember(route.postId) { mutableStateOf<MediaFeedItem?>(null) }
                    MediaFeedScreen(
                        viewModel = mediaFeedViewModel(user.id, route, sourceContext),
                        settings = settings,
                        onClose = { nav.popBackStack() },
                        onOpenDetails = { detailItem = it },
                        onOpenCommunity = ::openCommunity,
                        onOpenUser = { username ->
                            nav.navigateShared(AppDestination.PublicProfile(username))
                        },
                        onShare = { item -> sharePost(item.postId, item.title) },
                        interactionTimer = mediaFeedInteractionTimer,
                    )
                    detailItem?.let { item ->
                        val mediaDetailViewModel = detailViewModel(
                            accountId = user.id,
                            postId = item.postId,
                            projectedHeader = item.toPostHeader(),
                            performanceSurface = PerformanceSurface.MEDIA,
                            keyPrefix = "media-comments",
                        )
                        ModalBottomSheet(
                            onDismissRequest = { detailItem = null },
                            containerColor = MaterialTheme.colorScheme.surface,
                        ) {
                            CompositionLocalProvider(LocalUriHandler provides readThatUriHandler) {
                                PostDetailScreen(
                                    viewModel = mediaDetailViewModel,
                                    onBack = { detailItem = null },
                                    onCommunityClick = { communityName ->
                                        detailItem = null
                                        openCommunity(communityName)
                                    },
                                    onSharePost = { sharePost(item.postId, item.title) },
                                    onResharePost = { community ->
                                        mediaDetailViewModel.reshare(community) { error ->
                                            reportReshare(community, error)
                                        }
                                    },
                                    onContinueThread = { commentId ->
                                        detailItem = null
                                        nav.navigateShared(
                                            AppDestination.PostDetail(
                                                item.postId,
                                                rootCommentId = commentId,
                                            ),
                                        )
                                    },
                                    onCommentsInteractive = { fromPrefetch, phase, successful ->
                                        performanceSession?.commentsInteractive(fromPrefetch, phase, successful)
                                    },
                                    settings = settings,
                                    transitionPreview = item.toTransitionPreview(),
                                    presentation = PostDetailPresentation.MediaBottomSheet,
                                    productSurface = ProductSurface.MEDIA,
                                    modifier = Modifier.fillMaxHeight(.88f),
                                )
                            }
                        }
                    }
                }
                composable<PostDetailRoute>(
                    // A visually neutral enter transition keeps the source destination composed
                    // beneath our independently animated detail surface for the full handoff.
                    enterTransition = {
                        if (settings.reduceAnimations) EnterTransition.None else fadeIn(
                            animationSpec = tween(POST_DETAIL_POSITION_MILLIS),
                            // Keep a real (but visually imperceptible) transition running so
                            // Navigation retains and draws the feed beneath the transparent gaps.
                            initialAlpha = 0.999f,
                        )
                    },
                    popExitTransition = { ExitTransition.None },
                ) { backStack ->
                    val route = backStack.toRoute<PostDetailRoute>()
                    val preview = transitionPreview?.takeIf { it.postId == route.postId }
                    val postDetailViewModel = detailViewModel(
                        accountId = user.id,
                        postId = route.postId,
                        focusedCommentId = route.focusCommentId,
                    )
                    val postDetailState by postDetailViewModel.uiState.collectAsStateWithLifecycle()
                    val animateFeedPosition = preview != null &&
                        transitionFeedY != null &&
                        !settings.reduceAnimations
                    val popProgress by this@composable.transition.animateFloat(
                        transitionSpec = {
                            if (!animateFeedPosition) snap()
                            else tween(
                                durationMillis = POST_DETAIL_POSITION_MILLIS,
                                easing = FastOutSlowInEasing,
                            )
                        },
                        label = "detail-to-feed-position",
                    ) { state ->
                        if (animateFeedPosition && state == EnterExitState.PostExit) 1f else 0f
                    }
                    val density = LocalDensity.current
                    val destinationContentY = WindowInsets.safeDrawing.getTop(density).toFloat() +
                        with(density) { PostDetailToolbarHeight.toPx() }
                    PostDetailPositionTransition(
                        enabled = animateFeedPosition,
                        sourceY = transitionFeedY,
                        destinationY = destinationContentY,
                        popProgress = popProgress,
                    ) { detailContentModifier ->
                        DetailEdgeToEdgeContent {
                            CompositionLocalProvider(LocalUriHandler provides readThatUriHandler) {
                                PostDetailScreen(
                                    viewModel = postDetailViewModel,
                                    onBack = { nav.popBackStack() },
                                    onCommunityClick = ::openCommunity,
                                    onSharePost = {
                                        sharePost(
                                            route.postId,
                                            postDetailState.detail.post?.title ?: preview?.title ?: "ReadThat post",
                                        )
                                    },
                                    onResharePost = { community ->
                                        postDetailViewModel.reshare(community) { error ->
                                            reportReshare(community, error)
                                        }
                                    },
                                    onContinueThread = {
                                        nav.navigateShared(
                                            AppDestination.PostDetail(route.postId, rootCommentId = it),
                                        )
                                    },
                                    onCommentsInteractive = { fromPrefetch, phase, successful ->
                                        performanceSession?.commentsInteractive(fromPrefetch, phase, successful)
                                    },
                                    settings = settings,
                                    transitionPreview = preview,
                                    detailContentModifier = detailContentModifier,
                                    containerColor = if (animateFeedPosition) {
                                        Color.Transparent
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
                                )
                            }
                        }
                    }
                }
            composable<ThreadDetailRoute> { backStack ->
                val route = backStack.toRoute<ThreadDetailRoute>()
                val threadDetailViewModel = detailViewModel(
                    accountId = user.id,
                    postId = route.postId,
                    rootCommentId = route.rootCommentId,
                    keyPrefix = "thread-detail",
                )
                val threadDetailState by threadDetailViewModel.uiState.collectAsStateWithLifecycle()
                DetailEdgeToEdgeContent {
                    CompositionLocalProvider(LocalUriHandler provides readThatUriHandler) {
                        PostDetailScreen(
                            viewModel = threadDetailViewModel,
                            onBack = { nav.popBackStack() },
                            onCommunityClick = ::openCommunity,
                            onSharePost = {
                                sharePost(
                                    route.postId,
                                    threadDetailState.detail.post?.title ?: "ReadThat post",
                                )
                            },
                            onResharePost = { community ->
                                threadDetailViewModel.reshare(community) { error ->
                                    reportReshare(community, error)
                                }
                            },
                            onContinueThread = {
                                nav.navigateShared(
                                    AppDestination.PostDetail(route.postId, rootCommentId = it),
                                )
                            },
                            onCommentsInteractive = { fromPrefetch, phase, successful ->
                                performanceSession?.commentsInteractive(fromPrefetch, phase, successful)
                            },
                            settings = settings,
                        )
                    }
                }
            }
              }
          }
        }
      }
}

/** Insets interactive destination content while leaving the NavHost itself truly edge-to-edge. */
@Composable
private fun TopSafeDrawingContent(
    includeBottom: Boolean = false,
    content: @Composable () -> Unit,
) {
    val sides = if (includeBottom) {
        WindowInsetsSides.Top + WindowInsetsSides.Bottom
    } else {
        WindowInsetsSides.Top
    }
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(sides)),
    ) {
        content()
    }
}

/**
 * Draws detail chrome behind the transparent status bar while keeping controls below the cutout.
 * Feed-to-detail motion is applied only to the content beneath this fixed destination chrome.
 */
@Composable
private fun DetailEdgeToEdgeContent(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        TopSafeDrawingContent(content = content)
        Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.safeDrawing)
                .background(PostDetailChromeColor)
                .align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun DetailSystemBarAppearance(
    useLightStatusIcons: Boolean,
    darkTheme: Boolean,
) {
    val view = LocalView.current
    DisposableEffect(view, useLightStatusIcons, darkTheme) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = !useLightStatusIcons && !darkTheme
        controller?.isAppearanceLightNavigationBars = !darkTheme

        onDispose {
            controller?.isAppearanceLightStatusBars = !darkTheme
            controller?.isAppearanceLightNavigationBars = !darkTheme
        }
    }
}

@Composable
private fun AppBottomBar(nav: NavHostController, destination: AppDestination?) {
    SharedBottomNavigation(
        selected = destination,
        onNavigate = { target ->
            when (target) {
                AppDestination.Feed -> nav.navigateShared(AppDestination.Feed) {
                    popUpTo<HomeRoute> { inclusive = false }
                    launchSingleTop = true
                }
                is AppDestination.CreatePost -> nav.navigateShared(target)
                AppDestination.Activity -> nav.navigateShared(AppDestination.Activity) {
                    launchSingleTop = true
                }
                AppDestination.Profile -> nav.navigateShared(AppDestination.Profile) {
                    launchSingleTop = true
                }
                else -> Unit
            }
        },
    )
}

/** Mature Android bottom navigation retained compiled as a migration reference. */
@Suppress("unused")
@Composable
private fun LegacyAppBottomBar(nav: NavHostController, destination: androidx.navigation.NavDestination?) {
    NavigationBar {
        NavigationBarItem(
            selected = destination?.hasRoute<HomeRoute>() == true,
            onClick = { nav.navigate(HomeRoute) { popUpTo<HomeRoute> { inclusive = false }; launchSingleTop = true } },
            icon = { Icon(Icons.Default.Home, null) },
            label = { Text("Home", style = ReadThatTextStyles.bottomNavigationLabel) },
        )
        NavigationBarItem(
            selected = false, onClick = { nav.navigate(CreateRoute()) },
            icon = { Icon(Icons.Default.Add, null) },
            label = { Text("Create", style = ReadThatTextStyles.bottomNavigationLabel) },
        )
        NavigationBarItem(
            selected = destination?.hasRoute<ActivityRoute>() == true,
            onClick = { nav.navigate(ActivityRoute) { launchSingleTop = true } },
            icon = { Icon(Icons.Default.Notifications, null) },
            label = { Text("Activity", style = ReadThatTextStyles.bottomNavigationLabel) },
        )
        NavigationBarItem(
            selected = destination?.hasRoute<ProfileRoute>() == true,
            onClick = { nav.navigate(ProfileRoute) { launchSingleTop = true } },
            icon = { Icon(Icons.Default.Person, null) },
            label = { Text("You", style = ReadThatTextStyles.bottomNavigationLabel) },
        )
    }
}

/** Mature Android activity empty state retained compiled as a migration reference. */
@Suppress("unused")
@Composable
private fun LegacyEmptyActivityScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nothing new yet") }
}

@Composable
private fun detailViewModel(
    accountId: String,
    postId: String,
    rootCommentId: String? = null,
    focusedCommentId: String? = null,
    projectedHeader: PostHeader? = null,
    canMutate: Boolean = true,
    performanceSurface: PerformanceSurface = PerformanceSurface.DETAIL,
    keyPrefix: String = "post-detail",
): SharedDetailViewModel {
    val context = LocalContext.current.applicationContext
    val factory = remember(
        context,
        accountId,
        postId,
        rootCommentId,
        focusedCommentId,
        projectedHeader,
        canMutate,
        performanceSurface,
    ) {
        viewModelFactory {
            initializer {
                val runtime = AndroidReadThatClientRegistry.get(
                    context,
                    AndroidReadThatClientConfiguration(
                        baseUrl = BuildConfig.READTHAT_API_BASE_URL,
                        appVersion = BuildConfig.VERSION_NAME,
                        demoUsername = BuildConfig.READTHAT_DEMO_USERNAME,
                        demoPassword = BuildConfig.READTHAT_DEMO_PASSWORD,
                    ),
                )
                SharedDetailViewModel(
                    client = runtime.client,
                    database = runtime.database,
                    accountId = accountId,
                    postId = postId,
                    rootCommentId = rootCommentId,
                    focusedCommentId = focusedCommentId,
                    projectedHeader = projectedHeader,
                    canMutate = canMutate,
                    onVoteQueued = { FeedSyncScheduler.enqueueVoteOutbox(context) },
                    performanceSurface = performanceSurface,
                )
            }
        }
    }
    return viewModel(
        key = "$keyPrefix:$accountId:$postId:${rootCommentId.orEmpty()}:${focusedCommentId.orEmpty()}",
        factory = factory,
    )
}

@Composable
private fun mediaFeedViewModel(
    accountId: String,
    route: MediaFeedRoute,
    sourceContext: NormalFeedMediaContext?,
): SharedMediaFeedViewModel {
    val context = LocalContext.current.applicationContext
    val snapshotId = sourceContext?.snapshotId ?: route.snapshotId
    val factory = remember(
        context,
        accountId,
        route.postId,
        route.subreddit,
        snapshotId,
        sourceContext != null,
    ) {
        viewModelFactory {
            initializer {
                val runtime = AndroidReadThatClientRegistry.get(
                    context,
                    AndroidReadThatClientConfiguration(
                        baseUrl = BuildConfig.READTHAT_API_BASE_URL,
                        appVersion = BuildConfig.VERSION_NAME,
                        demoUsername = BuildConfig.READTHAT_DEMO_USERNAME,
                        demoPassword = BuildConfig.READTHAT_DEMO_PASSWORD,
                    ),
                )
                val savedStateHandle = createSavedStateHandle()
                SharedMediaFeedViewModel(
                    client = runtime.client,
                    database = runtime.database,
                    accountId = accountId,
                    scope = SharedMediaFeedScope(route.postId, route.subreddit, snapshotId),
                    launchContext = sourceContext?.toSharedMediaFeedLaunchContext(),
                    restoredPage = savedStateHandle[MEDIA_FEED_CURRENT_PAGE],
                    onCurrentPageChanged = { page -> savedStateHandle[MEDIA_FEED_CURRENT_PAGE] = page },
                    onVoteQueued = { FeedSyncScheduler.enqueueVoteOutbox(context) },
                )
            }
        }
    }
    return viewModel(
        key = "media:$accountId:${route.postId}:${route.subreddit}:${snapshotId ?: "remote"}",
        factory = factory,
    )
}

private const val MEDIA_FEED_CURRENT_PAGE = "media_feed_current_page"

@Composable
private fun feedViewModel(
    accountId: String,
    feedId: String = dev.readthat.data.db.CacheScope.HOME_FEED_ID,
    subreddit: String? = null,
): SharedFeedViewModel {
    val context = LocalContext.current.applicationContext
    val factory = remember(context, accountId, feedId, subreddit) {
        viewModelFactory {
            initializer {
                val runtime = AndroidReadThatClientRegistry.get(
                    context,
                    AndroidReadThatClientConfiguration(
                        baseUrl = BuildConfig.READTHAT_API_BASE_URL,
                        appVersion = BuildConfig.VERSION_NAME,
                        demoUsername = BuildConfig.READTHAT_DEMO_USERNAME,
                        demoPassword = BuildConfig.READTHAT_DEMO_PASSWORD,
                    ),
                )
                SharedFeedViewModel(
                    client = runtime.client,
                    database = runtime.database,
                    accountId = accountId,
                    feedId = feedId,
                    subreddit = subreddit,
                    onVoteQueued = { FeedSyncScheduler.enqueueVoteOutbox(context) },
                )
            }
        }
    }
    return viewModel(key = "feed:$accountId:$feedId", factory = factory)
}

@Composable
private fun searchViewModel(accountId: String): SearchViewModel {
    val context = LocalContext.current.applicationContext
    val factory = remember(context, accountId) {
        viewModelFactory {
            initializer {
                val runtime = AndroidReadThatClientRegistry.get(
                    context,
                    AndroidReadThatClientConfiguration(
                        baseUrl = BuildConfig.READTHAT_API_BASE_URL,
                        appVersion = BuildConfig.VERSION_NAME,
                        demoUsername = BuildConfig.READTHAT_DEMO_USERNAME,
                        demoPassword = BuildConfig.READTHAT_DEMO_PASSWORD,
                    ),
                )
                SearchViewModel(runtime.client, runtime.database, accountId)
            }
        }
    }
    return viewModel(key = "search:$accountId", factory = factory)
}

@Composable
private fun profileViewModel(
    accountId: String,
    editorUser: UserProfile? = null,
    publicUsername: String? = null,
): SharedProfileViewModel {
    val context = LocalContext.current.applicationContext
    val factory = remember(context, accountId, editorUser, publicUsername) {
        viewModelFactory {
            initializer {
                val runtime = AndroidReadThatClientRegistry.get(
                    context,
                    AndroidReadThatClientConfiguration(
                        baseUrl = BuildConfig.READTHAT_API_BASE_URL,
                        appVersion = BuildConfig.VERSION_NAME,
                        demoUsername = BuildConfig.READTHAT_DEMO_USERNAME,
                        demoPassword = BuildConfig.READTHAT_DEMO_PASSWORD,
                    ),
                )
                SharedProfileViewModel(
                    client = runtime.client,
                    database = runtime.database,
                    accountId = accountId,
                    editorUser = editorUser,
                    publicUsername = publicUsername,
                )
            }
        }
    }
    val modeKey = publicUsername?.let { "public:${it.lowercase()}" } ?: "edit"
    return viewModel(key = "profile:$accountId:$modeKey", factory = factory)
}

@Composable
private fun settingsViewModel(accountId: String): SharedSettingsViewModel {
    val context = LocalContext.current.applicationContext
    val factory = remember(context, accountId) {
        viewModelFactory {
            initializer {
                val runtime = AndroidReadThatClientRegistry.get(
                    context,
                    AndroidReadThatClientConfiguration(
                        baseUrl = BuildConfig.READTHAT_API_BASE_URL,
                        appVersion = BuildConfig.VERSION_NAME,
                        demoUsername = BuildConfig.READTHAT_DEMO_USERNAME,
                        demoPassword = BuildConfig.READTHAT_DEMO_PASSWORD,
                    ),
                )
                SharedSettingsViewModel(runtime.client, runtime.database, accountId)
            }
        }
    }
    return viewModel(key = "settings:$accountId", factory = factory)
}

@Composable
private fun creationViewModel(
    accountId: String,
    initialSubreddit: String = "",
    mode: CreateMode = CreateMode.Post,
    pendingPostId: String? = null,
    pendingCommunityId: String? = null,
): SharedCreationViewModel {
    val context = LocalContext.current.applicationContext
    val factory = remember(context, accountId, initialSubreddit, mode, pendingPostId, pendingCommunityId) {
        viewModelFactory {
            initializer {
                val runtime = AndroidReadThatClientRegistry.get(
                    context,
                    AndroidReadThatClientConfiguration(
                        baseUrl = BuildConfig.READTHAT_API_BASE_URL,
                        appVersion = BuildConfig.VERSION_NAME,
                        demoUsername = BuildConfig.READTHAT_DEMO_USERNAME,
                        demoPassword = BuildConfig.READTHAT_DEMO_PASSWORD,
                    ),
                )
                SharedCreationViewModel(
                    client = runtime.client,
                    database = runtime.database,
                    accountId = accountId,
                    initialSubreddit = initialSubreddit,
                    mode = mode,
                    pendingPostId = pendingPostId,
                    pendingCommunityId = pendingCommunityId,
                    onCreationQueued = { outcome ->
                        when (outcome) {
                            is SharedCreationOutcome.PostQueued ->
                                PostUploadScheduler.enqueue(context, outcome.mutationId)
                            is SharedCreationOutcome.CommunityQueued ->
                                SubredditCreationScheduler.enqueue(context, outcome.mutationId)
                        }
                    },
                )
            }
        }
    }
    val routeKey = pendingPostId?.let { "pending-post:$it" }
        ?: pendingCommunityId?.let { "pending-community:$it" }
        ?: "${mode.name.lowercase()}:${initialSubreddit.lowercase()}"
    return viewModel(key = "creation:$accountId:$routeKey", factory = factory)
}

@Composable
private fun communityDrawerViewModel(accountId: String): SharedCommunityDrawerViewModel {
    val context = LocalContext.current.applicationContext
    val factory = remember(context, accountId) {
        viewModelFactory {
            initializer {
                val runtime = AndroidReadThatClientRegistry.get(
                    context,
                    AndroidReadThatClientConfiguration(
                        baseUrl = BuildConfig.READTHAT_API_BASE_URL,
                        appVersion = BuildConfig.VERSION_NAME,
                        demoUsername = BuildConfig.READTHAT_DEMO_USERNAME,
                        demoPassword = BuildConfig.READTHAT_DEMO_PASSWORD,
                    ),
                )
                SharedCommunityDrawerViewModel(
                    client = runtime.client,
                    database = runtime.database,
                    accountId = accountId,
                    onVisitMutationQueued = { queuedAccountId ->
                        dev.readthat.data.sync.CommunityVisitSyncScheduler.enqueue(
                            context,
                            queuedAccountId,
                        )
                    },
                )
            }
        }
    }
    return viewModel(key = "community-drawer:$accountId", factory = factory)
}

@Composable
private fun communityDetailViewModel(accountId: String, name: String): SharedCommunityDetailViewModel {
    val context = LocalContext.current.applicationContext
    val normalized = name.trim().removePrefix("r/").lowercase()
    val factory = remember(context, accountId, normalized) {
        viewModelFactory {
            initializer {
                val runtime = AndroidReadThatClientRegistry.get(
                    context,
                    AndroidReadThatClientConfiguration(
                        baseUrl = BuildConfig.READTHAT_API_BASE_URL,
                        appVersion = BuildConfig.VERSION_NAME,
                        demoUsername = BuildConfig.READTHAT_DEMO_USERNAME,
                        demoPassword = BuildConfig.READTHAT_DEMO_PASSWORD,
                    ),
                )
                SharedCommunityDetailViewModel(
                    client = runtime.client,
                    database = runtime.database,
                    accountId = accountId,
                    communityName = normalized,
                    onMembershipMutationQueued = { queuedAccountId ->
                        dev.readthat.data.sync.CommunityMembershipSyncScheduler.enqueue(
                            context,
                            queuedAccountId,
                        )
                    },
                )
            }
        }
    }
    return viewModel(key = "community-detail:$accountId:$normalized", factory = factory)
}

@Composable
private fun communityDiscoveryViewModel(accountId: String): SharedCommunityDiscoveryViewModel {
    val context = LocalContext.current.applicationContext
    val factory = remember(context, accountId) {
        viewModelFactory {
            initializer {
                val runtime = AndroidReadThatClientRegistry.get(
                    context,
                    AndroidReadThatClientConfiguration(
                        baseUrl = BuildConfig.READTHAT_API_BASE_URL,
                        appVersion = BuildConfig.VERSION_NAME,
                        demoUsername = BuildConfig.READTHAT_DEMO_USERNAME,
                        demoPassword = BuildConfig.READTHAT_DEMO_PASSWORD,
                    ),
                )
                SharedCommunityDiscoveryViewModel(
                    client = runtime.client,
                    database = runtime.database,
                    accountId = accountId,
                )
            }
        }
    }
    return viewModel(key = "community-discovery:$accountId", factory = factory)
}
