package dev.readthat.feed.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Repeat
import dev.readthat.domain.AdMediaItemUi
import dev.readthat.domain.AdMediaKind
import dev.readthat.domain.CellUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedFeedCellRendererTest {
    @Test
    fun productionIconDefaultsAreFeatureOwnedInsteadOfHostFallbacks() {
        val icons = SharedFeedCellIcons()

        assertEquals(Icons.Default.PlayCircle, icons.playIndicator)
        assertEquals(Icons.Outlined.ArrowUpward, icons.actions.upvote)
        assertEquals(Icons.Outlined.ChatBubbleOutline, icons.actions.comments)
        assertEquals(Icons.Outlined.Repeat, icons.actions.reshare)
        assertEquals(Icons.Default.Replay, icons.promoted.replay)
        assertEquals(Icons.AutoMirrored.Filled.VolumeOff, icons.promoted.volumeOff)
    }

    @Test
    fun organicVideoMappingPreservesAdaptiveSourcesAndStableIdentity() {
        val media = CellUi.Media(
            key = "post-42/media",
            placeholderColor = 0x112233,
            aspectRatio = 16f / 9f,
            altText = "A video",
            sourceUrl = "https://cdn.example/fallback.mp4",
            cacheKey = null,
            durationLabel = "0:12",
            durationSeconds = 12,
            video = CellUi.VideoPlaybackUi(
                hlsUrl = "https://cdn.example/master.m3u8",
                dashUrl = "https://cdn.example/manifest.mpd",
                posterUrl = "https://cdn.example/poster.jpg",
                fallbackUrl = "https://cdn.example/fallback.mp4",
                deliveryStatus = "processing",
                processingProgress = 73,
            ),
        ).toFeedPostMedia()

        assertTrue(media.isVideo)
        assertEquals("https://cdn.example/master.m3u8", media.hlsUrl)
        assertEquals("https://cdn.example/manifest.mpd", media.dashUrl)
        assertEquals("https://cdn.example/poster.jpg", media.posterUrl)
        assertEquals("processing", media.deliveryStatus)
        assertEquals(73, media.processingProgress)
        assertEquals("post:post-42", media.cacheKey)
    }

    @Test
    fun organicStillMappingDoesNotInventVideoState() {
        val media = CellUi.Media(
            key = "post-7/media",
            placeholderColor = 0,
            aspectRatio = 1f,
            altText = "A still",
            sourceUrl = "https://cdn.example/image.jpg",
            cacheKey = "media:7",
            durationLabel = null,
        ).toFeedPostMedia()

        assertFalse(media.isVideo)
        assertNull(media.hlsUrl)
        assertEquals("not_applicable", media.deliveryStatus)
        assertEquals("media:7", media.cacheKey)
    }

    @Test
    fun promotedVideoMappingPreservesNativePlaybackInputs() {
        val media = AdMediaItemUi(
            creativeId = "creative-9",
            kind = AdMediaKind.Video,
            placeholderColor = 0,
            aspectRatio = 9f / 16f,
            altText = "Promoted video",
            imageUrl = null,
            hlsUrl = "https://cdn.example/ad.m3u8",
            dashUrl = "https://cdn.example/ad.mpd",
            posterUrl = "https://cdn.example/ad.jpg",
            fallbackUrl = "https://cdn.example/ad.mp4",
            durationSeconds = 30,
            cacheKey = "ad:creative-9",
        ).toFeedPostMedia()

        assertTrue(media.isVideo)
        assertEquals("https://cdn.example/ad.m3u8", media.hlsUrl)
        assertEquals("https://cdn.example/ad.mpd", media.dashUrl)
        assertEquals("https://cdn.example/ad.mp4", media.fallbackUrl)
        assertEquals("ad:creative-9", media.cacheKey)
    }
}
