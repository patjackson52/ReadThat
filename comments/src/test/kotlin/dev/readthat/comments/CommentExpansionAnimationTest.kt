package dev.readthat.comments

import dev.readthat.comments.domain.CommentRow
import dev.readthat.comments.ui.collapsedCommentCountLabel
import dev.readthat.comments.ui.expansionDescendantKeys
import dev.readthat.comments.ui.nextRootCommentListIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentExpansionAnimationTest {
    @Test
    fun `collapsed count label is concise and handles pluralization`() {
        assertEquals(null, collapsedCommentCountLabel(0))
        assertEquals("Show 1 hidden reply", collapsedCommentCountLabel(1))
        assertEquals("Show 15 hidden replies", collapsedCommentCountLabel(15))
    }

    @Test
    fun `expansion reveal includes only the tapped comment subtree`() {
        val rows = listOf(
            comment("parent", depth = 0),
            comment("child", depth = 1),
            comment("grandchild", depth = 2),
            CommentRow.LoadMore("more", renderDepth = 2, label = "More", parentId = "child"),
            comment("sibling", depth = 0),
            comment("sibling-child", depth = 1),
        )

        assertEquals(
            setOf("child", "grandchild", "more"),
            expansionDescendantKeys(rows, "parent"),
        )
        assertTrue(expansionDescendantKeys(rows, "missing").isEmpty())
        assertTrue(expansionDescendantKeys(rows, null).isEmpty())
    }

    @Test
    fun `next comment targets roots and skips the current root subtree`() {
        val rows = listOf(
            comment("root-a", depth = 0),
            comment("a-child", depth = 1),
            CommentRow.LoadMore("more-a", renderDepth = 1, label = "More", parentId = "root-a"),
            comment("root-b", depth = 0),
            comment("b-child", depth = 1),
            comment("root-c", depth = 0),
        )

        // Lazy index zero is the post header, so the first tap lands on the first root.
        assertEquals(1, nextRootCommentListIndex(rows, firstVisibleListIndex = 0))
        // From either the root or anything in its subtree, advance to the next root block.
        assertEquals(4, nextRootCommentListIndex(rows, firstVisibleListIndex = 1))
        assertEquals(4, nextRootCommentListIndex(rows, firstVisibleListIndex = 2))
        assertEquals(6, nextRootCommentListIndex(rows, firstVisibleListIndex = 4))
        assertEquals(6, nextRootCommentListIndex(rows, firstVisibleListIndex = 5))
        assertEquals(null, nextRootCommentListIndex(rows, firstVisibleListIndex = 6))
    }

    private fun comment(key: String, depth: Int) = CommentRow.Comment(
        key = key,
        renderDepth = depth,
        author = "u/$key",
        authorDisplayName = key,
        authorAvatarUrl = null,
        body = "body",
        scoreLabel = "1",
        ageLabel = "1h",
        isEdited = false,
        isCollapsed = false,
        hasChildren = false,
        collapsedDescendants = 0,
    )
}
