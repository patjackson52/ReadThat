package dev.readthat.comments

import dev.readthat.comments.data.FakeCommentsApi
import dev.readthat.comments.domain.CommentFlattener
import dev.readthat.comments.domain.CommentNode
import dev.readthat.comments.domain.CommentRow
import dev.readthat.comments.domain.CommentTree
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The depth-cap affordance and the permalink fetch.
 *
 * "Continue this thread" and "x more replies" are DIFFERENT affordances with
 * different intents: load-more expands in place; continue-thread NAVIGATES to a
 * fresh screen re-rooted at the deep comment (Reddit's permalink behavior — the
 * deep comment renders at depth 0 there). Conflating them is the classic mistake.
 */
class ContinueThreadAndRootingTest {

    private fun c(id: String, vararg children: CommentNode) =
        CommentNode.Comment(id, "u/$id", "body", 100, children = children.toList())

    private fun cursor(id: String, parentId: String?) =
        CommentNode.LoadMore(id, parentId, remainingCount = 3, childIds = listOf("z1", "z2", "z3"))

    @Test
    fun `a cursor at the continue-thread depth renders as ContinueThread, not LoadMore`() {
        // chain: d0 -> d1 -> ... -> d9 -> cursor (cursor row lands at depth 10)
        var node: CommentNode = cursor("more_d9", "d9")
        for (i in 9 downTo 0) node = c("d$i", node)
        val deep = CommentTree("p1", listOf(node), 8, 10)

        val rows = CommentFlattener.flatten(deep).rows

        val last = rows.last()
        assertTrue("expected ContinueThread, got $last", last is CommentRow.ContinueThread)
        assertEquals("d9", (last as CommentRow.ContinueThread).parentId)
        // and no LoadMore row was emitted for it
        assertTrue(rows.none { it is CommentRow.LoadMore })
    }

    @Test
    fun `a shallow cursor still renders as LoadMore`() {
        val t = CommentTree("p1", listOf(c("a", cursor("more_a", "a"))), 8, 10)
        val rows = CommentFlattener.flatten(t).rows
        assertTrue(rows.any { it is CommentRow.LoadMore })
        assertTrue(rows.none { it is CommentRow.ContinueThread })
    }

    @Test
    fun `fetching rooted at a comment re-roots depth to zero`() = runTest {
        val api = FakeCommentsApi()
        val full = api.fetchTree("p1", maxCount = 200)
        // pick a comment that has comment children
        val parent = sequence {
            val stack = ArrayDeque(full.roots)
            while (stack.isNotEmpty()) {
                when (val n = stack.removeLast()) {
                    is CommentNode.Comment -> {
                        if (n.children.any { it is CommentNode.Comment }) yield(n)
                        n.children.forEach(stack::addLast)
                    }
                    is CommentNode.LoadMore -> Unit
                }
            }
        }.first()

        val rooted = api.fetchTree("p1", maxCount = 50, rootCommentId = parent.id)

        // roots of the rooted tree are the parent's children — depth restarts at 0
        val rootIds = rooted.roots.filterIsInstance<CommentNode.Comment>().map { it.id }
        val childIds = parent.children.filterIsInstance<CommentNode.Comment>().map { it.id }
        assertTrue(rootIds.isNotEmpty())
        assertTrue(childIds.containsAll(rootIds.take(childIds.size)))
        val rows = CommentFlattener.flatten(rooted).rows
        assertEquals(0, rows.first().renderDepth)
    }

    @Test
    fun `post header is deterministic and carries a comment count`() = runTest {
        val api = FakeCommentsApi()
        val h1 = api.fetchPostHeader("p1")
        val h2 = api.fetchPostHeader("p1")
        assertEquals(h1, h2)
        assertTrue(h1.title.isNotBlank())
        assertTrue(h1.commentCount > 0)
    }
}
