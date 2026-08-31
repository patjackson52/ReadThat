package dev.readthat.ui

import dev.readthat.domain.AdMediaItemUi
import dev.readthat.domain.AdMediaKind
import dev.readthat.domain.CellUi
import dev.readthat.domain.ImageMediaUi
import dev.readthat.feed.ui.feedMediaPrefetchCatalog
import dev.readthat.shared.feedImageCacheKey
import org.junit.Assert.assertEquals
import org.junit.Test

class FeedImagePrefetchTest {
    @Test
    fun `feed warmer covers still images every carousel page and ad media`() {
        val items = listOf(
            CellUi.Media(
                key = "post:still:media",
                placeholderColor = 0xff23386b,
                aspectRatio = 1f,
                altText = "still",
                sourceUrl = "https://example.test/still.jpg",
                cacheKey = "image:still",
                durationLabel = null,
            ),
            CellUi.ImageCarousel(
                key = "post:carousel:media",
                items = listOf(
                    image("one", "https://example.test/one.jpg", "image:one"),
                    image("two", "https://example.test/two.jpg", null),
                ),
            ),
            CellUi.AdMedia(
                key = "ad:media",
                adId = "ad",
                items = listOf(
                    adImage("creative-image", "https://example.test/ad.jpg"),
                    adVideo("creative-video", "https://example.test/poster.jpg"),
                ),
                destinationUrl = "https://example.test",
                displayDomain = "example.test",
                ctaLabel = "Open",
            ),
        )

        assertEquals(
            listOf(
                Triple(0, "https://example.test/still.jpg", "image:still"),
                Triple(1, "https://example.test/one.jpg", "image:one"),
                Triple(1, "https://example.test/two.jpg", "image:two"),
                Triple(2, "https://example.test/ad.jpg", "creative-image"),
            ),
            feedMediaPrefetchCatalog(items).stillImages.map {
                Triple(it.cellIndex, it.url, it.cacheKey)
            },
        )
    }

    @Test
    fun `renderer fallback keys are stable when server cache keys are absent`() {
        val still = CellUi.Media(
            key = "post:still:media",
            placeholderColor = 0xff23386b,
            aspectRatio = 1f,
            altText = "still",
            sourceUrl = "https://example.test/signed.jpg?token=one",
            durationLabel = null,
        )
        val carousel = image(null, "https://example.test/signed-2.jpg?token=one", null)

        assertEquals("feed-image:post:still:media", still.feedImageCacheKey())
        assertEquals("feed-carousel:post:carousel:media:2", carousel.feedImageCacheKey("post:carousel:media", 2))
    }

    private fun image(id: String?, url: String, cacheKey: String?) = ImageMediaUi(
        mediaId = id,
        placeholderColor = 0xff23386b,
        aspectRatio = 1f,
        altText = id.orEmpty(),
        sourceUrl = url,
        zoomUrl = url,
        cacheKey = cacheKey,
        width = 1_000,
        height = 1_000,
    )

    private fun adImage(key: String, url: String) = AdMediaItemUi(
        creativeId = key,
        kind = AdMediaKind.Image,
        placeholderColor = 0xff23386b,
        aspectRatio = 1f,
        altText = key,
        imageUrl = url,
        hlsUrl = null,
        dashUrl = null,
        posterUrl = null,
        fallbackUrl = null,
        durationSeconds = null,
        cacheKey = key,
    )

    private fun adVideo(key: String, poster: String) = AdMediaItemUi(
        creativeId = key,
        kind = AdMediaKind.Video,
        placeholderColor = 0xff23386b,
        aspectRatio = 1f,
        altText = key,
        imageUrl = null,
        hlsUrl = "https://example.test/video.m3u8",
        dashUrl = null,
        posterUrl = poster,
        fallbackUrl = null,
        durationSeconds = 10,
        cacheKey = key,
    )
}
