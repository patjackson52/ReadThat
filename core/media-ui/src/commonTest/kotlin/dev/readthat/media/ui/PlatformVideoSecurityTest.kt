package dev.readthat.media.ui

import dev.readthat.shared.PostMedia
import dev.readthat.shared.VideoPlaybackPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlatformVideoSecurityTest {
    @Test
    fun `native playback keeps https hls and distinct https fallback`() {
        val urls = media(
            hlsUrl = "https://stream.example/video.m3u8",
            fallbackUrl = "https://stream.example/video.mp4",
        ).secureVideoUrls()

        assertEquals("https://stream.example/video.m3u8", urls.primary)
        assertEquals("https://stream.example/video.mp4", urls.fallback)
    }

    @Test
    fun `cleartext hls is discarded before native player creation`() {
        val urls = media(
            hlsUrl = "http://stream.example/video.m3u8",
            fallbackUrl = "https://stream.example/video.mp4",
        ).secureVideoUrls()

        assertEquals("https://stream.example/video.mp4", urls.primary)
        assertNull(urls.fallback)
    }

    @Test
    fun `all cleartext sources produce no playable media`() {
        val urls = media(
            hlsUrl = "http://stream.example/video.m3u8",
            fallbackUrl = "http://stream.example/video.mp4",
        ).secureVideoUrls()

        assertNull(urls.primary)
        assertNull(urls.fallback)
    }

    @Test
    fun `autoplay policy blocks unsolicited playback but never an explicit play request`() {
        assertFalse(shouldRequestNativePlayback(
            playRequested = true,
            autoplayAllowed = false,
            userInitiatedPlayback = false,
        ))
        assertTrue(shouldRequestNativePlayback(
            playRequested = true,
            autoplayAllowed = false,
            userInitiatedPlayback = true,
        ))
        assertFalse(shouldRequestNativePlayback(
            playRequested = false,
            autoplayAllowed = true,
            userInitiatedPlayback = true,
        ))
    }

    @Test
    fun `media feed acquires lazily then retains its player across manual pause`() {
        assertFalse(shouldAcquireNativePlayer(
            PlatformVideoRole.MediaFeed,
            playWhenReady = false,
            userInitiatedPlayback = false,
        ))
        assertTrue(shouldAcquireNativePlayer(
            PlatformVideoRole.MediaFeed,
            playWhenReady = true,
            userInitiatedPlayback = false,
        ))
        assertTrue(shouldAcquireNativePlayer(
            PlatformVideoRole.MediaFeed,
            playWhenReady = false,
            userInitiatedPlayback = true,
        ))
        assertFalse(shouldAcquireNativePlayer(
            PlatformVideoRole.Feed,
            playWhenReady = false,
            userInitiatedPlayback = true,
        ))
        assertTrue(shouldAcquireNativePlayer(
            PlatformVideoRole.Detail,
            playWhenReady = false,
            userInitiatedPlayback = false,
        ))
        assertTrue(shouldAcquireNativePlayer(
            PlatformVideoRole.MediaFeed,
            playWhenReady = false,
            userInitiatedPlayback = false,
            alreadyAcquired = true,
        ))
    }

    @Test
    fun `seek commands distinguish repeated targets and reject invalid positions`() {
        assertFalse(PlatformVideoSeekRequest(1L, 5_000L) == PlatformVideoSeekRequest(2L, 5_000L))
        assertEquals(0L, PlatformVideoSeekRequest(3L, 0L).positionMs)
    }

    @Test
    fun `native forward buffer keeps surface bounds and shared data saver ceiling`() {
        val unrestricted = policy(forwardBufferSeconds = 45)
        val constrained = policy(forwardBufferSeconds = 15)

        assertEquals(8.0, preferredForwardBufferSeconds(PlatformVideoRole.Feed, unrestricted))
        assertEquals(20.0, preferredForwardBufferSeconds(PlatformVideoRole.MediaFeed, unrestricted))
        assertEquals(30.0, preferredForwardBufferSeconds(PlatformVideoRole.Detail, unrestricted))
        assertEquals(15.0, preferredForwardBufferSeconds(PlatformVideoRole.MediaFeed, constrained))
        assertEquals(15.0, preferredForwardBufferSeconds(PlatformVideoRole.AdDetail, constrained))
    }

    private fun policy(forwardBufferSeconds: Int) = VideoPlaybackPolicy(
        autoplay = true,
        allowPrefetch = true,
        readCache = true,
        writeCache = true,
        maxVideoHeight = 1080,
        preferredPeakBitrate = 8_000_000,
        forwardBufferSeconds = forwardBufferSeconds,
        cacheBytes = 192L * 1_024L * 1_024L,
    )

    private fun media(hlsUrl: String?, fallbackUrl: String?) = PostMedia(
        placeholderColor = 0,
        aspectRatio = 1f,
        isVideo = true,
        hlsUrl = hlsUrl,
        fallbackUrl = fallbackUrl,
    )
}
