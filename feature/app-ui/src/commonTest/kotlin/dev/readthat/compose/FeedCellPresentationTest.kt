package dev.readthat.compose

import dev.readthat.domain.CellUi
import dev.readthat.domain.ImageMediaUi
import dev.readthat.feed.ui.feedMediaPrefetchPlan
import kotlin.test.Test
import kotlin.test.assertEquals

class FeedCellPresentationTest {
    @Test
    fun flattenedPagingWindowReconstructsOrderedPostGroups() {
        val cells = listOf(
            title("post-a/title", "A"),
            action("post-a/actions"),
            title("post-b/title", "B"),
            CellUi.GroupDivider("post-b/divider"),
        )

        val cards = feedCardsFromCells(cells)

        assertEquals(listOf("post-a", "post-b"), cards.map { it.id })
        assertEquals(listOf("post-a/title", "post-a/actions"), cards[0].cells.map(CellUi::key))
        assertEquals(listOf("post-b/title", "post-b/divider"), cards[1].cells.map(CellUi::key))
    }

    @Test
    fun everyCellUsesItsCompositeKeyAsAStableGroupIdentity() {
        assertEquals("post-a", title("post-a/title", "A").feedGroupId())
        assertEquals("unscoped", title("unscoped", "A").feedGroupId())
    }

    @Test
    fun decodedPrefetchUsesStableStillAndFirstFramePreviewKeys() {
        val cells = listOf<CellUi>(
            media("post-a/media", sourceUrl = "https://cdn.test/still.jpg"),
            media(
                "post-b/media",
                video = CellUi.VideoPlaybackUi(
                    hlsUrl = "https://cdn.test/video.m3u8",
                    dashUrl = null,
                    posterUrl = "https://cdn.test/poster.jpg?v=2",
                    fallbackUrl = null,
                    deliveryStatus = "ready",
                    processingProgress = 100,
                ),
            ),
            CellUi.ImageCarousel(
                "post-c/gallery",
                listOf(ImageMediaUi(
                    mediaId = "image-c",
                    placeholderColor = 0,
                    aspectRatio = 1f,
                    altText = "gallery",
                    sourceUrl = "https://cdn.test/gallery.jpg",
                    zoomUrl = null,
                    cacheKey = null,
                    width = null,
                    height = null,
                )),
            ),
        )

        val requests = feedMediaPrefetchPlan(cells, 0).decodedImages

        assertEquals(3, requests.size)
        assertEquals("image:feed-image:post-a/media", requests[0].decodedKey)
        kotlin.test.assertTrue(requests[1].decodedKey.startsWith("preview:post:post-b:poster:v3:"))
        assertEquals("image:image:image-c", requests[2].decodedKey)
    }

    private fun title(key: String, text: String) = CellUi.Title(key, text)

    private fun action(key: String) = CellUi.ActionBar(
        key = key,
        scoreLabel = "1",
        commentLabel = "0",
        itemId = key.substringBefore('/'),
        score = 1,
        commentCount = 0,
    )

    private fun media(
        key: String,
        sourceUrl: String? = null,
        video: CellUi.VideoPlaybackUi? = null,
    ) = CellUi.Media(
        key = key,
        placeholderColor = 0,
        aspectRatio = 16f / 9f,
        altText = "media",
        sourceUrl = sourceUrl,
        durationLabel = null,
        video = video,
    )
}
