package dev.readthat

import dev.readthat.data.DecodedGroup
import dev.readthat.data.toCells
import dev.readthat.domain.CellUi
import dev.readthat.domain.WireCell
import dev.readthat.domain.WireGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SDUI merge point.
 *
 * These are the tests that justify splitting storage into an opaque payload
 * blob plus a mutable `item_state` row. Each one asserts a property that would
 * be impossible — or would require parsing and rewriting the blob — if the
 * like count lived inside the server payload.
 */
class FeedMergeTest {

    private fun group(id: String, score: Int, liked: Boolean = false) = WireGroup(
        groupId = id,
        cells = listOf(
            WireCell.Title("t", "title-$id"),
            WireCell.ActionBar("bar", score = score, commentCount = 3, liked = liked),
        ),
    )

    private fun List<CellUi>.actionBar() = filterIsInstance<CellUi.ActionBar>().single()

    @Test
    fun `item_state overrides the score the server sent`() {
        val cells = DecodedGroup(group("a", score = 100), likeCount = 101, liked = true).toCells()

        assertEquals("101", cells.actionBar().scoreLabel)
        assertTrue(cells.actionBar().liked)
    }

    @Test
    fun `payload score is used when no state row exists yet`() {
        // A freshly fetched group: the LEFT JOIN produced nulls. It must still
        // render — an INNER JOIN here would make new items invisible.
        val cells = DecodedGroup(group("a", score = 100), likeCount = null, liked = null).toCells()

        assertEquals("100", cells.actionBar().scoreLabel)
        assertFalse(cells.actionBar().liked)
    }

    @Test
    fun `action bar carries the group id, not the cell id, as its write target`() {
        // A like writes against the item. Keys are "groupId/cellId", so the
        // converter has to strip the cell half or every write targets "bar".
        val cells = DecodedGroup(group("post-7", score = 5), likeCount = null, liked = null).toCells()

        assertEquals("post-7", cells.actionBar().itemId)
    }

    @Test
    fun `an undecodable row degrades to no cells instead of throwing`() {
        // Repository.decode() yields group == null for an unreadable or
        // wrong-version blob. One poisoned cached row must not take down the
        // feed — Paging would surface the throw as a load error for the page.
        assertEquals(emptyList<CellUi>(), DecodedGroup(null, 1, true).toCells())
    }

    @Test
    fun `dropped unknown cell types are reported to the telemetry sink`() {
        val withUnknown = WireGroup(
            groupId = "a",
            cells = listOf(
                WireCell.Title("t", "hello"),
                WireCell.Unknown("u1", typeName = "poll"),
                WireCell.Unknown("u2", typeName = "poll"),
            ),
        )
        var reported: Map<String, Int> = emptyMap()

        DecodedGroup(withUnknown, null, null).toCells { reported = it }

        // Keyed by the *server's* type name, not the Kotlin class: "Unknown"
        // would tell the platform team nothing, "poll" tells them which feature
        // this app version cannot render.
        assertEquals(mapOf("poll" to 2), reported)
    }
}
