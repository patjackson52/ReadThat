package dev.readthat.client

import dev.readthat.observability.ProductContentType
import dev.readthat.observability.ProductEvent
import dev.readthat.observability.ProductEventName
import dev.readthat.observability.ProductSurface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProductAnalyticsSanitizerTest {
    @Test
    fun rejectsUnknownOrInvalidlyTimestampedEvents() {
        assertNull(ProductEvent(
            name = "unbounded_custom_event",
            surface = ProductSurface.APP,
        ).sanitizedForProductAnalytics())
        assertNull(ProductEvent(
            name = ProductEventName.POST_IMPRESSION,
            surface = ProductSurface.FEED,
            recordedAtEpochMs = 0,
        ).sanitizedForProductAnalytics())
    }

    @Test
    fun removesUnsafeIdentifiersAndBoundsMeasurements() {
        val sanitized = ProductEvent(
            name = ProductEventName.MEDIA_PLAYBACK,
            surface = ProductSurface.MEDIA,
            recordedAtEpochMs = 1,
            contentId = "https://signed.example/video?token=secret",
            contentType = ProductContentType.VIDEO,
            durationMs = Long.MAX_VALUE,
            position = Int.MAX_VALUE,
            completionPercent = Double.NaN,
        ).sanitizedForProductAnalytics()

        assertNull(sanitized?.contentId)
        assertEquals(7L * 24L * 60L * 60L * 1_000L, sanitized?.durationMs)
        assertEquals(24 * 60 * 60 * 1_000, sanitized?.position)
        assertNull(sanitized?.completionPercent)
    }

    @Test
    fun preservesAllowedStableContentKeys() {
        val event = ProductEvent(
            name = ProductEventName.POST_DETAIL_VIEW,
            surface = ProductSurface.DETAIL,
            recordedAtEpochMs = 1,
            contentId = "post:abc-123_v2",
            contentType = ProductContentType.POST,
            completionPercent = 125.0,
        )

        assertEquals("post:abc-123_v2", event.sanitizedForProductAnalytics()?.contentId)
        assertEquals(100.0, event.sanitizedForProductAnalytics()?.completionPercent)
    }
}
