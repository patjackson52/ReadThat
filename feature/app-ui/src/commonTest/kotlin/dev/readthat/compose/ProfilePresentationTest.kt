package dev.readthat.compose

import kotlin.test.Test
import kotlin.test.assertEquals

class ProfilePresentationTest {
    @Test
    fun accountAgeUsesStableHumanScaleBuckets() {
        val day = 86_400_000L
        val now = 2_000L * day

        assertEquals("Today", accountAgeLabel(now, now))
        assertEquals("12d", accountAgeLabel(now - 12L * day, now))
        assertEquals("3mo", accountAgeLabel(now - 90L * day, now))
        assertEquals("2y", accountAgeLabel(now - 730L * day, now))
    }

    @Test
    fun invalidOrFutureCreationDateIsNew() {
        assertEquals("New", accountAgeLabel(0L, 10L))
        assertEquals("New", accountAgeLabel(20L, 10L))
    }
}
