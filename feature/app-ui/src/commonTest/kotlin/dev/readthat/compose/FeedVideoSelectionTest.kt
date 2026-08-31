package dev.readthat.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FeedVideoSelectionTest {
    @Test
    fun choosesLargestVisibleVideo() {
        assertEquals(
            "second",
            selectActiveFeedVideo(
                visibleItems = listOf(
                    FeedVisibleItem("first", -700, 1_000),
                    FeedVisibleItem("second", 300, 800),
                ),
                videoKeys = setOf("first", "second"),
                viewportStart = 0,
                viewportEnd = 1_000,
            ),
        )
    }

    @Test
    fun ignoresVideoBelowHalfVisibilityThreshold() {
        assertNull(
            selectActiveFeedVideo(
                visibleItems = listOf(FeedVisibleItem("video", 900, 600)),
                videoKeys = setOf("video"),
                viewportStart = 0,
                viewportEnd = 1_000,
            ),
        )
    }
}
