package dev.readthat.client

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.paging.cachedIn
import androidx.paging.map
import androidx.room3.withWriteTransaction
import dev.readthat.data.db.AppDatabase
import dev.readthat.data.db.CacheScope
import dev.readthat.data.db.MediaFeedEntryEntity
import dev.readthat.data.db.MediaFeedRemoteKeyEntity
import dev.readthat.data.db.MediaFeedRow
import dev.readthat.data.db.MediaPostContentEntity
import dev.readthat.domain.CellConverterRegistry
import dev.readthat.domain.FeedFlattener
import dev.readthat.domain.NormalFeedMediaContext
import dev.readthat.domain.WireGroup
import dev.readthat.domain.toPostTransitionPreview
import dev.readthat.mediafeed.domain.MediaFeedItem
import dev.readthat.mediafeed.domain.MediaFeedLaunchContext
import dev.readthat.mediafeed.domain.MediaFeedPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

data class SharedMediaFeedScope(
    val anchorPostId: String,
    val subreddit: String? = null,
    val snapshotId: String? = null,
) {
    val databaseId: String = buildString {
        append("media:")
        if (subreddit.isNullOrBlank()) append("home")
        else append("subreddit:").append(subreddit.removePrefix("r/").lowercase())
        append(":anchor:").append(anchorPostId)
        snapshotId?.let { append(":snapshot:").append(it) }
    }
}

fun NormalFeedMediaContext.toSharedMediaFeedLaunchContext(): MediaFeedLaunchContext {
    val mediaItems = items.mapNotNull(MediaFeedItem::fromPreview)
    val mediaAnchorIndex = mediaItems.indexOfFirst { it.postId == anchorPostId }
    check(mediaAnchorIndex >= 0) { "Normal-feed media snapshot lost its anchor" }
    return MediaFeedLaunchContext(
        snapshotId = snapshotId,
        sourceFeedId = sourceFeedId,
        anchorPostId = anchorPostId,
        items = mediaItems,
        anchorIndex = mediaAnchorIndex,
        continuationCursor = nextFeedCursor?.let(::rankedFeedContinuationCursor),
    )
}

/**
 * Lifecycle-agnostic media-feed controller owned by the KMP application ViewModel. Its caller
 * owns [coroutineScope], which makes destination teardown explicit.
 */
class SharedMediaFeedController(
    client: ReadThatClient,
    database: AppDatabase,
    accountId: String,
    mediaScope: SharedMediaFeedScope,
    private val coroutineScope: CoroutineScope,
    launchContext: MediaFeedLaunchContext? = null,
    restoredPage: Int? = null,
    private val onCurrentPageChanged: (Int) -> Unit = {},
    private val votePost: suspend (postId: String, value: Int) -> Unit,
) {
    private val cacheTier = SharedMediaFeedCacheTier(
        initial = "navigation_seed".takeIf { !launchContext?.items.isNullOrEmpty() },
    )
    private val repository = SharedMediaFeedRepository(
        database = database,
        api = ReadThatApi(client),
        scope = mediaScope,
        launchContext = launchContext,
        accountId = accountId,
        onInitialCacheTier = cacheTier::record,
    )
    private val mutableNavigationItems = MutableStateFlow(repository.navigationItems)
    private val mutableCurrentPage = MutableStateFlow(
        (restoredPage ?: repository.initialPage).coerceAtLeast(0),
    )

    val feed: Flow<PagingData<MediaFeedItem>> = repository.feed().cachedIn(coroutineScope)
    val navigationItems: StateFlow<List<MediaFeedItem>> = mutableNavigationItems.asStateFlow()
    val currentPage: StateFlow<Int> = mutableCurrentPage.asStateFlow()
    val initialCacheTier: StateFlow<String?> = cacheTier.value
    val restoredPage: Int get() = mutableCurrentPage.value

    fun setCurrentPage(page: Int) {
        val normalized = page.coerceAtLeast(0)
        if (mutableCurrentPage.value == normalized) return
        mutableCurrentPage.value = normalized
        onCurrentPageChanged(normalized)
    }

    fun releaseNavigationFallback() {
        if (mutableNavigationItems.value.isEmpty()) return
        repository.releaseNavigationFallback()
        mutableNavigationItems.value = emptyList()
    }

    fun vote(postId: String, value: Int) {
        if (postId.isBlank() || value !in -1..1) return
        coroutineScope.launch { votePost(postId, value) }
    }
}

