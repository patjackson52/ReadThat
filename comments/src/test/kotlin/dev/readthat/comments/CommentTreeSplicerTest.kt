package dev.readthat.comments

import dev.readthat.comments.data.FakeCommentsApi
import dev.readthat.comments.domain.CommentFlattener
import dev.readthat.comments.domain.CommentNode
import dev.readthat.comments.domain.CommentTree
import dev.readthat.comments.domain.CommentTreeSplicer
import dev.readthat.comments.domain.LoadMoreResponse
import dev.readthat.comments.domain.RawComment
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Splicing a flat, parent-linked load-more response into the tree.
 *
 * The invariants that matter:
 *  - spliced nodes appear AT the spent cursor's position, nested by their own parentId
 *  - render depth of spliced rows is derived from the node they attach to — the
 *    response carries no depth, so the tree is the only source of truth
 *  - nothing that was already on screen moves
 *  - the collapse set is never touched; splicing under a collapsed ancestor is
 *    legal but invisible
 */
class CommentTreeSplicerTest {

    private fun c(id: String, vararg children: CommentNode, score: Int = 100) =
        CommentNode.Comment(id = id, author = "u/$id", body = "body $id", score = score,
            children = children.toList())

    private fun raw(id: String, parentId: String?, viewerVote: Int = 0) =
        RawComment(
            id = id,
            parentId = parentId,
            author = "u/$id",
            body = "body $id",
            score = 50,
            viewerVote = viewerVote,
        )

    private fun cursor(id: String, parentId: String?, childIds: List<String>) =
        CommentNode.LoadMore(id = id, parentId = parentId,
            remainingCount = childIds.size, childIds = childIds)

    private fun tree(vararg roots: CommentNode) =
        CommentTree("p1", roots.toList(), requestedCount = 8, requestedDepth = 10)

    @Test
    fun `spliced comments replace the cursor at its position, nested by parentId`() {
        // a
        //   [more_a: x, y]     <- tapped
        // b
        val t = tree(c("a", cursor("more_a", "a", listOf("x", "y"))), c("b"))
        // response: x and y under a, plus z under x — three nodes, two depths
        val response = LoadMoreResponse(
            comments = listOf(raw("x", "a"), raw("y", "a"), raw("z", "x")),
            cursors = emptyList(),
        )

        val result = CommentTreeSplicer.splice(t, "more_a", response)

        val rows = CommentFlattener.flatten(result).rows
        assertEquals(listOf("a", "x", "z", "y", "b"), rows.map { it.key })
    }

    @Test
    fun `render depth of spliced rows derives from the node they attach to`() {
        val t = tree(c("a", c("a1", cursor("more_a1", "a1", listOf("x")))))
        val response = LoadMoreResponse(
            comments = listOf(raw("x", "a1"), raw("y", "x")),
            cursors = emptyList(),
        )

        val rows = CommentFlattener.flatten(
            CommentTreeSplicer.splice(t, "more_a1", response)
        ).rows

        val byKey = rows.associateBy { it.key }
        assertEquals(0, byKey.getValue("a").renderDepth)
        assertEquals(1, byKey.getValue("a1").renderDepth)
        assertEquals(2, byKey.getValue("x").renderDepth)   // a1.renderDepth + 1 — not from the wire
        assertEquals(3, byKey.getValue("y").renderDepth)
    }

    @Test
    fun `existing rows keep their order and collapse state elsewhere is untouched`() {
        val t = tree(
            c("a", c("a1"), c("a2")),
            c("b", cursor("more_b", "b", listOf("x"))),
            c("d"),
        )
        val response = LoadMoreResponse(listOf(raw("x", "b")), emptyList())
        val collapsed = setOf("a") // user collapsed a; splice happens under b

        val before = CommentFlattener.flatten(t, collapsed).rows.map { it.key }
        val after = CommentFlattener.flatten(
            CommentTreeSplicer.splice(t, "more_b", response), collapsed
        ).rows.map { it.key }

        // every pre-existing key keeps its relative order
        val survivors = after.filter { it in before.toSet() }
        assertEquals(before.filter { it != "more_b" }, survivors)
        // and "a" is still collapsed with its descendants hidden
        assertTrue("a1" !in after && "a2" !in after)
    }

    @Test
    fun `splice under a collapsed ancestor is legal but invisible`() {
        val t = tree(c("p", c("p1", cursor("more_p1", "p1", listOf("x", "y")))))
        val response = LoadMoreResponse(listOf(raw("x", "p1"), raw("y", "p1")), emptyList())
        val collapsed = setOf("p")

        val render = CommentFlattener.flatten(
            CommentTreeSplicer.splice(t, "more_p1", response), collapsed
        )

        assertEquals(listOf("p"), render.rows.map { it.key })
        val p = render.rows.single() as dev.readthat.comments.domain.CommentRow.Comment
        // the spliced comments count toward the hidden-descendant badge
        assertEquals(3, p.collapsedDescendants) // p1 + x + y
    }

    @Test
    fun `unknown cursor id leaves the tree unchanged`() {
        val t = tree(c("a"), c("b"))
        val response = LoadMoreResponse(listOf(raw("x", "a")), emptyList())
        assertEquals(t, CommentTreeSplicer.splice(t, "no_such_cursor", response))
    }

    @Test
    fun `spliced comments retain hydrated viewer votes`() {
        val t = tree(c("a", cursor("more_a", "a", listOf("x"))))
        val response = LoadMoreResponse(listOf(raw("x", "a", viewerVote = -1)), emptyList())

        val row = CommentFlattener.flatten(CommentTreeSplicer.splice(t, "more_a", response))
            .rows.filterIsInstance<dev.readthat.comments.domain.CommentRow.Comment>()
            .first { it.key == "x" }

        assertEquals(-1, row.viewerVote)
    }

    @Test
    fun `end to end - loadMore response splices with no duplicate keys`() = runTest {
        val api = FakeCommentsApi()
        val t = api.fetchTree("p1", maxCount = FakeCommentsApi.FIRST_PHASE_COUNT)
        val spent = generateSequence(t.roots.toList()) { level ->
            level.flatMap { (it as? CommentNode.Comment)?.children ?: emptyList() }
                .takeIf { it.isNotEmpty() }
        }.flatten().filterIsInstance<CommentNode.LoadMore>().first()

        val response = api.loadMore("p1", spent, limit = 50)
        val spliced = CommentTreeSplicer.splice(t, spent.id, response)
        val rows = CommentFlattener.flatten(spliced).rows

        assertTrue(rows.size > CommentFlattener.flatten(t).rows.size)
        // duplicate keys are a CRASH in LazyColumn, not a cosmetic bug
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
    }
}
