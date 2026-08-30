package dev.readthat.observability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class ProductAnalyticsTest {
    @Test
    fun recorderReceivesBehaviorEvent() {
        var captured: ProductEvent? = null
        ProductAnalytics.install { captured = it }

        ProductAnalytics.record(ProductEvent(
            name = ProductEventName.POST_DETAIL_VIEW,
            surface = ProductSurface.DETAIL,
            contentId = "post-1",
            contentType = ProductContentType.POST,
        ))

        assertEquals(ProductEventName.POST_DETAIL_VIEW, captured?.name)
        assertEquals("post-1", captured?.contentId)
    }

    @Test
    fun wireFormatRetainsSessionAndInstallationIdentity() {
        val root = ProductAnalyticsWireFormat.encode(ProductAnalyticsBatch(
            platform = "android",
            appVersion = "1.0",
            buildType = "debug",
            installationId = "123e4567-e89b-42d3-a456-426614174001",
            sessionId = "123e4567-e89b-42d3-a456-426614174002",
            events = listOf(ProductEvent(
                name = ProductEventName.MEDIA_PLAYBACK,
                surface = ProductSurface.FEED,
                durationMs = 1_500,
            )),
        )).jsonObject

        assertNotNull(root["schemaVersion"])
        assertNotNull(root["installationId"])
        assertNotNull(root["sessionId"])
        val event = root.getValue("events").jsonArray.first().jsonObject
        assertEquals("media_playback", event.getValue("name").toString().trim('"'))
    }

    @Test
    fun wireFormatSupportsPromotedContentFunnelEvents() {
        val root = ProductAnalyticsWireFormat.encode(ProductAnalyticsBatch(
            platform = "android",
            appVersion = "1.0",
            buildType = "debug",
            installationId = "123e4567-e89b-42d3-a456-426614174001",
            sessionId = "123e4567-e89b-42d3-a456-426614174002",
            events = listOf(ProductEvent(
                name = ProductEventName.AD_VIDEO_WATCH,
                surface = ProductSurface.AD_DETAIL,
                contentId = "patrick-platform-01",
                contentType = ProductContentType.AD,
                durationMs = 12_500,
                position = 18_000,
                completionPercent = 72.0,
            )),
        )).jsonObject

        val event = root.getValue("events").jsonArray.first().jsonObject
        assertEquals("ad_video_watch", event.getValue("name").toString().trim('"'))
        assertEquals("AD_DETAIL", event.getValue("surface").toString().trim('"'))
        assertEquals("AD", event.getValue("contentType").toString().trim('"'))
        assertEquals("12500", event.getValue("durationMs").toString())
    }
}
