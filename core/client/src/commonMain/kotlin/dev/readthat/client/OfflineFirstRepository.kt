package dev.readthat.client

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.paging.flatMap
import androidx.room3.withWriteTransaction
import dev.readthat.comments.domain.CommentNode
import dev.readthat.comments.domain.CommentTree
import dev.readthat.comments.domain.CommentTreeEditor
import dev.readthat.comments.domain.CommentTreeMerger
import dev.readthat.comments.domain.CommentTreeSplicer
import dev.readthat.communities.domain.CommunityDrawerSnapshot
import dev.readthat.communities.domain.CommunityVisitCommand
import dev.readthat.communities.domain.DrawerCommunity
import dev.readthat.communities.domain.RecentCommunity
import dev.readthat.data.db.AppDatabase
import dev.readthat.data.db.AppSettingsEntity
import dev.readthat.data.db.CacheScope
import dev.readthat.data.db.CachedDocumentEntity
import dev.readthat.data.db.GroupEntity
import dev.readthat.data.db.GroupWithState
import dev.readthat.data.db.ItemStateEntity
import dev.readthat.data.db.CommunityDrawerSyncEntity
import dev.readthat.data.db.CommunityMembershipEntity
import dev.readthat.data.db.CommunityVisitEntity
import dev.readthat.data.db.CommunityVisitMutationEntity
import dev.readthat.data.db.PendingCommunityMembershipEntity
import dev.readthat.data.db.PendingPostEntity
import dev.readthat.data.db.PendingSubredditEntity
import dev.readthat.data.db.PendingVoteEntity
import dev.readthat.data.db.RemoteKeyEntity
import dev.readthat.data.db.SearchRecentEntity
import dev.readthat.data.db.SubredditEntity
import dev.readthat.domain.CellConverterRegistry
import dev.readthat.domain.CellUi
import dev.readthat.domain.FeedFlattener
import dev.readthat.domain.NormalFeedMediaContext
import dev.readthat.domain.WireCell
import dev.readthat.domain.WireFeedPage
import dev.readthat.domain.WireGroup
import dev.readthat.domain.toPostTransitionPreview
import dev.readthat.observability.PerformanceMetric
import dev.readthat.observability.PerformanceOutcome
import dev.readthat.observability.PerformanceEvent
import dev.readthat.observability.PerformanceSurface
import dev.readthat.observability.PerformanceTelemetry
import dev.readthat.observability.PerformanceUnit
import dev.readthat.observability.performanceTimer
import dev.readthat.search.domain.SearchDiscover
import dev.readthat.search.domain.SearchPage
import dev.readthat.search.domain.SearchRequest
import dev.readthat.search.domain.SearchTypeahead
import dev.readthat.shared.AppSettings
import dev.readthat.shared.CreateCommunityDraft
import dev.readthat.shared.CreatePostDraft
import dev.readthat.shared.CreatedPost
import dev.readthat.shared.LocalPostMedia
import dev.readthat.shared.PostHeader
import dev.readthat.shared.PostFlair
import dev.readthat.shared.PostKind
import dev.readthat.shared.PostTransitionPreview
import dev.readthat.shared.Subreddit
import dev.readthat.shared.UserProfile
import dev.readthat.shared.VoteSnapshot
import dev.readthat.communitydetail.domain.CommunityDetail
import dev.readthat.communitydetail.domain.CommunityRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable

data class FeedCard(
    val id: String,
    val cells: List<CellUi>,
    val preview: PostTransitionPreview,
)

data class FeedState(
    val initialCacheTier: String? = null,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val endReached: Boolean = false,
    val isOffline: Boolean = false,
    val error: String? = null,
)

data class DetailState(
    val postId: String? = null,
    val post: PostHeader? = null,
    val comments: CommentTree? = null,
    val community: CommunityDetail? = null,
    val communityMembershipChanging: Boolean = false,
    val rootCommentId: String? = null,
    val focusedCommentId: String? = null,
    val collapsedCommentIds: Set<String> = emptySet(),
    /** Ephemeral progressive-disclosure boundaries; unlike user intent these are not persisted. */
    val autoCollapsedCommentIds: Set<String> = emptySet(),
    val commentLoadStates: Map<String, CommentLoadState> = emptyMap(),
    /** Source that produced the first renderable comment tree for this detail visit. */
    val initialCacheTier: String? = null,
    val loading: Boolean = false,
    val refreshingComments: Boolean = false,
    val commentDraft: String = "",
    val replyingToId: String? = null,
    val submittingComment: Boolean = false,
    val reshareTarget: String = "",
    val resharing: Boolean = false,
    /** Definitive server 404 for the post itself; ordinary/offline failures remain recoverable. */
    val postNotFound: Boolean = false,
    val error: String? = null,
)

enum class CommentLoadState { Loading, Error }

data class MutationOutcome<T>(
    val value: T,
    val queuedOffline: Boolean,
    /** Stable idempotency key and Room outbox primary key for status/retry UI. */
    val mutationId: String,
)

@Serializable
internal data class PendingMediaDescriptor(
    val media: LocalPostMedia,
    val remoteMediaId: String? = null,
)

/**
 * Pending media written by the mature Android create screen before creation moved to KMP.
 * Keep decoding this shape until every installed Android outbox has had a chance to drain.
 */
@Serializable
private data class LegacyPendingMediaDescriptor(
    val name: String,
    val contentType: String,
    val localPath: String,
    val byteSize: Long,
    val width: Int? = null,
    val height: Int? = null,
    val durationSeconds: Int? = null,
    val remoteMediaId: String? = null,
)

internal fun decodePendingMediaDescriptors(
    pending: PendingPostEntity,
    json: kotlinx.serialization.json.Json,
): List<PendingMediaDescriptor> {
    val current = runCatching {
        json.decodeFromString<List<PendingMediaDescriptor>>(pending.mediaItemsJson)
    }.getOrDefault(emptyList())
    if (current.isNotEmpty()) return current

    val legacy = runCatching {
        json.decodeFromString<List<LegacyPendingMediaDescriptor>>(pending.mediaItemsJson)
    }.getOrDefault(emptyList())
    if (legacy.isNotEmpty()) {
        return legacy.map { item ->
            PendingMediaDescriptor(
                media = LocalPostMedia(
                    name = item.name,
                    mimeType = item.contentType,
                    localPath = item.localPath,
                    byteSize = item.byteSize,
                    width = item.width,
                    height = item.height,
                    durationSeconds = item.durationSeconds,
                ),
                remoteMediaId = item.remoteMediaId,
            )
        }
    }

    return if (pending.localPath != null && pending.contentType != null && pending.byteSize != null) {
        listOf(PendingMediaDescriptor(
            LocalPostMedia(
                pending.title,
                requireNotNull(pending.contentType),
                requireNotNull(pending.localPath),
                requireNotNull(pending.byteSize),
                pending.width,
                pending.height,
                pending.durationSeconds,
            ),
            pending.mediaId,
        ))
    } else {
        emptyList()
    }
}

/** One process-wide publication lane shared by UI retries and native background schedulers. */
private object SharedPostPublicationLane {
    val mutex = Mutex()
}