/** Records the first tier capable of painting the media destination; later refreshes cannot
 * relabel a Room- or navigation-backed first frame as network. */
internal class SharedMediaFeedCacheTier(initial: String? = null) {
    private val mutableValue = MutableStateFlow(initial)
    val value: StateFlow<String?> = mutableValue.asStateFlow()

    fun record(tier: String) {
        if (tier.isBlank()) return
        mutableValue.compareAndSet(expect = null, update = tier)
    }
}

@OptIn(ExperimentalPagingApi::class)
internal class SharedMediaFeedRepository(
    private val database: AppDatabase,
    private val api: ReadThatApi,
    private val scope: SharedMediaFeedScope,
    launchContext: MediaFeedLaunchContext?,
    private val accountId: String = CacheScope.DEFAULT_ACCOUNT_ID,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    private val onInitialCacheTier: (String) -> Unit = {},
) {
    private val mediaDao = database.mediaFeedDao()
    private var pendingLaunchContext: MediaFeedLaunchContext? = launchContext
    private var navigationFallback: List<MediaFeedItem> = launchContext?.items.orEmpty()
    private val initialKey: Int? = launchContext?.anchorIndex

    val navigationItems: List<MediaFeedItem> get() = navigationFallback
    val initialPage: Int get() = initialKey ?: 0

    fun feed(): Flow<PagingData<MediaFeedItem>> = flow {
        val launchSeed = pendingLaunchContext
        if (launchSeed != null && launchSeed.items.isNotEmpty()) {
            onInitialCacheTier("navigation_seed")
            seedIfEmpty(launchSeed)
        } else if (mediaDao.entryCount(accountId, scope.databaseId) > 0) {
            onInitialCacheTier("room")
        }
        emitAll(Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                initialLoadSize = PAGE_SIZE,
                prefetchDistance = PAGE_SIZE - 2,
                enablePlaceholders = true,
                maxSize = MAX_IN_MEMORY_ITEMS,
            ),
            initialKey = initialKey,
            remoteMediator = SharedMediaFeedRemoteMediator(
                accountId = accountId,
                feedId = scope.databaseId,
                anchorPostId = scope.anchorPostId,
                subreddit = scope.subreddit,
                database = database,
                api = api,
                json = json,
                preserveInitialSnapshot = scope.snapshotId != null,
                onInitialCacheTier = onInitialCacheTier,
            ),
            pagingSourceFactory = { mediaDao.pagingSource(accountId, scope.databaseId) },
        ).flow.map { paging ->
            paging.map { row ->
                val cached = json.decodeFromString<MediaFeedItem>(row.payloadJson)
                val vote = when {
                    row.liked == true -> 1
                    row.downvoted == true -> -1
                    else -> 0
                }
                row.likeCount?.let { cached.copy(score = it, viewerVote = vote) } ?: cached
            }
        })
    }

    fun releaseNavigationFallback() {
        navigationFallback = emptyList()
        pendingLaunchContext = null
    }

    private suspend fun seedIfEmpty(context: MediaFeedLaunchContext) {
        if (mediaDao.entryCount(accountId, scope.databaseId) > 0) return
        val now = platformEpochMillis()
        database.withWriteTransaction {
            mediaDao.upsertContent(context.items.map { item ->
                MediaPostContentEntity(
                    accountId = accountId,
                    postId = item.postId,
                    payloadJson = json.encodeToString(MediaFeedItem.serializer(), item),
                    updatedAt = now,
                )
            })
            mediaDao.upsertEntries(context.items.mapIndexed { index, item ->
                MediaFeedEntryEntity(
                    accountId = accountId,
                    feedId = scope.databaseId,
                    postId = item.postId,
                    position = index.toLong(),
                )
            })
            context.items.forEach { item ->
                database.feedDao().seedStateIfAbsent(
                    accountId = accountId,
                    itemId = item.postId,
                    likeCount = item.score,
                    liked = item.viewerVote == 1,
                    downvoted = item.viewerVote == -1,
                )
            }
            mediaDao.putRemoteKey(MediaFeedRemoteKeyEntity(
                accountId = accountId,
                feedId = scope.databaseId,
                nextCursor = context.continuationCursor,
                updatedAt = now,
            ))
            mediaDao.pruneOldEntries(accountId, scope.databaseId)
            mediaDao.pruneOldRemoteKeys(accountId, scope.databaseId)
            mediaDao.pruneUnreferencedContent(accountId)
        }
    }

    private companion object {
        const val PAGE_SIZE = 8
        const val MAX_IN_MEMORY_ITEMS = 80
    }
}

