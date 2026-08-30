package dev.readthat.comments

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import dev.readthat.comments.data.CommentsRepository
import dev.readthat.comments.data.FakeCommentsApi
import dev.readthat.comments.domain.PostHeader
import dev.readthat.comments.ui.CommentsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coherence: the comments "server" answers about the SAME posts the feed shows.
 * The app injects a catalog (the feed fake is the canonical source); the header
 * comes from it, and the tree size respects its comment count — a post the feed
 * says has 37 comments must not render 220.
 */
class PostCatalogTest {

    private val catalog = { postId: String ->
        when (postId) {
            "post_1" -> PostHeader("post_1", "Title one", "u/op_1", "r/androiddev", 999, 37)
            "post_2" -> PostHeader("post_2", "Title two", "u/op_2", "r/Kotlin", 5, 1_400)
            else -> null
        }
    }

    @Test
    fun `header comes from the injected catalog`() = runTest {
        val api = FakeCommentsApi(postCatalog = catalog)
        assertEquals("Title one", api.fetchPostHeader("post_1").title)
        assertEquals("r/Kotlin", api.fetchPostHeader("post_2").subreddit)
    }

    @Test
    fun `tree size never exceeds the catalog's comment count`() = runTest {
        val api = FakeCommentsApi(postCatalog = catalog)
        val tree = api.fetchTree("post_1", maxCount = 200)
        assertTrue("feed said 37 comments; tree has ${tree.commentCount}",
            tree.commentCount <= 37)
    }

    @Test
    fun `different posts get different comment trees`() = runTest {
        val api = FakeCommentsApi(postCatalog = catalog)
        val t1 = api.fetchTree("post_1", maxCount = 8)
        val t2 = api.fetchTree("post_2", maxCount = 8)
        assertNotEquals(
            t1.roots.map { it.id },
            t2.roots.map { it.id },
        )
    }
}

class SynchronousHeaderTest {
    private val dispatcher = StandardTestDispatcher()
    init { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `header renders with the FIRST content frame when the catalog knows the post`() =
        runTest(dispatcher) {
            val catalog = { id: String ->
                if (id == "post_1") PostHeader("post_1", "T", "u/op", "r/x", 10, 50) else null
            }
            val api = FakeCommentsApi(latencyMs = 100, postCatalog = catalog)
            val repo = CommentsRepository(api)
            // Prefetch-hit case — the one where the gap is visible on screen:
            // comments render at t~0 from memory, while a fetched header would
            // arrive a full network RTT later and shove every row down.
            repo.prefetch("post_1")
            val vm = CommentsViewModel(
                repo,
                SavedStateHandle(mapOf("postId" to "post_1")),
                flattenDispatcher = dispatcher,
            )
            vm.uiState.test {
                var s = awaitItem()
                while (s.isEmpty) s = awaitItem()
                // The first frame that shows comments must already carry the header —
                // a header that pops in later shoves every row down, animated.
                assertEquals("T", s.header?.title)
                cancelAndIgnoreRemainingEvents()
            }
        }
}
