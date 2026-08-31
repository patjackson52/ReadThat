package dev.readthat.feed.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class FeedColorTest {
    @Test
    fun parsesRgbAndArgbFlairColorsWithFallback() {
        val fallback = Color.Magenta

        assertEquals(Color(0xFF123456), parseFeedColor("#123456", fallback))
        assertEquals(Color(0x80123456), parseFeedColor("80123456", fallback))
        assertEquals(fallback, parseFeedColor("not-a-color", fallback))
    }
}
