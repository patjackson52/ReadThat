package dev.readthat.feed.ui

import dev.readthat.domain.AdMediaKind
import dev.readthat.domain.AdMediaItemUi
import dev.readthat.domain.CellUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedFeedPresentationTest {
    @Test
    fun snapshotResolvesGroupTitleAndCompanionAdMedia() {
        val title = CellUi.Title("post-a/title", "A title")
        val ad = CellUi.AdMedia(
            key = "ad-a/media",
            adId = "ad-a",
            items = listOf(AdMediaItemUi(
                creativeId = "creative-a",
                kind = AdMediaKind.Image,
                placeholderColor = 0,
                aspectRatio = 1f,
                altText = "Ad",
                imageUrl = "https://cdn.test/ad.jpg",
                hlsUrl = null,
                dashUrl = null,
                posterUrl = null,
                fallbackUrl = null,
                durationSeconds = null,
                cacheKey = "ad-a",
            )),
            destinationUrl = "https://example.test",
            displayDomain = "example.test",
            ctaLabel = "Open",
        )
        val presentation = sharedFeedPresentation(listOf(title, ad))

        assertEquals("post-a", title.feedGroupId())
        assertEquals("A title", presentation.titleFor(title))
        assertEquals(ad, presentation.adMediaFor(ad))
        assertEquals("ReadThat post", presentation.titleFor(ad))
        assertNull(presentation.adMediaFor(title))
    }
}
