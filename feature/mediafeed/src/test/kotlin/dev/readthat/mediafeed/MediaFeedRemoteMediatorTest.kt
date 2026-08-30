package dev.readthat.mediafeed

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingConfig
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.readthat.mediafeed.data.MediaFeedRemoteMediator
import dev.readthat.mediafeed.data.MediaFeedRemoteSource
import dev.readthat.mediafeed.domain.MediaFeedItem
import dev.readthat.mediafeed.domain.MediaFeedMedia
import dev.readthat.mediafeed.domain.MediaFeedPage
import dev.readthat.data.db.AppDatabase
import dev.readthat.data.db.ItemStateEntity
import dev.readthat.data.db.MediaFeedEntryEntity
import dev.readthat.data.db.MediaFeedRemoteKeyEntity
import dev.readthat.data.db.MediaFeedRow
import dev.readthat.data.db.MediaPostContentEntity
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalPagingApi::class, ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaFeedRemoteMediatorTest {
    private lateinit var db: AppDatabase
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val account = "account"
    private val feed = "media:home:anchor:anchor"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun item(id: String, score: Int = 1) = MediaFeedItem(
        postId = id,
        author = "author",
        subreddit = "media",
        title = "title-$id",
        score = score,
        commentCount = 2,
        kind = "image",
        media = MediaFeedMedia(
            mediaId = "media-$id",
            placeholderColor = 0xff000000,
            aspectRatio = 1f,
            isVideo = false,
            url = "https://example.test/$id.jpg",
            cacheKey = "image:media-$id:etag:detail",
        ),
    )

    private fun state() = PagingState<Int, MediaFeedRow>(
        pages = emptyList(),
        anchorPosition = null,
        config = PagingConfig(pageSize = 8),
        leadingPlaceholderCount = 0,
    )

    private fun mediator(
        remote: MediaFeedRemoteSource,
        preserveInitialSnapshot: Boolean = false,
    ) = MediaFeedRemoteMediator(
        accountId = account,
        feedId = feed,
        anchorPostId = "anchor",
        subreddit = null,
        db = db,
        remote = remote,
        json = json,
        preserveInitialSnapshot = preserveInitialSnapshot,
    )

    @Test
    fun `normal feed snapshot skips stale initial refresh to preserve ordering`() = runTest {
        db.mediaFeedDao().putRemoteKey(MediaFeedRemoteKeyEntity(account, feed, "normal-next", 1))
        val remote = object : MediaFeedRemoteSource {
            override suspend fun loadPage(cursor: String?, anchorPostId: String?, subreddit: String?) =
                error("initialize must not refresh an immutable handoff")
        }

        val action = mediator(remote, preserveInitialSnapshot = true).initialize()

        assertEquals(RemoteMediator.InitializeAction.SKIP_INITIAL_REFRESH, action)
    }

    @Test
    fun `refresh stores the exact anchor first and append advances without duplicates`() = runTest {
        val requests = mutableListOf<String?>()
        val remote = object : MediaFeedRemoteSource {
            override suspend fun loadPage(cursor: String?, anchorPostId: String?, subreddit: String?): MediaFeedPage {
                requests += cursor
                return if (cursor == null) {
                    assertEquals("anchor", anchorPostId)
                    MediaFeedPage(listOf(item("anchor"), item("b")), "next", 1, true)
                } else {
                    MediaFeedPage(listOf(item("b", 99), item("c")), null, 1, false)
                }
            }
        }
        val mediator = mediator(remote)

        val refresh = mediator.load(LoadType.REFRESH, state())
        val append = mediator.load(LoadType.APPEND, state())

        assertFalse((refresh as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertTrue((append as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertEquals(listOf(null, "next"), requests)
        assertEquals(listOf("anchor", "b", "c"), db.mediaFeedDao().postIds(account, feed))
        assertEquals(null, db.mediaFeedDao().remoteKey(account, feed)?.nextCursor)
    }

    @Test
    fun `page state never overwrites a newer optimistic vote`() = runTest {
        val remote = object : MediaFeedRemoteSource {
            override suspend fun loadPage(cursor: String?, anchorPostId: String?, subreddit: String?) =
                MediaFeedPage(listOf(item("anchor", 10)), null, 1, true)
        }
        db.feedDao().putState(ItemStateEntity("anchor", 42, liked = true, accountId = account))

        mediator(remote).load(LoadType.REFRESH, state())

        assertEquals(42, db.feedDao().stateFor("anchor", account)?.likeCount)
        assertTrue(db.feedDao().stateFor("anchor", account)?.liked == true)
    }

    @Test
    fun `failed refresh keeps the navigation seed visible`() = runTest {
        val seed = item("anchor")
        db.mediaFeedDao().upsertContent(listOf(MediaPostContentEntity(
            account, seed.postId, json.encodeToString(MediaFeedItem.serializer(), seed), 1,
        )))
        db.mediaFeedDao().upsertEntries(listOf(MediaFeedEntryEntity(account, feed, seed.postId, 0)))
        val remote = object : MediaFeedRemoteSource {
            override suspend fun loadPage(cursor: String?, anchorPostId: String?, subreddit: String?): MediaFeedPage {
                throw IOException("offline")
            }
        }

        val result = mediator(remote).load(LoadType.REFRESH, state())

        assertTrue(result is RemoteMediator.MediatorResult.Error)
        assertEquals(listOf("anchor"), db.mediaFeedDao().postIds(account, feed))
    }

    @Test
    fun `refresh without a server anchor preserves the navigation seed at page zero`() = runTest {
        val seed = item("anchor")
        db.mediaFeedDao().upsertContent(listOf(MediaPostContentEntity(
            account, seed.postId, json.encodeToString(MediaFeedItem.serializer(), seed), 1,
        )))
        db.mediaFeedDao().upsertEntries(listOf(MediaFeedEntryEntity(account, feed, seed.postId, 0)))
        val remote = object : MediaFeedRemoteSource {
            override suspend fun loadPage(cursor: String?, anchorPostId: String?, subreddit: String?) =
                MediaFeedPage(listOf(item("b"), item("c")), null, 1, anchorIncluded = false)
        }

        val result = mediator(remote).load(LoadType.REFRESH, state())

        assertTrue(result is RemoteMediator.MediatorResult.Success)
        assertEquals(listOf("anchor", "b", "c"), db.mediaFeedDao().postIds(account, feed))
    }

    @Test
    fun `a non advancing cursor fails and preserves the cache`() = runTest {
        db.mediaFeedDao().putRemoteKey(MediaFeedRemoteKeyEntity(account, feed, "same", 1))
        val remote = object : MediaFeedRemoteSource {
            override suspend fun loadPage(cursor: String?, anchorPostId: String?, subreddit: String?) =
                MediaFeedPage(listOf(item("b")), "same", 1, false)
        }

        val result = mediator(remote).load(LoadType.APPEND, state())

        assertTrue(result is RemoteMediator.MediatorResult.Error)
        assertTrue(db.mediaFeedDao().postIds(account, feed).isEmpty())
        assertEquals("same", db.mediaFeedDao().remoteKey(account, feed)?.nextCursor)
    }

    @Test
    fun `competing pagers serialize a scope and re-read its advanced cursor`() = runTest {
        db.mediaFeedDao().putRemoteKey(MediaFeedRemoteKeyEntity(account, feed, "old", 1))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val requests = mutableListOf<String?>()
        val remote = object : MediaFeedRemoteSource {
            override suspend fun loadPage(cursor: String?, anchorPostId: String?, subreddit: String?): MediaFeedPage {
                requests += cursor
                val count = active.incrementAndGet()
                maximumActive.updateAndGet { maxOf(it, count) }
                return try {
                    if (cursor == "old") {
                        entered.complete(Unit)
                        release.await()
                        MediaFeedPage(listOf(item("b")), "new", 1, false)
                    } else {
                        MediaFeedPage(listOf(item("c")), null, 1, false)
                    }
                } finally {
                    active.decrementAndGet()
                }
            }
        }

        val first = async { mediator(remote).load(LoadType.APPEND, state()) }
        entered.await()
        val second = async { mediator(remote).load(LoadType.APPEND, state()) }
        runCurrent()
        assertEquals(1, maximumActive.get())
        release.complete(Unit)
        first.await()
        second.await()

        assertEquals(listOf("old", "new"), requests)
        assertEquals(1, maximumActive.get())
        assertEquals(listOf("b", "c"), db.mediaFeedDao().postIds(account, feed))
    }
}
