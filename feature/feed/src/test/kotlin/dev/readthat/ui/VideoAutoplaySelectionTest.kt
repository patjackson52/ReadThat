package dev.readthat.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoAutoplaySelectionTest {
    @Test
    fun `fully visible next video wins over departing sliver`() {
        val selected = selectActiveVideoKey(
            visibleItems = listOf(
                FeedVisibleItem("previous", offset = -420, size = 560),
                FeedVisibleItem("next", offset = 520, size = 560),
            ),
            videoKeys = setOf("previous", "next"),
            viewportStart = 0,
            viewportEnd = 1_600,
        )

        assertEquals("next", selected)
    }

    @Test
    fun `video below half viewability stays prefetched but inactive`() {
        val selected = selectActiveVideoKey(
            visibleItems = listOf(FeedVisibleItem("video", offset = -390, size = 560)),
            videoKeys = setOf("video"),
            viewportStart = 0,
            viewportEnd = 1_600,
        )

        assertNull(selected)
    }

    @Test
    fun `tall video uses viewport as its maximum possible exposure`() {
        val selected = selectActiveVideoKey(
            visibleItems = listOf(FeedVisibleItem("portrait", offset = -900, size = 2_400)),
            videoKeys = setOf("portrait"),
            viewportStart = 0,
            viewportEnd = 1_600,
        )

        assertEquals("portrait", selected)
    }

    @Test
    fun `first frame preview is skipped only for the rendered active source`() {
        assertFalse(retainRenderedVideoFrame(playInline = false, coordinatorHasRenderedSource = true))
        assertFalse(retainRenderedVideoFrame(playInline = true, coordinatorHasRenderedSource = false))
        assertTrue(retainRenderedVideoFrame(playInline = true, coordinatorHasRenderedSource = true))
    }

}
