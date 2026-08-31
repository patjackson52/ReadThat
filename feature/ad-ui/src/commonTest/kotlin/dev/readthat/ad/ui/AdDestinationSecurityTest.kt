package dev.readthat.ad.ui

import dev.readthat.observability.ProductEvent
import dev.readthat.observability.ProductEventName
import dev.readthat.observability.ProductEventReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdDestinationSecurityTest {
    @Test
    fun acceptsOnlyStructurallyValidHttpsDestinations() {
        assertTrue(isSecureAdDestination("https://example.com/landing?q=readthat"))
        assertFalse(isSecureAdDestination("http://example.com/landing"))
        assertFalse(isSecureAdDestination("https://"))
        assertFalse(isSecureAdDestination("https://user@example.com/landing"))
        assertFalse(isSecureAdDestination(" https://example.com"))
        assertFalse(isSecureAdDestination("javascript:alert(1)"))
    }

    @Test
    fun landingLoadTelemetryRecordsExactlyOneTerminalEventPerStart() {
        val events = mutableListOf<ProductEvent>()
        val tracker = AdLandingLoadTracker("ad-1", events::add)

        tracker.succeeded()
        tracker.started()
        tracker.succeeded()
        tracker.failed()
        tracker.started()
        tracker.failed()

        assertEquals(2, events.size)
        assertTrue(events.all { it.name == ProductEventName.AD_LANDING_LOAD })
        assertTrue(events.all { it.contentId == "ad-1" && (it.durationMs ?: -1L) >= 0L })
        assertNull(events[0].reason)
        assertEquals(ProductEventReason.ERROR, events[1].reason)
    }
}
