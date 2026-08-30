package dev.readthat.mediafeed.data

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.withTransaction
import dev.readthat.mediafeed.domain.MediaFeedItem
import dev.readthat.data.db.AppDatabase
import dev.readthat.data.db.ItemStateEntity
import dev.readthat.data.db.MediaFeedEntryEntity
import dev.readthat.data.db.MediaFeedRemoteKeyEntity
import dev.readthat.data.db.MediaFeedRow
import dev.readthat.data.db.MediaPostContentEntity
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(ExperimentalPagingApi::class)
class MediaFeedRemoteMediator(
    private val accountId: String,
    private val feedId: String,
    private val anchorPostId: String,
    private val subreddit: String?,
    private val db: AppDatabase,
    private val remote: MediaFeedRemoteSource,
    private val json: Json,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val freshnessMillis: Long = DEFAULT_FRESHNESS_MILLIS,
    /** A normal-feed handoff is an immutable ranked generation, not a refreshable query. */
    private val preserveInitialSnapshot: Boolean = false,
) : RemoteMediator<Int, MediaFeedRow>() {
    private val mediaDao = db.mediaFeedDao()

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
        val lockIndex = ((31 * accountId.hashCode() + feedId.hashCode()) and Int.MAX_VALUE) % scopeLocks.size
        return scopeLocks[lockIndex].withLock {
            // Re-read after acquiring the cross-instance scope lock. A worker or a competing Pager
            // may have advanced the cursor while this load was queued.
            val cursor = when (loadType) {
                LoadType.PREPEND -> return@withLock MediatorResult.Success(endOfPaginationReached = true)
                LoadType.REFRESH -> null
                LoadType.APPEND -> mediaDao.remoteKey(accountId, feedId)?.nextCursor
                    ?: return@withLock MediatorResult.Success(endOfPaginationReached = true)
            }
            try {
                val page = remote.loadPage(cursor, if (cursor == null) anchorPostId else null, subreddit)
                if (cursor != null && page.nextCursor == cursor) {
                    error("Media feed cursor did not advance")
                }
                val existingIds = if (loadType == LoadType.APPEND) {
                    mediaDao.postIds(accountId, feedId).toHashSet()
                } else {
                    emptySet()
                }
                val preserveNavigationSeed = loadType == LoadType.REFRESH &&
                    !page.anchorIncluded &&
                    anchorPostId in mediaDao.postIds(accountId, feedId)
                val uniqueItems = page.items.distinctBy(MediaFeedItem::postId)
                    .filterNot { it.postId in existingIds }
                    .filterNot { preserveNavigationSeed && it.postId == anchorPostId }
                db.withTransaction {
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
                        if (preserveNavigationSeed) 1 else 0
                    } else {
                        mediaDao.maxPosition(accountId, feedId) + 1
                    }
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
                        MediaFeedEntryEntity(
                            accountId = accountId,
                            feedId = feedId,
                            postId = item.postId,
                            position = start + index,
                        )
                    })
                    uniqueItems.forEach { item ->
                        db.feedDao().seedStateIfAbsent(
                            accountId = accountId,
                            itemId = item.postId,
                            likeCount = item.score,
                            liked = item.viewerVote == 1,
                            downvoted = item.viewerVote == -1,
                        )
                    }
                    mediaDao.putRemoteKey(MediaFeedRemoteKeyEntity(
                        accountId = accountId,
                        feedId = feedId,
                        nextCursor = page.nextCursor,
                        updatedAt = now,
                    ))
                    mediaDao.pruneOldEntries(accountId, feedId)
                    mediaDao.pruneOldRemoteKeys(accountId, feedId)
                    mediaDao.pruneUnreferencedContent(accountId)
                }
                MediatorResult.Success(endOfPaginationReached = page.nextCursor == null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                MediatorResult.Error(error)
            }
        }
    }

    companion object {
        const val DEFAULT_FRESHNESS_MILLIS = 5 * 60 * 1_000L
        // Cross-instance serialization without retaining every account/feed scope forever.
        private val scopeLocks = Array(64) { Mutex() }
    }
}
