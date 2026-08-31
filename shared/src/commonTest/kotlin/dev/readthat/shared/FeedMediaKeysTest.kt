package dev.readthat.shared

import dev.readthat.domain.CellUi
import dev.readthat.domain.ImageMediaUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FeedMediaKeysTest {
    @Test
    fun stillAndCarouselKeysAreStableWithoutServerKeys() {
        val still = media(key = "post-1/media")
        val image = image(mediaId = null)

        assertEquals("feed-image:post-1/media", still.feedImageCacheKey())
        assertEquals("feed-carousel:post-1/gallery:2", image.feedImageCacheKey("post-1/gallery", 2))
        assertEquals("image:asset-7", image(mediaId = "asset-7").feedImageCacheKey("ignored", 0))
    }

    @Test
    fun videoPosterKeyTracksPosterRevisionButNotSignedPlaybackUrls() {
        val first = media(
            key = "post-1/media",
            video = video("https://cdn.test/poster.jpg?v=1"),
        )
        val next = first.copy(
            video = video("https://cdn.test/poster.jpg?v=2"),
        )

        assertNotEquals(first.feedVideoPosterCacheKey(), next.feedVideoPosterCacheKey())
        assertEquals(first.feedVideoPosterCacheKey(), first.feedVideoPosterCacheKey())
    }

    private fun media(
        key: String,
        video: CellUi.VideoPlaybackUi? = null,
    ) = CellUi.Media(
        key = key,
        placeholderColor = 0,
        aspectRatio = 16f / 9f,
        altText = "media",
        durationLabel = null,
        video = video,
    )

    private fun video(posterUrl: String) = CellUi.VideoPlaybackUi(
        hlsUrl = "https://cdn.test/stream.m3u8?token=short-lived",
        dashUrl = null,
        posterUrl = posterUrl,
        fallbackUrl = null,
        deliveryStatus = "ready",
        processingProgress = 100,
    )

    private fun image(mediaId: String?) = ImageMediaUi(
        mediaId = mediaId,
        placeholderColor = 0,
        aspectRatio = 1f,
        altText = "image",
        sourceUrl = "https://cdn.test/image.jpg",
        zoomUrl = null,
        cacheKey = null,
        width = null,
        height = null,
    )
}