@OptIn(ExperimentalPagingApi::class)
internal class SharedMediaFeedRemoteMediator(
    private val accountId: String,
    private val feedId: String,
    private val anchorPostId: String,
    private val subreddit: String?,
    private val database: AppDatabase,
    private val api: ReadThatApi,
    private val json: Json,
    private val nowMillis: () -> Long = ::platformEpochMillis,
    private val freshnessMillis: Long = DEFAULT_FRESHNESS_MILLIS,
    private val preserveInitialSnapshot: Boolean = false,
    private val onInitialCacheTier: (String) -> Unit = {},
) : RemoteMediator<Int, MediaFeedRow>() {
    private val mediaDao = database.mediaFeedDao()

    override suspend fun initialize(): InitializeAction {
        val key = mediaDao.remoteKey(accountId, feedId) ?: return InitializeAction.LAUNCH_INITIAL_REFRESH
        if (preserveInitialSnapshot) return InitializeAction.SKIP_INITIAL_REFRESH
        return if (nowMillis() - key.updatedAt < freshnessMillis) {
            InitializeAction.SKIP_INITIAL_REFRESH
        } else {
            InitializeAction.LAUNCH_INITIAL_REFRESH
        }
    }

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, MediaFeedRow>,
    ): MediatorResult {
        val lockIndex = ((31 * accountId.hashCode() + feedId.hashCode()) and Int.MAX_VALUE) %
            scopeLocks.size
        return scopeLocks[lockIndex].withLock {
            val cursor = when (loadType) {
                LoadType.PREPEND -> return@withLock MediatorResult.Success(true)
                LoadType.REFRESH -> null
                LoadType.APPEND -> mediaDao.remoteKey(accountId, feedId)?.nextCursor
                    ?: return@withLock MediatorResult.Success(true)
            }
            try {
                val page = loadRemotePage(cursor, if (cursor == null) anchorPostId else null)
                if (cursor != null && page.nextCursor == cursor) error("Media feed cursor did not advance")
                val existingIds = if (loadType == LoadType.APPEND) {
                    mediaDao.postIds(accountId, feedId).toHashSet()
                } else emptySet()
                val preserveNavigationSeed = loadType == LoadType.REFRESH &&
                    !page.anchorIncluded && anchorPostId in mediaDao.postIds(accountId, feedId)
                val uniqueItems = page.items.distinctBy(MediaFeedItem::postId)
                    .filterNot { it.postId in existingIds }
                    .filterNot { preserveNavigationSeed && it.postId == anchorPostId }
                database.withWriteTransaction {
                    if (loadType == LoadType.REFRESH) {
                        mediaDao.clearEntries(accountId, feedId)
                        mediaDao.clearRemoteKey(accountId, feedId)
                        if (preserveNavigationSeed) {
                            mediaDao.upsertEntries(listOf(MediaFeedEntryEntity(
                                accountId = accountId,
                                feedId = feedId,
                                postId = anchorPostId,
                                position = 0,
                            )))
                        }
                    }
                    val start = if (loadType == LoadType.REFRESH) {
                        if (preserveNavigationSeed) 1L else 0L
                    } else mediaDao.maxPosition(accountId, feedId) + 1L
                    val now = nowMillis()
                    mediaDao.upsertContent(uniqueItems.map { item ->
                        MediaPostContentEntity(
                            accountId = accountId,
                            postId = item.postId,
                            payloadJson = json.encodeToString(MediaFeedItem.serializer(), item),
                            updatedAt = now,
                        )
                    })
                    mediaDao.upsertEntries(uniqueItems.mapIndexed { index, item ->
                        MediaFeedEntryEntity(accountId, feedId, item.postId, start + index)
                    })
                    uniqueItems.forEach { item ->
                        database.feedDao().seedStateIfAbsent(
                            itemId = item.postId,
                            likeCount = item.score,
                            liked = item.viewerVote == 1,
                            downvoted = item.viewerVote == -1,
                            accountId = accountId,
                        )
                    }
                    mediaDao.putRemoteKey(MediaFeedRemoteKeyEntity(
                        accountId,
                        feedId,
                        page.nextCursor,
                        now,
                    ))
                    mediaDao.pruneOldEntries(accountId, feedId)
                    mediaDao.pruneOldRemoteKeys(accountId, feedId)
                    mediaDao.pruneUnreferencedContent(accountId)
                }
                if (loadType == LoadType.REFRESH) onInitialCacheTier("network")
                MediatorResult.Success(page.nextCursor == null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                MediatorResult.Error(error)
            }
        }
    }

    private suspend fun loadRemotePage(cursor: String?, anchor: String?): MediaFeedPage {
        val rankedCursor = cursor?.removePrefix(RANKED_FEED_CURSOR_PREFIX)
            ?.takeIf { cursor.startsWith(RANKED_FEED_CURSOR_PREFIX) }
        if (rankedCursor != null) return loadRankedFeedPage(rankedCursor, anchor)
        return try {
            api.mediaFeed(cursor, anchor, subreddit)
        } catch (error: ReadThatHttpException) {
            if (error.status != 404 || cursor != null) throw error
            loadRankedFeedPage(null, anchor)
        }
    }

    private suspend fun loadRankedFeedPage(initialCursor: String?, anchor: String?): MediaFeedPage {
        val items = mutableListOf<MediaFeedItem>()
        var cursor = initialCursor
        var pagesRead = 0
        do {
            val page = api.feed(cursor, subreddit)
            items += page.groups.mapNotNull(WireGroup::toSharedMediaFeedItemOrNull)
            cursor = page.nextCursor
            pagesRead += 1
        } while (items.size < PAGE_SIZE && cursor != null && pagesRead < MAX_RANKED_PAGES)
        val unique = items.distinctBy(MediaFeedItem::postId).toMutableList()
        val anchorIndex = unique.indexOfFirst { it.postId == anchor }
        val anchorIncluded = anchorIndex >= 0
        if (anchorIndex > 0) unique.add(0, unique.removeAt(anchorIndex))
        return MediaFeedPage(
            items = unique,
            nextCursor = cursor?.let(::rankedFeedContinuationCursor),
            snapshotAt = nowMillis(),
            anchorIncluded = anchorIncluded,
        )
    }

    private companion object {
        const val DEFAULT_FRESHNESS_MILLIS = 5 * 60 * 1_000L
        const val PAGE_SIZE = 8
        const val MAX_RANKED_PAGES = 4
        val scopeLocks = Array(64) { Mutex() }
    }
}

private const val RANKED_FEED_CURSOR_PREFIX = "ranked-feed-v1:"
private fun rankedFeedContinuationCursor(cursor: String): String = RANKED_FEED_CURSOR_PREFIX + cursor

private fun WireGroup.toSharedMediaFeedItemOrNull(): MediaFeedItem? {
    val cells = FeedFlattener.flatten(
        listOf(this),
        CellConverterRegistry(),
        appendDividers = false,
    ).items
    return MediaFeedItem.fromPreview(cells.toPostTransitionPreview(groupId))
}
