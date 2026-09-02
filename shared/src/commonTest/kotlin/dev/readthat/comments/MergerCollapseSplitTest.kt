package dev.readthat.comments

import dev.readthat.comments.domain.CommentNode
import dev.readthat.comments.domain.CommentSort
import dev.readthat.comments.domain.CommentTree
import dev.readthat.comments.domain.CommentTreeMerger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * User-collapsed and auto-collapsed are DIFFERENT states and the merger must not
 * conflate them. Auto-collapse is a merger artifact (anti-flicker suppression);
 * user collapse is intent. Only intent gets persisted, only intent gets announced
 * as "Collapsed" to TalkBack, and expand-all clears artifacts without destroying
 * intent.
 */
class MergerCollapseSplitTest {

    private fun c(id: String, vararg children: CommentNode) =
        CommentNode.Comment(id, "u/$id", "body", 100, children = children.toList())

    private fun tree(vararg roots: CommentNode) =
        CommentTree("p1", roots.toList(), 8, 10)

    @Test
    fun `merge reports auto-collapsed ids separately and never echoes the user's set`() {
        val small = tree(c("a"), c("b"))                   // a is a childless leaf
        val large = tree(c("a", c("a1")), c("b"), c("d"))  // a gained a child

        val result = CommentTreeMerger.merge(small, large, collapsedIds = setOf("b"))

        // "a" would pop open -> merger suppresses it, and says so in its OWN field.
        assertEquals(setOf("a"), result.autoCollapsedIds)
        // The user's set is the caller's business; the merger doesn't launder it.
        assertTrue("b" !in result.autoCollapsedIds)
    }

    @Test
    fun `first merge with no existing tree auto-collapses nothing`() {
        val result = CommentTreeMerger.merge(null, tree(c("a", c("a1"))))
        assertEquals(emptySet<String>(), result.autoCollapsedIds)
    }

    @Test
    fun `trees from different sorts replace instead of preserving the previous root order`() {
        val best = tree(c("best-first"), c("top-first"))
        val top = tree(c("top-first"), c("best-first")).copy(sort = CommentSort.Top)

        val result = CommentTreeMerger.merge(best, top)

        assertEquals(top, result.tree)
        assertEquals(emptySet(), result.autoCollapsedIds)
    }
}
