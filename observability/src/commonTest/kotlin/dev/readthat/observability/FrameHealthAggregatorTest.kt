package dev.readthat.observability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FrameHealthAggregatorTest {
    @Test
    fun emitsSharedFramePolicyAtBoundedBatchSize() {
        val aggregator = FrameHealthAggregator(framesPerEvent = 3)

        assertNull(aggregator.add(PerformanceSurface.FEED, 8.0, isJank = false))
        assertNull(aggregator.add(PerformanceSurface.FEED, 20.0, isJank = true))
        val event = aggregator.add(PerformanceSurface.FEED, 800.0, isJank = true)

        assertEquals(PerformanceMetric.SCREEN_FRAME_SUMMARY, event?.name)
        assertEquals(PerformanceSurface.FEED, event?.surface)
        assertEquals(800.0, event?.value)
        assertEquals(3.0, event?.measurements?.get("frame_count"))
        assertEquals(2.0, event?.measurements?.get("jank_count"))
        assertEquals(2.0, event?.measurements?.get("slow_frame_count"))
        assertEquals(1.0, event?.measurements?.get("frozen_frame_count"))
        assertEquals(emptyList(), aggregator.drain())
    }

    @Test
    fun drainKeepsSurfacesSeparateAndDropsInvalidSamples() {
        val aggregator = FrameHealthAggregator()

        assertNull(aggregator.add(PerformanceSurface.FEED, Double.NaN, isJank = true))
        assertNull(aggregator.add(PerformanceSurface.FEED, 0.0, isJank = true))
        aggregator.add(PerformanceSurface.FEED, 12.0, isJank = false)
        aggregator.add(PerformanceSurface.DETAIL, 24.0, isJank = true)

        val events = aggregator.drain().associateBy(PerformanceEvent::surface)
        assertEquals(1.0, events.getValue(PerformanceSurface.FEED).measurements["frame_count"])
        assertEquals(0.0, events.getValue(PerformanceSurface.FEED).measurements["jank_count"])
        assertEquals(1.0, events.getValue(PerformanceSurface.DETAIL).measurements["jank_count"])
        assertEquals(emptyList(), aggregator.drain())
    }

    @Test
    fun nativeVsyncBudgetUsesSharedJankThreshold() {
        val aggregator = FrameHealthAggregator(framesPerEvent = 2)

        assertNull(aggregator.addVsyncInterval(PerformanceSurface.MEDIA, 16.0, 8.0))
        val event = aggregator.addVsyncInterval(PerformanceSurface.MEDIA, 16.1, 8.0)

        assertEquals(1.0, event?.measurements?.get("jank_count"))
        assertNull(aggregator.addVsyncInterval(PerformanceSurface.MEDIA, 10.0, 0.0))
    }
}
