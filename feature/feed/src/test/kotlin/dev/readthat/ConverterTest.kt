package dev.readthat

import dev.readthat.domain.CellConverter
import dev.readthat.domain.CellConverterRegistry
import dev.readthat.domain.CellUi
import dev.readthat.domain.Converters
import dev.readthat.domain.AdMediaKind
import dev.readthat.domain.WireCell
import dev.readthat.domain.WireAdMediaItem
import dev.readthat.domain.WireAdMediaKind
import dev.readthat.domain.WireImageItem
import dev.readthat.domain.WirePostFlair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConverterTest {

    @Test
    fun `registry uses the first converter that can handle the cell`() {
        val loser = CellConverter { _, key -> CellUi.Title(key, "SECOND") }
        val winner = CellConverter { _, key -> CellUi.Title(key, "FIRST") }

        val registry = CellConverterRegistry(listOf(winner, loser))
        val result = registry.convert(WireCell.Title("t", "ignored"), "k")

        assertEquals("FIRST", (result as CellUi.Title).text)
    }

    @Test
    fun `a converter returning null defers to the next one`() {
        val abstains = CellConverter { _, _ -> null }
        val handles = CellConverter { _, key -> CellUi.Title(key, "handled") }

        val registry = CellConverterRegistry(listOf(abstains, handles))

        assertEquals("handled", (registry.convert(WireCell.Title("t", "x"), "k") as CellUi.Title).text)
    }

    @Test
    fun `unknown cells resolve to null so the caller can choose policy`() {
        val registry = CellConverterRegistry()
        assertNull(registry.convert(WireCell.Unknown("u", "BrandNewCell"), "k"))
    }

    @Test
    fun `video converts to media with a formatted duration, image without`() {
        val registry = CellConverterRegistry()

        val video = registry.convert(
            WireCell.Video("m", 0xFF000000, 1.77f, 194, "alt"), "k",
        ) as CellUi.Media
        val image = registry.convert(
            WireCell.Image("m", 0xFF000000, 1.77f, "alt"), "k",
        ) as CellUi.Media

        assertEquals("3:14", video.durationLabel)
        assertNull(image.durationLabel)
    }

    @Test
    fun `image carousel preserves server order and stable media identities`() {
        val ui = CellConverterRegistry().convert(
            WireCell.ImageCarousel(
                cellId = "media",
                items = listOf(
                    WireImageItem(
                        mediaId = "first",
                        placeholderColor = 0xff112233,
                        aspectRatio = 4f / 3f,
                        altText = "First",
                        url = "https://example.test/first.jpg",
                        cacheKey = "image:first:feed",
                    ),
                    WireImageItem(
                        mediaId = "second",
                        placeholderColor = 0xff445566,
                        aspectRatio = 3f / 4f,
                        altText = "Second",
                        url = "https://example.test/second.jpg",
                        cacheKey = "image:second:feed",
                    ),
                ),
            ),
            "post-1/media",
        ) as CellUi.ImageCarousel

        assertEquals(listOf("first", "second"), ui.items.map { it.mediaId })
        assertEquals(listOf("First", "Second"), ui.items.map { it.altText })
        assertEquals("image:second:feed", ui.items[1].cacheKey)
    }

    @Test
    fun `duration pads seconds`() {
        assertEquals("0:05", Converters.formatDuration(5))
        assertEquals("1:00", Converters.formatDuration(60))
        assertEquals("10:09", Converters.formatDuration(609))
    }

    @Test
    fun `counts are compacted for display`() {
        assertEquals("999", Converters.compactCount(999))
        assertEquals("1.2k", Converters.compactCount(1234))
        assertEquals("12k", Converters.compactCount(12000))
        assertEquals("1.5M", Converters.compactCount(1_500_000))
    }

    @Test
    fun `metadata converter formats the subreddit line`() {
        val registry = CellConverterRegistry()
        val ui = registry.convert(
            WireCell.Metadata("meta", "androiddev", "2h ago", pinned = true), "k",
        ) as CellUi.Metadata

        assertEquals("r/androiddev · 2h ago", ui.line)
        assertTrue(ui.pinned)
    }

    @Test
    fun `title converter preserves post flair presentation`() {
        val registry = CellConverterRegistry()
        val ui = registry.convert(
            WireCell.Title(
                cellId = "title",
                text = "A title",
                flair = WirePostFlair("discussion", "Discussion", "#E4E9EC", "#0B1416"),
            ),
            "post/title",
        ) as CellUi.Title

        assertEquals("Discussion", ui.flair?.text)
        assertEquals("#E4E9EC", ui.flair?.backgroundColor)
    }

    @Test
    fun `promoted media converts every carousel creative with a stable cache identity`() {
        val registry = CellConverterRegistry()
        val ui = registry.convert(
            WireCell.AdMedia(
                cellId = "media",
                adId = "patrick-platform-01",
                items = listOf(
                    WireAdMediaItem(
                        creativeId = "architecture-video",
                        kind = WireAdMediaKind.Video,
                        placeholderColor = 0xFF000000,
                        aspectRatio = 0.8f,
                        altText = "Architecture demo",
                        hlsUrl = "https://example.test/master.m3u8",
                    ),
                    WireAdMediaItem(
                        creativeId = "systems-image",
                        kind = WireAdMediaKind.Image,
                        placeholderColor = 0xFFFFFFFF,
                        aspectRatio = 1f,
                        altText = "Systems diagram",
                        imageUrl = "https://example.test/systems.png",
                        cacheKey = "portfolio:systems-image",
                    ),
                ),
                destinationUrl = "https://patrickjackson.dev/platform",
                displayDomain = "patrickjackson.dev",
                ctaLabel = "View work",
            ),
            "ad:patrick-platform-01/media",
        ) as CellUi.AdMedia

        assertEquals(2, ui.items.size)
        assertEquals(AdMediaKind.Video, ui.items[0].kind)
        assertEquals("ad:patrick-platform-01:architecture-video", ui.items[0].cacheKey)
        assertEquals(AdMediaKind.Image, ui.items[1].kind)
        assertEquals("portfolio:systems-image", ui.items[1].cacheKey)
        assertEquals("https://patrickjackson.dev/platform", ui.destinationUrl)
    }
}
