package dev.readthat.detail.ui

import dev.readthat.shared.PostMedia
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class SharedPostDetailMediaGalleryTest {
    @Test
    fun missingMediaIdentityUsesStablePostAndPageKey() {
        val media = media(cacheKey = null)

        val resolved = media.withStableCacheKey("post:42", 3)

        assertEquals("post:42:3", resolved.cacheKey)
    }

    @Test
    fun serverMediaIdentityIsPreservedAcrossSignedUrlRotation() {
        val media = media(cacheKey = "media:immutable")

        val resolved = media.withStableCacheKey("post:42", 0)

        assertSame(media, resolved)
        assertEquals("media:immutable", resolved.cacheKey)
    }

    @Test
    fun detailTransitionReusesFeedImageIdentityAndPrefersZoomUrl() {
        val request = detailStillImageRequest(
            media(cacheKey = "image:asset:etag:feed").copy(
                isVideo = false,
                url = "https://cdn.example/feed.jpg?expired-signature",
                zoomUrl = "https://cdn.example/detail.jpg?fresh-signature",
            ),
        )

        assertEquals("image:asset:etag:feed", request?.cacheKey)
        assertEquals("https://cdn.example/detail.jpg?fresh-signature", request?.url)
    }

    @Test
    fun invalidStillImageRequestIsNotSentToPlatformLoader() {
        assertNull(detailStillImageRequest(media(cacheKey = "").copy(isVideo = false)))
        assertNull(detailStillImageRequest(media(cacheKey = "image:asset").copy(isVideo = false, url = null)))
    }

    @Test
    fun durationLabelIsLocaleIndependentAndZeroPadded() {
        assertEquals("0:05", durationLabel(5))
        assertEquals("1:09", durationLabel(69))
        assertEquals("10:00", durationLabel(600))
    }

    private fun media(cacheKey: String?) = PostMedia(
        placeholderColor = 0,
        aspectRatio = 16f / 9f,
        isVideo = true,
        hlsUrl = "https://cdn.example/master.m3u8",
        posterUrl = "https://cdn.example/poster.jpg",
        cacheKey = cacheKey,
    )
}
