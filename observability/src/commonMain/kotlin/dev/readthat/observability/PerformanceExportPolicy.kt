package dev.readthat.observability

/**
 * Normalizes both current and persisted legacy metrics at the network boundary.
 * Keeping this in common code prevents one platform from wedging its durable FIFO on a retired
 * metric or an accidentally high-cardinality dimension.
 */
fun PerformanceEvent.sanitizedForExport(): PerformanceEvent? {
    if (name !in ALLOWED_METRICS ||
        !value.isFinite() || value !in 0.0..MAX_SAFE_NUMBER ||
        recordedAtEpochMs <= 0L
    ) return null
    val normalizedAttributes = buildMap {
        attributes.forEach { (name, value) ->
            if (name in ALLOWED_ATTRIBUTES && value.isNotBlank()) put(name, value.take(80))
        }
        if ("route" !in this) {
            attributes["purpose"]?.takeIf(String::isNotBlank)?.let { put("route", it.take(80)) }
        }
    }
    val normalizedMeasurements = buildMap {
        measurements.forEach { (name, value) ->
            if (name in ALLOWED_MEASUREMENTS && value.isFinite() && value in 0.0..MAX_SAFE_NUMBER) {
                put(name, value)
            }
        }
        if ("bytes_in" !in this) {
            measurements["response_bytes"]
                ?.takeIf { it.isFinite() && it in 0.0..MAX_SAFE_NUMBER }
                ?.let { put("bytes_in", it) }
        }
    }
    return copy(attributes = normalizedAttributes, measurements = normalizedMeasurements)
}

private val ALLOWED_METRICS = setOf(
    "home_tti", "feed_initial_fetch", "feed-load-success", "feed-load-fail",
    "feed_query_response_size", "comments_tti", "comments_initial_fetch",
    "comments_full_fetch", "community_tti", "community_initial_fetch",
    "media_feed_tti", "mutation_local_commit", "mutation_server_ack",
    "interaction_to_next_frame", "screen_frame_summary", "network_request",
    "video_time_to_first_frame", "video_rebuffer", "sdui_dropped_cell",
    "largest_contentful_paint", "interaction_to_next_paint", "cumulative_layout_shift",
)
private val ALLOWED_ATTRIBUTES = setOf(
    "start_type", "load_type", "mutation_type", "cache_tier", "protocol", "route",
    "network_type", "content_kind", "phase", "interaction_type", "from_prefetch",
    "status_class",
)
private val ALLOWED_MEASUREMENTS = setOf(
    "frame_count", "jank_count", "slow_frame_count", "frozen_frame_count", "fps",
    "bytes_in", "bytes_out", "edge_ms", "cache_hit", "dropped_count",
)
private const val MAX_SAFE_NUMBER = 9_007_199_254_740_991.0
