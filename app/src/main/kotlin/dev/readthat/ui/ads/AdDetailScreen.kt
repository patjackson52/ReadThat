package dev.readthat.ui.ads

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.readthat.ad.ui.SharedPlatformAdDetailScreen
import dev.readthat.ad.ui.PlatformAdLanding
import dev.readthat.observability.ProductAnalytics
import dev.readthat.observability.ProductContentType
import dev.readthat.observability.ProductEvent
import dev.readthat.observability.ProductEventName
import dev.readthat.observability.ProductEventReason
import dev.readthat.observability.ProductSurface
import dev.readthat.playback.AdaptiveVideoPlayer
import dev.readthat.playback.AdaptiveVideoSource
import dev.readthat.playback.VideoPlaybackCoordinator
import dev.readthat.playback.VideoPlaybackRole
import dev.readthat.playback.VideoPlaybackSnapshot
import dev.readthat.playback.VideoPlaybackState
import dev.readthat.domain.AdLaunchContext
import dev.readthat.domain.AdMediaKind
import dev.readthat.shared.AppSettings
import dev.readthat.playback.rememberVideoPlaybackPolicy

/** Android host for the canonical shared ad-detail layout and analytics. */
@Composable
fun AdDetailScreen(
    ad: AdLaunchContext,
    settings: AppSettings,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = remember(ad.cacheKey, ad.hlsUrl, ad.fallbackUrl) {
        AdaptiveVideoSource(ad.hlsUrl, ad.fallbackUrl, ad.cacheKey)
    }
    SharedPlatformAdDetailScreen(
        ad = ad,
        settings = settings,
        onClose = onClose,
        initialMuted = remember(source) { VideoPlaybackCoordinator.isMuted(source) ?: true },
        onMutedChanged = { muted -> VideoPlaybackCoordinator.setMuted(source, muted) },
        modifier = modifier,
    )
}

