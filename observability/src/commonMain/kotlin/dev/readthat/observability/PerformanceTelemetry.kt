package dev.readthat.observability

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.math.roundToLong
import kotlin.time.TimeSource

/** Stable Reddit/industry metric names. Do not put ids or URLs in a name. */
object PerformanceMetric {
    const val HOME_TTI = "home_tti"
    const val FEED_INITIAL_FETCH = "feed_initial_fetch"
    const val FEED_LOAD_SUCCESS = "feed-load-success"
    const val FEED_LOAD_FAIL = "feed-load-fail"
    const val FEED_QUERY_RESPONSE_SIZE = "feed_query_response_size"
    const val COMMENTS_TTI = "comments_tti"
    const val COMMENTS_INITIAL_FETCH = "comments_initial_fetch"
    const val COMMENTS_FULL_FETCH = "comments_full_fetch"
    const val COMMUNITY_TTI = "community_tti"
    const val COMMUNITY_INITIAL_FETCH = "community_initial_fetch"
    const val MEDIA_FEED_TTI = "media_feed_tti"
    const val MUTATION_LOCAL_COMMIT = "mutation_local_commit"
    const val MUTATION_SERVER_ACK = "mutation_server_ack"
    const val INTERACTION_TO_NEXT_FRAME = "interaction_to_next_frame"
    const val SCREEN_FRAME_SUMMARY = "screen_frame_summary"
    const val NETWORK_REQUEST = "network_request"
    const val VIDEO_TIME_TO_FIRST_FRAME = "video_time_to_first_frame"
    const val VIDEO_REBUFFER = "video_rebuffer"
    const val SDUI_DROPPED_CELL = "sdui_dropped_cell"
    const val LARGEST_CONTENTFUL_PAINT = "largest_contentful_paint"
    const val INTERACTION_TO_NEXT_PAINT = "interaction_to_next_paint"
    const val CUMULATIVE_LAYOUT_SHIFT = "cumulative_layout_shift"
}

@Serializable
enum class PerformanceUnit {
    MILLISECOND,
    BYTE,
    COUNT,
    PERCENT,
}

@Serializable
enum class PerformanceSurface {
    APP,
    FEED,
    DETAIL,
    COMMUNITY,
    CREATE_POST,
    MEDIA,
    BACKGROUND,
    UNKNOWN,
}

@Serializable
enum class PerformanceOutcome {
    SUCCESS,
    FAILURE,
    QUEUED,
    CANCELLED,
}

/**
 * One bounded distribution sample. Attributes are dimensions, measurements are
 * numeric side values. Exporters must reject unknown/high-cardinality fields.
 */
@Serializable
data class PerformanceEvent(
    val name: String,
    val value: Double,
    val unit: PerformanceUnit = PerformanceUnit.MILLISECOND,
    val surface: PerformanceSurface = PerformanceSurface.UNKNOWN,
    val outcome: PerformanceOutcome = PerformanceOutcome.SUCCESS,
    val recordedAtEpochMs: Long = epochMilliseconds(),
    val attributes: Map<String, String> = emptyMap(),
    val measurements: Map<String, Double> = emptyMap(),
)

@Serializable
data class PerformanceBatch(
    val schemaVersion: Int = 1,
    val platform: String,
    val appVersion: String,
    val buildType: String,
    /** Random per process. It is not an account, installation, advertising, or device id. */
    val sessionId: String,
    val events: List<PerformanceEvent>,
)

/** Stable wire encoder: required default-valued fields must never disappear. */
object PerformanceWireFormat {
    private val json = Json {
        explicitNulls = false
        encodeDefaults = true
    }

    fun encode(batch: PerformanceBatch): JsonElement = json.encodeToJsonElement(batch)
}

fun interface PerformanceRecorder {
    /** Must return immediately; disk/network export belongs off the caller thread. */
    fun record(event: PerformanceEvent)
}

/** Installed once by the platform app; feature modules remain vendor-neutral. */
object PerformanceTelemetry : PerformanceRecorder {
    private var delegate: PerformanceRecorder = PerformanceRecorder { }

    var currentSurface: PerformanceSurface = PerformanceSurface.APP
        private set

    fun install(recorder: PerformanceRecorder) {
        delegate = recorder
    }

    fun enterSurface(surface: PerformanceSurface) {
        currentSurface = surface
    }

    override fun record(event: PerformanceEvent) {
        delegate.record(event)
    }

    fun duration(
        name: String,
        timer: PerformanceTimer,
        surface: PerformanceSurface = currentSurface,
        outcome: PerformanceOutcome = PerformanceOutcome.SUCCESS,
        attributes: Map<String, String> = emptyMap(),
        measurements: Map<String, Double> = emptyMap(),
    ) = record(PerformanceEvent(
        name = name,
        value = timer.elapsedMilliseconds(),
        surface = surface,
        outcome = outcome,
        attributes = attributes,
        measurements = measurements,
    ))
}

/** Monotonic elapsed time; immune to wall-clock corrections and time-zone changes. */
class PerformanceTimer internal constructor(
    private val started: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow(),
) {
    fun elapsedMilliseconds(): Double =
        (started.elapsedNow().inWholeNanoseconds / 1_000.0).roundToLong() / 1_000.0
}

fun performanceTimer(): PerformanceTimer = PerformanceTimer()

internal expect fun epochMilliseconds(): Long
