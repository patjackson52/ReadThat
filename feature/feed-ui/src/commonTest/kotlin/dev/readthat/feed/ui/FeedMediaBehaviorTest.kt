package dev.readthat.feed.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeedMediaBehaviorTest {
    @Test
    fun firstFramePreviewHandsOffOnlyAfterNativePlaybackRenders() {
        assertTrue(shouldShowFirstFramePreview(playInline = false, renderedFirstFrame = false))
        assertTrue(shouldShowFirstFramePreview(playInline = true, renderedFirstFrame = false))
        assertFalse(shouldShowFirstFramePreview(playInline = true, renderedFirstFrame = true))
    }

    @Test
    fun malformedServerAspectRatiosCannotBreakMeasurement() {
        assertEquals(.5f, safeFeedAspectRatio(.1f))
        assertEquals(2.5f, safeFeedAspectRatio(8f))
        assertEquals(16f / 9f, safeFeedAspectRatio(Float.NaN))
    }
}
