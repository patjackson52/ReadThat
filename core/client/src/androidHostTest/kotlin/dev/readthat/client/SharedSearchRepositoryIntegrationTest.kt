package dev.readthat.client

import android.content.Context
import androidx.paging.testing.asSnapshot
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import dev.readthat.data.db.AppDatabase
import dev.readthat.search.domain.SearchDiscover
import dev.readthat.search.domain.SearchPage
import dev.readthat.search.domain.SearchPost
import dev.readthat.search.domain.SearchRequest
import dev.readthat.search.domain.SearchSections
import dev.readthat.search.domain.SearchType
import dev.readthat.search.domain.SearchTypeahead
import kotlinx.coroutines.flow.first
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
class SharedSearchRepositoryIntegrationTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `recent queries are normalized deduplicated and isolated by account`() = runTest {
        val remote = FakeSharedSearchTransport()
        val first = SharedSearchRepository(database, remote, "first")
        val second = SharedSearchRepository(database, remote, "second")

        first.record("  Kotlin   Multiplatform ")
        first.record("kotlin multiplatform")

        assertEquals(listOf("kotlin multiplatform"), first.recent.first())
        assertTrue(second.recent.first().isEmpty())
    }

    @Test
    fun `all results survive repository recreation through account scoped Room L2`() = runTest {
        val remote = FakeSharedSearchTransport()
        val request = SearchRequest("kmp", SearchType.All)

        val first = SharedSearchRepository(database, remote, "account")
        assertEquals("post-0", (first.all(request).sections!!.posts.single() as SearchPost).id)
        assertEquals(1, remote.searchCalls)

        val recreated = SharedSearchRepository(database, remote, "account")
        assertEquals("post-0", (recreated.all(request).sections!!.posts.single() as SearchPost).id)
        assertEquals(1, remote.searchCalls)

        SharedSearchRepository(database, remote, "another-account").all(request)
        assertEquals(2, remote.searchCalls)
    }

    @Test
    fun `Room Paging appends cursor pages without duplicate identities`() = runTest {
        val remote = FakeSharedSearchTransport()
        val repository = SharedSearchRepository(database, remote, "account")

        val items = repository.paged(SearchRequest("kmp", SearchType.Posts)).asSnapshot {
            scrollTo(25)
        }

        assertEquals(35, items.size)
        assertEquals(2, remote.searchCalls)
        assertEquals(items.size, items.map { it.id }.distinct().size)
    }
}

private class FakeSharedSearchTransport : SharedSearchTransport {
    var searchCalls = 0

    override suspend fun discover() = SearchDiscover()

    override suspend fun typeahead(query: String, limit: Int) = SearchTypeahead(query)

    override suspend fun search(request: SearchRequest, cursor: String?, limit: Int): SearchPage {
        searchCalls += 1
        if (request.type == SearchType.All) {
            return SearchPage(
                query = request.query,
                type = request.type.wire,
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
        subreddit = "kotlin",
        author = "tester",
        kind = "text",
        title = "KMP result $index",
    )
}
