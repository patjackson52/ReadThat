package dev.readthat

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingConfig
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.sqlite.execSQL
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.readthat.data.FeedRemoteSource
import dev.readthat.data.PostVoteResult
import dev.readthat.data.db.AppDatabase
import dev.readthat.data.db.CacheScope
import dev.readthat.data.db.GroupEntity
import dev.readthat.data.db.GroupWithState
import dev.readthat.data.db.ItemStateEntity
import dev.readthat.data.db.PendingVoteEntity
import dev.readthat.data.paging.FeedRemoteMediator
import dev.readthat.domain.WireCell
import dev.readthat.domain.WireFeedPage
import dev.readthat.domain.WireGroup
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The network↔database seam, tested against a real Room database.
 *
 * Robolectric is here so `./gradlew test` covers the DB invariants with no
 * emulator: SQLite semantics (`INSERT OR IGNORE`, transactions, ordering) are
 * exactly the part that a fake DAO would let you get wrong silently.
 */
@OptIn(ExperimentalPagingApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeedRemoteMediatorTest {

    private lateinit var db: AppDatabase
    private val json = Json { ignoreUnknownKeys = true }

    /** Records what it was asked for, so "did not hit the network" is assertable. */
    private class FakeRemote(
        private val pages: Map<String?, WireFeedPage>,
    ) : FeedRemoteSource {
        val requested = mutableListOf<String?>()
        val voted = mutableListOf<Triple<String, Int, String>>()
        var failNext = false
        var failVote = false

        override suspend fun loadPage(cursor: String?): WireFeedPage {
            requested += cursor
            if (failNext) throw java.io.IOException("offline")
            return pages[cursor] ?: error("no fake page for cursor=$cursor")
        }

        override suspend fun votePost(postId: String, value: Int, clientMutationId: String): PostVoteResult {
            voted += Triple(postId, value, clientMutationId)
            if (failVote) throw java.io.IOException("offline")
            return PostVoteResult(score = 42, liked = value == 1)
        }
    }

    private fun group(id: String, score: Int = 10) = WireGroup(
        groupId = id,
        cells = listOf(
            WireCell.Title("t", "title-$id"),
            WireCell.ActionBar("bar", score = score, commentCount = 1),
        ),
    )

    private fun emptyState() = PagingState<Int, GroupWithState>(
        pages = emptyList(),
        anchorPosition = null,
        config = PagingConfig(pageSize = 12),
        leadingPlaceholderCount = 0,
    )

    private fun mediator(remote: FeedRemoteSource) =
        FeedRemoteMediator(db = db, remote = remote, json = json)

    private fun mediator(
        remote: FeedRemoteSource,
        accountId: String,
        feedId: String,
    ) = FeedRemoteMediator(
        db = db,
        remote = remote,
        json = json,
        accountId = accountId,
        feedId = feedId,
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `refresh writes the page and stores the cursor`() = runTest {
        val remote = FakeRemote(
            mapOf(null to WireFeedPage(listOf(group("a"), group("b")), nextCursor = "p1")),
        )

        val result = mediator(remote).load(LoadType.REFRESH, emptyState())

        assertTrue(result is RemoteMediator.MediatorResult.Success)
        assertFalse((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertEquals(2, db.feedDao().groupCount())
        assertEquals("p1", db.feedDao().remoteKey(FeedRemoteMediator.DEFAULT_FEED_ID)?.nextCursor)
    }

    @Test
    fun `append follows the stored cursor and lands after the existing rows`() = runTest {
        val remote = FakeRemote(
            mapOf(
                null to WireFeedPage(listOf(group("a"), group("b")), nextCursor = "p1"),
                "p1" to WireFeedPage(listOf(group("c")), nextCursor = "p2"),
            ),
        )
        val mediator = mediator(remote)

        mediator.load(LoadType.REFRESH, emptyState())
        mediator.load(LoadType.APPEND, emptyState())

        assertEquals(listOf(null, "p1"), remote.requested)
        assertEquals(3, db.feedDao().groupCount())
        // sortIndex is what the PagingSource orders by; an append that reused
        // indices 0..n would interleave the new page into the old one.
        assertEquals(listOf("a", "b", "c"), orderedGroupIds())
    }

    @Test
    fun `overlapping ranked pages update in place without moving or duplicating groups`() = runTest {
        val remote = FakeRemote(
            mapOf(
                null to WireFeedPage(listOf(group("a"), group("b"), group("c")), nextCursor = "p1"),
                "p1" to WireFeedPage(
                    // b crossed the keyset boundary after a rank change; d is
                    // duplicated by a malformed/overlapping server page.
                    listOf(group("b", 20), group("d"), group("d", 99), group("e")),
                    nextCursor = null,
                ),
            ),
        )
        val mediator = mediator(remote)

        mediator.load(LoadType.REFRESH, emptyState())
        mediator.load(LoadType.APPEND, emptyState())

        assertEquals(listOf("a", "b", "c", "d", "e"), orderedGroupIds())
        assertEquals(5, db.feedDao().groupCount())
        assertEquals(4, db.feedDao().maxSortIndex(CacheScope.DEFAULT_ACCOUNT_ID, CacheScope.HOME_FEED_ID))
    }

    @Test
    fun `a non advancing append cursor fails without mutating the cache`() = runTest {
        val remote = FakeRemote(
            mapOf(
                null to WireFeedPage(listOf(group("a")), nextCursor = "p1"),
                "p1" to WireFeedPage(listOf(group("b")), nextCursor = "p1"),
            ),
        )
        val mediator = mediator(remote)
        mediator.load(LoadType.REFRESH, emptyState())

        val result = mediator.load(LoadType.APPEND, emptyState())

        assertTrue(result is RemoteMediator.MediatorResult.Error)
        assertEquals(listOf("a"), orderedGroupIds())
        assertEquals("p1", db.feedDao().remoteKey(FeedRemoteMediator.DEFAULT_FEED_ID)?.nextCursor)
    }

    @Test
    fun `room never stores two groups at one sort position`() = runTest {
        val duplicatePosition = listOf(
            GroupEntity("a", 0, json.encodeToString(group("a"))),
            GroupEntity("b", 0, json.encodeToString(group("b"))),
        )

        db.feedDao().upsertGroups(duplicatePosition)

        // Room's generated @Upsert handles a secondary unique-index conflict by
        // ignoring the conflicting insert; the invariant is the stored result,
        // not whether the adapter chooses to surface an exception.
        assertEquals(1, db.feedDao().groupCount())
        assertEquals(0, db.feedDao().maxSortIndex(CacheScope.DEFAULT_ACCOUNT_ID, CacheScope.HOME_FEED_ID))
    }

    @Test
    fun `v8 migration deterministically normalizes legacy duplicate positions`() = runTest {
        AndroidSQLiteDriver().open(":memory:").use { connection ->
            connection.execSQL(
                """
                CREATE TABLE feed_groups (
                    accountId TEXT NOT NULL,
                    feedId TEXT NOT NULL,
                    groupId TEXT NOT NULL,
                    sortIndex INTEGER NOT NULL,
                    payloadJson TEXT NOT NULL,
                    payloadVersion INTEGER NOT NULL,
                    PRIMARY KEY(accountId, feedId, groupId)
                )
                """.trimIndent(),
            )
            listOf("a" to 5, "b" to 5, "c" to 1).forEach { (id, sortIndex) ->
                connection.execSQL(
                    "INSERT INTO feed_groups VALUES " +
                        "('${CacheScope.DEFAULT_ACCOUNT_ID}', '${CacheScope.HOME_FEED_ID}', " +
                        "'$id', $sortIndex, '{}', 1)",
                )
            }

            AppDatabase.MIGRATION_7_8.migrate(connection)

            val positions = buildList {
                connection.prepare(
                    "SELECT groupId, sortIndex FROM feed_groups ORDER BY sortIndex",
                ).use { statement ->
                    while (statement.step()) {
                        add(statement.getText(0) to statement.getLong(1).toInt())
                    }
                }
            }
            assertEquals(listOf("c" to 0, "a" to 1, "b" to 2), positions)
        }
    }

    @Test
    fun `a null cursor ends pagination without another request`() = runTest {
        val remote = FakeRemote(mapOf(null to WireFeedPage(listOf(group("a")), nextCursor = null)))
        val mediator = mediator(remote)

        val refresh = mediator.load(LoadType.REFRESH, emptyState())
        val append = mediator.load(LoadType.APPEND, emptyState())

        assertTrue((refresh as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertTrue((append as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        // One request total: the terminus is remembered in the DB, not re-probed.
        assertEquals(listOf<String?>(null), remote.requested)
    }

    @Test
    fun `prepend ends immediately and never hits the network`() = runTest {
        val remote = FakeRemote(emptyMap())

        val result = mediator(remote).load(LoadType.PREPEND, emptyState())

        assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertTrue(remote.requested.isEmpty())
    }

    @Test
    fun `a failed load is an Error, and the cached rows survive it`() = runTest {
        val remote = FakeRemote(
            mapOf(
                null to WireFeedPage(listOf(group("a"), group("b")), nextCursor = "p1"),
                "p1" to WireFeedPage(emptyList(), nextCursor = null),
            ),
        )
        val mediator = mediator(remote)
        mediator.load(LoadType.REFRESH, emptyState())

        remote.failNext = true
        val result = mediator.load(LoadType.APPEND, emptyState())

        assertTrue(result is RemoteMediator.MediatorResult.Error)
        // The point of DB-as-source-of-truth: an offline append leaves the feed
        // on screen instead of blanking it.
        assertEquals(2, db.feedDao().groupCount())
    }

    @Test
    fun `a page fetch never clobbers a local like`() = runTest {
        val remote = FakeRemote(
            mapOf(
                null to WireFeedPage(listOf(group("a", score = 10)), nextCursor = "p1"),
                "p1" to WireFeedPage(listOf(group("a", score = 10)), nextCursor = null),
            ),
        )
        val mediator = mediator(remote)
        mediator.load(LoadType.REFRESH, emptyState())

        // User likes it; the write touches item_state only.
        db.feedDao().putState(ItemStateEntity("a", likeCount = 11, liked = true))

        // A page fetch that includes the same group arrives afterwards.
        mediator.load(LoadType.APPEND, emptyState())

        val state = db.feedDao().stateFor("a")
        assertEquals(11, state?.likeCount)
        assertTrue(state?.liked == true)
    }

    @Test
    fun `refresh replaces the payloads but preserves the user's state`() = runTest {
        val remote = FakeRemote(
            mapOf(null to WireFeedPage(listOf(group("a", score = 10)), nextCursor = "p1")),
        )
        val mediator = mediator(remote)
        mediator.load(LoadType.REFRESH, emptyState())
        db.feedDao().putState(ItemStateEntity("a", likeCount = 11, liked = true))

        mediator.load(LoadType.REFRESH, emptyState())

        // ⭐ The split earning its keep: pull-to-refresh throws away the server
        // blob wholesale and the like still survives, because it was never in
        // the blob to begin with.
        assertEquals(1, db.feedDao().groupCount())
        assertTrue(db.feedDao().stateFor("a")?.liked == true)
    }

    @Test
    fun `refresh prunes orphan state but preserves queued mutations`() = runTest {
        val remote = FakeRemote(
            mapOf(null to WireFeedPage(listOf(group("current")), nextCursor = null)),
        )
        remote.failVote = true
        db.feedDao().putState(ItemStateEntity("stale", 1, false))
        db.feedDao().putState(ItemStateEntity("queued", 2, true))
        db.feedDao().enqueueVote(PendingVoteEntity("queued", "m1", 1, 1))

        mediator(remote).load(LoadType.REFRESH, emptyState())

        assertNull(db.feedDao().stateFor("stale"))
        assertTrue(db.feedDao().stateFor("queued")?.liked == true)
    }

    @Test
    fun `initialize skips the initial refresh once rows are cached`() = runTest {
        val remote = FakeRemote(
            mapOf(null to WireFeedPage(listOf(group("a")), nextCursor = "p1")),
        )
        val mediator = mediator(remote)

        assertEquals(
            RemoteMediator.InitializeAction.LAUNCH_INITIAL_REFRESH,
            mediator.initialize(),
        )

        mediator.load(LoadType.REFRESH, emptyState())

        // Cold start with a populated DB shows cached content instead of a spinner.
        assertEquals(
            RemoteMediator.InitializeAction.SKIP_INITIAL_REFRESH,
            mediator.initialize(),
        )
    }

    @Test
    fun `refresh clears a stale cursor so pagination restarts from the head`() = runTest {
        val remote = FakeRemote(
            mapOf(
                null to WireFeedPage(listOf(group("a")), nextCursor = "p1"),
                "p1" to WireFeedPage(listOf(group("b")), nextCursor = null),
            ),
        )
        val mediator = mediator(remote)
        mediator.load(LoadType.REFRESH, emptyState())
        mediator.load(LoadType.APPEND, emptyState())
        assertNull(db.feedDao().remoteKey(FeedRemoteMediator.DEFAULT_FEED_ID)?.nextCursor)

        mediator.load(LoadType.REFRESH, emptyState())

        assertEquals("p1", db.feedDao().remoteKey(FeedRemoteMediator.DEFAULT_FEED_ID)?.nextCursor)
        assertEquals(1, db.feedDao().groupCount())
    }

    @Test
    fun `a successful page request drains and confirms the durable vote outbox`() = runTest {
        val remote = FakeRemote(mapOf(null to WireFeedPage(listOf(group("a")), nextCursor = null)))
        db.feedDao().enqueueVote(PendingVoteEntity("a", "mutation-123", 1, createdAt = 1))

        mediator(remote).load(LoadType.REFRESH, emptyState())

        assertEquals(listOf(Triple("a", 1, "mutation-123")), remote.voted)
        assertTrue(db.feedDao().pendingVotes().isEmpty())
        assertEquals(42, db.feedDao().stateFor("a")?.likeCount)
        assertTrue(db.feedDao().stateFor("a")?.liked == true)
    }

    @Test
    fun `offline vote retry stays queued without blocking a cached feed refresh`() = runTest {
        val remote = FakeRemote(mapOf(null to WireFeedPage(listOf(group("a")), nextCursor = null)))
        remote.failVote = true
        db.feedDao().enqueueVote(PendingVoteEntity("a", "mutation-123", 1, createdAt = 1))

        val result = mediator(remote).load(LoadType.REFRESH, emptyState())

        assertTrue(result is RemoteMediator.MediatorResult.Success)
        assertEquals("mutation-123", db.feedDao().pendingVote("a")?.mutationId)
        assertEquals(1, db.feedDao().groupCount())
    }

    @Test
    fun `personalized rows votes and cursors never cross account boundaries`() = runTest {
        val accountA = "account-a"
        val accountB = "account-b"
        val feed = "feed:home"
        val remoteA = FakeRemote(mapOf(null to WireFeedPage(listOf(group("same", 10)), "cursor-a")))
        val remoteB = FakeRemote(mapOf(null to WireFeedPage(listOf(group("same", 90)), "cursor-b")))

        mediator(remoteA, accountA, feed).load(LoadType.REFRESH, emptyState())
        mediator(remoteB, accountB, feed).load(LoadType.REFRESH, emptyState())
        db.feedDao().putState(ItemStateEntity("same", 11, true, accountId = accountA))
        db.feedDao().putState(ItemStateEntity("same", 89, false, downvoted = true, accountId = accountB))

        assertEquals(1, db.feedDao().groupCount(accountA, feed))
        assertEquals(1, db.feedDao().groupCount(accountB, feed))
        assertEquals("cursor-a", db.feedDao().remoteKey(feed, accountA)?.nextCursor)
        assertEquals("cursor-b", db.feedDao().remoteKey(feed, accountB)?.nextCursor)
        assertTrue(db.feedDao().stateFor("same", accountA)?.liked == true)
        assertTrue(db.feedDao().stateFor("same", accountB)?.downvoted == true)
    }

    @Test
    fun `one account can cache independent ranked feed scopes`() = runTest {
        val account = "account-a"
        val home = "feed:home"
        val community = "feed:subreddit:kotlin"

        mediator(
            FakeRemote(mapOf(null to WireFeedPage(listOf(group("home-post")), "home-next"))),
            account,
            home,
        ).load(LoadType.REFRESH, emptyState())
        mediator(
            FakeRemote(mapOf(null to WireFeedPage(listOf(group("community-post")), null))),
            account,
            community,
        ).load(LoadType.REFRESH, emptyState())

        assertEquals(1, db.feedDao().groupCount(account, home))
        assertEquals(1, db.feedDao().groupCount(account, community))
        assertEquals("home-next", db.feedDao().remoteKey(home, account)?.nextCursor)
        assertNull(db.feedDao().remoteKey(community, account)?.nextCursor)
    }

    private suspend fun orderedGroupIds(): List<String> {
        val page = db.feedDao().pagingSource().load(
            androidx.paging.PagingSource.LoadParams.Refresh(
                key = null, loadSize = 50, placeholdersEnabled = false,
            ),
        ) as androidx.paging.PagingSource.LoadResult.Page
        return page.data.map { it.groupId }
    }
}
