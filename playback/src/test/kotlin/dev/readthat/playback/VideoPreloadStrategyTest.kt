package dev.readthat.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPreloadStrategyTest {
    @Test
    fun `immersive media feed loops while feed and detail remain one shot`() {
        assertEquals(Player.REPEAT_MODE_OFF, videoRepeatMode(VideoPlaybackRole.Feed))
        assertEquals(Player.REPEAT_MODE_ONE, videoRepeatMode(VideoPlaybackRole.MediaFeed))
        assertEquals(Player.REPEAT_MODE_OFF, videoRepeatMode(VideoPlaybackRole.Detail))
        assertEquals(Player.REPEAT_MODE_OFF, videoRepeatMode(VideoPlaybackRole.AdDetail))
    }

    @Test
    fun `playback snapshots report bounded completion`() {
        assertEquals(25.0, VideoPlaybackSnapshot(VideoPlaybackState.Playing, 250, 1_000).completionPercent)
        assertEquals(100.0, VideoPlaybackSnapshot(VideoPlaybackState.Ended, 1_250, 1_000).completionPercent)
        assertEquals(0.0, VideoPlaybackSnapshot(VideoPlaybackState.Ready, -1, 1_000).completionPercent)
        assertEquals(null, VideoPlaybackSnapshot(VideoPlaybackState.Idle, 0, null).completionPercent)
    }

    @Test
    fun `before playback the focused offscreen video is loaded`() {
        assertEquals(VideoPreloadTier.Loaded, videoPreloadTier(distance = 0, playbackActive = false))
    }

    @Test
    fun `active playback loads only immediate neighbors`() {
        assertEquals(VideoPreloadTier.None, videoPreloadTier(0, playbackActive = true))
        assertEquals(VideoPreloadTier.Loaded, videoPreloadTier(1, playbackActive = true))
        assertEquals(VideoPreloadTier.Loaded, videoPreloadTier(-1, playbackActive = true))
        assertEquals(VideoPreloadTier.Tracks, videoPreloadTier(2, playbackActive = true))
        assertEquals(VideoPreloadTier.Source, videoPreloadTier(4, playbackActive = true))
        assertEquals(VideoPreloadTier.None, videoPreloadTier(5, playbackActive = true))
    }

    @Test
    fun `playing same-media handoff suppresses controller auto show`() {
        assertTrue(
            suppressVideoControllerAutoShow(
                showControls = true,
                continueExistingPlayback = true,
                sameMedia = true,
                playWhenReady = true,
                playbackEnded = false,
            ),
        )
    }

    @Test
    fun `fresh paused and ended detail videos retain normal controller policy`() {
        fun suppress(
            continuePlayback: Boolean = true,
            sameMedia: Boolean = true,
            playWhenReady: Boolean = true,
            playbackEnded: Boolean = false,
        ) = suppressVideoControllerAutoShow(
            showControls = true,
            continueExistingPlayback = continuePlayback,
            sameMedia = sameMedia,
            playWhenReady = playWhenReady,
            playbackEnded = playbackEnded,
        )

        assertFalse(suppress(continuePlayback = false))
        assertFalse(suppress(sameMedia = false))
        assertFalse(suppress(playWhenReady = false))
        assertFalse(suppress(playbackEnded = true))
    }
}
