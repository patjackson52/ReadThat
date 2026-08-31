package dev.readthat.compose

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaZoomTest {
    @Test
    fun `one times scale always hands paging an unshifted image`() {
        assertEquals(
            Offset.Zero,
            dev.readthat.mediafeed.ui.constrainMediaOffset(
                Offset(80f, -40f), scale = 1f, viewportWidth = 400, viewportHeight = 800,
            ),
        )
    }

    @Test
    fun `zoomed image translation is bounded to visible content`() {
        assertEquals(
            Offset(200f, -400f),
            dev.readthat.mediafeed.ui.constrainMediaOffset(
                proposed = Offset(900f, -900f),
                scale = 2f,
                viewportWidth = 400,
                viewportHeight = 800,
            ),
        )
    }

    @Test
    fun `invalid viewport cannot retain a stale pan`() {
        assertEquals(
            Offset.Zero,
            dev.readthat.mediafeed.ui.constrainMediaOffset(
                Offset(50f, 50f), scale = 3f, viewportWidth = 0, viewportHeight = 800,
            ),
        )
    }
}
