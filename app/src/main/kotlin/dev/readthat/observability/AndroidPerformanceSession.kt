package dev.readthat.observability

import androidx.metrics.performance.FrameData
import dev.readthat.observability.PerformanceEvent
import dev.readthat.observability.PerformanceMetric
import dev.readthat.observability.PerformanceOutcome
import dev.readthat.observability.PerformanceSurface
import dev.readthat.observability.PerformanceTelemetry
import dev.readthat.observability.PerformanceTimer
import dev.readthat.observability.performanceTimer
import kotlin.math.ceil

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
    private val buckets = mutableMapOf<PerformanceSurface, FrameBucket>()

    fun onFrame(frame: FrameData) {
        val surface = PerformanceTelemetry.currentSurface
        val event = synchronized(lock) {
            val bucket = buckets.getOrPut(surface, ::FrameBucket)
            bucket.add(frame.frameDurationUiNanos / 1_000_000.0, frame.isJank)
            if (bucket.frameCount >= FRAMES_PER_EVENT) {
                bucket.toEvent(surface).also { buckets[surface] = FrameBucket() }
            } else null
        }
        event?.let(PerformanceTelemetry::record)
    }

    fun flush() {
        val events = synchronized(lock) {
            buckets.mapNotNull { (surface, bucket) ->
                bucket.takeIf { it.frameCount > 0 }?.toEvent(surface)
            }.also { buckets.clear() }
        }
        events.forEach(PerformanceTelemetry::record)
    }

    private class FrameBucket {
        private val samples = ArrayList<Double>(FRAMES_PER_EVENT)
        var frameCount = 0
            private set
        private var totalDurationMs = 0.0
        private var jankCount = 0
        private var slowFrameCount = 0
        private var frozenFrameCount = 0

        fun add(durationMs: Double, isJank: Boolean) {
            frameCount += 1
            totalDurationMs += durationMs
            samples += durationMs
            if (isJank) jankCount += 1
            if (durationMs > 16.67) slowFrameCount += 1
            if (durationMs > 700.0) frozenFrameCount += 1
        }

        fun toEvent(surface: PerformanceSurface): PerformanceEvent {
            samples.sort()
            val p95 = samples[(ceil(samples.size * 0.95).toInt() - 1).coerceIn(0, samples.lastIndex)]
            val average = totalDurationMs / frameCount
            return PerformanceEvent(
                name = PerformanceMetric.SCREEN_FRAME_SUMMARY,
                value = p95,
                surface = surface,
                measurements = mapOf(
                    "frame_count" to frameCount.toDouble(),
                    "jank_count" to jankCount.toDouble(),
                    "slow_frame_count" to slowFrameCount.toDouble(),
                    "frozen_frame_count" to frozenFrameCount.toDouble(),
                    "fps" to (1_000.0 / average).coerceAtMost(240.0),
                ),
            )
        }
    }

    private companion object { const val FRAMES_PER_EVENT = 300 }
}
