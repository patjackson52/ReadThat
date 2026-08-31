package dev.readthat.observability

import kotlin.math.ceil

/**
 * Platform-neutral frame-health policy shared by Android JankStats and the iOS display-frame
 * collector. Collection stays native; batching, thresholds, percentile math and wire fields do
 * not drift between hosts.
 *
 * Callers serialize access when their native callback can arrive from multiple threads.
 */
class FrameHealthAggregator(
    private val framesPerEvent: Int = DEFAULT_FRAMES_PER_EVENT,
) {
    private val buckets = mutableMapOf<PerformanceSurface, FrameBucket>()

    init {
        require(framesPerEvent > 0)
    }

    fun add(
        surface: PerformanceSurface,
        durationMs: Double,
        isJank: Boolean,
    ): PerformanceEvent? {
        if (!durationMs.isFinite() || durationMs <= 0.0) return null
        val bucket = buckets.getOrPut(surface, ::FrameBucket)
        bucket.add(durationMs, isJank)
        return if (bucket.frameCount >= framesPerEvent) {
            bucket.toEvent(surface).also { buckets.remove(surface) }
        } else {
            null
        }
    }

    /** iOS supplies CADisplayLink's current refresh budget; policy remains common and testable. */
    fun addVsyncInterval(
        surface: PerformanceSurface,
        durationMs: Double,
        frameBudgetMs: Double,
    ): PerformanceEvent? {
        if (!frameBudgetMs.isFinite() || frameBudgetMs <= 0.0) return null
        return add(
            surface = surface,
            durationMs = durationMs,
            isJank = durationMs > frameBudgetMs * JANK_BUDGET_MULTIPLIER,
        )
    }

    fun drain(): List<PerformanceEvent> = buckets.mapNotNull { (surface, bucket) ->
        bucket.takeIf { it.frameCount > 0 }?.toEvent(surface)
    }.also { buckets.clear() }

    private class FrameBucket {
        private val samples = ArrayList<Double>()
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
            if (durationMs > SLOW_FRAME_MILLIS) slowFrameCount += 1
            if (durationMs > FROZEN_FRAME_MILLIS) frozenFrameCount += 1
        }

        fun toEvent(surface: PerformanceSurface): PerformanceEvent {
            samples.sort()
            val p95 = samples[(ceil(samples.size * P95).toInt() - 1).coerceIn(0, samples.lastIndex)]
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
                    "fps" to (1_000.0 / average).coerceAtMost(MAX_REPORTED_FPS),
                ),
            )
        }
    }

    private companion object {
        const val DEFAULT_FRAMES_PER_EVENT = 300
        const val SLOW_FRAME_MILLIS = 16.67
        const val FROZEN_FRAME_MILLIS = 700.0
        const val P95 = 0.95
        const val MAX_REPORTED_FPS = 240.0
        const val JANK_BUDGET_MULTIPLIER = 2.0
    }
}
