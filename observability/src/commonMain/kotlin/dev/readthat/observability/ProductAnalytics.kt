package dev.readthat.observability

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** Bounded product-event names. Content identifiers belong in [ProductEvent.contentId]. */
object ProductEventName {
    const val SESSION_START = "session_start"
    const val SESSION_FOREGROUND = "session_foreground"
    const val SESSION_CHECKPOINT = "session_checkpoint"
    const val SESSION_SUMMARY = "session_summary"
    const val POST_IMPRESSION = "post_impression"
    const val POST_DETAIL_VIEW = "post_detail_view"
    const val COMMENTS_VIEW = "comments_view"
    const val COMMENT_CREATE = "comment_create"
    const val MEDIA_PLAYBACK = "media_playback"
    const val MEDIA_FEED_TIME_SPENT = "media_feed_time_spent"
    const val COMMUNITY_VIEW = "community_view"
    const val COMMUNITY_TIME_SPENT = "community_time_spent"
    const val COMMUNITY_JOIN = "community_join"
    const val COMMUNITY_LEAVE = "community_leave"
    const val COMMUNITY_POST_VIEW = "community_post_view"
    const val AD_IMPRESSION = "ad_impression"
    const val AD_VIEW_TIME = "ad_view_time"
    const val AD_CLICK = "ad_click"
    const val AD_CTA_CLICK = "ad_cta_click"
    const val AD_CAROUSEL_SWIPE = "ad_carousel_swipe"
    const val AD_RELATED_CLICK = "ad_related_click"
    const val AD_VIDEO_PLAY = "ad_video_play"
    const val AD_VIDEO_WATCH = "ad_video_watch"
    const val AD_VIDEO_COMPLETE = "ad_video_complete"
    const val AD_DETAIL_VIEW = "ad_detail_view"
    const val AD_LANDING_LOAD = "ad_landing_load"
}

@Serializable
enum class ProductSurface { APP, FEED, DETAIL, COMMENTS, COMMUNITY, MEDIA, AD_DETAIL, BACKGROUND }

@Serializable
enum class ProductContentType { POST, COMMENT, COMMUNITY, VIDEO, AD }

@Serializable
enum class ProductEventReason {
    COLD_START,
    FOREGROUND,
    BACKGROUND,
    TIMEOUT,
    IDENTITY_CHANGE,
    PAUSE,
    ENDED,
    MEDIA_CHANGE,
    SURFACE_CHANGE,
    ERROR,
}

/**
 * Privacy-bounded behavior event. The server irreversibly pseudonymizes
 * [contentId] before storage; no titles, bodies, URLs, usernames, or tokens are
 * accepted by the wire schema.
 */
@Serializable
data class ProductEvent(
    val name: String,
    val surface: ProductSurface,
    val recordedAtEpochMs: Long = epochMilliseconds(),
    val contentId: String? = null,
    val contentType: ProductContentType? = null,
    val reason: ProductEventReason? = null,
    val durationMs: Long? = null,
    val position: Int? = null,
    val completionPercent: Double? = null,
)

@Serializable
data class ProductAnalyticsBatch(
    val schemaVersion: Int = 1,
    val platform: String,
    val appVersion: String,
    val buildType: String,
    /** Random UUID persisted only in app-private storage; never stored raw by the server. */
    val installationId: String,
    /** Foreground engagement session, distinct from both process and authentication sessions. */
    val sessionId: String,
    val events: List<ProductEvent>,
)

object ProductAnalyticsWireFormat {
    private val json = Json { explicitNulls = false; encodeDefaults = true }

    fun encode(batch: ProductAnalyticsBatch): JsonElement = json.encodeToJsonElement(batch)
}

fun interface ProductAnalyticsRecorder {
    /** Must return immediately; the platform exporter owns persistence and network work. */
    fun record(event: ProductEvent)
}

/** Vendor-neutral feature seam. The Android app installs its lifecycle-aware exporter. */
object ProductAnalytics : ProductAnalyticsRecorder {
    private var delegate: ProductAnalyticsRecorder = ProductAnalyticsRecorder { }

    fun install(recorder: ProductAnalyticsRecorder) {
        delegate = recorder
    }

    override fun record(event: ProductEvent) = delegate.record(event)
}
