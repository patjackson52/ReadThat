package dev.readthat.observability

import androidx.metrics.performance.FrameData

/**
 * Allocation-light JankStats aggregation. It exports one bounded distribution
 * every 300 frames per surface rather than doing telemetry work per frame.
 */
class FramePerformanceAggregator {
    private val lock = Any()
    private val aggregator = FrameHealthAggregator()

    fun onFrame(frame: FrameData) {
        val surface = PerformanceTelemetry.currentSurface
        val event = synchronized(lock) {
            aggregator.add(surface, frame.frameDurationUiNanos / 1_000_000.0, frame.isJank)
        }
        event?.let(PerformanceTelemetry::record)
    }

    fun flush() {
        val events = synchronized(lock) { aggregator.drain() }
        events.forEach(PerformanceTelemetry::record)
    }
}
