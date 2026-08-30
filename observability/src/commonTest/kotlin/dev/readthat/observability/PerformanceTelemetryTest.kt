package dev.readthat.observability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class PerformanceTelemetryTest {
    @Test
    fun recorderReceivesTypedEvent() {
        var captured: PerformanceEvent? = null
        PerformanceTelemetry.install { captured = it }
        PerformanceTelemetry.enterSurface(PerformanceSurface.FEED)
        PerformanceTelemetry.duration(PerformanceMetric.HOME_TTI, performanceTimer())

        assertEquals(PerformanceMetric.HOME_TTI, captured?.name)
        assertEquals(PerformanceSurface.FEED, captured?.surface)
        assertTrue(requireNotNull(captured).value >= 0.0)
    }

    @Test
    fun wireFormatRetainsRequiredDefaultFields() {
        val root = PerformanceWireFormat.encode(PerformanceBatch(
            platform = "android",
            appVersion = "1.0",
            buildType = "debug",
            sessionId = "8f71cd90-1c32-4b80-9a70-43f8605ee7d1",
            events = listOf(PerformanceEvent(name = PerformanceMetric.HOME_TTI, value = 42.0)),
        )).jsonObject
        assertNotNull(root["schemaVersion"])
        val event = root.getValue("events").jsonArray.first().jsonObject
        assertNotNull(event["unit"])
        assertNotNull(event["surface"])
        assertNotNull(event["outcome"])
    }
}
