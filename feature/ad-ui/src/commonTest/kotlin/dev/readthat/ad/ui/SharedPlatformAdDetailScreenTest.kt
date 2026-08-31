package dev.readthat.ad.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Replay
import dev.readthat.domain.AdLaunchContext
import dev.readthat.domain.AdMediaKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedPlatformAdDetailScreenTest {
    @Test
    fun promotedDetailIconsAreCommonUiInsteadOfPlatformSlots() {
        val icons = AdDetailIcons()

        assertEquals(Icons.Default.Close, icons.close)
        assertEquals(Icons.Default.Replay, icons.replay)
        assertEquals(Icons.AutoMirrored.Filled.VolumeOff, icons.muted)
    }

    @Test
    fun promotedVideoMappingPreservesAdaptiveFallbackAndStableIdentity() {
        val media = AdLaunchContext(
            adId = "ad-1",
            creativeId = "creative-1",
            kind = AdMediaKind.Video,
            placeholderColor = 0,
            aspectRatio = 16f / 9f,
            altText = "Promoted video",
            imageUrl = null,
            hlsUrl = "https://cdn.example/ad.m3u8",
            posterUrl = "https://cdn.example/ad.jpg",
            fallbackUrl = "https://cdn.example/ad.mp4",
            cacheKey = "ad:creative-1",
            destinationUrl = "https://example.com",
            displayDomain = "example.com",
            ctaLabel = "Learn more",
        ).toAdPostMedia()

        assertTrue(media.isVideo)
        assertEquals("https://cdn.example/ad.m3u8", media.hlsUrl)
        assertEquals("https://cdn.example/ad.mp4", media.fallbackUrl)
        assertEquals("ad:creative-1", media.cacheKey)
    }
}
