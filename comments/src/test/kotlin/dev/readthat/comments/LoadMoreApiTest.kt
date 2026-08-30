package dev.readthat.comments

import dev.readthat.comments.data.FakeCommentsApi
import dev.readthat.comments.domain.CommentNode
import dev.readthat.comments.domain.CommentTree
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The load-more endpoint, modelled on /api/morechildren: the client sends the id
 * list the cursor carries; the server answers with a FLAT list of comments, each
 * with its own parentId, plus fresh cursors for anything still unsent.
 *
 * Flat-plus-parent-linked is the shape that makes depth underivable locally — the
 * splicer has to re-derive it from the node it attaches to. A nested response would
 * hide that problem; the real API doesn't.
 */
class LoadMoreApiTest {

    private val api = FakeCommentsApi()

    private fun allCommentIds(tree: CommentTree): Set<String> {
        val out = HashSet<String>()
        val stack = ArrayDeque(tree.roots)
        while (stack.isNotEmpty()) {
            when (val n = stack.removeLast()) {
                is CommentNode.Comment -> { out += n.id; n.children.forEach(stack::addLast) }
                is CommentNode.LoadMore -> Unit
            }
        }
        return out
    }

    private fun firstCursor(tree: CommentTree): CommentNode.LoadMore {
        val stack = ArrayDeque(tree.roots)
        while (stack.isNotEmpty()) {
            when (val n = stack.removeLast()) {
                is CommentNode.Comment -> n.children.forEach(stack::addLast)
                is CommentNode.LoadMore -> return n
            }
        }
        error("tree has no cursor — raise totalComments or lower count")
    }

    @Test
    fun `response is flat, parent-linked, and contains the cursor's direct children`() = runTest {
        val tree = api.fetchTree("p1", maxCount = FakeCommentsApi.FIRST_PHASE_COUNT)
        val cursor = firstCursor(tree)
        val have = allCommentIds(tree)

        val response = api.loadMore("p1", cursor, limit = 500)

        val returnedIds = response.comments.map { it.id }.toSet()
        // Every direct child the cursor stood for is present…
        assertTrue(returnedIds.containsAll(cursor.childIds))
        // …and every returned comment links to a parent the client can resolve:
        // either something already on screen or something else in this response.
        for (c in response.comments) {
            assertTrue(
                "orphan ${c.id} -> ${c.parentId}",
                c.parentId == null || c.parentId in have || c.parentId in returnedIds,
            )
        }
    }

    @Test
    fun `limit smaller than remaining returns a fresh cursor for the leftovers`() = runTest {
        val tree = api.fetchTree("p1", maxCount = FakeCommentsApi.FIRST_PHASE_COUNT)
        val cursor = firstCursor(tree)
        check(cursor.remainingCount > 1) { "need a cursor with >1 remaining" }

        val response = api.loadMore("p1", cursor, limit = 1)

        assertEquals(1, response.comments.size)
        assertTrue(response.cursors.isNotEmpty())
        // The fresh cursors name exactly the direct children not yet delivered.
        val redelivered = response.cursors.flatMap { it.childIds }.toSet()
        val delivered = response.comments.map { it.id }.toSet()
        val expectedLeft = cursor.childIds.toSet() - delivered
        assertTrue(redelivered.containsAll(expectedLeft))
        assertTrue(delivered.none { it in redelivered })
    }

    @Test
    fun `never re-sends a comment the client already has`() = runTest {
        val tree = api.fetchTree("p1", maxCount = FakeCommentsApi.FIRST_PHASE_COUNT)
        val cursor = firstCursor(tree)
        val have = allCommentIds(tree)

        val response = api.loadMore("p1", cursor, limit = 500)

        assertFalse(response.comments.any { it.id in have })
    }
}
