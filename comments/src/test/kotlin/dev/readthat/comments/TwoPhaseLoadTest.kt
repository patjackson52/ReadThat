package dev.readthat.comments

import app.cash.turbine.test
import dev.readthat.comments.data.CommentsRepository
import dev.readthat.comments.data.FakeCommentsApi
import dev.readthat.comments.domain.CommentFlattener
import dev.readthat.comments.domain.CommentNode
import dev.readthat.comments.domain.CommentRow
import dev.readthat.comments.domain.CommentTree
import dev.readthat.comments.domain.CommentTreeMerger
import androidx.lifecycle.SavedStateHandle
import dev.readthat.comments.ui.CommentsViewModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@kotlinx.coroutines.ExperimentalCoroutinesApi
class TwoPhaseLoadTest {

    private fun c(id: String, vararg children: CommentNode) = CommentNode.Comment(
        id = id, author = "u/$id", body = "b", score = 100, children = children.toList(),
    )

    private fun tree(count: Int, vararg roots: CommentNode) =
        CommentTree("p1", roots.toList(), requestedCount = count, requestedDepth = 10)

    // --- the server's tree builder ------------------------------------------

    @Test
    fun `requested count bounds the number of comments returned`() = runTest {
        val api = FakeCommentsApi()

        val small = api.fetchTree("p1", maxCount = 8)
        val large = api.fetchTree("p1", maxCount = 200)

        assertEquals(8, small.commentCount)
        assertTrue(large.commentCount > small.commentCount)
    }

    @Test
    fun `truncation produces load more cursors rather than silently dropping replies`() =
        runTest {
            val api = FakeCommentsApi()
            val small = api.fetchTree("p1", maxCount = 8)

            val cursors = CommentFlattener.flatten(small).rows
                .filterIsInstance<CommentRow.LoadMore>()

            assertTrue("a truncated tree must tell the user more exists", cursors.isNotEmpty())
        }

    @Test
    fun `depth cap converts deeper replies into more replies cursors`() = runTest {
        val api = FakeCommentsApi()
        val shallow = api.fetchTree("p1", maxCount = 200, maxDepth = 2)

        val maxDepth = CommentFlattener.flatten(shallow).rows
            .filterIsInstance<CommentRow.Comment>()
            .maxOf { it.renderDepth }

        assertTrue("no comment may render deeper than the cap", maxDepth <= 2)
    }

    @Test
    fun `the tree builder is deterministic for a given seed`() = runTest {
        val a = FakeCommentsApi(seed = 3).fetchTree("p1", 8)
        val b = FakeCommentsApi(seed = 3).fetchTree("p1", 8)
        assertEquals(a, b)
    }

    // --- the merge, and the flicker it has to prevent -----------------------

    @Test
    fun `a bigger tree really does expand children under previously-childless nodes`() =
        runTest {
            // This is the premise of the whole bug — assert it actually happens.
            val api = FakeCommentsApi()
            val small = api.fetchTree("p1", maxCount = 8)
            val large = api.fetchTree("p1", maxCount = 200)

            val smallLeaves = CommentFlattener.flatten(small).rows
                .filterIsInstance<CommentRow.Comment>()
                .filterNot { it.hasChildren }
                .map { it.key }
                .toSet()

            val largeWithKids = CommentFlattener.flatten(large).rows
                .filterIsInstance<CommentRow.Comment>()
                .filter { it.hasChildren }
                .map { it.key }
                .toSet()

            assertTrue(
                "some node that was a leaf at count=8 gains children at count=200",
                smallLeaves.intersect(largeWithKids).isNotEmpty(),
            )
        }

    @Test
    fun `merge collapses newly-expanded nodes so nothing pops open under the user`() {
        val small = tree(8, c("a"), c("b"))                 // a and b are leaves
        val large = tree(200, c("a", c("a1"), c("a2")), c("b"), c("z"))

        val result = CommentTreeMerger.merge(small, large)

        assertTrue("a was a leaf and gained children -> keep it collapsed",
            "a" in result.autoCollapsedIds)

        val rows = CommentFlattener.flatten(result.tree, result.autoCollapsedIds).rows
        assertEquals("a's new children must not appear", listOf("a", "b", "z"), rows.map { it.key })
    }

    @Test
    fun `merge preserves the order of comments the user is already looking at`() {
        val small = tree(8, c("b"), c("a"))                  // server ranked b first
        val large = tree(200, c("a"), c("b"), c("new"))      // ranking shifted

        val result = CommentTreeMerger.merge(small, large)
        val roots = result.tree.roots.map { (it as CommentNode.Comment).id }

        assertEquals("already-visible roots keep their on-screen order", listOf("b", "a", "new"), roots)
    }

