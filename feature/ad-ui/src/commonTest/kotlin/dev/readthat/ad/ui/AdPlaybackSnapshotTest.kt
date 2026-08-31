package dev.readthat.ad.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AdPlaybackSnapshotTest {
    @Test
    fun completionIsClampedToTheNativeDuration() {
        assertEquals(50.0, AdPlaybackSnapshot(AdPlaybackState.Playing, 500L, 1_000L).completionPercent)
        assertEquals(100.0, AdPlaybackSnapshot(AdPlaybackState.Ended, 1_500L, 1_000L).completionPercent)
        assertEquals(0.0, AdPlaybackSnapshot(AdPlaybackState.Playing, -1L, 1_000L).completionPercent)
    }

    @Test
    fun unknownOrInvalidDurationHasNoCompletion() {
        assertNull(AdPlaybackSnapshot(AdPlaybackState.Buffering, 0L, null).completionPercent)
        assertNull(AdPlaybackSnapshot(AdPlaybackState.Error, 0L, 0L).completionPercent)
    }
}
