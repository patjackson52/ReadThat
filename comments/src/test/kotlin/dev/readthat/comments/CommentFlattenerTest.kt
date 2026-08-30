package dev.readthat.comments

import dev.readthat.comments.domain.CommentFlattener
import dev.readthat.comments.domain.CommentNode
import dev.readthat.comments.domain.CommentRow
import dev.readthat.comments.domain.CommentTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentFlattenerTest {

    private fun c(id: String, vararg children: CommentNode) = CommentNode.Comment(
        id = id, author = "u/$id", body = "body-$id", score = 100, children = children.toList(),
    )

    private fun tree(vararg roots: CommentNode) =
        CommentTree("p1", roots.toList(), requestedCount = 8, requestedDepth = 10)

    @Test
    fun `depth-first order is preserved and depth is carried onto each row`() {
        //  a
        //   ├ b
        //   │  └ c
        //   └ d
        //  e
        val t = tree(c("a", c("b", c("c")), c("d")), c("e"))

        val rows = CommentFlattener.flatten(t).rows

        assertEquals(listOf("a", "b", "c", "d", "e"), rows.map { it.key })
        assertEquals(listOf(0, 1, 2, 1, 0), rows.map { it.renderDepth })
    }

    @Test
    fun `collapsing hides the whole subtree but keeps the collapsed node visible`() {
        val t = tree(c("a", c("b", c("c")), c("d")), c("e"))

        val rows = CommentFlattener.flatten(t, collapsedIds = setOf("a")).rows

        // "a" survives so there is something to tap; b, c, d are gone.
        assertEquals(listOf("a", "e"), rows.map { it.key })
        val a = rows.first() as CommentRow.Comment
        assertTrue(a.isCollapsed)
        assertEquals("all three descendants counted", 3, a.collapsedDescendants)
    }

    @Test
    fun `collapsing a mid-tree node hides only its own subtree`() {
        val t = tree(c("a", c("b", c("c")), c("d")))

        val rows = CommentFlattener.flatten(t, collapsedIds = setOf("b")).rows

        assertEquals(listOf("a", "b", "d"), rows.map { it.key })
        assertEquals(1, (rows[1] as CommentRow.Comment).collapsedDescendants)
    }

    @Test
    fun `hiddenByCollapse totals what the user cannot see`() {
        val t = tree(c("a", c("b", c("c")), c("d")), c("e", c("f")))

        val result = CommentFlattener.flatten(t, collapsedIds = setOf("a", "e"))

        assertEquals(2, result.visibleCommentCount)
        assertEquals(4, result.hiddenByCollapse)
    }

    @Test
    fun `load more cursors render at the depth of the children they replace`() {
        val t = tree(
            c("a", CommentNode.LoadMore("more_a", parentId = "a", remainingCount = 12)),
            CommentNode.LoadMore("more_root", parentId = null, remainingCount = 40),
        )

        val rows = CommentFlattener.flatten(t).rows

        val childCursor = rows[1] as CommentRow.LoadMore
        assertEquals(1, childCursor.renderDepth)
        assertEquals("12 more replies", childCursor.label)

        val rootCursor = rows[2] as CommentRow.LoadMore
        assertEquals(0, rootCursor.renderDepth)
        assertEquals("Load 40 more comments", rootCursor.label)
    }

    @Test
    fun `a collapsed node also hides its load more cursor`() {
        val t = tree(c("a", CommentNode.LoadMore("more_a", "a", 12)))

        val rows = CommentFlattener.flatten(t, collapsedIds = setOf("a")).rows

        assertEquals(listOf("a"), rows.map { it.key })
    }

    @Test
    fun `keys are unique across the whole render list`() {
        val t = tree(c("a", c("b"), c("c")), c("d", c("e")))
        val keys = CommentFlattener.flatten(t).rows.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `deeply nested threads do not blow the stack`() {
        // 5,000 deep — a recursive flattener would StackOverflowError here.
        var node: CommentNode.Comment = c("leaf")
        repeat(5_000) { i -> node = c("n$i", node) }

        val rows = CommentFlattener.flatten(tree(node)).rows

        assertEquals(5_001, rows.size)
        assertEquals(5_000, rows.last().renderDepth)
    }

    @Test
    fun `hasChildren is false for leaves so the UI knows what is tappable`() {
        val t = tree(c("a", c("b")))
        val rows = CommentFlattener.flatten(t).rows

        assertTrue((rows[0] as CommentRow.Comment).hasChildren)
        assertFalse((rows[1] as CommentRow.Comment).hasChildren)
    }

    @Test
    fun `scores are compacted`() {
        assertEquals("999", CommentFlattener.compactScore(999))
        assertEquals("1.2k", CommentFlattener.compactScore(1_234))
        assertEquals("8.9k", CommentFlattener.compactScore(8_912))
    }

    @Test
    fun `identity and edit metadata reach the render row`() {
        val tree = tree(CommentNode.Comment(
            id = "a",
            author = "u/alice",
            authorDisplayName = "Alice Example",
            authorAvatarUrl = "https://cdn.example/alice.jpg",
            isEdited = true,
            body = "body",
            score = 10,
        ))

        val row = CommentFlattener.flatten(tree).rows.single() as CommentRow.Comment

        assertEquals("u/alice", row.author)
        assertEquals("Alice Example", row.authorDisplayName)
        assertEquals("https://cdn.example/alice.jpg", row.authorAvatarUrl)
        assertTrue(row.isEdited)
    }
}

class AgeLabelTest {
    @Test
    fun `rows carry a compact age label formatted from minutes`() {
        val tree = CommentTree(
            "p1",
            listOf(
                CommentNode.Comment("a", "u/a", "body", 10, createdAgoMin = 5),
                CommentNode.Comment("b", "u/b", "body", 10, createdAgoMin = 90),
                CommentNode.Comment("c", "u/c", "body", 10, createdAgoMin = 3000),
            ),
            8, 10,
        )
        val rows = CommentFlattener.flatten(tree).rows.filterIsInstance<CommentRow.Comment>()
        org.junit.Assert.assertEquals(listOf("5m", "1h", "2d"), rows.map { it.ageLabel })
    }
}
