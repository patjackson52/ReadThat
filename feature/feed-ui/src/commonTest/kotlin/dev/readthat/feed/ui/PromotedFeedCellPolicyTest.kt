package dev.readthat.feed.ui

import dev.readthat.observability.ProductEvent
import dev.readthat.observability.ProductEventName
import dev.readthat.observability.ProductEventReason
import dev.readthat.observability.ProductSurface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PromotedFeedCellPolicyTest {
    @Test
    fun compactCountsMatchMatureFeedPresentation() {
        assertEquals("999", compactPromotedCount(999))
        assertEquals("1.0k", compactPromotedCount(1_000))
        assertEquals("12.5k", compactPromotedCount(12_500))
        assertEquals("1.2M", compactPromotedCount(1_250_000))
    }

    @Test
    fun playbackAnalyticsUsesMediaMillisecondsAndMeasuredWatchDuration() {
        var elapsed = 1_000L
        val events = mutableListOf<ProductEvent>()
        val analytics = PromotedPlaybackAnalytics(
            adId = "ad-1",
            surface = ProductSurface.FEED,
            elapsedMillis = { elapsed },
            record = events::add,
        )

        analytics.update(PromotedPlaybackSnapshot(PromotedPlaybackState.Playing, 2_500L, 10_000L))
        elapsed += 275L
        analytics.update(PromotedPlaybackSnapshot(PromotedPlaybackState.Paused, 2_775L, 10_000L))

        assertEquals(listOf(ProductEventName.AD_VIDEO_PLAY, ProductEventName.AD_VIDEO_WATCH), events.map { it.name })
        assertEquals(2_500, events[0].position)
        assertEquals(275L, events[1].durationMs)
        assertEquals(2_500, events[1].position)
        assertEquals(27.75, events[1].completionPercent)
        assertEquals(ProductEventReason.PAUSE, events[1].reason)
    }

    @Test
    fun completionIsRecordedOnceAndShortWatchNoiseIsSuppressed() {
        var elapsed = 0L
        val events = mutableListOf<ProductEvent>()
        val analytics = PromotedPlaybackAnalytics(
            adId = "ad-2",
            surface = ProductSurface.FEED,
            elapsedMillis = { elapsed },
            record = events::add,
        )

        analytics.update(PromotedPlaybackSnapshot(PromotedPlaybackState.Playing, 900L, 1_000L))
        elapsed = 75L
        val ended = PromotedPlaybackSnapshot(PromotedPlaybackState.Ended, 1_000L, 1_000L)
        analytics.update(ended)
        analytics.update(ended)

        assertEquals(
            listOf(ProductEventName.AD_VIDEO_PLAY, ProductEventName.AD_VIDEO_COMPLETE),
            events.map { it.name },
        )
        assertEquals(100.0, events.last().completionPercent)
        assertNull(events.last().position)
    }
}
