package dev.readthat.mediafeed.ui

import dev.readthat.media.ui.PlatformPlaybackSnapshot
import dev.readthat.media.ui.PlatformPlaybackState
import dev.readthat.mediafeed.domain.MediaFeedMedia
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedPlatformMediaFeedRouteTest {
    @Test
    fun immutableServerCacheKeyWins() {
        val media = video(cacheKey = "media:stable", mediaId = "42")

        assertEquals("media:stable", media.toStablePostMedia("post-1").cacheKey)
    }

    @Test
    fun mediaIdStabilizesRotatingDeliveryUrls() {
        val media = video(cacheKey = null, mediaId = "42")

        assertEquals("video:42", media.toStablePostMedia("post-1").cacheKey)
    }

    @Test
    fun postIdentityIsTheFinalLegacyFallback() {
        val media = video(cacheKey = null, mediaId = null)

        assertEquals("post:post-1", media.toStablePostMedia("post-1").cacheKey)
    }

    @Test
    fun nativePlaybackStateMapsWithoutLosingPositionOrDuration() {
        PlatformPlaybackState.entries.forEach { state ->
            val mapped = PlatformPlaybackSnapshot(state, positionMs = 1_234L, durationMs = 9_876L)
                .toMediaFeedPlaybackSnapshot()

            assertEquals(state.name, mapped.state.name)
            assertEquals(1_234L, mapped.positionMs)
            assertEquals(9_876L, mapped.durationMs)
        }
    }

    private fun video(cacheKey: String?, mediaId: String?) = MediaFeedMedia(
        mediaId = mediaId,
        placeholderColor = 0,
        aspectRatio = 16f / 9f,
        isVideo = true,
        hlsUrl = "https://cdn.example/master.m3u8",
        cacheKey = cacheKey,
    )
}
