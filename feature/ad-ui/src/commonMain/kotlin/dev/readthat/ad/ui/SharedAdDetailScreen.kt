package dev.readthat.ad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Replay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.readthat.domain.AdLaunchContext
import dev.readthat.domain.AdMediaKind
import dev.readthat.observability.ProductAnalytics
import dev.readthat.observability.ProductContentType
import dev.readthat.observability.ProductEvent
import dev.readthat.observability.ProductEventName
import dev.readthat.observability.ProductEventReason
import dev.readthat.observability.ProductSurface
import kotlin.time.TimeMark
import kotlin.time.TimeSource

enum class AdPlaybackState { Idle, Buffering, Ready, Playing, Paused, Ended, Error }

data class AdPlaybackSnapshot(
    val state: AdPlaybackState,
    val positionMs: Long,
    val durationMs: Long?,
) {
    val completionPercent: Double?
        get() = durationMs?.takeIf { it > 0L }?.let { duration ->
            positionMs.coerceIn(0L, duration).toDouble() * 100.0 / duration.toDouble()
        }
}

typealias AdDetailVideoRenderer = @Composable (
    ad: AdLaunchContext,
    muted: Boolean,
    replayRequest: Int,
    onFirstFrame: () -> Unit,
    onPlaybackState: (AdPlaybackSnapshot) -> Unit,
    modifier: Modifier,
) -> Unit

typealias AdDetailImageRenderer = @Composable (
    url: String,
    cacheKey: String,
    videoPreview: Boolean,
    contentDescription: String?,
    modifier: Modifier,
) -> Unit

typealias AdDetailLandingRenderer = @Composable (
    ad: AdLaunchContext,
    modifier: Modifier,
) -> Unit

/** Canonical promoted-detail iconography shared by Android and iOS. */
data class AdDetailIcons(
    val close: ImageVector = Icons.Default.Close,
    val options: ImageVector = Icons.Default.MoreVert,
    val lock: ImageVector = Icons.Default.Lock,
    val replay: ImageVector = Icons.Default.Replay,
    val muted: ImageVector = Icons.AutoMirrored.Filled.VolumeOff,
    val audible: ImageVector = Icons.AutoMirrored.Filled.VolumeUp,
)

/**
 * Canonical promoted-content detail shared by Android and iOS.
 *
 * Safe-area ownership, layout, mute/replay state, first-frame poster behavior and product
 * telemetry are common. Hosts inject only decoding, native playback and the secure web surface.
 */
