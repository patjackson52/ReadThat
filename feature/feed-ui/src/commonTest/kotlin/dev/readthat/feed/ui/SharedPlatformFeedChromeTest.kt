package dev.readthat.feed.ui

import dev.readthat.image.ui.PlatformImageKind
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedPlatformFeedChromeTest {
    @Test
    fun accountHeaderUsesAvatarDecodeIdentity() {
        val request = feedAccountAvatarRequest("https://cdn.example/me.png", "account:1")

        assertEquals(PlatformImageKind.Avatar, request.kind)
        assertEquals("avatar:account:1", request.decodedCacheKey)
    }

    @Test
    fun constrainedPolicyKeepsStillsButDropsSpeculativePosters() {
        val plan = FeedMediaPrefetchPlan(
            videos = emptyList(),
            videoFocusIndex = 0,
            stillImages = listOf(image("still", FeedImagePrefetchKind.Still)),
            videoPosters = listOf(image("poster", FeedImagePrefetchKind.VideoPoster)),
        )

        assertEquals(
            listOf(PlatformImageKind.Still),
            plan.platformImageRequests(includeVideoPosters = false).map { it.kind },
        )
        assertEquals(
            listOf(PlatformImageKind.Still, PlatformImageKind.VideoPreview),
            plan.platformImageRequests(includeVideoPosters = true).map { it.kind },
        )
    }

    private fun image(cacheKey: String, kind: FeedImagePrefetchKind) = FeedImagePrefetchRequest(
        cellIndex = 0,
        url = "https://cdn.example/$cacheKey.jpg",
        cacheKey = cacheKey,
        kind = kind,
    )
}
