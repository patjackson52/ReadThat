package dev.readthat.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoSeekPolicyTest {
    @Test
    fun seekTargetsStayInsideKnownMediaBounds() {
        assertEquals(0L, clampVideoSeekPosition(-1L, 10_000L))
        assertEquals(5_000L, clampVideoSeekPosition(5_000L, 10_000L))
        assertEquals(10_000L, clampVideoSeekPosition(20_000L, 10_000L))
    }

    @Test
    fun unknownDurationOnlyClampsNegativeTargets() {
        assertEquals(5_000L, clampVideoSeekPosition(5_000L, C.TIME_UNSET))
        assertEquals(0L, clampVideoSeekPosition(-1L, C.TIME_UNSET))
    }

    @Test
    fun progressPublishesOnlyForForegroundPlayingOwner() {
        assertEquals(true, shouldPublishVideoProgress(
            hasWinner = true,
            appForeground = true,
            isPlaying = true,
        ))
        assertEquals(false, shouldPublishVideoProgress(
            hasWinner = false,
            appForeground = true,
            isPlaying = true,
        ))
        assertEquals(false, shouldPublishVideoProgress(
            hasWinner = true,
            appForeground = false,
            isPlaying = true,
        ))
        assertEquals(false, shouldPublishVideoProgress(
            hasWinner = true,
            appForeground = true,
            isPlaying = false,
        ))
    }
}
