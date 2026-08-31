package dev.readthat.observability

import androidx.metrics.performance.FrameData
import dev.readthat.observability.FrameHealthAggregator
import dev.readthat.observability.PerformanceMetric
import dev.readthat.observability.PerformanceOutcome
import dev.readthat.observability.PerformanceSurface
import dev.readthat.observability.PerformanceTelemetry
import dev.readthat.observability.PerformanceTimer
import dev.readthat.observability.performanceTimer

/** User-journey timers whose boundaries cross navigation destinations. */
class AndroidPerformanceSession(
    private val homeTimer: PerformanceTimer,
    private val startType: String,
) {
    private var homeRecorded = false
    private var commentsTimer: PerformanceTimer? = null

    fun homeInteractive(cacheTier: String) {
        if (homeRecorded) return
        homeRecorded = true
        PerformanceTelemetry.duration(
            PerformanceMetric.HOME_TTI,
            homeTimer,
            surface = PerformanceSurface.FEED,
            attributes = mapOf("start_type" to startType, "cache_tier" to cacheTier),
        )
    }

    fun beginComments() {
        commentsTimer = performanceTimer()
    }

    fun commentsInteractive(fromPrefetch: Boolean, phase: String, successful: Boolean) {
        val timer = commentsTimer ?: return
        commentsTimer = null
        PerformanceTelemetry.duration(
            PerformanceMetric.COMMENTS_TTI,
            timer,
            surface = PerformanceSurface.DETAIL,
            outcome = if (successful) PerformanceOutcome.SUCCESS else PerformanceOutcome.FAILURE,
            attributes = mapOf(
                "from_prefetch" to fromPrefetch.toString(),
                "phase" to phase,
            ),
        )
    }
}

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
