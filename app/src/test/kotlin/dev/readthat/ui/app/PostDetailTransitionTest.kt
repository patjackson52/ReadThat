package dev.readthat.ui.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PostDetailTransitionTest {
    @Test
    fun `detail content begins at feed post y and settles at destination y`() {
        assertEquals(
            420f,
            detailContentTranslationY(sourceY = 560f, destinationY = 140f, progress = 0f),
            0f,
        )
        assertEquals(
            0f,
            detailContentTranslationY(sourceY = 560f, destinationY = 140f, progress = 1f),
            0f,
        )
    }

    @Test
    fun `detail content supports a feed post above its final position`() {
        assertEquals(
            -90f,
            detailContentTranslationY(sourceY = 50f, destinationY = 140f, progress = 0f),
            0f,
        )
    }

    @Test
    fun `missing feed position renders content at its final position`() {
        assertEquals(
            0f,
            detailContentTranslationY(sourceY = null, destinationY = 140f, progress = 0f),
            0f,
        )
    }

    @Test
    fun `position interpolation clamps progress`() {
        assertEquals(
            420f,
            detailContentTranslationY(sourceY = 560f, destinationY = 140f, progress = -1f),
            0f,
        )
        assertEquals(
            0f,
            detailContentTranslationY(sourceY = 560f, destinationY = 140f, progress = 2f),
            0f,
        )
    }

    @Test
    fun `navigation pop reverses entry progress toward feed position`() {
        assertEquals(1f, detailContentTransitionProgress(entryProgress = 1f, popProgress = 0f), 0f)
        assertEquals(.5f, detailContentTransitionProgress(entryProgress = 1f, popProgress = .5f), 0f)
        assertEquals(0f, detailContentTransitionProgress(entryProgress = 1f, popProgress = 1f), 0f)
    }
}