@Composable
fun SharedAdDetailScreen(
    ad: AdLaunchContext,
    onClose: () -> Unit,
    videoRenderer: AdDetailVideoRenderer,
    imageRenderer: AdDetailImageRenderer,
    landingRenderer: AdDetailLandingRenderer,
    icons: AdDetailIcons = AdDetailIcons(),
    initialMuted: Boolean = true,
    onMutedChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var muted by remember(ad.creativeId) { mutableStateOf(initialMuted) }
    var renderedFirstFrame by remember(ad.creativeId) { mutableStateOf(false) }
    var replayRequest by remember(ad.creativeId, ad.restartAtBeginning) {
        mutableStateOf(if (ad.restartAtBeginning) 1 else 0)
    }
    var playback by remember(ad.creativeId) {
        mutableStateOf(AdPlaybackSnapshot(AdPlaybackState.Idle, 0L, null))
    }
    val playbackAnalytics = rememberAdVideoPlaybackAnalytics(ad.adId)

    LaunchedEffect(ad.adId, ad.creativeId) {
        ProductAnalytics.record(ProductEvent(
            name = ProductEventName.AD_DETAIL_VIEW,
            surface = ProductSurface.AD_DETAIL,
            contentId = ad.adId,
            contentType = ProductContentType.AD,
        ))
    }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).background(Color(ad.placeholderColor)),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.fillMaxHeight().aspectRatio(ad.aspectRatio.coerceIn(.5f, 2.5f))) {
                if (ad.kind == AdMediaKind.Video) {
                    videoRenderer(
                        ad,
                        muted,
                        replayRequest,
                        { renderedFirstFrame = true },
                        { snapshot ->
                            playback = snapshot
                            playbackAnalytics(snapshot)
                        },
                        Modifier.fillMaxSize(),
                    )
                    val posterUrl = ad.posterUrl
                    if (!renderedFirstFrame && posterUrl != null) {
                        imageRenderer(
                            posterUrl,
                            ad.cacheKey,
                            true,
                            ad.altText,
                            Modifier.fillMaxSize(),
                        )
                    }
                    if (playback.state == AdPlaybackState.Ended) {
                        Box(
                            Modifier.fillMaxSize().background(Color.Black.copy(alpha = .44f))
                                .clickable(onClickLabel = "Replay video") { replayRequest += 1 },
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(icons.replay, null, Modifier.size(22.dp), tint = Color.White)
                                Text("REPLAY VIDEO", color = Color.White)
                            }
                        }
                    }
                } else {
                    val imageUrl = ad.imageUrl ?: ad.posterUrl
                    if (imageUrl != null) {
                        imageRenderer(imageUrl, ad.cacheKey, false, ad.altText, Modifier.fillMaxSize())
                    }
                }
            }
            Box(
                Modifier.fillMaxSize().windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
                ),
            ) {
                Surface(
                    color = Color.Black.copy(alpha = .62f),
                    shape = CircleShape,
                    modifier = Modifier.align(Alignment.TopStart).padding(14.dp).size(40.dp)
                        .clickable(onClickLabel = "Close ad", onClick = onClose),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            icons.close,
                            "Close ad",
                            Modifier.size(24.dp),
                            tint = Color.White,
                        )
                    }
                }
                Box(Modifier.align(Alignment.TopEnd).padding(14.dp).size(40.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        icons.options,
                        "Ad options",
                        Modifier.size(24.dp),
                        tint = Color.White,
                    )
                }
                if (ad.kind == AdMediaKind.Video) {
                    Surface(
                        color = Color.Black.copy(alpha = .62f),
                        shape = CircleShape,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp).size(40.dp)
                            .clickable {
                                muted = !muted
                                onMutedChanged(muted)
                            },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (muted) icons.muted else icons.audible,
                                if (muted) "Unmute" else "Mute",
                                Modifier.size(21.dp),
                                tint = Color.White,
                            )
                        }
                    }
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().weight(1f).windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            ),
        ) {
            Row(
                Modifier.fillMaxWidth().height(48.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    icons.lock,
                    null,
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    ad.displayDomain,
                    Modifier.padding(start = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            HorizontalDivider()
            landingRenderer(ad, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun rememberAdVideoPlaybackAnalytics(adId: String): (AdPlaybackSnapshot) -> Unit {
    val analytics = remember(adId) { AdVideoPlaybackAnalytics(adId) }
    DisposableEffect(analytics) {
        onDispose { analytics.finish(ProductEventReason.SURFACE_CHANGE) }
    }
    return remember(analytics) { analytics::update }
}

private class AdVideoPlaybackAnalytics(private val adId: String) {
    private var prior = AdPlaybackSnapshot(AdPlaybackState.Idle, 0L, null)
    private var watchStartedAt: TimeMark? = null
    private var watchStartedPosition = 0L
    private var completed = false

    fun update(snapshot: AdPlaybackSnapshot) {
        if (snapshot.state == AdPlaybackState.Playing && prior.state != AdPlaybackState.Playing) {
            if (prior.state == AdPlaybackState.Ended) completed = false
            watchStartedAt = TimeSource.Monotonic.markNow()
            watchStartedPosition = snapshot.positionMs
            ProductAnalytics.record(ProductEvent(
                name = ProductEventName.AD_VIDEO_PLAY,
                surface = ProductSurface.AD_DETAIL,
                contentId = adId,
                contentType = ProductContentType.AD,
                position = snapshot.positionMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            ))
        } else if (prior.state == AdPlaybackState.Playing && snapshot.state != AdPlaybackState.Playing) {
            finish(
                if (snapshot.state == AdPlaybackState.Ended) ProductEventReason.ENDED
                else ProductEventReason.PAUSE,
                snapshot,
            )
        }
        if (snapshot.state == AdPlaybackState.Ended && !completed) {
            completed = true
            ProductAnalytics.record(ProductEvent(
                name = ProductEventName.AD_VIDEO_COMPLETE,
                surface = ProductSurface.AD_DETAIL,
                contentId = adId,
                contentType = ProductContentType.AD,
                reason = ProductEventReason.ENDED,
                completionPercent = 100.0,
            ))
        }
        prior = snapshot
    }

    fun finish(reason: ProductEventReason, snapshot: AdPlaybackSnapshot = prior) {
        val startedAt = watchStartedAt ?: return
        watchStartedAt = null
        val playedMs = startedAt.elapsedNow().inWholeMilliseconds.coerceAtLeast(0L)
        if (playedMs < MIN_WATCH_MILLIS) return
        ProductAnalytics.record(ProductEvent(
            name = ProductEventName.AD_VIDEO_WATCH,
            surface = ProductSurface.AD_DETAIL,
            contentId = adId,
            contentType = ProductContentType.AD,
            reason = reason,
            durationMs = playedMs,
            position = watchStartedPosition.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            completionPercent = snapshot.completionPercent,
        ))
    }

    private companion object {
        const val MIN_WATCH_MILLIS = 100L
    }
}
