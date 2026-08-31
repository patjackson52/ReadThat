package dev.readthat.client

import dev.readthat.comments.domain.CommentNode
import dev.readthat.comments.domain.CommentRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommentInteractionPolicyTest {
    @Test
    fun expansionRevealsOneLevelAndAutoCollapsesGrandchildren() {
        val tree = listOf(comment("root", listOf(comment("child", listOf(comment("grandchild"))))))
        val collapsed = progressiveCommentCollapse(tree, "root", setOf("root"), emptySet())

        assertEquals(emptySet(), collapsed.userCollapsed)
        assertEquals(setOf("grandchild"), collapsed.autoCollapsed)
    }

    @Test
    fun collapsingTracksExplicitIntentWithoutDisturbingAutoBoundaries() {
        val collapsed = progressiveCommentCollapse(
            roots = listOf(comment("root")),
            commentId = "root",
            userCollapsed = emptySet(),
            autoCollapsed = setOf("elsewhere"),
        )

        assertEquals(setOf("root"), collapsed.userCollapsed)
        assertEquals(setOf("elsewhere"), collapsed.autoCollapsed)
    }

    @Test
    fun viewportSelectsFirstIdleCursorInsidePrefetchWindow() {
        val rows = listOf(
            row("root", 0),
            CommentRow.LoadMore("more-a", 1, "more", "root"),
            row("next", 0),
            CommentRow.LoadMore("more-b", 1, "more", "next"),
        )

        assertEquals("more-a", nextCommentCursorKey(rows, emptyMap(), 0, 1, prefetchDistance = 1))
        assertEquals(
            "more-b",
            nextCommentCursorKey(
                rows,
                mapOf("more-a" to CommentLoadState.Error),
                0,
                4,
                prefetchDistance = 0,
            ),
        )
        assertNull(nextCommentCursorKey(rows, mapOf("more-a" to CommentLoadState.Loading), 0, 1, prefetchDistance = 1))
    }

    private fun comment(id: String, children: List<CommentNode> = emptyList()) = CommentNode.Comment(
        id = id,
        author = id,
        body = id,
        score = 1,
        children = children,
    )

    private fun row(id: String, depth: Int) = CommentRow.Comment(
        key = id,
        renderDepth = depth,
        author = id,
        authorDisplayName = id,
        authorAvatarUrl = null,
        body = id,
        scoreLabel = "1",
        ageLabel = "1m",
        isEdited = false,
        isCollapsed = false,
        hasChildren = false,
        collapsedDescendants = 0,
    )
}
