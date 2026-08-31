package dev.readthat.search.ui

import dev.readthat.image.ui.PlatformImageKind
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedPlatformSearchRouteTest {
    @Test
    fun videoThumbnailKeepsPreviewDecodeIdentity() {
        val request = searchImageRequest(
            url = "https://cdn.example/poster.jpg",
            cacheKey = "media:42",
            videoPreview = true,
        )

        assertEquals(PlatformImageKind.VideoPreview, request.kind)
        assertEquals("preview:media:42", request.decodedCacheKey)
    }

    @Test
    fun stillThumbnailUsesIndependentDecodedVariant() {
        val request = searchImageRequest(
            url = "https://cdn.example/image.jpg",
            cacheKey = "media:42",
            videoPreview = false,
        )

        assertEquals(PlatformImageKind.Still, request.kind)
        assertEquals("image:media:42", request.decodedCacheKey)
    }
}
