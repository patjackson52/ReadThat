package dev.readthat.community.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import dev.readthat.client.SharedCommunityDetailState
import dev.readthat.feed.ui.CommunityAvatarRenderer
import dev.readthat.feed.ui.SharedCommunityHeader
import dev.readthat.feed.ui.SharedCommunityHeaderState
import dev.readthat.observability.PerformanceMetric
import dev.readthat.observability.PerformanceSurface
import dev.readthat.observability.PerformanceTelemetry
import dev.readthat.observability.ProductAnalytics
import dev.readthat.observability.ProductContentType
import dev.readthat.observability.ProductEvent
import dev.readthat.observability.ProductEventName
import dev.readthat.observability.ProductSurface
import dev.readthat.observability.performanceTimer
import kotlin.time.TimeSource

/**
 * Resolves the authoritative tier for the first rendered community frame.
 *
 * A successful render is never guessed as network: it waits for the shared detail controller's
 * Room/network provenance. Paging can independently prove an offline cached render, while a
 * terminal empty result is reported as an error state instead of a misleading TTI success.
 */
fun communityDetailInitialRenderTier(
    state: SharedCommunityDetailState,
    hasFeedContent: Boolean,
    feedLoadFailed: Boolean,
): String? {
    val hasContent = state.detail != null || hasFeedContent
    if (hasContent) {
        return state.initialCacheTier ?: when {
            hasFeedContent && (state.offline || feedLoadFailed) -> "room_offline"
            else -> null
        }
    }
    return if (feedLoadFailed || (!state.refreshing && state.error != null)) "error_state" else null
}

/** Community TTI and dwell policy shared by the Android and iOS hosts. */
@Composable
fun SharedCommunityDetailTelemetry(
    state: SharedCommunityDetailState,
    communityName: String,
    hasFeedContent: Boolean = false,
    feedLoadFailed: Boolean = false,
) {
    val ttiTimer = remember(communityName) { performanceTimer() }
    var ttiReported by remember(communityName) { mutableStateOf(false) }
    val initialRenderTier = communityDetailInitialRenderTier(
        state = state,
        hasFeedContent = hasFeedContent,
        feedLoadFailed = feedLoadFailed,
    )

    LaunchedEffect(Unit) { PerformanceTelemetry.enterSurface(PerformanceSurface.COMMUNITY) }
    LaunchedEffect(initialRenderTier) {
        val cacheTier = initialRenderTier ?: return@LaunchedEffect
        if (!ttiReported) {
            withFrameNanos { }
            ttiReported = true
            PerformanceTelemetry.duration(
                PerformanceMetric.COMMUNITY_TTI,
                ttiTimer,
                surface = PerformanceSurface.COMMUNITY,
                attributes = mapOf("cache_tier" to cacheTier),
            )
        }
    }
    DisposableEffect(communityName) {
        val startedAt = TimeSource.Monotonic.markNow()
        onDispose {
            ProductAnalytics.record(ProductEvent(
                name = ProductEventName.COMMUNITY_TIME_SPENT,
                surface = ProductSurface.COMMUNITY,
                contentId = communityName,
                contentType = ProductContentType.COMMUNITY,
                durationMs = startedAt.elapsedNow().inWholeMilliseconds.coerceAtLeast(0L),
            ))
        }
    }
}

/** Canonical community-detail chrome; hosts inject only their native image/cache renderer. */
@Composable
fun SharedCommunityDetailHeader(
    state: SharedCommunityDetailState,
    communityName: String,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onOptions: () -> Unit = {},
    onToggleMembership: () -> Unit,
    onRetry: () -> Unit,
    onCreatePost: () -> Unit,
    avatarRenderer: CommunityAvatarRenderer,
    modifier: Modifier = Modifier,
) {
    SharedCommunityHeader(
        state = SharedCommunityHeaderState(
            detail = state.detail,
            refreshing = state.refreshing,
            membershipChanging = state.membershipChanging,
            offline = state.offline,
            error = state.error,
        ),
        communityName = communityName,
        onBack = onBack,
        onSearch = onSearch,
        onOptions = onOptions,
        onToggleMembership = onToggleMembership,
        onRetry = onRetry,
        onCreatePost = onCreatePost,
        avatarRenderer = avatarRenderer,
        modifier = modifier,
    )
}
