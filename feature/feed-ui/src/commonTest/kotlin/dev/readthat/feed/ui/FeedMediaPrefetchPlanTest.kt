package dev.readthat.feed.ui

import dev.readthat.domain.AdMediaItemUi
import dev.readthat.domain.AdMediaKind
import dev.readthat.domain.CellUi
import dev.readthat.domain.ImageMediaUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeedMediaPrefetchPlanTest {
    @Test
    fun catalogIndexesStillCarouselAdAndVideoMediaWithStableKeys() {
        val cells = listOf(
            still("a/media", "https://cdn.test/a.jpg"),
            CellUi.ImageCarousel("b/gallery", listOf(image("b-1", "https://cdn.test/b.jpg"))),
            video("c/media", "https://cdn.test/c.m3u8", "https://cdn.test/c.jpg"),
            adMedia(),
        )

        val plan = feedMediaPrefetchPlan(cells, 2)

        assertEquals(2, plan.videos.size)
        assertEquals(0, plan.videoFocusIndex)
        assertEquals(listOf("feed-image:a/media", "image:b-1", "ad-image"), plan.stillImages.map { it.cacheKey })
        assertEquals(2, plan.videoPosters.size)
        assertTrue(plan.decodedImages.all { it.url.startsWith("https://") })
    }

    @Test
    fun movingViewportProducesBoundedForwardBiasedWindows() {
        val cells = (0 until 60).map { index ->
            if (index % 2 == 0) still("p$index/media", "https://cdn.test/$index.jpg")
            else video("p$index/media", "https://cdn.test/$index.m3u8", "https://cdn.test/$index-poster.jpg")
        }
        val catalog = feedMediaPrefetchCatalog(cells)
        val plan = catalog.plan(20)

        assertEquals(16, plan.stillImages.size)
        assertEquals(9, plan.videoPosters.size)
        assertEquals(21, plan.videos[plan.videoFocusIndex].cellIndex)
        assertTrue(plan.decodedImages.size <= 24)
    }

    private fun still(key: String, url: String) = CellUi.Media(
        key = key,
        placeholderColor = 0,
        aspectRatio = 1f,
        altText = "still",
        sourceUrl = url,
        durationLabel = null,
    )

    private fun video(key: String, hls: String, poster: String) = CellUi.Media(
        key = key,
        placeholderColor = 0,
        aspectRatio = 16f / 9f,
        altText = "video",
        cacheKey = "video:$key",
        durationLabel = "0:10",
        video = CellUi.VideoPlaybackUi(hls, null, poster, null, "ready", 100),
    )

    private fun image(id: String, url: String) = ImageMediaUi(
        mediaId = id,
        placeholderColor = 0,
        aspectRatio = 1f,
        altText = "gallery",
        sourceUrl = url,
        zoomUrl = null,
        cacheKey = null,
        width = null,
        height = null,
    )

    private fun adMedia() = CellUi.AdMedia(
        key = "ad/media",
        adId = "ad",
        items = listOf(
            AdMediaItemUi("still", AdMediaKind.Image, 0, 1f, "ad", "https://cdn.test/ad.jpg", null, null, null, null, null, "ad-image"),
            AdMediaItemUi("video", AdMediaKind.Video, 0, 1f, "ad video", null, "https://cdn.test/ad.m3u8", null, "https://cdn.test/ad-poster.jpg", null, 10, "ad-video"),
        ),
        destinationUrl = "https://example.test",
        displayDomain = "example.test",
        ctaLabel = "Open",
    )
}
