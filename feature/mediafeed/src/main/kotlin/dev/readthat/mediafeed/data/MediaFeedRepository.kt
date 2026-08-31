package dev.readthat.mediafeed.data

import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.room3.withWriteTransaction
import dev.readthat.mediafeed.domain.MediaFeedItem
import dev.readthat.mediafeed.domain.MediaFeedLaunchContext
import dev.readthat.mediafeed.domain.MediaFeedPage
import dev.readthat.data.db.AppDatabase
import dev.readthat.data.db.CacheScope
import dev.readthat.data.db.MediaFeedEntryEntity
import dev.readthat.data.db.MediaPostContentEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

interface MediaFeedRemoteSource {
    suspend fun loadPage(
        cursor: String?,
        anchorPostId: String?,
        subreddit: String?,
    ): MediaFeedPage

    /** Translate a normal ranked-feed cursor into this source's opaque namespace. */
    fun continuationCursorFromFeed(feedCursor: String): String = feedCursor
}

data class MediaFeedScope(
    val anchorPostId: String,
    val subreddit: String? = null,
    val snapshotId: String? = null,
) {
    val databaseId: String = buildString {
        append("media:")
        if (subreddit.isNullOrBlank()) append("home") else append("subreddit:").append(subreddit.lowercase())
        append(":anchor:").append(anchorPostId)
        snapshotId?.let { append(":snapshot:").append(it) }
    }
}

@OptIn(ExperimentalPagingApi::class)
class MediaFeedRepository(
    private val db: AppDatabase,
    private val remote: MediaFeedRemoteSource,
    private val scope: MediaFeedScope,
    launchContext: MediaFeedLaunchContext? = null,
    private val accountId: String = CacheScope.DEFAULT_ACCOUNT_ID,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    internal val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    private val mediaDao = db.mediaFeedDao()

    private var pendingLaunchContext: MediaFeedLaunchContext? = launchContext
    private var navigationFallback: List<MediaFeedItem> = launchContext?.items.orEmpty()
    private val initialKey: Int? = launchContext?.anchorIndex
    val navigationItems: List<MediaFeedItem> get() = navigationFallback
    val initialPage: Int = initialKey ?: 0

    fun feed(): Flow<PagingData<MediaFeedItem>> = flow {
        withContext(io) { pendingLaunchContext?.let { seedIfEmpty(it) } }
        emitAll(Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                initialLoadSize = PAGE_SIZE,
                // A full next page is requested before the current item approaches the end.
                prefetchDistance = PAGE_SIZE - 2,
                // The launch snapshot has a known Room count. Placeholders keep
                // its absolute indices stable while Paging loads around the anchor.
                enablePlaceholders = true,
                maxSize = MAX_IN_MEMORY_ITEMS,
            ),
            initialKey = initialKey,
            remoteMediator = MediaFeedRemoteMediator(
                accountId = accountId,
                feedId = scope.databaseId,
                anchorPostId = scope.anchorPostId,
                subreddit = scope.subreddit,
                db = db,
                remote = remote,
                json = json,
                preserveInitialSnapshot = scope.snapshotId != null,
            ),
            pagingSourceFactory = { mediaDao.pagingSource(accountId, scope.databaseId) },
        ).flow.map { data ->
            data.map { row ->
                val cached = json.decodeFromString<MediaFeedItem>(row.payloadJson)
                val vote = when {
                    row.liked == true -> 1
                    row.downvoted == true -> -1
                    else -> 0
                }
                val localScore = row.likeCount
                if (localScore == null) cached else cached.copy(
                    score = localScore,
                    viewerVote = vote,
                )
            }
        })
    }

    /** Room/Paging owns the generation once the anchor row is materialized. */
    fun releaseNavigationFallback() {
        navigationFallback = emptyList()
        pendingLaunchContext = null
    }

    private suspend fun seedIfEmpty(context: MediaFeedLaunchContext) {
        if (mediaDao.entryCount(accountId, scope.databaseId) > 0) return
        val now = System.currentTimeMillis()
        db.withWriteTransaction {
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
                db.feedDao().seedStateIfAbsent(
                    accountId = accountId,
                    itemId = item.postId,
                    likeCount = item.score,
                    liked = item.viewerVote == 1,
                    downvoted = item.viewerVote == -1,
                )
            }
            mediaDao.putRemoteKey(dev.readthat.data.db.MediaFeedRemoteKeyEntity(
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
