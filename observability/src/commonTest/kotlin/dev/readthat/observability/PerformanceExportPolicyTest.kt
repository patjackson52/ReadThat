package dev.readthat.observability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PerformanceExportPolicyTest {
    @Test
    fun legacyDimensionsAreNormalizedAtTheSharedExportBoundary() {
        val sanitized = PerformanceEvent(
            name = PerformanceMetric.NETWORK_REQUEST,
            value = 12.0,
            recordedAtEpochMs = 1L,
            attributes = mapOf(
                "purpose" to "image",
                "content_id" to "must-not-leave-device",
                "protocol" to "h3",
            ),
            measurements = mapOf(
                "response_bytes" to 42.0,
                "unbounded" to 9.0,
            ),
        ).sanitizedForExport()

        assertEquals(mapOf("protocol" to "h3", "route" to "image"), sanitized?.attributes)
        assertEquals(mapOf("bytes_in" to 42.0), sanitized?.measurements)
    }

    @Test
    fun retiredOrInvalidMetricsAreDropped() {
        assertNull(PerformanceEvent(name = "retired_metric", value = 1.0).sanitizedForExport())
        assertNull(PerformanceEvent(
            name = PerformanceMetric.HOME_TTI,
            value = Double.NaN,
        ).sanitizedForExport())
    }
}
