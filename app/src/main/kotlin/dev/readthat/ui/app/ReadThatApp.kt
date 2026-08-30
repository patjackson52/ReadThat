package dev.readthat.ui.app

import android.app.Activity
import android.app.Application
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.material3.rememberDrawerState
import dev.readthat.core.ui.typography.ReadThatTextStyles
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.SavedStateHandle
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
import dev.readthat.comments.ui.CommentsViewModel
import dev.readthat.comments.ui.PostDetailChromeColor
import dev.readthat.comments.ui.PostDetailScreen
import dev.readthat.comments.ui.PostDetailPresentation
import dev.readthat.comments.ui.PostCommunityHeaderState
import dev.readthat.comments.ui.PostDetailToolbarHeight
import dev.readthat.communities.domain.ClearCommunityVisitsUseCase
import dev.readthat.communities.domain.ObserveCommunityDrawerUseCase
import dev.readthat.communities.domain.QueueCommunityCreationUseCase
import dev.readthat.communities.domain.RecordCommunityVisitUseCase
import dev.readthat.communities.domain.RefreshCommunityDrawerUseCase
import dev.readthat.communities.domain.RemoveCommunityVisitUseCase
import dev.readthat.communities.ui.CommunityDrawerContent
import dev.readthat.communities.ui.CommunityDrawerViewModel
import dev.readthat.communitydetail.ui.CommunityDetailHeader
import dev.readthat.communitydetail.ui.CommunityDetailTelemetry
import dev.readthat.communitydetail.ui.CommunityDetailViewModel
import dev.readthat.CommentsGraph
import dev.readthat.PostInteractionGraph
import dev.readthat.data.FeedRepository
import dev.readthat.data.backend.BackendGraph
import dev.readthat.data.community.CommunityGraph
import dev.readthat.data.community.CommunityDetailGraph
import dev.readthat.data.db.AppDatabase
import dev.readthat.data.sync.FeedSyncScheduler
import dev.readthat.shared.AuthMode
import dev.readthat.shared.SessionState
import dev.readthat.shared.UserProfile
import dev.readthat.shared.PostTransitionPreview
import dev.readthat.domain.NormalFeedMediaContext
import dev.readthat.domain.AdLaunchContext
import dev.readthat.domain.AdMediaKind
import dev.readthat.ui.FeedScreen
import dev.readthat.ui.FeedAccountHeader
import dev.readthat.ui.FeedViewModel
import dev.readthat.ui.auth.AuthScreen
import dev.readthat.ui.auth.WelcomeScreen
import dev.readthat.ui.ads.AdDetailScreen
import dev.readthat.ui.create.CreatePostScreen
import dev.readthat.ui.create.CreatePostViewModel
import dev.readthat.ui.create.CommunityCreationStatusScreen
import dev.readthat.ui.create.CommunityCreationStatusViewModel
import dev.readthat.ui.create.CreateCommunityScreen
import dev.readthat.ui.create.CreateCommunityViewModel
import dev.readthat.ui.create.PendingPostScreen
import dev.readthat.ui.create.PendingPostViewModel
import dev.readthat.profile.ui.EditProfileScreen
import dev.readthat.profile.ui.ProfileScreen
import dev.readthat.profile.ui.PublicProfileScreen
import dev.readthat.mediafeed.data.MediaFeedRepository
import dev.readthat.mediafeed.data.MediaFeedScope
import dev.readthat.mediafeed.domain.MediaFeedItem
import dev.readthat.mediafeed.domain.MediaFeedLaunchContext
import dev.readthat.mediafeed.ui.MediaFeedScreen
import dev.readthat.mediafeed.ui.MediaFeedViewModel
import dev.readthat.search.data.SearchRepository
import dev.readthat.search.ui.SearchScreen
import dev.readthat.search.ui.SearchViewModel
import dev.readthat.ui.settings.SettingsScreen
import dev.readthat.ui.theme.ReadThatTheme
import dev.readthat.observability.AndroidPerformanceSession
import dev.readthat.observability.PerformanceSurface
import dev.readthat.observability.PerformanceTimer
import dev.readthat.observability.performanceTimer
import dev.readthat.observability.ProductEventName
import dev.readthat.observability.ProductSurface
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable private object HomeRoute
@Serializable private data class CreateRoute(val subreddit: String = "")
@Serializable private object CreateCommunityRoute
@Serializable private data class CommunityCreationStatusRoute(val mutationId: String)
@Serializable private data class PendingPostRoute(val mutationId: String)
@Serializable private object ActivityRoute
@Serializable private object ProfileRoute
@Serializable private object SettingsRoute
@Serializable private object EditProfileRoute
@Serializable private data class PostDetailRoute(val postId: String, val focusCommentId: String? = null)
@Serializable private data class ThreadDetailRoute(val postId: String, val rootCommentId: String)
@Serializable private object SearchRoute
@Serializable private data class CommunityRoute(val name: String)
@Serializable private data class PublicProfileRoute(val username: String)
@Serializable private data class MediaFeedRoute(
    val postId: String,
    val subreddit: String? = null,
    /** Small durable pointer to the Room-backed normal-feed generation. */
    val snapshotId: String? = null,
)
@Serializable private data class AdDetailRoute(
    val adId: String,
    val creativeId: String,
    val mediaKind: String,
    val placeholderColor: Long,
    val aspectRatio: Float,
    val altText: String,
    val imageUrl: String? = null,
    val hlsUrl: String? = null,
    val posterUrl: String? = null,
    val fallbackUrl: String? = null,
    val cacheKey: String,
    val destinationUrl: String,
    val displayDomain: String,
    val ctaLabel: String,
    val restartAtBeginning: Boolean = false,
)

