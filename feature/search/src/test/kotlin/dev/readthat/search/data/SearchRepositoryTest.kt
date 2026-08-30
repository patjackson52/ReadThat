package dev.readthat.search.data

import android.content.Context
import androidx.paging.testing.asSnapshot
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.readthat.search.domain.SearchDiscover
import dev.readthat.search.domain.SearchPage
import dev.readthat.search.domain.SearchPost
import dev.readthat.search.domain.SearchRequest
import dev.readthat.search.domain.SearchSections
import dev.readthat.search.domain.SearchType
import dev.readthat.search.domain.SearchTypeahead
import dev.readthat.data.db.AppDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class SearchRepositoryTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test
    fun `recent terms are normalized deduplicated and reactive`() = runTest {
        val repository = SearchRepository(db, FakeSearchRemote(), "account", StandardTestDispatcher(testScheduler))
        repository.record("  Android   dev ")
        repository.record("android dev")
        assertEquals(listOf("android dev"), repository.recent.first())
        repository.clearRecent()
        assertTrue(repository.recent.first().isEmpty())
    }

    @Test
    fun `all snapshot uses L1 and Room L2 before another request`() = runTest {
        val remote = FakeSearchRemote()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val request = SearchRequest("android", SearchType.All)
        val first = SearchRepository(db, remote, "account", dispatcher)
        assertEquals("post-0", (first.all(request).sections!!.posts.single() as SearchPost).id)
        assertEquals(1, remote.searchCalls)

        val processRestart = SearchRepository(db, remote, "account", dispatcher)
        assertEquals("post-0", (processRestart.all(request).sections!!.posts.single() as SearchPost).id)
        assertEquals(1, remote.searchCalls)
    }

    @Test
    fun `Paging RemoteMediator appends cursor pages through Room`() = runTest {
        val remote = FakeSearchRemote()
        val repository = SearchRepository(
            db,
            remote,
            "account",
            StandardTestDispatcher(testScheduler),
        )
        val items = repository.paged(SearchRequest("android", SearchType.Posts)).asSnapshot {
            scrollTo(25)
        }
        assertEquals(35, items.size)
        assertEquals(2, remote.searchCalls)
        assertEquals(items.size, items.map { it.id }.distinct().size)
    }
}

private class FakeSearchRemote : SearchRemoteSource {
    var searchCalls = 0
    override suspend fun discover() = SearchDiscover()
    override suspend fun typeahead(query: String, limit: Int) = SearchTypeahead(query)

    override suspend fun search(request: SearchRequest, cursor: String?, limit: Int): SearchPage {
        searchCalls++
        if (request.type == SearchType.All) {
            return SearchPage(
                request.query,
                request.type.wire,
                sections = SearchSections(posts = listOf(post(0))),
            )
        }
        val start = cursor?.toInt() ?: 0
        val count = if (start == 0) 20 else 15
        return SearchPage(
            query = request.query,
            type = request.type.wire,
            items = List(count) { post(start + it) },
            nextCursor = if (start == 0) "20" else null,
        )
    }

    private fun post(index: Int) = SearchPost(
        id = "post-$index",
        subreddit = "android",
        author = "tester",
        kind = "text",
        title = "Android result $index",
    )
}
