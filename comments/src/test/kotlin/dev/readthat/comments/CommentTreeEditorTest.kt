package dev.readthat.comments

import dev.readthat.comments.domain.CommentNode
import dev.readthat.comments.domain.CommentTreeEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CommentTreeEditorTest {
    @Test
    fun `vote and insert path-copy a 1500-level thread without recursion`() {
        var root = comment("c1499")
        for (depth in 1498 downTo 0) root = comment("c$depth", listOf(root))
        val untouched = comment("sibling")
        val roots: List<CommentNode> = listOf(root, untouched)

        val voted = CommentTreeEditor.updateVote(roots, "c1499", value = 1, score = 42)
        val inserted = CommentTreeEditor.insert(voted, "c1499", comment("reply"))

        var current = inserted.first() as CommentNode.Comment
        assertEquals(1500, current.descendantCount)
        repeat(1499) { current = current.children.single() as CommentNode.Comment }
        assertEquals(42, current.score)
        assertEquals(1, current.viewerVote)
        assertEquals("reply", (current.children.single() as CommentNode.Comment).id)
        assertSame(untouched, inserted[1])
    }

    @Test
    fun `optimistic edits adjust authoritative totals only along the ancestor spine`() {
        val loadedChild = comment("loaded").copy(descendantCount = 4)
        val root = comment("root", listOf(loadedChild)).copy(descendantCount = 9)
        val roots: List<CommentNode> = listOf(root)

        val inserted = CommentTreeEditor.insert(roots, "loaded", comment("new"))
        val insertedRoot = inserted.single() as CommentNode.Comment
        val insertedChild = insertedRoot.children.single() as CommentNode.Comment
        assertEquals(10, insertedRoot.descendantCount)
        assertEquals(5, insertedChild.descendantCount)

        val removed = CommentTreeEditor.remove(inserted, "new")
        val removedRoot = removed.single() as CommentNode.Comment
        val removedChild = removedRoot.children.single() as CommentNode.Comment
        assertEquals(9, removedRoot.descendantCount)
        assertEquals(4, removedChild.descendantCount)
    }

    @Test
    fun `missing target leaves roots unchanged`() {
        val roots: List<CommentNode> = listOf(comment("root"))
        assertSame(roots, CommentTreeEditor.updateVote(roots, "missing", 1, 2))
        assertSame(roots, CommentTreeEditor.insert(roots, "missing", comment("reply")))
    }

    private fun comment(
        id: String,
        children: List<CommentNode> = emptyList(),
    ) = CommentNode.Comment(
        id = id,
        author = "author",
        body = "body",
        score = 1,
        createdAgoMin = 1,
        children = children,
    )
}
