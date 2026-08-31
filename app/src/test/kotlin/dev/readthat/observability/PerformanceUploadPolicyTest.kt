package dev.readthat.observability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceUploadPolicyTest {
    @Test
    fun onlyNonRetryableClientFailuresArePermanent() {
        assertTrue(isPermanentTelemetryHttpFailure(400))
        assertTrue(isPermanentTelemetryHttpFailure(422))
        assertFalse(isPermanentTelemetryHttpFailure(408))
        assertFalse(isPermanentTelemetryHttpFailure(429))
        assertFalse(isPermanentTelemetryHttpFailure(500))
    }

    @Test
    fun productAnalyticsUsesTheSamePermanentFailureBoundary() {
        assertTrue(isPermanentProductAnalyticsHttpFailure(400))
        assertTrue(isPermanentProductAnalyticsHttpFailure(422))
        assertFalse(isPermanentProductAnalyticsHttpFailure(408))
        assertFalse(isPermanentProductAnalyticsHttpFailure(429))
        assertFalse(isPermanentProductAnalyticsHttpFailure(500))
    }

    @Test
    fun sessionIdSupportsMatureAndSharedOutboxRowIds() {
        val matureSession = "123e4567-e89b-42d3-a456-426614174000"
        val sharedEvent = "123e4567-e89b-42d3-a456-426614174001"

        assertEquals(matureSession, performanceUploadSessionId("$matureSession:42"))
        assertEquals(sharedEvent, performanceUploadSessionId("metric:$sharedEvent"))
    }

    @Test
    fun malformedLegacyRowUsesAnAnonymousFallbackSession() {
        val fallback = "123e4567-e89b-42d3-a456-426614174002"

        assertEquals(fallback, performanceUploadSessionId("legacy-row", fallback))
    }
}
