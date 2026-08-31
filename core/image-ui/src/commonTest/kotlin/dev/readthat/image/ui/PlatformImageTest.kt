package dev.readthat.image.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlatformImageTest {
    @Test
    fun cleartextAndMalformedUrlsNeverReachNativeImageLoaders() {
        assertNull(PlatformImageRequest("http://example.com/a.jpg", "a").secureUrlOrNull())
        assertNull(PlatformImageRequest("not a url", "a").secureUrlOrNull())
        assertEquals(
            "https://example.com/a.jpg",
            PlatformImageRequest("https://example.com/a.jpg", "a").secureUrlOrNull(),
        )
    }

    @Test
    fun decodedKeysSeparatePosterStillAndAvatarVariants() {
        assertEquals("image:same", PlatformImageRequest("https://a.test/i", "same").decodedCacheKey)
        assertEquals(
            "preview:same",
            PlatformImageRequest(
                "https://a.test/i",
                "same",
                PlatformImageKind.VideoPreview,
            ).decodedCacheKey,
        )
        assertEquals(
            "avatar:same",
            PlatformImageRequest(
                "https://a.test/i",
                "same",
                PlatformImageKind.Avatar,
            ).decodedCacheKey,
        )
    }

    @Test
    fun prefetchWindowIsSecureStableDeduplicatedAndBounded() {
        val requests = buildList {
            add(PlatformImageRequest("http://a.test/rejected", "rejected"))
            repeat(40) { index ->
                add(PlatformImageRequest("https://a.test/$index", "key-$index"))
            }
            add(PlatformImageRequest("https://a.test/duplicate", "key-0"))
            add(PlatformImageRequest("https://a.test/blank", ""))
        }

        val bounded = boundedPlatformImageRequests(requests)

        assertEquals(MAX_PLATFORM_IMAGE_PREFETCH_REQUESTS, bounded.size)
        assertEquals((0 until MAX_PLATFORM_IMAGE_PREFETCH_REQUESTS).map { "key-$it" }, bounded.map { it.cacheKey })
    }
}