/** Mature Android implementation retained compiled as a migration reference. */
@Composable
fun LegacyAdDetailScreen(
    ad: AdLaunchContext,
    settings: AppSettings,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val videoPolicy = rememberVideoPlaybackPolicy(settings)
    val source = remember(ad.cacheKey, ad.hlsUrl, ad.fallbackUrl) {
        AdaptiveVideoSource(ad.hlsUrl, ad.fallbackUrl, ad.cacheKey)
    }
    var playback by remember(ad.creativeId) {
        mutableStateOf(VideoPlaybackSnapshot(VideoPlaybackState.Idle, 0L, null))
    }
    var priorState by remember(ad.creativeId) { mutableStateOf(VideoPlaybackState.Idle) }
    var watchStartedAt by remember(ad.creativeId) { mutableLongStateOf(0L) }
    var watchStartedPosition by remember(ad.creativeId) { mutableLongStateOf(0L) }
    var muted by remember(ad.creativeId) {
        mutableStateOf(VideoPlaybackCoordinator.isMuted(source) ?: true)
    }
    // Keep every stage of the funnel joinable by ad id. The selected creative
    // remains part of the route and media cache key, while `position` records
    // playback depth in milliseconds for video events.
    val analyticsId = ad.adId

    fun finishWatch(snapshot: VideoPlaybackSnapshot, reason: ProductEventReason) {
        if (watchStartedAt == 0L) return
        val duration = (SystemClock.elapsedRealtime() - watchStartedAt).coerceAtLeast(0L)
        watchStartedAt = 0L
        if (duration < 100L) return
        ProductAnalytics.record(ProductEvent(
            name = ProductEventName.AD_VIDEO_WATCH,
            surface = ProductSurface.AD_DETAIL,
            contentId = analyticsId,
            contentType = ProductContentType.AD,
            reason = reason,
            durationMs = duration,
            position = watchStartedPosition.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            completionPercent = snapshot.completionPercent,
        ))
    }

    fun playbackChanged(snapshot: VideoPlaybackSnapshot) {
        val old = priorState
        playback = snapshot
        priorState = snapshot.state
        if (snapshot.state == VideoPlaybackState.Playing && old != VideoPlaybackState.Playing) {
            watchStartedAt = SystemClock.elapsedRealtime()
            watchStartedPosition = snapshot.positionMs
            ProductAnalytics.record(ProductEvent(
                name = ProductEventName.AD_VIDEO_PLAY,
                surface = ProductSurface.AD_DETAIL,
                contentId = analyticsId,
                contentType = ProductContentType.AD,
                position = snapshot.positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            ))
        } else if (old == VideoPlaybackState.Playing && snapshot.state != VideoPlaybackState.Playing) {
            finishWatch(
                snapshot,
                if (snapshot.state == VideoPlaybackState.Ended) {
                    ProductEventReason.ENDED
                } else {
                    ProductEventReason.PAUSE
                },
            )
        }
        if (snapshot.state == VideoPlaybackState.Ended && old != VideoPlaybackState.Ended) {
            ProductAnalytics.record(ProductEvent(
                name = ProductEventName.AD_VIDEO_COMPLETE,
                surface = ProductSurface.AD_DETAIL,
                contentId = analyticsId,
                contentType = ProductContentType.AD,
                reason = ProductEventReason.ENDED,
                completionPercent = 100.0,
            ))
        }
    }

    LaunchedEffect(ad.adId, ad.creativeId) {
        ProductAnalytics.record(ProductEvent(
            name = ProductEventName.AD_DETAIL_VIEW,
            surface = ProductSurface.AD_DETAIL,
            contentId = ad.adId,
            contentType = ProductContentType.AD,
        ))
    }
    LaunchedEffect(ad.restartAtBeginning, source) {
        if (ad.restartAtBeginning && ad.kind == AdMediaKind.Video) {
            withFrameNanos { }
            VideoPlaybackCoordinator.replay(source)
        }
    }
    DisposableEffect(ad.creativeId) {
        onDispose { finishWatch(playback, ProductEventReason.SURFACE_CHANGE) }
    }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(Color(ad.placeholderColor)),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.fillMaxHeight().aspectRatio(ad.aspectRatio)) {
                if (ad.kind == AdMediaKind.Video) {
                    AdaptiveVideoPlayer(
                        source = source,
                        policy = videoPolicy,
                        autoplay = true,
                        muted = muted,
                        showControls = false,
                        role = VideoPlaybackRole.AdDetail,
                        continueExistingPlayback = true,
                        onPlaybackState = ::playbackChanged,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (playback.state == VideoPlaybackState.Ended) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = .44f))
                                .clickable { VideoPlaybackCoordinator.replay(source) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.Replay, null, tint = Color.White)
                                Text("REPLAY VIDEO", color = Color.White)
                            }
                        }
                    }
                } else {
                    AsyncImage(
                        model = ad.imageUrl ?: ad.posterUrl,
                        contentDescription = ad.altText,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Icon(
                Icons.Default.Close,
                "Close ad",
                Modifier.align(Alignment.TopStart).padding(16.dp).size(24.dp).clickable(onClick = onClose),
            )
            Icon(
                Icons.Default.MoreVert,
                "Ad options",
                Modifier.align(Alignment.TopEnd).padding(16.dp).size(24.dp),
            )
            if (ad.kind == AdMediaKind.Video) {
                Surface(
                    color = Color.Black.copy(alpha = .55f),
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                        .size(38.dp)
                        .clickable {
                            muted = !muted
                            VideoPlaybackCoordinator.setMuted(source, muted)
                        },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            if (muted) "Unmute" else "Mute",
                            Modifier.size(21.dp),
                            tint = Color.White,
                        )
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().height(48.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                ad.displayDomain,
                modifier = Modifier.padding(start = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        PlatformAdLanding(
            ad = ad,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}
