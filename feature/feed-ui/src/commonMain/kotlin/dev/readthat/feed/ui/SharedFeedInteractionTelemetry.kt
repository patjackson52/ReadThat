package dev.readthat.feed.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import dev.readthat.observability.PerformanceMetric
import dev.readthat.observability.PerformanceSurface
import dev.readthat.observability.PerformanceTelemetry
import dev.readthat.observability.performanceTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Bounded interaction dimension; never substitute ids, routes, or URLs for these values. */
enum class SharedFeedInteraction(val telemetryValue: String) {
    Vote("vote"),
    OpenDetail("open_detail"),
    OpenMedia("open_media"),
    OpenCommunity("open_community"),
    OpenProfile("open_profile"),
    OpenAdProfile("open_ad_profile"),
    OpenAd("open_ad"),
    OpenAdRelated("open_ad_related"),
    Share("share"),
    Reshare("reshare"),
}

fun SharedFeedInteraction.telemetryAttributes(): Map<String, String> =
    mapOf("interaction_type" to telemetryValue)

/**
 * Measures input-to-next-frame around the host action while keeping the metric policy in KMP.
 * Navigation, share sheets, and native media remain injected platform actions.
 */
@Stable
class SharedFeedInteractionRecorder internal constructor(
    private val surface: PerformanceSurface,
    private val scope: CoroutineScope,
) {
    fun record(interaction: SharedFeedInteraction, action: () -> Unit) {
        val timer = performanceTimer()
        action()
        scope.launch {
            withFrameNanos { }
            PerformanceTelemetry.duration(
                PerformanceMetric.INTERACTION_TO_NEXT_FRAME,
                timer,
                surface = surface,
                attributes = interaction.telemetryAttributes(),
            )
        }
    }
}

@Composable
fun rememberSharedFeedInteractionRecorder(
    surface: PerformanceSurface,
): SharedFeedInteractionRecorder {
    val scope = rememberCoroutineScope()
    return remember(surface, scope) { SharedFeedInteractionRecorder(surface, scope) }
}