private fun AdLaunchContext.toRoute() = AdDetailRoute(
    adId = adId,
    creativeId = creativeId,
    mediaKind = kind.name,
    placeholderColor = placeholderColor,
    aspectRatio = aspectRatio,
    altText = altText,
    imageUrl = imageUrl,
    hlsUrl = hlsUrl,
    posterUrl = posterUrl,
    fallbackUrl = fallbackUrl,
    cacheKey = cacheKey,
    destinationUrl = destinationUrl,
    displayDomain = displayDomain,
    ctaLabel = ctaLabel,
    restartAtBeginning = restartAtBeginning,
)

private fun AdDetailRoute.toLaunchContext() = AdLaunchContext(
    adId = adId,
    creativeId = creativeId,
    kind = AdMediaKind.valueOf(mediaKind),
    placeholderColor = placeholderColor,
    aspectRatio = aspectRatio,
    altText = altText,
    imageUrl = imageUrl,
    hlsUrl = hlsUrl,
    posterUrl = posterUrl,
    fallbackUrl = fallbackUrl,
    cacheKey = cacheKey,
    destinationUrl = destinationUrl,
    displayDomain = displayDomain,
    ctaLabel = ctaLabel,
    restartAtBeginning = restartAtBeginning,
)

@Composable
fun ReadThatApp(
    appViewModel: AppViewModel = viewModel(),
    performanceSession: AndroidPerformanceSession? = null,
) {
    val state by appViewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(appViewModel) {
        appViewModel.messages.collectLatest { snackbar.showSnackbar(it) }
    }

    ReadThatTheme(state.settings.darkTheme) {
        Surface(Modifier.fillMaxSize()) {
            when (val session = state.session) {
                SessionState.Restoring -> StartupShell()
                SessionState.SignedOut -> SignedOutApp(appViewModel, state.auth, snackbar)
                is SessionState.SignedIn -> SignedInApp(
                    appViewModel,
                    session.user,
                    state.profileSaving,
                    snackbar,
                    performanceSession,
                )
            }
        }
    }
}

