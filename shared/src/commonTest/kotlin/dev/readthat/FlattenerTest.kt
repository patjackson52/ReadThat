package dev.readthat

import dev.readthat.domain.CellUi
import dev.readthat.domain.FeedFlattener
import dev.readthat.domain.WireAdMediaItem
import dev.readthat.domain.WireAdMediaKind
import dev.readthat.domain.WireCell
import dev.readthat.domain.WireGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlattenerTest {

    private fun postGroup(id: String, extra: List<WireCell> = emptyList()) = WireGroup(
        groupId = id,
        cells = listOf(
            WireCell.Metadata("meta", "androiddev", "2h ago"),
            WireCell.Title("title", "Hello"),
        ) + extra,
    )

    @Test
    fun `flattens nested groups into a single ordered list`() {
        val groups = listOf(postGroup("p1"), postGroup("p2"))

        val result = FeedFlattener.flatten(groups)

        // 2 cells + divider, twice
        assertEquals(6, result.items.size)
        assertTrue(result.items[0] is CellUi.Metadata)
        assertTrue(result.items[1] is CellUi.Title)
        assertTrue(result.items[2] is CellUi.GroupDivider)
        assertTrue(result.items[3] is CellUi.Metadata)
    }

    @Test
    fun `keys are unique across the whole render list`() {
        val groups = listOf(postGroup("p1"), postGroup("p2"), postGroup("p3"))

        val keys = FeedFlattener.flatten(groups).items.map { it.key }

        assertEquals(
            keys.size,
            keys.toSet().size,
            "duplicate keys crash LazyColumn, so this must hold",
        )
    }

    @Test
    fun `identical cell ids in different groups do not collide`() {
        // Both groups use cellId "meta" and "title" — only the composite key saves us.
        val groups = listOf(postGroup("p1"), postGroup("p2"))

        val keys = FeedFlattener.flatten(groups).items.map { it.key }

        assertTrue(keys.contains("p1/meta"))
        assertTrue(keys.contains("p2/meta"))
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `unknown cell types are dropped and counted rather than crashing`() {
        val groups = listOf(
            postGroup("p1", extra = listOf(WireCell.Unknown("poll", "PollCell_v2"))),
            postGroup("p2", extra = listOf(WireCell.Unknown("q", "QuizCell_v1"))),
            postGroup("p3", extra = listOf(WireCell.Unknown("poll2", "PollCell_v2"))),
        )

        val result = FeedFlattener.flatten(groups)

        // The known cells still render.
        assertEquals(9, result.items.size) // (2 known + divider) x 3
        // The unknown ones are reported for telemetry.
        assertEquals(3, result.droppedCount)
        assertEquals(2, result.droppedCellTypes["PollCell_v2"])
        assertEquals(1, result.droppedCellTypes["QuizCell_v1"])
    }

    @Test
    fun `a group that renders nothing does not emit a stray divider`() {
        val groups = listOf(
            WireGroup("ghost", listOf(WireCell.Unknown("x", "TotallyNewCell"))),
            postGroup("p1"),
        )

        val result = FeedFlattener.flatten(groups)

        val dividers = result.items.count { it is CellUi.GroupDivider }
        assertEquals(1, dividers, "only the group that actually rendered should emit a divider")
        assertEquals(1, result.droppedCount)
    }

    @Test
    fun `empty input produces an empty render list`() {
        val result = FeedFlattener.flatten(emptyList())
        assertTrue(result.items.isEmpty())
        assertEquals(0, result.droppedCount)
    }

    @Test
    fun `promoted group preserves the complete server-driven cell order`() {
        val group = WireGroup(
            groupId = "ad:patrick-platform-01",
            cells = listOf(
                WireCell.AdHeader("header", "patrick-platform-01", "patrickjackson"),
                WireCell.AdTitle("title", "patrick-platform-01", "I build resilient Android platforms."),
                WireCell.AdMedia(
                    cellId = "media",
                    adId = "patrick-platform-01",
                    items = listOf(
                        WireAdMediaItem(
                            creativeId = "demo",
                            kind = WireAdMediaKind.Video,
                            placeholderColor = 0xFF000000,
                            aspectRatio = 0.8f,
                            altText = "Demo",
                        ),
                    ),
                    destinationUrl = "https://patrickjackson.dev",
                    displayDomain = "patrickjackson.dev",
                    ctaLabel = "Learn more",
                ),
                WireCell.AdSummary("summary", "patrick-platform-01", "A concise summary."),
                WireCell.AdRelatedPosts("related", "patrick-platform-01", emptyList()),
                WireCell.AdActionBar("actions", "patrick-platform-01"),
            ),
        )

        val result = FeedFlattener.flatten(listOf(group))

        assertEquals(
            listOf(
                CellUi.AdHeader::class,
                CellUi.AdTitle::class,
                CellUi.AdMedia::class,
                CellUi.AdSummary::class,
                CellUi.AdRelatedPosts::class,
                CellUi.AdActionBar::class,
                CellUi.GroupDivider::class,
            ),
            result.items.map { it::class },
        )
        assertEquals(result.items.size, result.items.map { it.key }.toSet().size)
    }
}