/**
 * Shared source-of-truth repository.
 *
 * StateFlow is the hot L1 used by Compose; Room is the durable L2. Network
 * refreshes only write Room and the UI reacts to invalidation, so offline and
 * online paths never produce competing models.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineFirstRepository(
    private val client: ReadThatClient,
    private val database: AppDatabase,
    private val scope: CoroutineScope,
    private val api: ReadThatApi = ReadThatApi(client),
    /** Stable account scope for native workers and destination-scoped controllers. */
    private val accountIdOverride: String? = null,
    /** Android schedules WorkManager immediately after the durable local vote commit. */
    private val onVoteQueued: () -> Unit = {},
    /** Focused screen controllers opt out; the application repository owns global maintenance. */
    private val maintainGlobalState: Boolean = true,
) {
    private val json get() = client.json
    private val feedDao = database.feedDao()
    private val searchDao = database.searchDao()
    private val documents = database.cachedDocumentDao()
    private val refreshMutex = Mutex()
    private val droppedFeedCellsMutex = Mutex()
    private val reportedDroppedFeedCells = LinkedHashSet<String>()
    private val initialCommentRequestMutex = Mutex()
    private val initialCommentRequests = mutableMapOf<String, CompletableDeferred<CommentTree>>()
    private val mutableFeed = MutableStateFlow(FeedState())
    private var mutableFeedAccount: String? = null
    val feed: StateFlow<FeedState> = mutableFeed
    /** Mutable feed status is shared only with the canonical controller in this module. */
    internal val homeFeedStatus: MutableStateFlow<FeedState> get() = mutableFeed
    private val pendingFeedLoadTypes = MutableStateFlow<Map<String, String>>(emptyMap())
    private val accountScopes: Flow<String> = accountIdOverride?.let(::flowOf)
        ?: client.session.map { accountScope() }.distinctUntilChanged()

    /**
     * Room-backed, bounded feed presentation shared by Android and iOS.
     *
     * A new Pager is created per account. Room remains the only item source;
     * the RemoteMediator writes network pages and durable cursors transactionally.
     */
    val pagedFeed: Flow<PagingData<FeedCard>> = pagedFeedFor(
        feedId = CacheScope.HOME_FEED_ID,
        subreddit = null,
        state = mutableFeed,
    )

    /** Community feeds use the same Room/Paging/cursor pipeline as Home, scoped by subreddit. */
    fun pagedCommunityFeed(name: String): Flow<PagingData<FeedCard>> {
        val normalized = normalizeCommunity(name)
        return pagedFeedFor(
            feedId = CacheScope.communityFeedId(normalized),
            subreddit = normalized,
            state = null,
        )
    }

    @OptIn(ExperimentalPagingApi::class)
    internal fun pagedFeedFor(
        feedId: String,
        subreddit: String?,
        state: MutableStateFlow<FeedState>?,
    ): Flow<PagingData<FeedCard>> = accountScopes
        .flatMapLatest { account ->
            Pager(
                config = sharedFeedPagingConfig(),
                remoteMediator = SharedFeedRemoteMediator(account, feedId, subreddit, state),
                pagingSourceFactory = { feedDao.pagingSource(account, feedId) },
            ).flow.map { paging ->
                paging.flatMap { row -> listOfNotNull(toPagedFeedCard(row)) }
            }
        }

    fun markUserFeedRefresh() {
        markFeedLoad(CacheScope.HOME_FEED_ID, "User Refresh")
    }

    fun markFeedErrorRetry() {
        markFeedLoad(CacheScope.HOME_FEED_ID, "Error Retry")
    }

    fun markUserCommunityFeedRefresh(name: String) {
        markFeedLoad(CacheScope.communityFeedId(normalizeCommunity(name)), "User Refresh")
    }

    fun markCommunityFeedErrorRetry(name: String) {
        markFeedLoad(CacheScope.communityFeedId(normalizeCommunity(name)), "Error Retry")
    }

    internal fun markFeedLoad(feedId: String, phase: String) {
        pendingFeedLoadTypes.update { it + (feedId to phase) }
    }

    val settings: StateFlow<AppSettings> = database.appSettingsDao().observe()
        .map { it?.toDomain() ?: AppSettings() }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    val communityDrawer: StateFlow<CommunityDrawerSnapshot> = client.session
        .map { accountScope() }
        .distinctUntilChanged()
        .flatMapLatest { account ->
            combine(
                database.communityDrawerDao().observeMemberships(account),
                database.communityDrawerDao().observeVisits(account),
                database.communityDrawerDao().observeSyncState(account),
            ) { memberships, visits, sync ->
                CommunityDrawerSnapshot(
                    communities = memberships.map { row ->
                        DrawerCommunity(
                            row.id, row.name, row.displayName, row.accessType, row.viewerRole,
                        )
                    },
                    recentlyVisited = visits.map { row ->
                        RecentCommunity(row.id, row.name, row.displayName, row.visitedAt)
                    },
                    lastSuccessfulSyncAt = sync?.lastSuccessfulSyncAt,
                )
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, CommunityDrawerSnapshot())

    val recentSearches: Flow<List<String>> = client.session
        .map { accountScope() }
        .distinctUntilChanged()
        .flatMapLatest { account -> searchDao.observeRecent(account) }
        .map { rows -> rows.map(SearchRecentEntity::query) }

    init {
        if (maintainGlobalState) {
            scope.launch {
                accountScopes
                    .collect { account ->
                        documents.prune(account, platformEpochMillis() - DOCUMENT_RETENTION_MS)
                    }
            }
            scope.launch {
                accountScopes
                    .flatMapLatest { account ->
                        feedDao.observeGroupCount(account, CacheScope.HOME_FEED_ID).map { count -> account to count }
                    }
                    .collect { (account, count) ->
                        val accountChanged = mutableFeedAccount != account
                        mutableFeedAccount = account
                        mutableFeed.value = if (accountChanged) {
                            FeedState(initialCacheTier = "room".takeIf { count > 0 })
                        } else {
                            mutableFeed.value.copy(
                                initialCacheTier = mutableFeed.value.initialCacheTier
                                    ?: "room".takeIf { count > 0 },
                            )
                        }
                    }
                }
        }
    }

    suspend fun refreshFeed(force: Boolean = false) = refreshMutex.withLock {
        if (mutableFeed.value.isRefreshing) return@withLock
        val account = accountScope()
        val existing = feedDao.groupCount(account, CacheScope.HOME_FEED_ID)
        if (mutableFeed.value.initialCacheTier == null && existing == 0) {
            mutableFeed.value = mutableFeed.value.copy(initialCacheTier = "network")
        }
        if (!force && existing > 0) {
            val synced = feedDao.syncMetadata(account, CacheScope.HOME_FEED_ID)?.lastSuccessfulSyncAt ?: 0L
            if (platformEpochMillis() - synced < FEED_FRESH_MS) return@withLock
        }
        mutableFeed.value = mutableFeed.value.copy(isRefreshing = true, error = null)
        val timer = performanceTimer()
        try {
            val page = fetchAndCommitHomeFeed(account)
            mutableFeed.value = mutableFeed.value.copy(
                isRefreshing = false,
                endReached = page.nextCursor == null,
                isOffline = false,
            )
            PerformanceTelemetry.duration(
                PerformanceMetric.FEED_LOAD_SUCCESS, timer, PerformanceSurface.FEED,
                attributes = mapOf("cache_tier" to "network", "phase" to "refresh"),
            )
            syncPendingVotes()
        } catch (cancelled: CancellationException) {
            mutableFeed.value = mutableFeed.value.copy(isRefreshing = false)
            throw cancelled
        } catch (error: Throwable) {
            val canRenderOffline = feedDao.groupCount(account, CacheScope.HOME_FEED_ID) > 0
            mutableFeed.value = mutableFeed.value.copy(
                isRefreshing = false,
                isOffline = canRenderOffline,
                error = if (canRenderOffline) null else error.userMessage(),
            )
            PerformanceTelemetry.duration(
                PerformanceMetric.FEED_LOAD_FAIL, timer, PerformanceSurface.FEED,
                outcome = PerformanceOutcome.FAILURE,
                attributes = mapOf(
                    "cache_tier" to if (canRenderOffline) "disk" else "network",
                    "phase" to "fallback",
                ),
            )
        }
    }

    /**
     * Background refresh entry point. Native schedulers own OS constraints and media warming;
     * the authenticated request, Room transaction, vote reconciliation, and telemetry stay KMP.
     */
    suspend fun refreshHomeFeedForBackground(): WireFeedPage {
        val timer = performanceTimer()
        return try {
            val page = fetchAndCommitHomeFeed(accountScope())
            PerformanceTelemetry.duration(
                PerformanceMetric.FEED_LOAD_SUCCESS,
                timer,
                PerformanceSurface.BACKGROUND,
                attributes = mapOf("cache_tier" to "network", "phase" to "background_refresh"),
            )
            syncPendingVotes()
            page
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            PerformanceTelemetry.duration(
                PerformanceMetric.FEED_LOAD_FAIL,
                timer,
                PerformanceSurface.BACKGROUND,
                outcome = PerformanceOutcome.FAILURE,
                attributes = mapOf("cache_tier" to "network", "phase" to "background_refresh"),
            )
            throw error
        }
    }

    private suspend fun fetchAndCommitHomeFeed(account: String): WireFeedPage =
        api.feed().also { page ->
            commitFeedPage(account, CacheScope.HOME_FEED_ID, page, refresh = true)
        }

    @OptIn(ExperimentalPagingApi::class)
    private inner class SharedFeedRemoteMediator(
        private val account: String,
        private val feedId: String,
        private val subreddit: String?,
        private val status: MutableStateFlow<FeedState>?,
    ) : RemoteMediator<Int, GroupWithState>() {
        private val surface = if (subreddit == null) PerformanceSurface.FEED else PerformanceSurface.COMMUNITY

        private fun updateStatus(transform: (FeedState) -> FeedState) {
            status?.let { it.value = transform(it.value) }
        }

        override suspend fun initialize(): InitializeAction {
            seedLegacyCommunityFeedIfNeeded(account, feedId, subreddit)
            val cachedCount = feedDao.groupCount(account, feedId)
            val synced = feedDao.syncMetadata(account, feedId)
                ?.lastSuccessfulSyncAt ?: 0L
            if (status?.value?.initialCacheTier == null) {
                updateStatus {
                    it.copy(
                    initialCacheTier = if (cachedCount > 0) "room" else "network",
                    )
                }
            }
            return if (shouldLaunchFeedRefresh(cachedCount, synced, platformEpochMillis())) {
                InitializeAction.LAUNCH_INITIAL_REFRESH
            } else {
                InitializeAction.SKIP_INITIAL_REFRESH
            }
        }

        override suspend fun load(
            loadType: LoadType,
            state: PagingState<Int, GroupWithState>,
        ): MediatorResult = refreshMutex.withLock {
            val cursor = when (loadType) {
                LoadType.REFRESH -> null
                LoadType.PREPEND -> return@withLock MediatorResult.Success(true)
                LoadType.APPEND -> feedDao.remoteKey(feedId, account)?.nextCursor
                    ?: return@withLock MediatorResult.Success(true)
            }
            val explicitPhase = pendingFeedLoadTypes.getAndUpdate { it - feedId }[feedId]
            if (loadType == LoadType.REFRESH && explicitPhase == null) {
                val cachedCount = feedDao.groupCount(account, feedId)
                val synced = feedDao.syncMetadata(account, feedId)
                    ?.lastSuccessfulSyncAt ?: 0L
                if (!shouldLaunchFeedRefresh(cachedCount, synced, platformEpochMillis())) {
                    val next = feedDao.remoteKey(feedId, account)?.nextCursor
                    return@withLock MediatorResult.Success(next == null)
                }
            }
            val phase = explicitPhase
                ?: if (loadType == LoadType.REFRESH) "Organic First Page" else "Next Page"
            val timer = performanceTimer()
            if (loadType == LoadType.REFRESH) {
                updateStatus { it.copy(isRefreshing = true, error = null) }
            } else {
                updateStatus { it.copy(isLoadingMore = true, error = null) }
            }
            try {
                val page = api.feed(cursor, subreddit)
                commitFeedPage(account, feedId, page, refresh = loadType == LoadType.REFRESH)
                updateStatus {
                    it.copy(
                        isRefreshing = false,
                        isLoadingMore = false,
                        endReached = page.nextCursor == null,
                        isOffline = false,
                        error = null,
                    )
                }
                PerformanceTelemetry.duration(
                    PerformanceMetric.FEED_LOAD_SUCCESS,
                    timer,
                    surface,
                    attributes = mapOf(
                        "load_type" to phase,
                        "cache_tier" to "network",
                        "feed_scope" to (subreddit?.let { "community" } ?: "home"),
                    ),
                )
                if (loadType == LoadType.REFRESH) syncPendingVotes()
                MediatorResult.Success(page.nextCursor == null)
            } catch (cancelled: CancellationException) {
                updateStatus { it.copy(isRefreshing = false, isLoadingMore = false) }
                throw cancelled
            } catch (error: Throwable) {
                val cached = feedDao.groupCount(account, feedId) > 0
                updateStatus {
                    it.copy(
                        isRefreshing = false,
                        isLoadingMore = false,
                        isOffline = cached,
                        error = if (cached && loadType == LoadType.REFRESH) null else error.userMessage(),
                    )
                }
                PerformanceTelemetry.duration(
                    PerformanceMetric.FEED_LOAD_FAIL,
                    timer,
                    surface,
                    outcome = PerformanceOutcome.FAILURE,
                    attributes = mapOf(
                        "load_type" to phase,
                        "cache_tier" to if (cached) "disk" else "network",
                        "feed_scope" to (subreddit?.let { "community" } ?: "home"),
                    ),
                )
                MediatorResult.Error(error)
            }
        }
    }

    /** One-time, lossless promotion of the old cached-document community page into Room Paging. */
    private suspend fun seedLegacyCommunityFeedIfNeeded(
        account: String,
        feedId: String,
        subreddit: String?,
    ) {
        if (subreddit == null || feedDao.groupCount(account, feedId) > 0) return
        getDocument<WireFeedPage>("community-feed:$subreddit")?.let { cached ->
            commitFeedPage(account, feedId, cached, refresh = true)
        }
    }

    suspend fun votePost(
        postId: String,
        value: Int,
        surface: PerformanceSurface = PerformanceSurface.FEED,
    ): VoteSnapshot? {
        require(value in -1..1)
        val account = accountScope()
        val mutationId = platformMutationId("vote")
        val cachedHeader = getDocument<PostHeader>(postKey(postId))
        val current = feedDao.stateFor(postId, account)
            ?: cachedHeader?.let {
                ItemStateEntity(postId, it.score, it.viewerVote == 1, it.viewerVote == -1, account)
            }
            ?: return null
        val previousValue = when {
            current.liked -> 1
            current.downvoted -> -1
            else -> 0
        }
        val optimisticScore = current.likeCount - previousValue + value
        val mutationType = when (value) {
            1 -> "post_upvote"
            -1 -> "post_downvote"
            else -> "post_vote_clear"
        }
        val localTimer = performanceTimer()
        database.withWriteTransaction {
            feedDao.putState(ItemStateEntity(postId, optimisticScore, value == 1, value == -1, account))
            feedDao.enqueueVote(PendingVoteEntity(postId, mutationId, value, platformEpochMillis(), account))
        }
        updateCachedPostVote(postId, optimisticScore, value)
        PerformanceTelemetry.duration(
            PerformanceMetric.MUTATION_LOCAL_COMMIT,
            localTimer,
            surface,
            attributes = mapOf("mutation_type" to mutationType, "cache_tier" to "room"),
        )
        onVoteQueued()
        val serverTimer = performanceTimer()
        return try {
            val result = api.votePost(postId, value, mutationId)
            database.withWriteTransaction {
                feedDao.confirmVote(postId, mutationId, result.score, result.value, account)
            }
            val resolved = feedDao.stateFor(postId, account)?.toVoteSnapshot()
                ?: VoteSnapshot(result.score, result.value)
            updateCachedPostVote(postId, resolved.score, resolved.viewerVote)
            PerformanceTelemetry.duration(
                PerformanceMetric.MUTATION_SERVER_ACK,
                serverTimer,
                surface,
                attributes = mapOf("mutation_type" to mutationType),
            )
            resolved
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            PerformanceTelemetry.record(PerformanceEvent(
                name = PerformanceMetric.MUTATION_SERVER_ACK,
                value = serverTimer.elapsedMilliseconds(),
                surface = surface,
                outcome = PerformanceOutcome.QUEUED,
                attributes = mapOf("mutation_type" to mutationType),
            ))
            // Optimistic state and coalesced outbox intentionally survive offline.
            feedDao.stateFor(postId, account)?.toVoteSnapshot() ?: VoteSnapshot(optimisticScore, value)
        }
    }

    suspend fun syncPendingVotes() {
        val account = accountScope()
        feedDao.pendingVotes(account).forEach { pending ->
            try {
                val result = api.votePost(pending.itemId, pending.value, pending.mutationId)
                database.withWriteTransaction {
                    feedDao.confirmVote(pending.itemId, pending.mutationId, result.score, result.value, account)
                }
                updateCachedPostVote(pending.itemId, result.score, result.value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return
            }
        }
    }

    /**
     * Captures Room ordering and the continuation cursor in one transaction so the shared media
     * pager never reconstructs ranking from an in-memory UI snapshot.
     */
    suspend fun mediaLaunchContext(
        feedId: String,
        anchorPostId: String,
        visibleFallback: PostTransitionPreview,
    ): NormalFeedMediaContext {
        val account = accountScope()
        val snapshot = database.withWriteTransaction {
            feedDao.orderedGroups(account, feedId) to feedDao.remoteKey(feedId, account)?.nextCursor
        }
        val cachedMedia = snapshot.first.mapNotNull { row ->
            toPagedFeedCard(row)?.preview?.takeIf { it.media != null }
        }
        val items = if (cachedMedia.any { it.postId == anchorPostId }) {
            cachedMedia
        } else {
            cachedMedia + listOfNotNull(visibleFallback.takeIf { it.media != null })
        }.distinctBy(PostTransitionPreview::postId)
        val anchorIndex = items.indexOfFirst { it.postId == anchorPostId }
        check(anchorIndex >= 0) { "Tapped media $anchorPostId is absent from the feed snapshot" }
        return NormalFeedMediaContext(
            snapshotId = platformMutationId("media-snapshot"),
            sourceFeedId = feedId,
            anchorPostId = anchorPostId,
            items = items,
            anchorIndex = anchorIndex,
            nextFeedCursor = snapshot.second,
        )
    }

    fun observePost(postId: String): Flow<PostHeader?> =
        documents.observe(accountScope(), postKey(postId)).map { row ->
            row?.payloadJson?.let { runCatching { json.decodeFromString<PostHeader>(it) }.getOrNull() }
        }

    fun observeComments(
        postId: String,
        rootCommentId: String? = null,
        focusCommentId: String? = null,
    ): Flow<CommentTree?> =
        documents.observe(accountScope(), commentsKey(postId, rootCommentId, focusCommentId)).map { row ->
            row?.payloadJson?.let { runCatching { json.decodeFromString<CommentTree>(it) }.getOrNull() }
        }

    suspend fun cachedComments(
        postId: String,
        rootCommentId: String? = null,
        focusCommentId: String? = null,
    ): CommentTree? = getDocument(commentsKey(postId, rootCommentId, focusCommentId))

    fun observePendingPost(mutationId: String): Flow<PendingPostEntity?> =
        database.postOutboxDao().observe(mutationId)

    fun observePendingCommunity(mutationId: String): Flow<PendingSubredditEntity?> =
        database.subredditOutboxDao().observe(mutationId)

    suspend fun refreshPost(postId: String): PostHeader {
        val value = api.post(postId)
        putDocument(postKey(postId), json.encodeToString(value))
        return value
    }

    /** Persists a feed/media projection so detail can paint identity before its refresh returns. */
    suspend fun persistPostHeader(header: PostHeader) {
        if (getDocument<PostHeader>(postKey(header.postId)) == null) {
            putDocument(postKey(header.postId), json.encodeToString(header))
        }
    }

    /**
     * Dwell-prefetches only the small phase-1 comment tree. The same in-flight request is awaited
     * by detail, and Room is the hand-off boundary, so the optimization also survives recreation.
     */
    suspend fun prefetchComments(postId: String) {
        require(postId.isNotBlank())
        val key = commentsKey(postId)
        if (getDocument<CommentTree>(key) != null) return
        val initial = initialComments(postId)
        val latest = getDocument<CommentTree>(key)
        val merged = CommentTreeMerger.merge(latest, initial).tree
        putDocument(key, json.encodeToString(merged))
    }

    /** Small tree renders first; the large tree merges without expanding visible leaves. */
    suspend fun refreshComments(
        postId: String,
        rootCommentId: String? = null,
        focusCommentId: String? = null,
    ): CommentTree {
        require(rootCommentId == null || focusCommentId == null) {
            "A comment view cannot be both rooted and focused"
        }
        val key = commentsKey(postId, rootCommentId, focusCommentId)
        if (rootCommentId != null || focusCommentId != null) {
            val timer = performanceTimer()
            return api.comments(
                postId = postId,
                count = 200,
                rootCommentId = rootCommentId,
                focusCommentId = focusCommentId,
            ).also {
                putDocument(key, json.encodeToString(it))
                PerformanceTelemetry.duration(
                    PerformanceMetric.COMMENTS_FULL_FETCH,
                    timer,
                    PerformanceSurface.DETAIL,
                    attributes = mapOf(
                        "phase" to if (focusCommentId != null) "focused" else "rooted",
                        "cache_tier" to "network",
                    ),
                )
            }
        }
        val cached = getDocument<CommentTree>(key)
        val initialTimer = performanceTimer()
        val initial = cached ?: initialComments(postId)
        val first = CommentTreeMerger.merge(cached, initial).tree
        putDocument(key, json.encodeToString(first))
        PerformanceTelemetry.duration(
            PerformanceMetric.COMMENTS_INITIAL_FETCH, initialTimer, PerformanceSurface.DETAIL,
            attributes = mapOf("cache_tier" to if (cached == null) "network" else "room"),
        )
        val fullTimer = performanceTimer()
        return try {
            val full = api.comments(postId, count = 200)
            CommentTreeMerger.merge(first, full).tree.also {
                putDocument(key, json.encodeToString(it))
                PerformanceTelemetry.duration(
                    PerformanceMetric.COMMENTS_FULL_FETCH, fullTimer, PerformanceSurface.DETAIL,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            first
        }
    }

    suspend fun loadMoreComments(
        postId: String,
        cursor: CommentNode.LoadMore,
        rootCommentId: String? = null,
        focusCommentId: String? = null,
    ): CommentTree? {
        val key = commentsKey(postId, rootCommentId, focusCommentId)
        val current = getDocument<CommentTree>(key) ?: return null
        val merged = CommentTreeSplicer.splice(current, cursor.id, api.loadMore(postId, cursor))
        putDocument(key, json.encodeToString(merged))
        return merged
    }

    private suspend fun initialComments(postId: String): CommentTree {
        val requestKey = "${accountScope()}:$postId"
        val (request, owner) = initialCommentRequestMutex.withLock {
            initialCommentRequests[requestKey]?.let { existing -> existing to false }
                ?: CompletableDeferred<CommentTree>().let { created ->
                    initialCommentRequests[requestKey] = created
                    created to true
                }
        }
        if (owner) {
            try {
                request.complete(api.comments(postId, count = 8))
            } catch (error: Throwable) {
                request.completeExceptionally(error)
            } finally {
                withContext(NonCancellable) {
                    initialCommentRequestMutex.withLock {
                        if (initialCommentRequests[requestKey] === request) {
                            initialCommentRequests.remove(requestKey)
                        }
                    }
                }
            }
        }
        return request.await()
    }

    suspend fun createComment(
        postId: String,
        parentId: String?,
        body: String,
        rootCommentId: String? = null,
        focusCommentId: String? = null,
        pendingId: String = platformMutationId("pending-comment"),
    ): CommentTree? {
        val key = commentsKey(postId, rootCommentId, focusCommentId)
        val current = getDocument<CommentTree>(key)
        val localParentId = parentId.takeUnless { rootCommentId != null && it == rootCommentId }
        val pending = CommentNode.Comment(
            id = pendingId,
            author = "you",
            authorDisplayName = "You",
            body = body.trim(),
            score = 1,
            viewerVote = 1,
        )
        current?.copy(roots = CommentTreeEditor.insert(current.roots, localParentId, pending))
            ?.let { putDocument(key, json.encodeToString(it)) }
        return try {
            val created = api.createComment(postId, parentId, body)
            val node = CommentNode.Comment(
                id = created.id,
                author = created.author,
                body = created.body,
                score = created.score,
                createdAgoMin = created.createdAgoMin,
                viewerVote = created.viewerVote,
                authorDisplayName = created.authorDisplayName,
                authorAvatarUrl = created.authorAvatarUrl,
                isEdited = created.isEdited,
                descendantCount = created.descendantCount,
            )
            val latest = getDocument<CommentTree>(key)
                ?: return refreshComments(postId, rootCommentId, focusCommentId)
            latest.copy(roots = CommentTreeEditor.replace(latest.roots, pendingId, node)).also {
                putDocument(key, json.encodeToString(it))
            }
        } catch (cancelled: CancellationException) {
            getDocument<CommentTree>(key)?.let { latest ->
                latest.copy(roots = CommentTreeEditor.remove(latest.roots, pendingId))
            }?.let { putDocument(key, json.encodeToString(it)) }
            throw cancelled
        } catch (error: Throwable) {
            getDocument<CommentTree>(key)?.let { latest ->
                latest.copy(roots = CommentTreeEditor.remove(latest.roots, pendingId))
            }?.let { putDocument(key, json.encodeToString(it)) }
            throw error
        }
    }

    suspend fun voteComment(
        postId: String,
        commentId: String,
        value: Int,
        rootCommentId: String? = null,
        focusCommentId: String? = null,
    ): CommentTree {
        val result = api.voteComment(commentId, value)
        val key = commentsKey(postId, rootCommentId, focusCommentId)
        val current = getDocument<CommentTree>(key)
            ?: return refreshComments(postId, rootCommentId, focusCommentId)
        return current.copy(
            roots = CommentTreeEditor.updateVote(current.roots, commentId, result.value, result.score),
        ).also { putDocument(key, json.encodeToString(it)) }
    }

    suspend fun reshare(postId: String, subreddit: String): CreatedPost = api.reshare(postId, subreddit)

    suspend fun loadMoreComments(
        postId: String,
        cursorId: String,
        rootCommentId: String? = null,
        focusCommentId: String? = null,
    ): CommentTree? {
        val current = getDocument<CommentTree>(commentsKey(postId, rootCommentId, focusCommentId)) ?: return null
        val cursor = findCommentCursor(current.roots, cursorId) ?: return current
        return loadMoreComments(postId, cursor, rootCommentId, focusCommentId)
    }

    suspend fun search(request: SearchRequest, cursor: String? = null): SearchPage {
        val key = "search:${request.cacheKey}:${cursor.orEmpty()}"
        return try {
            api.search(request, cursor).also { putDocument(key, json.encodeToString(it)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            getDocument<SearchPage>(key) ?: throw error
        }
    }

    /** Flair options are small, community-scoped documents and remain available offline. */
    suspend fun postFlairs(subreddit: String): List<PostFlair> {
        val normalized = subreddit.trim().removePrefix("r/").lowercase()
        val key = "post-flairs:$normalized"
        return try {
            api.postFlairs(normalized).also { putDocument(key, json.encodeToString(it)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            getDocument<List<PostFlair>>(key) ?: throw error
        }
    }

    suspend fun typeahead(query: String): SearchTypeahead {
        val normalized = query.trim().lowercase()
        require(normalized.length >= 2)
        val key = "search:typeahead:$normalized"
        return try {
            api.typeahead(query.trim()).also { putDocument(key, json.encodeToString(it)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            getDocument<SearchTypeahead>(key) ?: throw error
        }
    }

    suspend fun recordRecentSearch(query: String) {
        val trimmed = query.trim().take(100)
        if (trimmed.length < 2) return
        val account = accountScope()
        searchDao.putRecent(SearchRecentEntity(account, trimmed.lowercase(), trimmed, platformEpochMillis()))
        searchDao.trimRecent(account, MAX_RECENT_SEARCHES)
    }

    suspend fun deleteRecentSearch(query: String) {
        searchDao.deleteRecent(accountScope(), query.trim().lowercase())
    }

    suspend fun clearRecentSearches() {
        searchDao.clearRecent(accountScope())
    }

    suspend fun discover(): SearchDiscover = try {
        refreshDiscover()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        getDocument<SearchDiscover>("search:discover") ?: throw error
    }

    /** Network-authoritative discovery refresh; callers retain Room content when this throws. */
    suspend fun refreshDiscover(): SearchDiscover =
        api.discover().also { putDocument("search:discover", json.encodeToString(it)) }

    suspend fun cachedDiscover(): SearchDiscover? = getDocument("search:discover")

    suspend fun refreshCommunityDrawer(force: Boolean = false) {
        val account = accountScope()
        if (account == CacheScope.DEFAULT_ACCOUNT_ID) return
        val dao = database.communityDrawerDao()
        val before = dao.syncState(account)
        if (!force && before != null && platformEpochMillis() - before.lastSuccessfulSyncAt < COMMUNITY_FRESH_MS) {
            return
        }
        val memberships = mutableListOf<DrawerCommunity>()
        var recents = emptyList<RecentCommunity>()
        var cursor: String? = null
        var validator = before?.validator
        do {
            val page = api.communityDrawer(cursor)
            memberships += page.communities
            if (cursor == null) recents = page.recentlyVisited
            validator = page.validator
            cursor = page.nextCursor
        } while (cursor != null)
        val syncedAt = platformEpochMillis()
        dao.replaceRemoteSnapshot(
            accountId = account,
            memberships = memberships.map { item ->
                CommunityMembershipEntity(
                    account, item.id, item.name.lowercase(), item.displayName, item.accessType,
                    item.role, "remote", syncedAt,
                )
            },
            visits = recents.map { item ->
                CommunityVisitEntity(account, item.id, item.name.lowercase(), item.displayName, item.visitedAt)
            },
            sync = CommunityDrawerSyncEntity(account, validator, syncedAt),
            preserveLocalVisits = dao.pendingMutationCount(account) > 0,
        )
    }

    suspend fun recordCommunityVisit(name: String, displayName: String? = null) {
        val account = accountScope()
        if (account == CacheScope.DEFAULT_ACCOUNT_ID) return
        val normalized = normalizeCommunity(name)
        val membership = communityDrawer.value.communities.firstOrNull { it.name == normalized }
        val recent = communityDrawer.value.recentlyVisited.firstOrNull { it.name == normalized }
        val dao = database.communityDrawerDao()
        val visitedAt = maxOf(
            platformEpochMillis(),
            (dao.latestMutationTime(account) ?: Long.MIN_VALUE) + 1,
        )
        dao.recordVisit(
            CommunityVisitEntity(
                account,
                membership?.id ?: recent?.id ?: normalized,
                normalized,
                displayName?.takeIf(String::isNotBlank)
                    ?: membership?.displayName ?: recent?.displayName ?: normalized,
                visitedAt,
            ),
            CommunityVisitMutationEntity(
                platformMutationUuid("community-visit"), account, "visit", normalized,
                visitedAt, visitedAt,
            ),
        )
        try {
            syncPendingCommunityVisits()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            Unit
        }
    }

    /** Room-first recent removal; the command outbox survives process death and offline use. */
    suspend fun removeCommunityVisit(name: String) {
        val account = accountScope()
        if (account == CacheScope.DEFAULT_ACCOUNT_ID) return
        val normalized = normalizeCommunity(name)
        val dao = database.communityDrawerDao()
        val occurredAt = maxOf(
            platformEpochMillis(),
            (dao.latestMutationTime(account) ?: Long.MIN_VALUE) + 1,
        )
        dao.removeVisit(
            account,
            normalized,
            CommunityVisitMutationEntity(
                platformMutationUuid("community-visit"), account, "remove", normalized,
                occurredAt, occurredAt,
            ),
        )
        try {
            syncPendingCommunityVisits()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            Unit
        }
    }

    /** Room-first bulk clear represented by one idempotent visit command. */
    suspend fun clearCommunityVisits() {
        val account = accountScope()
        if (account == CacheScope.DEFAULT_ACCOUNT_ID) return
        val dao = database.communityDrawerDao()
        val occurredAt = maxOf(
            platformEpochMillis(),
            (dao.latestMutationTime(account) ?: Long.MIN_VALUE) + 1,
        )
        dao.clearVisits(
            account,
            CommunityVisitMutationEntity(
                platformMutationUuid("community-visit"), account, "clear", null,
                occurredAt, occurredAt,
            ),
        )
        try {
            syncPendingCommunityVisits()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            Unit
        }
    }

    suspend fun community(name: String, force: Boolean = false): CommunityDetail {
        val normalized = normalizeCommunity(name)
        val cached = cachedCommunity(normalized)
        if (!force && cached != null && platformEpochMillis() - cached.updatedAt < COMMUNITY_FRESH_MS) {
            return cached
        }
        val timer = performanceTimer()
        return try {
            api.community(normalized).also { cacheCommunity(it) }
                .also { PerformanceTelemetry.duration(
                    PerformanceMetric.COMMUNITY_INITIAL_FETCH, timer, PerformanceSurface.COMMUNITY,
                ) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (force) throw error
            cached ?: throw error
        }
    }

    /** Room-backed community detail stream used by focused and application-level controllers. */
    fun observeCommunity(name: String): Flow<CommunityDetail?> {
        val normalized = normalizeCommunity(name)
        return accountScopes
            .flatMapLatest { account ->
                database.communityDetailDao().observe(account, normalized)
            }
            .map { row -> row?.toCommunityDetail(json) }
            .distinctUntilChanged()
    }

    suspend fun cachedCommunity(name: String): CommunityDetail? {
        val normalized = normalizeCommunity(name)
        val row = database.communityDetailDao().get(accountScope(), normalized) ?: return null
        return row.toCommunityDetail(json)
    }

    suspend fun hasCachedCommunityFeed(name: String): Boolean {
        val normalized = normalizeCommunity(name)
        val account = accountScope()
        return feedDao.groupCount(account, CacheScope.communityFeedId(normalized)) > 0 ||
            getDocument<WireFeedPage>("community-feed:$normalized") != null
    }

    suspend fun setCommunityJoined(
        name: String,
        joined: Boolean,
        onLocalCommit: () -> Unit = {},
    ): CommunityDetail {
        val normalized = normalizeCommunity(name)
        val current = community(normalized)
        val account = accountScope()
        val mutationId = platformMutationId("membership")
        val optimistic = current.copy(
            viewerRole = if (joined) "subscriber" else null,
            subscriberCount = (current.subscriberCount + if (joined) 1 else -1).coerceAtLeast(0),
            updatedAt = platformEpochMillis(),
        )
        database.communityDetailDao().commitOptimistic(
            optimistic.toEntity(account, json),
            optimistic.toMembership(account, source = "optimistic"),
            PendingCommunityMembershipEntity(account, normalized, mutationId, joined, platformEpochMillis()),
        )
        // Room is the durability boundary. Schedule platform background work before waiting for an
        // opportunistic foreground acknowledgement so a suspended/killed app cannot strand it.
        runCatching(onLocalCommit)
        return try {
            api.setCommunityJoined(normalized, joined).also { remote ->
                database.communityDetailDao().confirm(
                    remote.toEntity(account, json), remote.toMembership(account), mutationId,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            optimistic
        }
    }

    suspend fun createCommunity(draft: CreateCommunityDraft): MutationOutcome<Subreddit> {
        require(draft.canSubmit) { "Complete the required community fields" }
        val account = accountScope()
        check(account != CacheScope.DEFAULT_ACCOUNT_ID) { "Sign in before creating a community" }
        val mutationId = platformMutationUuid("community")
        val name = draft.normalizedName
        val optimistic = Subreddit(
            id = mutationId,
            name = name,
            displayName = draft.displayName.trim(),
            description = draft.description.trim(),
            accessType = draft.accessType,
            viewerRole = "owner",
        )
        val localTimer = performanceTimer()
        database.subredditOutboxDao().enqueueWithMembership(
            PendingSubredditEntity(
                mutationId, account, name, optimistic.displayName, optimistic.description,
                optimistic.accessType, "queued", null, null, platformEpochMillis(),
            ),
            optimistic.toEntity(account),
            optimistic.toMembership(account, "optimistic"),
        )
        PerformanceTelemetry.duration(
            PerformanceMetric.MUTATION_LOCAL_COMMIT, localTimer, PerformanceSurface.COMMUNITY,
            outcome = PerformanceOutcome.QUEUED,
            attributes = mapOf("mutation_type" to "community_create"),
        )
        val serverTimer = performanceTimer()
        return try {
            val remote = api.createCommunity(
                name, optimistic.displayName, optimistic.description, optimistic.accessType, mutationId,
            )
            database.subredditOutboxDao().completeWithMembership(
                mutationId, remote.toEntity(account), remote.toMembership(account, "remote"),
            )
            PerformanceTelemetry.duration(
                PerformanceMetric.MUTATION_SERVER_ACK, serverTimer, PerformanceSurface.COMMUNITY,
                attributes = mapOf("mutation_type" to "community_create"),
            )
            MutationOutcome(remote, false, mutationId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            database.subredditOutboxDao().updateProgress(
                mutationId,
                "queued",
                error.message ?: "Waiting for a network connection",
            )
            MutationOutcome(optimistic, true, mutationId)
        }
    }

    suspend fun createPost(draft: CreatePostDraft): MutationOutcome<CreatedPost> {
        require(draft.canSubmit) { "Complete the required post fields" }
        val localMedia = draft.localMediaItems.ifEmpty {
            listOfNotNull(
                draft.localMediaPath?.let { path ->
                    LocalPostMedia(
                        draft.localMediaName ?: "media",
                        requireNotNull(draft.localMediaMimeType),
                        path,
                        requireNotNull(draft.localMediaByteSize),
                        draft.mediaWidth,
                        draft.mediaHeight,
                        draft.mediaDurationSeconds,
                    )
                },
            )
        }
        if (draft.kind == PostKind.Image) require(localMedia.size in 1..20)
        if (draft.kind == PostKind.Video) require(localMedia.size == 1)
        val account = accountScope()
        check(account != CacheScope.DEFAULT_ACCOUNT_ID) { "Sign in before creating a post" }
        val mutationId = platformMutationId("post")
        val pending = PendingPostEntity(
            mutationId = mutationId,
            accountId = account,
            subreddit = draft.normalizedSubreddit,
            kind = draft.kind.name,
            title = draft.title.trim(),
            body = draft.body.trim(),
            linkUrl = draft.linkUrl.trim(),
            localPath = null,
            contentType = null,
            byteSize = null,
            width = null,
            height = null,
            durationSeconds = null,
            mediaId = null,
            state = "queued",
            remotePostId = null,
            lastError = null,
            createdAt = platformEpochMillis(),
            mediaItemsJson = json.encodeToString(localMedia.map(::PendingMediaDescriptor)),
            flairId = draft.flair?.id,
            flairText = draft.flair?.text,
            flairBackgroundColor = draft.flair?.backgroundColor,
            flairTextColor = draft.flair?.textColor,
        )
        val localTimer = performanceTimer()
        database.postOutboxDao().upsert(pending)
        PerformanceTelemetry.duration(
            PerformanceMetric.MUTATION_LOCAL_COMMIT, localTimer, PerformanceSurface.CREATE_POST,
            outcome = PerformanceOutcome.QUEUED,
            attributes = mapOf("mutation_type" to "post_create", "content_kind" to draft.kind.name.lowercase()),
        )
        val serverTimer = performanceTimer()
        return try {
            val remote = publishPendingPost(pending)
            PerformanceTelemetry.duration(
                PerformanceMetric.MUTATION_SERVER_ACK, serverTimer, PerformanceSurface.CREATE_POST,
                attributes = mapOf("mutation_type" to "post_create", "content_kind" to draft.kind.name.lowercase()),
            )
            MutationOutcome(remote, false, mutationId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val latest = database.postOutboxDao().get(mutationId)
            database.postOutboxDao().updateProgress(
                mutationId,
                "queued",
                latest?.mediaId,
                error.message ?: "Waiting for a network connection",
            )
            MutationOutcome(
                CreatedPost(
                    mutationId, pending.subreddit, "me", pending.title,
                    pending.body.takeIf(String::isNotBlank), pending.linkUrl.takeIf(String::isNotBlank),
                    flair = draft.flair,
                ),
                true,
                mutationId,
            )
        }
    }

    /** User-driven retry keeps the same idempotency key and resumes uploaded media. */
    suspend fun retryPendingPost(mutationId: String): CreatedPost {
        val dao = database.postOutboxDao()
        val pending = requireNotNull(dao.get(mutationId)) { "Post status is no longer available" }
        dao.retry(mutationId)
        return try {
            publishPendingPost(requireNotNull(dao.get(mutationId)))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val latest = dao.get(mutationId) ?: pending
            dao.updateProgress(
                mutationId,
                "queued",
                latest.mediaId,
                error.message ?: "Waiting for a network connection",
            )
            throw error
        }
    }

    /** Retry an offline-first community command without changing its optimistic identity. */
    suspend fun retryPendingCommunity(mutationId: String): Subreddit {
        val dao = database.subredditOutboxDao()
        val pending = requireNotNull(dao.get(mutationId)) { "Community status is no longer available" }
        dao.updateProgress(mutationId, "queued", null)
        return try {
            val remote = api.createCommunity(
                pending.name,
                pending.displayName,
                pending.description,
                pending.accessType,
                pending.mutationId,
            )
            val account = accountScope()
            dao.completeWithMembership(
                mutationId,
                remote.toEntity(account),
                remote.toMembership(account, "remote"),
            )
            remote
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            dao.updateProgress(
                mutationId,
                "queued",
                error.message ?: "Waiting for a network connection",
            )
            throw error
        }
    }

    suspend fun syncPendingMutations() {
        syncPendingVotes()
        syncPendingMemberships()
        syncPendingCommunities()
        syncPendingCommunityVisits()
        database.postOutboxDao().resumable(accountScope()).forEach { pending ->
            try {
                publishPendingPost(pending)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val latest = database.postOutboxDao().get(pending.mutationId) ?: pending
                database.postOutboxDao().updateProgress(
                    pending.mutationId,
                    "queued",
                    latest.mediaId,
                    error.message ?: "Waiting for a network connection",
                )
                return@forEach
            }
        }
    }

    /** Narrow background-worker entry point; keeps membership retries on the shared HTTP client. */
    suspend fun syncPendingCommunityMemberships() {
        syncPendingMemberships()
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val updated = transform(settings.value)
        database.appSettingsDao().upsert(updated.toEntity(platformEpochMillis()))
    }

    suspend fun user(username: String, force: Boolean = false): UserProfile {
        val normalized = username.trim().removePrefix("u/").lowercase()
        val key = "profile:$normalized"
        val cached = getDocument<UserProfile>(key)
        if (!force && cached != null) return cached
        return try {
            client.user(normalized).also { putDocument(key, json.encodeToString(it)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            cached ?: throw error
        }
    }

    suspend fun updateProfile(
        displayName: String,
        bio: String,
        avatar: LocalPostMedia?,
        removeAvatar: Boolean,
    ): UserProfile {
        val uploaded = avatar?.let { api.uploadMedia(PostKind.Image, it, "$displayName profile photo") }
        val updated = client.updateProfile(
            displayName,
            bio,
            uploaded?.id,
            avatar != null || removeAvatar,
        ).also { profile ->
            putDocument("profile:${profile.username.lowercase()}", json.encodeToString(profile))
        }
        avatar?.let { runCatching { deleteStagedMedia(it.localPath) } }
        return updated
    }

    private suspend fun commitFeedPage(
        account: String,
        feedId: String,
        page: WireFeedPage,
        refresh: Boolean,
    ) {
        database.withWriteTransaction {
            if (refresh) {
                feedDao.clearGroups(account, feedId)
                feedDao.clearRemoteKeys(account, feedId)
            }
            val firstIndex = if (refresh) 0 else feedDao.maxSortIndex(account, feedId) + 1
            feedDao.upsertGroups(page.groups.mapIndexed { index, group ->
                GroupEntity(
                    groupId = group.groupId,
                    sortIndex = firstIndex + index,
                    payloadJson = json.encodeToString(group),
                    accountId = account,
                    feedId = feedId,
                )
            })
            page.groups.forEach { group ->
                group.cells.filterIsInstance<WireCell.ActionBar>().firstOrNull()?.let { action ->
                    feedDao.seedStateIfAbsent(
                        group.groupId, action.score, action.vote == 1 || action.liked,
                        action.vote == -1, account,
                    )
                }
            }
            feedDao.putRemoteKey(RemoteKeyEntity(feedId, page.nextCursor, account))
            feedDao.putSyncMetadata(dev.readthat.data.db.SyncMetadataEntity(
                account, feedId, platformEpochMillis(),
            ))
        }
    }

    private suspend fun toPagedFeedCard(row: GroupWithState): FeedCard? {
        if (row.payloadVersion != GroupEntity.PAYLOAD_VERSION) return null
        val decoded = runCatching { json.decodeFromString<WireGroup>(row.payloadJson) }.getOrNull() ?: return null
        val localScore = row.likeCount
        val group = decoded.copy(cells = decoded.cells.map { cell ->
            if (cell is WireCell.ActionBar && localScore != null) cell.copy(
                score = localScore,
                liked = row.liked == true,
                vote = when {
                    row.liked == true -> 1
                    row.downvoted == true -> -1
                    else -> 0
                },
            ) else cell
        })
        val rendered = FeedFlattener.flatten(
            listOf(group), CellConverterRegistry(), appendDividers = false,
        )
        recordDroppedFeedCells(row, rendered.droppedCellTypes)
        val cells = rendered.items
        if (cells.isEmpty()) return null
        return FeedCard(group.groupId, cells, cells.toPostTransitionPreview(group.groupId))
    }

    private suspend fun recordDroppedFeedCells(row: GroupWithState, dropped: Map<String, Int>) {
        if (dropped.isEmpty()) return
        val unreportedCount = droppedFeedCellsMutex.withLock {
            dropped.entries.sumOf { (type, count) ->
                val identity = "${row.accountId}:${row.feedId}:${row.groupId}:${row.payloadVersion}:$type"
                if (reportedDroppedFeedCells.add(identity)) count else 0
            }.also {
                while (reportedDroppedFeedCells.size > MAX_REPORTED_DROPPED_CELL_IDENTITIES) {
                    reportedDroppedFeedCells.remove(reportedDroppedFeedCells.first())
                }
            }
        }
        if (unreportedCount == 0) return
        PerformanceTelemetry.record(PerformanceEvent(
            name = PerformanceMetric.SDUI_DROPPED_CELL,
            value = unreportedCount.toDouble(),
            unit = PerformanceUnit.COUNT,
            surface = if (row.feedId == CacheScope.HOME_FEED_ID) {
                PerformanceSurface.FEED
            } else {
                PerformanceSurface.COMMUNITY
            },
            outcome = PerformanceOutcome.FAILURE,
            attributes = mapOf(
                "feed_scope" to if (row.feedId == CacheScope.HOME_FEED_ID) "home" else "community",
            ),
            measurements = mapOf("dropped_count" to unreportedCount.toDouble()),
        ))
    }

    private suspend fun cacheCommunity(value: CommunityDetail) {
        database.communityDetailDao().commitRemote(
            value.toEntity(accountScope(), json), value.toMembership(accountScope()),
        )
    }

    private suspend fun syncPendingMemberships() {
        val account = accountScope()
        database.communityDetailDao().pending(account).forEach { pending ->
            try {
                val remote = api.setCommunityJoined(pending.name, pending.desiredJoined)
                database.communityDetailDao().confirm(
                    remote.toEntity(account, json), remote.toMembership(account), pending.mutationId,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return
            }
        }
    }

    private suspend fun syncPendingCommunities() {
        val account = accountScope()
        database.subredditOutboxDao().resumable(account).forEach { pending ->
            try {
                val remote = api.createCommunity(
                    pending.name, pending.displayName, pending.description, pending.accessType,
                    pending.mutationId,
                )
                database.subredditOutboxDao().completeWithMembership(
                    pending.mutationId, remote.toEntity(account), remote.toMembership(account, "remote"),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                database.subredditOutboxDao().updateProgress(
                    pending.mutationId,
                    "queued",
                    error.message ?: "Waiting for a network connection",
                )
                return
            }
        }
    }

    /** Narrow background-task entry point; native schedulers retain lifecycle and constraints. */
    suspend fun syncPendingCommunityVisits() {
        val account = accountScope()
        if (account == CacheScope.DEFAULT_ACCOUNT_ID) return
        val dao = database.communityDrawerDao()
        val pending = dao.pendingMutations(account)
        if (pending.isEmpty()) return
        val commands = pending.map { item ->
            item.mutationId to CommunityVisitCommand(
                item.mutationId.toWireUuid(), item.operation, item.name, item.occurredAt,
            )
        }
        val acknowledged = api.syncCommunityVisits(commands.map { it.second })
        dao.deleteMutations(
            commands.filter { (_, command) -> command.id in acknowledged }.map { it.first },
        )
    }

    private suspend fun publishPendingPost(pending: PendingPostEntity): CreatedPost =
        SharedPostPublicationLane.mutex.withLock {
            val kind = PostKind.entries.first { it.name.equals(pending.kind, true) }
            var media = decodePendingMediaDescriptors(pending, json)
            if (kind == PostKind.Image || kind == PostKind.Video) {
                require(media.isNotEmpty()) { "Media post has no local media" }
                for (index in media.indices) {
                    if (media[index].remoteMediaId != null) continue
                    database.postOutboxDao().updateMediaProgress(
                        pending.mutationId, "uploading", media.firstOrNull()?.remoteMediaId,
                        json.encodeToString(media), null,
                    )
                    val uploaded = api.uploadMedia(kind, media[index].media, pending.title)
                    media = media.toMutableList().apply {
                        this[index] = this[index].copy(remoteMediaId = uploaded.id)
                    }
                    database.postOutboxDao().updateMediaProgress(
                        pending.mutationId, if (index == media.lastIndex) "creating" else "uploading",
                        media.first().remoteMediaId, json.encodeToString(media), null,
                    )
                }
            }
            val post = api.createPost(
                pending.subreddit, kind, pending.title, pending.body, pending.linkUrl,
                mediaIds = media.map { requireNotNull(it.remoteMediaId) },
                flairId = pending.flairId, mutationId = pending.mutationId,
            )
            database.postOutboxDao().complete(pending.mutationId, post.id)
            media.map { it.media.localPath }.distinct().forEach { runCatching { deleteStagedMedia(it) } }
            post
        }

    private suspend fun putDocument(key: String, payload: String) {
        val account = accountScope()
        documents.upsert(CachedDocumentEntity(account, key, payload, platformEpochMillis()))
        documents.pruneToLimit(account, MAX_CACHED_DOCUMENTS)
    }

    private suspend fun updateCachedPostVote(postId: String, score: Int, viewerVote: Int) {
        getDocument<PostHeader>(postKey(postId))?.let { header ->
            putDocument(postKey(postId), json.encodeToString(header.copy(score = score, viewerVote = viewerVote)))
        }
    }

    private fun ItemStateEntity.toVoteSnapshot() = VoteSnapshot(
        score = likeCount,
        viewerVote = when {
            liked -> 1
            downvoted -> -1
            else -> 0
        },
    )

    private suspend inline fun <reified T> getDocument(key: String): T? =
        documents.get(accountScope(), key)?.payloadJson?.let {
            runCatching { json.decodeFromString<T>(it) }.getOrNull()
        }

    private fun accountScope(): String = accountIdOverride
        ?: client.activeAccountId
        ?: CacheScope.DEFAULT_ACCOUNT_ID
    private fun postKey(id: String) = "post:$id"
    private fun commentsKey(
        id: String,
        rootCommentId: String? = null,
        focusCommentId: String? = null,
    ) = when {
        focusCommentId != null -> "comments:$id:focus:$focusCommentId"
        rootCommentId != null -> "comments:$id:root:$rootCommentId"
        else -> "comments:$id"
    }

    private companion object {
        const val FEED_FRESH_MS = SHARED_FEED_FRESH_MILLIS
        const val COMMUNITY_FRESH_MS = 5L * 60 * 1_000
        const val MAX_RECENT_SEARCHES = 10
        const val MAX_CACHED_DOCUMENTS = 512
        const val MAX_REPORTED_DROPPED_CELL_IDENTITIES = 1_024
        const val DOCUMENT_RETENTION_MS = 30L * 24 * 60 * 60 * 1_000
    }
}

internal const val SHARED_FEED_PAGE_SIZE = 20
internal const val SHARED_FEED_MAX_SIZE = 200
internal const val SHARED_FEED_FRESH_MILLIS = 60_000L

internal fun sharedFeedPagingConfig() = PagingConfig(
    pageSize = SHARED_FEED_PAGE_SIZE,
    prefetchDistance = SHARED_FEED_PAGE_SIZE / 2,
    enablePlaceholders = false,
    initialLoadSize = SHARED_FEED_PAGE_SIZE,
    maxSize = SHARED_FEED_MAX_SIZE,
)

internal fun shouldLaunchFeedRefresh(
    cachedCount: Int,
    lastSuccessfulSyncAt: Long,
    nowMillis: Long,
    freshnessMillis: Long = SHARED_FEED_FRESH_MILLIS,
): Boolean = cachedCount <= 0 || nowMillis - lastSuccessfulSyncAt >= freshnessMillis

private fun findCommentCursor(nodes: List<CommentNode>, cursorId: String): CommentNode.LoadMore? {
    val stack = ArrayDeque<CommentNode>().apply { nodes.forEach(::addLast) }
    while (stack.isNotEmpty()) when (val node = stack.removeLast()) {
        is CommentNode.LoadMore -> if (node.id == cursorId) return node
        is CommentNode.Comment -> node.children.forEach(stack::addLast)
    }
    return null
}

private fun OfflineFirstRepository.normalizeCommunity(name: String): String =
    name.trim().removePrefix("r/").lowercase()

private fun CommunityDetail.toEntity(account: String, json: kotlinx.serialization.json.Json) = SubredditEntity(
    accountId = account,
    id = id,
    name = name.lowercase(),
    displayName = displayName,
    description = description,
    accessType = accessType,
    viewerRole = viewerRole,
    subscriberCount = subscriberCount,
    updatedAt = updatedAt,
    avatarUrl = avatarUrl,
    rulesJson = json.encodeToString(rules),
)

private fun SubredditEntity.toCommunityDetail(json: kotlinx.serialization.json.Json) = CommunityDetail(
    id = id,
    name = name,
    displayName = displayName,
    description = description,
    accessType = accessType,
    viewerRole = viewerRole,
    subscriberCount = subscriberCount,
    avatarUrl = avatarUrl,
    rules = runCatching { json.decodeFromString<List<CommunityRule>>(rulesJson) }.getOrDefault(emptyList()),
    updatedAt = updatedAt,
)

private fun CommunityDetail.toMembership(account: String, source: String = "remote") = viewerRole?.let {
    CommunityMembershipEntity(account, id, name.lowercase(), displayName, accessType, it, source, updatedAt)
}

private fun Subreddit.toEntity(account: String) = SubredditEntity(
    account, id, name.lowercase(), displayName, description, accessType, viewerRole,
    subscriberCount, platformEpochMillis(),
)

private fun Subreddit.toMembership(account: String, source: String) = CommunityMembershipEntity(
    account, id, name.lowercase(), displayName, accessType, viewerRole ?: "owner", source,
    platformEpochMillis(),
)

private inline fun MutableStateFlow<FeedState>.update(transform: FeedState.() -> FeedState) {
    value = value.transform()
}

private fun Throwable.userMessage(): String = message?.takeIf(String::isNotBlank) ?: "Something went wrong"

private fun AppSettingsEntity.toDomain() = AppSettings(
    darkTheme, compactPosts, autoplayVideo, autoplayOnMetered, reduceDataOnMetered,
    reduceAnimations, blurMatureMedia,
)

private fun AppSettings.toEntity(now: Long) = AppSettingsEntity(
    darkTheme = darkTheme,
    compactPosts = compactPosts,
    autoplayVideo = autoplayVideo,
    autoplayOnMetered = autoplayOnMetered,
    reduceDataOnMetered = reduceDataOnMetered,
    reduceAnimations = reduceAnimations,
    blurMatureMedia = blurMatureMedia,
    updatedAt = now,
)
