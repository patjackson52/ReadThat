package dev.readthat.client

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.flatMap
import dev.readthat.comments.domain.CommentFlattener
import dev.readthat.comments.domain.CommentNode
import dev.readthat.comments.domain.CommentSort
import dev.readthat.communities.domain.CommunityDrawerSnapshot
import dev.readthat.communitydetail.domain.CommunityDetail
import dev.readthat.data.db.AppDatabase
import dev.readthat.data.db.CacheScope
import dev.readthat.domain.CellUi
import dev.readthat.mediafeed.domain.MediaFeedItem
import dev.readthat.mediafeed.domain.MediaFeedLaunchContext
import dev.readthat.navigation.AppDestination
import dev.readthat.navigation.AppNavigationPolicy
import dev.readthat.navigation.DestinationHistory
import dev.readthat.navigation.NavigationSnapshot
import dev.readthat.navigation.NavigationSnapshotCodec
import dev.readthat.observability.ProductAnalytics
import dev.readthat.observability.PerformanceSurface
import dev.readthat.observability.ProductContentType
import dev.readthat.observability.ProductEvent
import dev.readthat.observability.ProductEventName
import dev.readthat.observability.ProductSurface
import dev.readthat.search.domain.SearchCommunity
import dev.readthat.search.domain.SearchComment
import dev.readthat.search.domain.SearchDiscover
import dev.readthat.search.domain.SearchItem
import dev.readthat.search.domain.SearchPost
import dev.readthat.search.domain.SearchRequest
import dev.readthat.search.domain.SearchSections
import dev.readthat.search.domain.SearchSort
import dev.readthat.search.domain.SearchTime
import dev.readthat.search.domain.SearchType
import dev.readthat.search.domain.SearchTypeahead
import dev.readthat.shared.AppSettings
import dev.readthat.shared.AuthForm
import dev.readthat.shared.AuthMode
import dev.readthat.shared.LocalPostMedia
import dev.readthat.shared.PostKind
import dev.readthat.shared.PostFlair
import dev.readthat.shared.PostHeader
import dev.readthat.shared.PostTransitionPreview
import dev.readthat.shared.SessionState
import dev.readthat.shared.VoteSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

data class SearchState(
    val query: String = "",
    val submittedQuery: String = "",
    val type: SearchType = SearchType.All,
    val sort: SearchSort = SearchSort.Relevance,
    val time: SearchTime = SearchTime.All,
    val safe: Boolean = true,
    val results: List<SearchItem> = emptyList(),
    val sections: SearchSections? = null,
    val typeahead: SearchTypeahead? = null,
    val recentQueries: List<String> = emptyList(),
    val nextCursor: String? = null,
    val searching: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
)

data class CommunityState(
    val discover: SearchDiscover = SearchDiscover(),
    val drawer: CommunityDrawerSnapshot = CommunityDrawerSnapshot(),
    val detail: CommunityDetail? = null,
    val loading: Boolean = false,
    val membershipChanging: Boolean = false,
    val initialCacheTier: String? = null,
    val isOffline: Boolean = false,
    val error: String? = null,
)

data class MediaFeedState(
    /** Detail/comments rendered as a sheet without replacing the media-feed destination. */
    val commentsPostId: String? = null,
)

data class ReadThatUiState(
    val session: SessionState = SessionState.Restoring,
    val auth: AuthForm = AuthForm(),
    val backendEnabled: Boolean = false,
    val feed: FeedState = FeedState(),
    val settings: AppSettings = AppSettings(),
    val destination: AppDestination = AppDestination.Feed,
    val detail: DetailState = DetailState(),
    val search: SearchState = SearchState(),
    val communities: CommunityState = CommunityState(),
    val create: CreateState = CreateState(),
    val creationStatus: CreationStatusState = CreationStatusState(),
    val mediaFeed: MediaFeedState = MediaFeedState(),
    val profile: ProfileState = ProfileState(),
    val message: String? = null,
)