    @Test
    fun `merge never discards a user's own explicit collapse`() {
        val small = tree(8, c("a", c("a1")), c("b"))
        val large = tree(200, c("a", c("a1"), c("a2")), c("b"))

        val userCollapsed = setOf("a")
        val result = CommentTreeMerger.merge(small, large, collapsedIds = userCollapsed)

        // The user's set no longer flows THROUGH the merger — the caller owns it and
        // flattens over the union. What the merger must guarantee is that the union
        // still hides a's subtree after the merge.
        val rows = CommentFlattener.flatten(result.tree, userCollapsed + result.autoCollapsedIds).rows
        assertTrue(rows.any { it.key == "a" })
        assertTrue(rows.none { it.key == "a1" || it.key == "a2" })
    }

    @Test
    fun `merging into nothing is just the incoming tree`() {
        val large = tree(200, c("a"), c("b"))
        val result = CommentTreeMerger.merge(existing = null, incoming = large)
        assertEquals(large, result.tree)
        assertEquals(2, result.addedComments)
    }

    // --- repository: two phases + prefetch ----------------------------------

    @Test
    fun `load emits the small tree first then the full one`() = runTest {
        val repo = CommentsRepository(FakeCommentsApi())

        repo.load("p1").test {
            val first = awaitItem()
            assertTrue(first is CommentsRepository.Phase.Initial)
            assertEquals(8, first.tree.commentCount)
            assertFalse((first as CommentsRepository.Phase.Initial).fromPrefetch)

            val second = awaitItem()
            assertTrue(second is CommentsRepository.Phase.Full)
            assertTrue(second.tree.commentCount > first.tree.commentCount)

            awaitComplete()
        }
    }

    @Test
    fun `the client requests exactly the two sizes the server pre-computes`() = runTest {
        val api = FakeCommentsApi()
        val repo = CommentsRepository(api)

        repo.load("p1").test {
            awaitItem(); awaitItem(); awaitComplete()
        }

        // Requesting the standard sizes is what guarantees the server cache hit.
        assertEquals(listOf(8 to 10, 200 to 10), api.requests)
    }

    @Test
    fun `a prefetched post skips the phase-one network call entirely`() = runTest {
        val api = FakeCommentsApi()
        val repo = CommentsRepository(api)

        repo.prefetch("p1")                       // happened while scrolling the feed
        assertEquals(listOf(8 to 10), api.requests)

        repo.load("p1").test {
            val first = awaitItem() as CommentsRepository.Phase.Initial
            assertTrue("served from the feed prefetch", first.fromPrefetch)
            awaitItem()
            awaitComplete()
        }

        // Only ONE extra call — the 200. The 8 came from cache.
        assertEquals(listOf(8 to 10, 200 to 10), api.requests)
    }

    @Test
    fun `prefetching the same post twice does not double-fetch`() = runTest {
        val api = FakeCommentsApi()
        val repo = CommentsRepository(api)

        repo.prefetch("p1")
        repo.prefetch("p1")

        assertEquals(1, api.requests.size)
    }

    // --- ViewModel ----------------------------------------------------------

    @Test
    fun `uiState renders phase one then refines to phase two without losing shape`() = runTest {
        // Latency matters here: stateIn CONFLATES, so with a zero-latency fake both
        // phases land in the same scheduler pass and the collector only ever sees the
        // final state. Separating them is what makes phase 1 observable at all —
        // which is also the real-world reason phase 1 is worth having.
        val repo = CommentsRepository(FakeCommentsApi(latencyMs = 10))
        val vm = CommentsViewModel(repo, SavedStateHandle(mapOf("postId" to "p1")))

        vm.uiState.test {
            skipItems(1) // initial empty

            var afterFirst = awaitItem()
            while (afterFirst.isEmpty) afterFirst = awaitItem()
            assertEquals(8, afterFirst.totalComments)

            var afterFull = awaitItem()
            while (afterFull.totalComments <= 8) afterFull = awaitItem()
            assertTrue(afterFull.totalComments > 8)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `toggling collapse re-derives the render list without refetching`() = runTest {
        val api = FakeCommentsApi()
        val repo = CommentsRepository(api)
        val vm = CommentsViewModel(repo, SavedStateHandle(mapOf("postId" to "p1")))

        vm.uiState.test {
            var loaded = awaitItem()
            while (loaded.totalComments <= 8) loaded = awaitItem()

            val callsBefore = api.requests.size
            val target = loaded.render.rows
                .filterIsInstance<CommentRow.Comment>()
                .first { it.hasChildren }

            vm.toggleCollapse(target.key)

            var collapsed = awaitItem()
            while (collapsed.render.hiddenByCollapse == 0) collapsed = awaitItem()

            assertTrue(collapsed.render.hiddenByCollapse > 0)
            assertEquals("collapse is pure local state", callsBefore, api.requests.size)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