/** Static first-frame shell: never block launch pixels on disk or network. */
@Composable
private fun StartupShell() {
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
    auth: dev.readthat.shared.AuthForm,
    snackbar: SnackbarHostState,
) {
    var showForm by rememberSaveable { mutableStateOf(false) }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Box(Modifier.padding(padding)) {
            if (!showForm) {
                WelcomeScreen(
                    backendEnabled = viewModel.backend.enabled,
                    onCreateAccount = { viewModel.setAuthMode(AuthMode.Register); showForm = true },
                    onLogin = { viewModel.setAuthMode(AuthMode.Login); showForm = true },
                )
            } else {
                AuthScreen(
                    form = auth,
                    onBack = { showForm = false },
                    onMode = viewModel::setAuthMode,
                    onUsername = viewModel::updateUsername,
                    onDisplayName = viewModel::updateDisplayName,
                    onPassword = viewModel::updatePassword,
                    onTogglePassword = viewModel::togglePasswordVisibility,
                    onSubmit = viewModel::submitAuth,
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SignedInApp(
    appViewModel: AppViewModel,
    user: UserProfile,
    profileSaving: Boolean,
    snackbar: SnackbarHostState,
    performanceSession: AndroidPerformanceSession?,
) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val settings by appViewModel.settings.collectAsStateWithLifecycle()
    val communityDrawerViewModel = communityDrawerViewModel(user.id)
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    var transitionPreview by remember { mutableStateOf<PostTransitionPreview?>(null) }
    var mediaLaunchContext by remember { mutableStateOf<NormalFeedMediaContext?>(null) }
    var mediaFeedInteractionTimer by remember { mutableStateOf<PerformanceTimer?>(null) }
    var transitionFeedY by remember { mutableStateOf<Float?>(null) }
    var retainedBottomDestination by remember { mutableStateOf<androidx.navigation.NavDestination?>(null) }
    val visibleFeedPositions = remember { mutableMapOf<String, Float>() }

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
        val name = rawName.trim().removePrefix("r/").lowercase()
        if (name.isBlank()) return
        clearFeedTransition()
        nav.navigate(CommunityRoute(name)) { launchSingleTop = true }
    }

    val isDetailDestination = destination?.hasRoute<PostDetailRoute>() == true ||
        destination?.hasRoute<ThreadDetailRoute>() == true
    val isMediaDestination = destination?.hasRoute<MediaFeedRoute>() == true
    val useDetailStatusIcons = isMediaDestination || isDetailDestination
    val showBottom = destination?.hasRoute<HomeRoute>() == true ||
        destination?.hasRoute<ActivityRoute>() == true || destination?.hasRoute<ProfileRoute>() == true
    LaunchedEffect(destination, showBottom) {
        if (showBottom) retainedBottomDestination = destination
    }
    DetailSystemBarAppearance(
        useLightStatusIcons = useDetailStatusIcons,
        darkTheme = settings.darkTheme,
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = destination?.hasRoute<HomeRoute>() == true,
        drawerContent = {
            CommunityDrawerContent(
                viewModel = communityDrawerViewModel,
                onCreateCommunity = {
                    drawerScope.launch {
                        drawerState.close()
                        communityDrawerViewModel.showDrawer()
                        nav.navigate(CreateCommunityRoute)
                    }
                },
                onCommunity = { name ->
                    drawerScope.launch {
                        drawerState.close()
                        communityDrawerViewModel.showDrawer()
                        openCommunity(name)
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
                    AppBottomBar(nav, retainedBottomDestination ?: destination)
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
                            onProfileClick = { username -> nav.navigate(PublicProfileRoute(username)) },
                            onSearch = { nav.navigate(SearchRoute) },
                            onOpenNavigation = {
                                communityDrawerViewModel.onOpened()
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
                                nav.navigate(ProfileRoute) { launchSingleTop = true }
                            },
                            onPostClick = { postId, preview ->
                                freezeFeedTransition(postId, preview)
                                performanceSession?.beginComments()
                                nav.navigate(PostDetailRoute(postId))
                            },
                            onMediaClick = { context ->
                                mediaLaunchContext = context
                                mediaFeedInteractionTimer = performanceTimer()
                                nav.navigate(MediaFeedRoute(
                                    postId = context.anchorPostId,
                                    snapshotId = context.snapshotId,
                                ))
                            },
                            onAdClick = { ad -> nav.navigate(ad.toRoute()) },
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
            composable<SearchRoute> {
                TopSafeDrawingContent(includeBottom = true) {
                    SearchScreen(
                        viewModel = searchViewModel(user.id),
                        onBack = { nav.popBackStack() },
                        onPost = { postId ->
                            clearFeedTransition()
                            performanceSession?.beginComments()
                            nav.navigate(PostDetailRoute(postId))
                        },
                        onComment = { postId, commentId ->
                            clearFeedTransition()
                            performanceSession?.beginComments()
                            nav.navigate(PostDetailRoute(postId, commentId))
                        },
                        onCommunity = ::openCommunity,
                        onProfile = { nav.navigate(PublicProfileRoute(it)) },
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
                    CommunityDetailTelemetry(detailViewModel, route.name.lowercase())
                    FeedScreen(
                        viewModel = feedViewModel(
                            accountId = user.id,
                            feedId = "feed:subreddit:${route.name.lowercase()}",
                            subreddit = route.name,
                        ),
                        onCommunityClick = ::openCommunity,
                        onProfileClick = { username -> nav.navigate(PublicProfileRoute(username)) },
                        showHeader = false,
                        onPostClick = { postId, preview ->
                            freezeFeedTransition(postId, preview)
                            performanceSession?.beginComments()
                            nav.navigate(PostDetailRoute(postId))
                        },
                        onMediaClick = { context ->
                            mediaLaunchContext = context
                            mediaFeedInteractionTimer = performanceTimer()
                            nav.navigate(MediaFeedRoute(
                                postId = context.anchorPostId,
                                subreddit = route.name,
                                snapshotId = context.snapshotId,
                            ))
                        },
                        onAdClick = { ad -> nav.navigate(ad.toRoute()) },
                        settings = settings,
                        listHeader = {
                            CommunityDetailHeader(
                                viewModel = detailViewModel,
                                communityName = route.name.lowercase(),
                                onBack = { nav.popBackStack() },
                                onSearch = { nav.navigate(SearchRoute) },
                                onCreatePost = { nav.navigate(CreateRoute(route.name)) },
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
                val profileState by publicProfileViewModel(route.username).state.collectAsStateWithLifecycle()
                TopSafeDrawingContent(includeBottom = true) {
                    when (val profile = profileState.user) {
                        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (profileState.loading) CircularProgressIndicator()
                            else Text(profileState.error ?: "Profile not found")
                        }
                        else -> PublicProfileScreen(profile, onBack = { nav.popBackStack() })
                    }
                }
            }
            composable<CreateRoute> { backStack ->
                val route = backStack.toRoute<CreateRoute>()
                TopSafeDrawingContent(includeBottom = true) {
                    CreatePostScreen(
                        viewModel = createPostViewModel(route.subreddit),
                        onBack = { nav.popBackStack() },
                        onQueued = { mutationId ->
                            nav.navigate(PendingPostRoute(mutationId)) {
                                popUpTo<CreateRoute> { inclusive = true }
                            }
                        },
                    )
                }
            }
            composable<PendingPostRoute> { backStack ->
                val route = backStack.toRoute<PendingPostRoute>()
                TopSafeDrawingContent(includeBottom = true) {
                    PendingPostScreen(
                        viewModel = pendingPostViewModel(route.mutationId),
                        onBack = { nav.popBackStack() },
                        onCommitted = { postId ->
                            clearFeedTransition()
                            nav.navigate(PostDetailRoute(postId)) {
                                popUpTo<PendingPostRoute> { inclusive = true }
                            }
                        },
                    )
                }
            }
            composable<CreateCommunityRoute> {
                TopSafeDrawingContent(includeBottom = true) {
                    CreateCommunityScreen(
                        viewModel = createCommunityViewModel(user.id),
                        onBack = { nav.popBackStack() },
                        onQueued = { mutationId ->
                            nav.navigate(CommunityCreationStatusRoute(mutationId)) {
                                popUpTo<CreateCommunityRoute> { inclusive = true }
                            }
                        },
                    )
                }
            }
            composable<CommunityCreationStatusRoute> { backStack ->
                val route = backStack.toRoute<CommunityCreationStatusRoute>()
                TopSafeDrawingContent(includeBottom = true) {
                    CommunityCreationStatusScreen(
                        viewModel = communityCreationStatusViewModel(route.mutationId),
                        onBack = { nav.popBackStack() },
                        onCreatePost = { subreddit -> nav.navigate(CreateRoute(subreddit)) },
                    )
                }
            }
            composable<ActivityRoute> { TopSafeDrawingContent { EmptyActivityScreen() } }
            composable<ProfileRoute> {
                TopSafeDrawingContent {
                    ProfileScreen(user, { nav.navigate(EditProfileRoute) }, { nav.navigate(SettingsRoute) })
                }
            }
            composable<SettingsRoute> {
                TopSafeDrawingContent(includeBottom = true) {
                    SettingsScreen(
                        user = user,
                        settings = settings,
                        onBack = { nav.popBackStack() },
                        onEditProfile = { nav.navigate(EditProfileRoute) },
                        onCreateCommunity = { nav.navigate(CreateCommunityRoute) },
                        onSettings = appViewModel::updateSettings,
                        onLogout = appViewModel::logout,
                    )
                }
            }
            composable<EditProfileRoute> {
                TopSafeDrawingContent(includeBottom = true) {
                    EditProfileScreen(
                        user = user,
                        saving = profileSaving,
                        onBack = { nav.popBackStack() },
                        onSave = { displayName, bio, selectedAvatar, removeAvatar ->
                            appViewModel.saveProfile(displayName, bio, selectedAvatar, removeAvatar) {
                                nav.popBackStack()
                            }
                        },
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
                    TopSafeDrawingContent(includeBottom = true) {
                        AdDetailScreen(
                            ad = route.toLaunchContext(),
                            settings = settings,
                            onClose = { nav.popBackStack() },
                        )
                    }
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
                        onOpenUser = { username -> nav.navigate(PublicProfileRoute(username)) },
                        interactionTimer = mediaFeedInteractionTimer,
                    )
                    detailItem?.let { item ->
                        val communityHeader = postCommunityHeaderBinding(user.id, item.subreddit)
                        LaunchedEffect(item.postId) {
                            CommentsGraph.repository.persistHeader(item.toPostHeader())
                        }
                        ModalBottomSheet(
                            onDismissRequest = { detailItem = null },
                            containerColor = MaterialTheme.colorScheme.surface,
                        ) {
                            PostDetailScreen(
                                viewModel = mediaCommentsViewModel(item.postId),
                                onBack = { detailItem = null },
                                onCommunityClick = { communityName ->
                                    detailItem = null
                                    openCommunity(communityName)
                                },
                                communityHeader = communityHeader.state,
                                onJoinCommunity = communityHeader.onJoin,
                                onContinueThread = { commentId ->
                                    detailItem = null
                                    nav.navigate(ThreadDetailRoute(item.postId, commentId))
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
                    val postDetailViewModel = commentsViewModel()
                    val postDetailState by postDetailViewModel.uiState.collectAsStateWithLifecycle()
                    val communityHeader = postCommunityHeaderBinding(
                        accountId = user.id,
                        name = postDetailState.header?.subreddit ?: preview?.subreddit.orEmpty(),
                    )
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
                            PostDetailScreen(
                                viewModel = postDetailViewModel,
                                onBack = { nav.popBackStack() },
                                onCommunityClick = ::openCommunity,
                                communityHeader = communityHeader.state,
                                onJoinCommunity = communityHeader.onJoin,
                                onContinueThread = { nav.navigate(ThreadDetailRoute(route.postId, it)) },
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
            composable<ThreadDetailRoute> { backStack ->
                val route = backStack.toRoute<ThreadDetailRoute>()
                val threadDetailViewModel = commentsViewModel()
                val threadDetailState by threadDetailViewModel.uiState.collectAsStateWithLifecycle()
                val communityHeader = postCommunityHeaderBinding(
                    accountId = user.id,
                    name = threadDetailState.header?.subreddit.orEmpty(),
                )
                DetailEdgeToEdgeContent {
                    PostDetailScreen(
                        viewModel = threadDetailViewModel,
                        onBack = { nav.popBackStack() },
                        onCommunityClick = ::openCommunity,
                        communityHeader = communityHeader.state,
                        onJoinCommunity = communityHeader.onJoin,
                        onContinueThread = { nav.navigate(ThreadDetailRoute(route.postId, it)) },
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
private fun AppBottomBar(nav: NavHostController, destination: androidx.navigation.NavDestination?) {
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

@Composable
private fun EmptyActivityScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nothing new yet") }
}

@Composable
private fun commentsViewModel(): CommentsViewModel = viewModel(
    factory = viewModelFactory {
        initializer { CommentsViewModel(CommentsGraph.repository, createSavedStateHandle()) }
    },
)

@Composable
private fun mediaCommentsViewModel(postId: String): CommentsViewModel = viewModel(
    key = "media-comments:$postId",
    factory = remember(postId) {
        viewModelFactory {
            initializer {
                CommentsViewModel(
                    CommentsGraph.repository,
                    SavedStateHandle(mapOf(CommentsViewModel.KEY_POST_ID to postId)),
                )
            }
        }
    },
)

@Composable
private fun mediaFeedViewModel(
    accountId: String,
    route: MediaFeedRoute,
    sourceContext: NormalFeedMediaContext?,
): MediaFeedViewModel {
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
                val remote = BackendGraph.mediaFeed(context)
                val launchContext = sourceContext?.let { source ->
                    val mediaItems = source.items.mapNotNull(MediaFeedItem::fromPreview)
                    val anchorIndex = mediaItems.indexOfFirst { it.postId == source.anchorPostId }
                    check(anchorIndex >= 0) { "Normal-feed media snapshot lost its anchor" }
                    MediaFeedLaunchContext(
                        snapshotId = source.snapshotId,
                        sourceFeedId = source.sourceFeedId,
                        anchorPostId = source.anchorPostId,
                        items = mediaItems,
                        anchorIndex = anchorIndex,
                        continuationCursor = source.nextFeedCursor?.let(remote::continuationCursorFromFeed),
                    )
                }
                MediaFeedViewModel(
                    repository = MediaFeedRepository(
                        db = AppDatabase.get(context),
                        remote = remote,
                        scope = MediaFeedScope(route.postId, route.subreddit, snapshotId),
                        launchContext = launchContext,
                        accountId = accountId,
                    ),
                    savedStateHandle = createSavedStateHandle(),
                )
            }
        }
    }
    return viewModel(
        key = "media:$accountId:${route.postId}:${route.subreddit}:${snapshotId ?: "remote"}",
        factory = factory,
    )
}

@Composable
private fun feedViewModel(
    accountId: String,
    feedId: String = dev.readthat.data.db.CacheScope.HOME_FEED_ID,
    subreddit: String? = null,
): FeedViewModel {
    val context = LocalContext.current.applicationContext
    val factory = remember(context, accountId, feedId, subreddit) {
        viewModelFactory {
            initializer {
                val backend = BackendGraph.repository(context)
                FeedViewModel(
                    repository = FeedRepository(
                        db = AppDatabase.get(context),
                        remote = BackendGraph.feed(context, subreddit),
                        accountId = accountId,
                        feedId = feedId,
                        onVoteQueued = { FeedSyncScheduler.enqueueVoteOutbox(context) },
                        postInteractions = PostInteractionGraph.repository(context),
                    ),
                    prefetchCommentTree = CommentsGraph::prefetch,
                    resharePost = { postId, subreddit ->
                        backend.reshare(postId, subreddit)
                        Unit
                    },
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
                SearchViewModel(SearchRepository(
                    db = AppDatabase.get(context),
                    remote = BackendGraph.search(context),
                    accountId = accountId,
                ))
            }
        }
    }
    return viewModel(key = "search:$accountId", factory = factory)
}

@Composable
private fun publicProfileViewModel(username: String): PublicProfileViewModel {
    val context = LocalContext.current.applicationContext
    val factory = remember(context, username) {
        viewModelFactory {
            initializer { PublicProfileViewModel(BackendGraph.repository(context), username) }
        }
    }
    return viewModel(key = "public-profile:${username.lowercase()}", factory = factory)
}

@Composable
private fun createPostViewModel(initialSubreddit: String): CreatePostViewModel {
    val app = LocalContext.current.applicationContext as Application
    val factory = remember(app, initialSubreddit) {
        viewModelFactory { initializer { CreatePostViewModel(app, initialSubreddit) } }
    }
    return viewModel(key = "create-post:$initialSubreddit", factory = factory)
}

@Composable
private fun pendingPostViewModel(mutationId: String): PendingPostViewModel {
    val app = LocalContext.current.applicationContext as Application
    val factory = remember(app, mutationId) {
        viewModelFactory { initializer { PendingPostViewModel(app, mutationId) } }
    }
    return viewModel(key = "pending-post:$mutationId", factory = factory)
}

@Composable
private fun communityCreationStatusViewModel(mutationId: String): CommunityCreationStatusViewModel {
    val app = LocalContext.current.applicationContext as Application
    val factory = remember(app, mutationId) {
        viewModelFactory { initializer { CommunityCreationStatusViewModel(app, mutationId) } }
    }
    return viewModel(key = "community-create:$mutationId", factory = factory)
}

@Composable
private fun communityDrawerViewModel(accountId: String): CommunityDrawerViewModel {
    val context = LocalContext.current.applicationContext
    val factory = remember(context, accountId) {
        viewModelFactory {
            initializer {
                val repository = CommunityGraph.repository(context, accountId)
                CommunityDrawerViewModel(
                    observe = ObserveCommunityDrawerUseCase(repository),
                    refresh = RefreshCommunityDrawerUseCase(repository),
                    recordVisit = RecordCommunityVisitUseCase(repository),
                    removeVisit = RemoveCommunityVisitUseCase(repository),
                    clearVisits = ClearCommunityVisitsUseCase(repository),
                )
            }
        }
    }
    return viewModel(key = "community-drawer:$accountId", factory = factory)
}

@Composable
private fun communityDetailViewModel(accountId: String, name: String): CommunityDetailViewModel {
    val context = LocalContext.current.applicationContext
    val normalized = name.trim().removePrefix("r/").lowercase()
    val factory = remember(context, accountId, normalized) {
        viewModelFactory {
            initializer {
                CommunityDetailViewModel(
                    CommunityDetailGraph.repository(context, accountId, normalized),
                )
            }
        }
    }
    return viewModel(key = "community-detail:$accountId:$normalized", factory = factory)
}

private data class PostCommunityHeaderBinding(
    val state: PostCommunityHeaderState?,
    val onJoin: () -> Unit,
)

/**
 * Projects the shared community aggregate into post-detail chrome. The community ViewModel keeps
 * Room as the source of truth and owns optimistic/outbox membership changes; comments remains a
 * navigation- and data-agnostic renderer.
 */
@Composable
private fun postCommunityHeaderBinding(
    accountId: String,
    name: String,
): PostCommunityHeaderBinding {
    val normalized = name.trim().removePrefix("r/").lowercase()
    if (normalized.isBlank()) return PostCommunityHeaderBinding(state = null, onJoin = {})

    val viewModel = communityDetailViewModel(accountId, normalized)
    val state by viewModel.state.collectAsStateWithLifecycle()
    return PostCommunityHeaderBinding(
        state = state.detail?.let { detail ->
            PostCommunityHeaderState(
                avatarUrl = detail.avatarUrl,
                isMember = detail.isJoined,
                canJoin = detail.canChangeMembership,
                membershipChanging = state.membershipChanging,
            )
        },
        onJoin = viewModel::toggleMembership,
    )
}

@Composable
private fun createCommunityViewModel(accountId: String): CreateCommunityViewModel {
    val context = LocalContext.current.applicationContext
    val factory = remember(context, accountId) {
        viewModelFactory {
            initializer {
                CreateCommunityViewModel(
                    QueueCommunityCreationUseCase(CommunityGraph.repository(context, accountId)),
                )
            }
        }
    }
    return viewModel(key = "create-community:$accountId", factory = factory)
}