/** Lifecycle ViewModel compiled once for Android and iOS. */
class ReadThatViewModel(
    private val client: ReadThatClient,
    private val database: AppDatabase,
    private val productAnalytics: ProductAnalyticsLifecycle = NoOpProductAnalyticsLifecycle,
    private val onCreationQueued: (SharedCreationOutcome) -> Unit = {},
    onCommunityVisitQueued: (String) -> Unit = {},
    onCommunityMembershipQueued: (String) -> Unit = {},
    initialNavigationState: String? = null,
    private val onNavigationStateChanged: (String) -> Unit = {},
) : ViewModel() {
    private val restoredNavigation = NavigationSnapshotCodec.decode(initialNavigationState)
    private val repository = OfflineFirstRepository(client, database, viewModelScope)
    private val feedController = SharedFeedController(
        repository = repository,
        scope = viewModelScope,
        feedId = CacheScope.HOME_FEED_ID,
        status = repository.homeFeedStatus,
    )
    private val settingsController = SharedSettingsController(repository, viewModelScope)
    private val communityDrawerController = SharedCommunityDrawerController(
        repository,
        viewModelScope,
        accountId = { (client.session.value as? SessionState.SignedIn)?.user?.id },
        onVisitMutationQueued = onCommunityVisitQueued,
    )
    private val communityDetailController = SharedCommunityDetailController(
        repository,
        viewModelScope,
        accountId = { (client.session.value as? SessionState.SignedIn)?.user?.id },
        onMembershipMutationQueued = onCommunityMembershipQueued,
        onCommunityLoaded = { name, displayName ->
            communityDrawerController.record(name, displayName)
        },
    )
    private val communityDiscoveryController = SharedCommunityDiscoveryController(
        repository,
        viewModelScope,
    )
    private val destination = MutableStateFlow(restoredNavigation?.current ?: AppDestination.Feed)
    /**
     * Only the cold-start leaf is eligible for automatic recovery. A user/deep-link navigation to
     * the same post clears this marker and keeps the explicit not-found state visible.
     */
    private var restoredPostPendingValidation =
        restoredNavigation?.current as? AppDestination.PostDetail
    private val detailController = DetailController(repository, viewModelScope)
    private val detail = detailController.state
    private val search = MutableStateFlow(SearchState())
    private val communities = MutableStateFlow(CommunityState())
    private val creationController = SharedCreationController(client, database, viewModelScope)
    private val create = creationController.create
    private val creationStatus = creationController.status
    private val mediaFeed = MutableStateFlow(MediaFeedState())
    private val activeMediaFeedController = MutableStateFlow<SharedMediaFeedController?>(null)
    private val activeSearchController = MutableStateFlow<SharedSearchController?>(null)
    private val profileController = SharedProfileController(client, database, viewModelScope)
    private val profile = profileController.state
    private val message = MutableStateFlow<String?>(null)
    private val authController = SharedAuthController(
        client = client,
        coroutineScope = viewModelScope,
        onAuthenticated = {
            viewModelScope.launch { bestEffort { repository.refreshFeed(force = true) } }
        },
        onSignedOut = { warning ->
            releaseSharedSearchController()
            resetNavigationForSignedOut()
            warning?.let { message.value = it }
        },
    )
    private val auth = authController.form
    private val activeCommunityFeedController = MutableStateFlow<SharedFeedController?>(null)
    private var mediaFeedControllerJob: Job? = null
    private var searchControllerJob: Job? = null
    private var searchControllerAccountId: String? = null
    private var mediaNavigationJob: Job? = null
    private var pendingMediaLaunchContext: MediaFeedLaunchContext? = null
    private var searchJob: Job? = null
    private var navigationJob: Job? = null
    private val commentPrefetchJobs = mutableMapOf<String, Job>()
    private val prefetchedCommentPostIds = LinkedHashSet<String>()
    private var feedPresentationWindow: Map<String, FeedCard> = emptyMap()
    private var communityPresentationWindow: Map<String, FeedCard> = emptyMap()
    /** Real bounded navigation history; nested details/threads no longer collapse back to Home. */
    private val destinationHistory = DestinationHistory(MAX_NAVIGATION_DEPTH).apply {
        restore(restoredNavigation?.history.orEmpty())
    }

    val sharedSettingsState: StateFlow<SharedSettingsState> = settingsController.state
    val sharedCommunityDrawerState: StateFlow<SharedCommunityDrawerState> = communityDrawerController.state
    val sharedCommunityDetailState: StateFlow<SharedCommunityDetailState> = communityDetailController.state
    val sharedCommunityDiscoveryState: StateFlow<SharedCommunityDiscoveryState> =
        communityDiscoveryController.state
    val sharedAuthState: StateFlow<SharedAuthState> = authController.state

    private val cachedPagedFeed = feedController.cards.cachedIn(viewModelScope)
    val pagedFeed: Flow<PagingData<FeedCard>> = cachedPagedFeed
    /**
     * Canonical mature feed presentation: each SDUI cell owns an independent lazy-list key and
     * content type. Keeping the Room/Paging load unit grouped while flattening only presentation
     * preserves transactional pages without forcing a whole post card to recompose as one item.
     */
    val pagedFeedCells: Flow<PagingData<CellUi>> = cachedPagedFeed.map { paging ->
        paging.flatMap { card -> card.cells }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val pagedMediaFeed: Flow<PagingData<MediaFeedItem>> = activeMediaFeedController
        .flatMapLatest { controller -> controller?.feed ?: flowOf(PagingData.empty()) }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val mediaFeedNavigationItems: StateFlow<List<MediaFeedItem>> = activeMediaFeedController
        .flatMapLatest { controller -> controller?.navigationItems ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val mediaFeedInitialCacheTier: StateFlow<String?> = activeMediaFeedController
        .flatMapLatest { controller -> controller?.initialCacheTier ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val mediaFeedRestoredPage: Int get() = activeMediaFeedController.value?.restoredPage ?: 0

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val sharedSearchState: StateFlow<SharedSearchUiState> = activeSearchController
        .flatMapLatest { controller -> controller?.state ?: flowOf(SharedSearchUiState()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SharedSearchUiState())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val pagedSearchResults: Flow<PagingData<SearchItem>> = activeSearchController
        .flatMapLatest { controller -> controller?.pagedResults ?: flowOf(PagingData.empty()) }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val cachedPagedCommunityFeed = activeCommunityFeedController
        .flatMapLatest { controller -> controller?.cards ?: flowOf(PagingData.empty()) }
        .cachedIn(viewModelScope)

    /** Active community feed, flattened through the same SDUI presentation contract as Home. */
    val pagedCommunityFeedCells: Flow<PagingData<CellUi>> = cachedPagedCommunityFeed.map { paging ->
        paging.flatMap { card -> card.cells }
    }

    /** Comment rows are derived off-main only when tree/collapse structure changes. */
    val detailRender = detail.commentRenderLists().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000L),
        dev.readthat.comments.domain.CommentRenderList(emptyList(), 0, 0),
    )

    fun markUserFeedRefresh() = feedController.markUserRefresh()

    fun markFeedErrorRetry() = feedController.markErrorRetry()

    fun markUserCommunityFeedRefresh(name: String) {
        activeCommunityFeedController.value
            ?.takeIf { it.matchesCommunity(name) }
            ?.markUserRefresh()
            ?: repository.markUserCommunityFeedRefresh(name)
    }

    fun markCommunityFeedErrorRetry(name: String) {
        activeCommunityFeedController.value
            ?.takeIf { it.matchesCommunity(name) }
            ?.markErrorRetry()
            ?: repository.markCommunityFeedErrorRetry(name)
    }

    val state: StateFlow<ReadThatUiState> = combine(
        client.session,
        feedController.state,
        sharedSettingsState,
        destination,
        detail,
        search,
        communities,
        create,
        creationStatus,
        mediaFeed,
        profile,
        auth,
        message,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        ReadThatUiState(
            session = values[0] as SessionState,
            feed = values[1] as FeedState,
            settings = (values[2] as SharedSettingsState).settings,
            destination = values[3] as AppDestination,
            detail = values[4] as DetailState,
            search = values[5] as SearchState,
            communities = values[6] as CommunityState,
            create = values[7] as CreateState,
            creationStatus = values[8] as CreationStatusState,
            mediaFeed = values[9] as MediaFeedState,
            profile = values[10] as ProfileState,
            auth = values[11] as AuthForm,
            backendEnabled = client.enabled,
            message = values[12] as String?,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ReadThatUiState())

    init {
        restoredNavigation?.let { activateDestination(it.current) } ?: persistNavigation()
        viewModelScope.launch {
            detail.collect { detailState ->
                val restored = restoredPostPendingValidation ?: return@collect
                if (detailState.post != null) {
                    // A Room/feed projection is valid offline content; never discard it because a
                    // later refresh failed, even when the server no longer exposes the post.
                    restoredPostPendingValidation = null
                } else if (shouldRecoverRestoredPostDetail(
                        restored = restored,
                        current = destination.value,
                        detail = detailState,
                    )
                ) {
                    restoredPostPendingValidation = null
                    backNow()
                }
            }
        }
        viewModelScope.launch {
            sharedCommunityDrawerState.collect { drawerState ->
                val drawer = drawerState.snapshot
                communities.value = communities.value.copy(drawer = drawer)
            }
        }
        viewModelScope.launch {
            sharedCommunityDetailState.collect { detailState ->
                communities.value = communities.value.copy(
                    detail = detailState.detail,
                    loading = detailState.refreshing,
                    membershipChanging = detailState.membershipChanging,
                    initialCacheTier = detailState.initialCacheTier,
                    isOffline = detailState.offline,
                    error = detailState.error,
                )
            }
        }
        viewModelScope.launch {
            sharedCommunityDiscoveryState.collect { discoveryState ->
                communities.value = communities.value.copy(discover = discoveryState.discover)
            }
        }
        viewModelScope.launch {
            repository.recentSearches.collect { recent ->
                search.value = search.value.copy(recentQueries = recent)
            }
        }
        viewModelScope.launch {
            authController.restoreNow()
            repository.refreshFeed()
            bestEffort { repository.syncPendingMutations() }
            bestEffort { repository.refreshCommunityDrawer() }
            communityDiscoveryController.load()
        }
    }

    fun navigate(next: AppDestination) {
        restoredPostPendingValidation = null
        scheduleNavigation { navigateNow(next) }
    }

    fun openCommunity(rawName: String) {
        AppNavigationPolicy.communityDestination(rawName)?.let(::navigate)
    }

    private fun navigateNow(next: AppDestination) {
        val current = destination.value
        if (next == current) return
        destinationHistory.record(current, next)
        activateDestination(next)
    }

    private fun activateDestination(next: AppDestination) {
        // Resolve the transition header while the source destination and its bounded
        // presentation window are still alive. Media controllers are released below.
        val projectedHeader = (next as? AppDestination.PostDetail)
            ?.let { destination -> projectedPostHeader(destination.postId) }
        if (destination.value == AppDestination.EditProfile && next != AppDestination.EditProfile) {
            profileController.discardEditor()
        }
        if (next !is AppDestination.Media && mediaFeed.value.commentsPostId != null) {
            mediaFeed.value = mediaFeed.value.copy(commentsPostId = null)
        }
        if (next !is AppDestination.PostDetail) {
            detailController.close()
        }
        if (next !is AppDestination.PendingPost && next !is AppDestination.PendingCommunity) {
            creationController.closeStatus()
        }
        if (next !is AppDestination.Community) communityDetailController.close()
        if (next !is AppDestination.Media) {
            mediaFeedControllerJob?.cancel()
            mediaFeedControllerJob = null
            activeMediaFeedController.value = null
            pendingMediaLaunchContext = null
        }
        val normalizedCommunity = (next as? AppDestination.Community)
            ?.name
            ?.trim()
            ?.removePrefix("r/")
            ?.lowercase()
        activeCommunityFeedController.value = normalizedCommunity?.let { normalized ->
            activeCommunityFeedController.value
                ?.takeIf { it.matchesCommunity(normalized) }
                ?: SharedFeedController(
                    repository = repository,
                    scope = viewModelScope,
                    feedId = CacheScope.communityFeedId(normalized),
                    subreddit = normalized,
                )
        }
        destination.value = next
        when (next) {
            is AppDestination.PostDetail -> detailController.open(
                postId = next.postId,
                rootCommentId = next.rootCommentId,
                focusedCommentId = next.focusCommentId,
                projectedHeader = projectedHeader,
            )
            is AppDestination.Community -> communityDetailController.open(next.name)
            AppDestination.Communities -> communityDiscoveryController.onOpened()
            is AppDestination.Media -> activateSharedMediaFeed(next.postId)
            AppDestination.Search -> ensureSharedSearchController()
            is AppDestination.PublicProfile -> loadPublicProfile(next.username)
            is AppDestination.PendingPost -> creationController.observePendingPost(next.mutationId)
            is AppDestination.PendingCommunity -> creationController.observePendingCommunity(next.mutationId)
            is AppDestination.CreatePost -> creationController.beginPost(next.subreddit)
            AppDestination.CreateCommunity -> creationController.beginCommunity()
            AppDestination.EditProfile -> seedProfileEditor()
            else -> Unit
        }
        persistNavigation()
    }

    private fun persistNavigation() {
        val encoded = NavigationSnapshotCodec.encode(NavigationSnapshot(
            current = destination.value,
            history = destinationHistory.snapshot(),
        ))
        // A host state-registry failure cannot make navigation itself fail.
        runCatching { onNavigationStateChanged(encoded) }
    }

    private fun resetNavigationForSignedOut() {
        destinationHistory.clear()
        activateDestination(AppDestination.Feed)
    }

    fun back() {
        restoredPostPendingValidation = null
        scheduleNavigation(::backNow)
    }

    private fun backNow() {
        activateDestination(destinationHistory.popOrFeed())
    }

    /**
     * UIKit may synchronously re-query the activated accessibility element before returning from
     * its action callback. Committing a destination on the next main dispatch turn keeps that
     * element alive until the callback completes and avoids stale native accessibility handles.
     * The same ordering is deterministic and harmless for Android pointer/key input.
     */
    private fun scheduleNavigation(block: () -> Unit) {
        navigationJob?.cancel()
        navigationJob = viewModelScope.launch {
            yield()
            block()
        }
    }

    fun refresh() = viewModelScope.launch { repository.refreshFeed(force = true) }

    fun onForeground() {
        productAnalytics.onForeground()
        viewModelScope.launch {
            if (client.session.value is SessionState.SignedIn) {
                bestEffort { repository.refreshFeed() }
                bestEffort { repository.syncPendingMutations() }
                bestEffort { repository.refreshCommunityDrawer() }
            }
        }
    }

    fun onBackground() {
        productAnalytics.onBackground()
    }

    fun onCommunityDrawerOpened() = communityDrawerController.onOpened()
    fun showAllRecentCommunities() = communityDrawerController.showAllRecents()
    fun showCommunityDrawerHome() = communityDrawerController.showDrawer()
    fun toggleDrawerCommunities() = communityDrawerController.toggleCommunities()
    fun retryCommunityDrawer() = communityDrawerController.retry()
    fun removeRecentCommunity(name: String) = communityDrawerController.removeRecent(name)
    fun clearRecentCommunities() = communityDrawerController.clearRecent()

    fun updateFeedPresentationWindow(cards: List<FeedCard>) {
        feedPresentationWindow = cards.associateBy(FeedCard::id)
    }

    fun updateCommunityPresentationWindow(cards: List<FeedCard>) {
        communityPresentationWindow = cards.associateBy(FeedCard::id)
    }

    /**
     * Opens immersive media from an exact Room-ranked feed snapshot. The visible card is only a
     * first-frame fallback; ordering and continuation cursor are captured transactionally.
     */
    fun openMedia(postId: String) {
        if (postId.isBlank()) return
        mediaNavigationJob?.cancel()
        val sourceDestination = destination.value
        val source = when (sourceDestination) {
            is AppDestination.Community -> Pair(
                CacheScope.communityFeedId(sourceDestination.name),
                communityPresentationWindow[postId],
            )
            AppDestination.Feed -> CacheScope.HOME_FEED_ID to feedPresentationWindow[postId]
            else -> null
        }
        val visibleFallback = source?.second?.preview?.takeIf { it.media != null }
        if (source == null || visibleFallback == null) {
            navigate(AppDestination.Media(postId))
            return
        }
        mediaNavigationJob = viewModelScope.launch {
            val launchContext = try {
                repository.mediaLaunchContext(source.first, postId, visibleFallback)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            if (destination.value != sourceDestination) return@launch
            pendingMediaLaunchContext = launchContext?.toSharedMediaFeedLaunchContext()
            navigateNow(AppDestination.Media(postId))
        }
    }

    fun setMediaFeedCurrentPage(page: Int) {
        activeMediaFeedController.value?.setCurrentPage(page)
    }

    fun releaseMediaFeedNavigationFallback() {
        activeMediaFeedController.value?.releaseNavigationFallback()
    }

    fun voteMediaFeed(postId: String, value: Int) {
        activeMediaFeedController.value?.vote(postId, value)
    }

    fun vote(postId: String, value: Int) = viewModelScope.launch {
        currentPostVote(postId)?.optimistic(value)?.let { applyPostVote(postId, it) }
        repository.votePost(postId, value)?.let { applyPostVote(postId, it) }
    }

    private fun currentPostVote(postId: String): VoteSnapshot? {
        detail.value.post?.takeIf { it.postId == postId }?.let { return VoteSnapshot(it.score, it.viewerVote) }
        communityPresentationWindow[postId]
            ?.cells?.filterIsInstance<dev.readthat.domain.CellUi.ActionBar>()?.firstOrNull()
            ?.let { return VoteSnapshot(it.score, it.viewerVote) }
        (search.value.results.firstOrNull { it is SearchPost && it.id == postId } as? SearchPost)
            ?.let { return VoteSnapshot(it.score, it.viewerVote) }
        feedPresentationWindow[postId]
            ?.cells?.filterIsInstance<dev.readthat.domain.CellUi.ActionBar>()?.firstOrNull()
            ?.let { return VoteSnapshot(it.score, it.viewerVote) }
        return null
    }

    private fun applyPostVote(postId: String, vote: VoteSnapshot) {
        if (detail.value.post?.postId == postId) {
            detail.value = detail.value.copy(
                post = detail.value.post?.copy(score = vote.score, viewerVote = vote.viewerVote),
            )
        }
        communityPresentationWindow[postId]?.let { card ->
            communityPresentationWindow = communityPresentationWindow + (postId to card.copy(
                cells = card.cells.map { cell ->
                    if (cell !is dev.readthat.domain.CellUi.ActionBar) cell else cell.copy(
                        score = vote.score,
                        scoreLabel = CommentFlattener.compactScore(vote.score),
                        liked = vote.viewerVote == 1,
                        viewerVote = vote.viewerVote,
                    )
                },
            ))
        }
        search.value = search.value.copy(
            results = search.value.results.map { item ->
                if (item is SearchPost && item.id == postId) {
                    item.copy(score = vote.score, viewerVote = vote.viewerVote)
                } else item
            },
        )
    }

    /** Dwell-gated by Compose; successful phase-1 trees are durable in Room and bounded here. */
    fun prefetchComments(postIds: Set<String>) {
        postIds.asSequence().filter(String::isNotBlank).take(3).forEach { postId ->
            if (postId in prefetchedCommentPostIds || postId in commentPrefetchJobs) return@forEach
            commentPrefetchJobs[postId] = viewModelScope.launch {
                try {
                    repository.prefetchComments(postId)
                    prefetchedCommentPostIds += postId
                    while (prefetchedCommentPostIds.size > MAX_PREFETCHED_COMMENT_POSTS) {
                        prefetchedCommentPostIds.remove(prefetchedCommentPostIds.first())
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // Speculative work never replaces cached UI with an error.
                } finally {
                    commentPrefetchJobs.remove(postId)
                }
            }
        }
    }

    fun setAuthMode(mode: AuthMode) = authController.setMode(mode)
    fun setAuthUsername(value: String) = authController.setUsername(value)
    fun setAuthDisplayName(value: String) = authController.setDisplayName(value)
    fun setAuthPassword(value: String) = authController.setPassword(value)
    fun toggleAuthPasswordVisibility() = authController.togglePasswordVisibility()
    fun submitAuth() = authController.submit()
    fun clearAuthMessage() = authController.clearMessage()
    fun logout() = authController.logout()

    fun search(query: String) {
        val cappedQuery = query.take(100)
        val normalized = cappedQuery.trim()
        searchJob?.cancel()
        search.value = search.value.copy(
            query = cappedQuery,
            typeahead = null,
            error = null,
            searching = false,
            loadingMore = false,
        )
        if (normalized.length < 2) {
            search.value = search.value.copy(
                submittedQuery = "",
                results = emptyList(),
                sections = null,
                nextCursor = null,
            )
            return
        }
        searchJob = viewModelScope.launch {
            delay(180)
            try {
                val suggestions = repository.typeahead(normalized)
                if (search.value.query.trim() == normalized && search.value.submittedQuery != normalized) {
                    search.value = search.value.copy(typeahead = suggestions)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Suggestions are speculative; discover/recent content remains useful offline.
            }
        }
    }

    fun submitSearch(query: String = search.value.query) {
        val normalized = query.trim().take(100)
        if (normalized.length < 2) return
        searchJob?.cancel()
        search.value = search.value.copy(
            query = normalized,
            submittedQuery = normalized,
            typeahead = null,
            searching = true,
            loadingMore = false,
            error = null,
        )
        searchJob = viewModelScope.launch {
            launch { repository.recordRecentSearch(normalized) }
            executeSearch(search.value.toRequest(), append = false)
        }
    }

    fun deleteRecentSearch(query: String) = viewModelScope.launch { repository.deleteRecentSearch(query) }
    fun clearRecentSearches() = viewModelScope.launch { repository.clearRecentSearches() }

    fun setSearchType(type: SearchType) {
        val compatibleSort = when (type) {
            SearchType.Communities, SearchType.Profiles -> SearchSort.Relevance
            SearchType.Comments -> search.value.sort.takeIf {
                it in setOf(SearchSort.Relevance, SearchSort.Top, SearchSort.New)
            } ?: SearchSort.Relevance
            else -> search.value.sort
        }
        search.value = search.value.copy(type = type, sort = compatibleSort)
        restartSearch()
    }

    fun cycleSearchSort() {
        val allowed = when (search.value.type) {
            SearchType.Communities, SearchType.Profiles -> listOf(SearchSort.Relevance)
            SearchType.Comments -> listOf(SearchSort.Relevance, SearchSort.Top, SearchSort.New)
            else -> SearchSort.entries
        }
        val index = allowed.indexOf(search.value.sort).coerceAtLeast(0)
        search.value = search.value.copy(sort = allowed[(index + 1) % allowed.size])
        restartSearch()
    }

    fun cycleSearchTime() {
        val allowed = SearchTime.entries
        val index = allowed.indexOf(search.value.time).coerceAtLeast(0)
        search.value = search.value.copy(time = allowed[(index + 1) % allowed.size])
        restartSearch()
    }

    fun toggleSafeSearch() {
        search.value = search.value.copy(safe = !search.value.safe)
        restartSearch()
    }

    fun loadMoreSearch() {
        val cursor = search.value.nextCursor ?: return
        if (search.value.searching || search.value.loadingMore) return
        val request = search.value.toRequest()
        searchJob = viewModelScope.launch { executeSearch(request, append = true, cursor = cursor) }
    }

    private fun restartSearch() {
        searchJob?.cancel()
        if (search.value.submittedQuery.length < 2) return
        search.value = search.value.copy(searching = true, loadingMore = false, error = null)
        searchJob = viewModelScope.launch { executeSearch(search.value.toRequest(), append = false) }
    }

    private suspend fun executeSearch(request: SearchRequest, append: Boolean, cursor: String? = null) {
        if (append) search.value = search.value.copy(loadingMore = true, error = null)
        else search.value = search.value.copy(searching = true, loadingMore = false, error = null)
        try {
            val page = repository.search(request, cursor)
            if (search.value.toRequest() != request) return
            val incoming = page.flattenedItems()
            val results = if (append) {
                (search.value.results + incoming).distinctBy(::searchItemIdentity)
            } else {
                incoming
            }
            search.value = search.value.copy(
                results = results,
                sections = if (append) search.value.sections else page.sections,
                nextCursor = page.nextCursor,
                searching = false,
                loadingMore = false,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (search.value.toRequest() == request) {
                search.value = search.value.copy(
                    searching = false,
                    loadingMore = false,
                    error = error.message ?: "Search failed",
                )
            }
        }
    }

    private fun SearchState.toRequest() = SearchRequest(
        query = submittedQuery.ifBlank { query.trim() },
        type = type,
        sort = sort,
        time = time,
        safe = safe,
    )

    private fun dev.readthat.search.domain.SearchPage.flattenedItems(): List<SearchItem> =
        (items + sections?.let { value ->
            value.communities + value.posts + value.comments + value.media + value.profiles
        }.orEmpty()).distinctBy(::searchItemIdentity)

    private fun searchItemIdentity(item: SearchItem): String = when (item) {
        is SearchPost -> "post:${item.id}"
        is SearchComment -> "comment:${item.id}"
        is SearchCommunity -> "community:${item.id}"
        is dev.readthat.search.domain.SearchProfile -> "profile:${item.id}"
    }

    fun openSearchResult(item: SearchItem) = when (item) {
        is SearchPost -> navigate(AppDestination.PostDetail(item.id))
        is SearchComment -> navigate(AppDestination.PostDetail(item.postId, focusCommentId = item.id))
        is SearchCommunity -> openCommunity(item.name)
        is dev.readthat.search.domain.SearchProfile -> navigate(AppDestination.PublicProfile(item.username))
    }

    fun onSharedSearchQueryChanged(value: String) {
        ensureSharedSearchController()?.onQueryChanged(value)
    }

    fun submitSharedSearch(query: String) {
        ensureSharedSearchController()?.submit(query)
    }

    fun clearSharedSearchQuery() {
        ensureSharedSearchController()?.clearQuery()
    }

    fun selectSharedSearchType(type: SearchType) {
        ensureSharedSearchController()?.selectType(type)
    }

    fun selectSharedSearchSort(sort: SearchSort) {
        ensureSharedSearchController()?.selectSort(sort)
    }

    fun selectSharedSearchTime(time: SearchTime) {
        ensureSharedSearchController()?.selectTime(time)
    }

    fun toggleSharedSafeSearch() {
        ensureSharedSearchController()?.toggleSafe()
    }

    fun deleteSharedRecentSearch(query: String) {
        ensureSharedSearchController()?.deleteRecent(query)
    }

    fun clearSharedRecentSearches() {
        ensureSharedSearchController()?.clearRecent()
    }

    fun retrySharedAllSearch() {
        ensureSharedSearchController()?.retryAll()
    }

    fun refreshCommunity() = communityDetailController.refresh()

    fun setCommunityJoined(joined: Boolean) = communityDetailController.setJoined(joined)

    fun refreshCommunityDiscovery() = communityDiscoveryController.refresh()

    fun setCreateMode(mode: CreateMode) = creationController.setMode(mode)
    fun setPostKind(kind: PostKind) = creationController.setPostKind(kind)
    fun setPostCommunity(value: String) = creationController.setPostCommunity(value)
    fun setPostFlair(value: PostFlair?) = creationController.setPostFlair(value)
    fun setPostTitle(value: String) = creationController.setPostTitle(value)
    fun setPostBody(value: String) = creationController.setPostBody(value)
    fun setPostLink(value: String) = creationController.setPostLink(value)
    fun addPickedMedia(items: List<LocalPostMedia>) = creationController.addPickedMedia(items)
    fun removePickedMedia(index: Int) = creationController.removePickedMedia(index)
    fun reportCreateError(value: String) = creationController.reportError(value)
    fun refreshCreationCommunities() = creationController.refreshCommunities()

    fun setProfileDisplayName(value: String) {
        profileController.setDisplayName(value)
    }

    fun setProfileBio(value: String) {
        profileController.setBio(value)
    }

    fun setProfileAvatar(items: List<LocalPostMedia>) {
        profileController.setAvatar(items)
    }

    fun removeProfileAvatar() {
        profileController.removeAvatar()
    }

    fun reportProfileError(value: String) {
        profileController.reportError(value)
    }

    fun retryPublicProfile() {
        val username = (destination.value as? AppDestination.PublicProfile)?.username ?: return
        profileController.loadPublicProfile(username, force = true)
    }

    fun saveProfile() {
        profileController.saveProfile {
            message.value = "Profile updated"
            navigate(AppDestination.Profile)
        }
    }

    fun setCommunityName(value: String) = creationController.setCommunityName(value)
    fun setCommunityDisplayName(value: String) = creationController.setCommunityDisplayName(value)
    fun setCommunityDescription(value: String) = creationController.setCommunityDescription(value)
    fun setCommunityAccess(value: String) = creationController.setCommunityAccess(value)

    fun submitCreate() = creationController.submit { outcome ->
        onCreationQueued(outcome)
        message.value = when (outcome) {
            is SharedCreationOutcome.PostQueued -> if (outcome.queuedOffline) {
                "Post queued and will publish when online"
            } else {
                "Post published"
            }
            is SharedCreationOutcome.CommunityQueued -> if (outcome.queuedOffline) {
                "Community queued and will publish when online"
            } else {
                "Community created"
            }
        }
        when (outcome) {
            is SharedCreationOutcome.PostQueued -> navigate(AppDestination.PendingPost(outcome.mutationId))
            is SharedCreationOutcome.CommunityQueued -> navigate(AppDestination.PendingCommunity(outcome.mutationId))
        }
    }

    fun setCommentDraft(value: String) = detailController.setCommentDraft(value)

    fun replyTo(commentId: String?) = detailController.replyTo(commentId)

    fun toggleCommentCollapsed(commentId: String) = detailController.toggleCommentCollapsed(commentId)

    fun selectCommentSort(sort: CommentSort) = detailController.selectCommentSort(sort)

    fun submitComment(postId: String) = detailController.submitComment(postId)

    fun setReshareTarget(value: String) {
        detail.value = detail.value.copy(reshareTarget = value, error = null)
    }

    fun reshare(postId: String) = viewModelScope.launch {
        val target = detail.value.reshareTarget.trim().removePrefix("r/")
        if (target.isBlank() || detail.value.resharing) return@launch
        detail.value = detail.value.copy(resharing = true, error = null)
        try {
            val post = repository.reshare(postId, target)
            detail.value = detail.value.copy(resharing = false, reshareTarget = "")
            message.value = "Post reshared to r/$target"
            navigate(AppDestination.PostDetail(post.id))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            detail.value = detail.value.copy(resharing = false, error = error.message ?: "Unable to reshare")
        }
    }

    fun sharePayload(postId: String, title: String) = client.postSharePayload(postId, title)

    fun loadMoreComments(postId: String, cursorId: String) {
        if (detail.value.postId == postId) detailController.loadMoreComments(cursorId)
    }

    /** Nested-tree equivalent of PagingConfig.prefetchDistance. */
    fun onCommentsViewport(postId: String, firstVisibleItemIndex: Int, lastVisibleItemIndex: Int) {
        if (detail.value.postId == postId) {
            detailController.onCommentsViewport(
                detailRender.value.rows,
                firstVisibleItemIndex,
                lastVisibleItemIndex,
            )
        }
    }

    fun clearDetailError() = detailController.clearError()

    fun setDetailCommunityJoined(joined: Boolean) = detailController.setCommunityJoined(joined)

    fun voteComment(postId: String, commentId: String, value: Int) {
        if (detail.value.postId == postId) detailController.voteComment(commentId, value)
    }

    fun retryPendingCreation() {
        when (val current = destination.value) {
            is AppDestination.PendingPost -> creationController.retryPendingPost(current.mutationId)
            is AppDestination.PendingCommunity -> creationController.retryPendingCommunity(current.mutationId)
            else -> Unit
        }
    }

    /**
     * Reuses the same Room-first detail/comment pipeline as full-screen detail while retaining
     * the media feed (and its warmed native player) underneath the modal presentation.
     */
    fun openMediaComments(item: MediaFeedItem) {
        if (destination.value !is AppDestination.Media) return
        if (mediaFeed.value.commentsPostId == item.postId) return
        mediaFeed.value = mediaFeed.value.copy(commentsPostId = item.postId)
        detailController.open(item.postId, projectedHeader = item.toPostHeader())
    }

    fun closeMediaComments() {
        if (mediaFeed.value.commentsPostId == null) return
        mediaFeed.value = mediaFeed.value.copy(commentsPostId = null)
        detailController.close()
    }

    fun setSettingPreference(preference: SettingsPreference, enabled: Boolean) =
        settingsController.setPreference(preference, enabled)

    fun clearSettingsError() = settingsController.clearError()

    fun setDarkTheme(enabled: Boolean) = setSettingPreference(SettingsPreference.DarkTheme, enabled)

    fun setAutoplay(enabled: Boolean) = setSettingPreference(SettingsPreference.AutoplayVideo, enabled)

    fun setCompactPosts(enabled: Boolean) = setSettingPreference(SettingsPreference.CompactPosts, enabled)

    fun setAutoplayOnMetered(enabled: Boolean) =
        setSettingPreference(SettingsPreference.AutoplayOnMetered, enabled)

    fun setReduceDataOnMetered(enabled: Boolean) =
        setSettingPreference(SettingsPreference.ReduceDataOnMetered, enabled)

    fun setReduceAnimations(enabled: Boolean) =
        setSettingPreference(SettingsPreference.ReduceAnimations, enabled)

    fun setBlurMatureMedia(enabled: Boolean) =
        setSettingPreference(SettingsPreference.BlurMatureMedia, enabled)

    fun reshareFromFeed(
        postId: String,
        subreddit: String,
        onComplete: (String?) -> Unit = {},
    ) = viewModelScope.launch {
        val target = subreddit.trim().removePrefix("r/")
        if (target.isBlank()) return@launch
        try {
            repository.reshare(postId, target)
            message.value = "Post reshared to r/$target"
            onComplete(null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val userMessage = error.message ?: "Unable to reshare"
            message.value = userMessage
            onComplete(userMessage)
        }
    }

    fun dismissMessage() { message.value = null }

    suspend fun loadMediaBytes(url: String, cacheKey: String, videoPreview: Boolean = false): ByteArray =
        client.mediaBytes(url, cacheKey, videoPreview)

    suspend fun loadStagedMediaBytes(media: LocalPostMedia): ByteArray {
        return creationController.stagedMediaBytes(media)
    }

    private fun projectedPostHeader(postId: String): PostHeader? =
        feedPresentationWindow[postId]?.preview?.toPostHeader()
            ?: communityPresentationWindow[postId]?.preview?.toPostHeader()
            ?: mediaFeedNavigationItems.value.firstOrNull { it.postId == postId }?.toPostHeader()

    private fun activateSharedMediaFeed(anchorPostId: String) {
        mediaFeedControllerJob?.cancel()
        val launchContext = pendingMediaLaunchContext?.takeIf { it.anchorPostId == anchorPostId }
        pendingMediaLaunchContext = null
        val accountId = (client.session.value as? SessionState.SignedIn)?.user?.id
            ?: CacheScope.DEFAULT_ACCOUNT_ID
        val subreddit = launchContext
            ?.takeIf { it.sourceFeedId != CacheScope.HOME_FEED_ID }
            ?.let { context -> context.items.getOrNull(context.anchorIndex) }
            ?.subreddit
            ?.removePrefix("r/")
            ?.takeIf(String::isNotBlank)
        val controllerJob = SupervisorJob(viewModelScope.coroutineContext[Job])
        val controllerScope = CoroutineScope(viewModelScope.coroutineContext + controllerJob)
        mediaFeedControllerJob = controllerJob
        activeMediaFeedController.value = SharedMediaFeedController(
            client = client,
            database = database,
            accountId = accountId,
            mediaScope = SharedMediaFeedScope(
                anchorPostId = anchorPostId,
                subreddit = subreddit,
                snapshotId = launchContext?.snapshotId,
            ),
            coroutineScope = controllerScope,
            launchContext = launchContext,
            votePost = { postId, value ->
                repository.votePost(postId, value, PerformanceSurface.MEDIA)
            },
        )
        mediaFeed.value = MediaFeedState()
    }

    private fun ensureSharedSearchController(): SharedSearchController? {
        val accountId = client.activeAccountId ?: return null
        activeSearchController.value?.takeIf { searchControllerAccountId == accountId }?.let {
            return it
        }
        releaseSharedSearchController()
        val controllerJob = SupervisorJob(viewModelScope.coroutineContext[Job])
        val controllerScope = CoroutineScope(viewModelScope.coroutineContext + controllerJob)
        return SharedSearchController(
            client = client,
            database = database,
            accountId = accountId,
            coroutineScope = controllerScope,
        ).also { controller ->
            searchControllerJob = controllerJob
            searchControllerAccountId = accountId
            activeSearchController.value = controller
        }
    }

    private fun releaseSharedSearchController() {
        searchControllerJob?.cancel()
        searchControllerJob = null
        searchControllerAccountId = null
        activeSearchController.value = null
    }

    private fun loadPublicProfile(username: String) = profileController.loadPublicProfile(username)

    private fun seedProfileEditor() {
        val user = (client.session.value as? SessionState.SignedIn)?.user ?: return
        profileController.beginEditing(user)
    }

    private companion object {
        const val MAX_NAVIGATION_DEPTH = 32
        const val MAX_PREFETCHED_COMMENT_POSTS = 64
    }
}

/** Best-effort work may ignore ordinary failures, but never consumes structured cancellation. */
private suspend fun bestEffort(block: suspend () -> Unit) {
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        Unit
    }
}

/**
 * A restored detail is removed only after a definitive post 404 and only when no durable/projected
 * post is available. Timeouts, TLS failures, offline launches, and deliberate deep links must keep
 * their destination so offline-first behavior is not weakened by navigation restoration.
 */
internal fun shouldRecoverRestoredPostDetail(
    restored: AppDestination.PostDetail?,
    current: AppDestination,
    detail: DetailState,
): Boolean = restored != null &&
    current == restored &&
    detail.postId == restored.postId &&
    detail.post == null &&
    detail.postNotFound &&
    !detail.loading

private fun PostTransitionPreview.toPostHeader() = PostHeader(
    postId = postId,
    title = title,
    author = author,
    subreddit = subreddit,
    score = score,
    commentCount = commentCount,
    body = body,
    media = media,
    viewerVote = viewerVote,
    kind = when {
        media?.isVideo == true -> "video"
        media != null || mediaItems.isNotEmpty() -> "image"
        !linkUrl.isNullOrBlank() -> "link"
        else -> "text"
    },
    linkUrl = linkUrl,
    mediaItems = mediaItems,
    flair = flair,
)

private fun findComment(nodes: List<CommentNode>, commentId: String): CommentNode.Comment? {
    val stack = ArrayDeque<CommentNode>()
    nodes.forEach(stack::addLast)
    while (stack.isNotEmpty()) {
        when (val node = stack.removeLast()) {
            is CommentNode.Comment -> {
                if (node.id == commentId) return node
                node.children.forEach(stack::addLast)
            }
            is CommentNode.LoadMore -> Unit
        }
    }
    return null
}

internal data class CommentCollapseState(
    val userCollapsed: Set<String>,
    val autoCollapsed: Set<String>,
)

internal fun progressiveCommentCollapse(
    roots: List<CommentNode>,
    commentId: String,
    userCollapsed: Set<String>,
    autoCollapsed: Set<String>,
): CommentCollapseState {
    if (commentId !in userCollapsed && commentId !in autoCollapsed) {
        return CommentCollapseState(userCollapsed + commentId, autoCollapsed)
    }
    val comment = findComment(roots, commentId)
    val directChildIds = comment?.children
        ?.filterIsInstance<CommentNode.Comment>()
        ?.mapTo(mutableSetOf(), CommentNode.Comment::id)
        .orEmpty()
    val grandchildIds = comment?.children
        ?.filterIsInstance<CommentNode.Comment>()
        ?.flatMapTo(mutableSetOf()) { child ->
            child.children.filterIsInstance<CommentNode.Comment>().map(CommentNode.Comment::id)
        }
        .orEmpty()
    return CommentCollapseState(
        userCollapsed = userCollapsed - commentId,
        autoCollapsed = (autoCollapsed - commentId - directChildIds) + grandchildIds,
    )
}

internal fun nextCommentCursorKey(
    rows: List<dev.readthat.comments.domain.CommentRow>,
    loadStates: Map<String, CommentLoadState>,
    firstVisibleItemIndex: Int,
    lastVisibleItemIndex: Int,
    headerItemCount: Int = 1,
    prefetchDistance: Int = 6,
): String? {
    if (rows.isEmpty() || firstVisibleItemIndex < 0 || lastVisibleItemIndex < firstVisibleItemIndex) return null
    val firstRow = (firstVisibleItemIndex - headerItemCount).coerceAtLeast(0)
    val lastRow = (lastVisibleItemIndex - headerItemCount + prefetchDistance).coerceAtMost(rows.lastIndex)
    if (firstRow > lastRow) return null
    return rows.subList(firstRow, lastRow + 1)
        .filterIsInstance<dev.readthat.comments.domain.CommentRow.LoadMore>()
        .firstOrNull { loadStates[it.key] == null }
        ?.key
}
