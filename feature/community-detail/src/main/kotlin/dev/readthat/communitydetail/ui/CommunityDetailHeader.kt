package dev.readthat.communitydetail.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.readthat.image.ui.PlatformImage
import dev.readthat.image.ui.PlatformImageKind
import dev.readthat.image.ui.PlatformImageRequest
import dev.readthat.client.SharedCommunityDetailViewModel
import dev.readthat.community.ui.SharedPlatformCommunityDetailHeader as CommonPlatformCommunityDetailHeader
import dev.readthat.community.ui.SharedCommunityDetailTelemetry as CommonCommunityDetailTelemetry
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

@Composable
fun CommunityDetailTelemetry(
    viewModel: CommunityDetailViewModel,
    communityName: String,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ttiTimer = remember(communityName) { performanceTimer() }
    var ttiReported by remember(communityName) { mutableStateOf(false) }

    LaunchedEffect(Unit) { PerformanceTelemetry.enterSurface(PerformanceSurface.COMMUNITY) }
    LaunchedEffect(state.detail?.id, state.initialCacheTier) {
        val cacheTier = state.initialCacheTier
        if (!ttiReported && state.detail != null && cacheTier != null) {
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
        val startedAt = SystemClock.elapsedRealtime()
        ProductAnalytics.record(ProductEvent(
            name = ProductEventName.COMMUNITY_VIEW,
            surface = ProductSurface.COMMUNITY,
            contentId = communityName,
            contentType = ProductContentType.COMMUNITY,
        ))
        onDispose {
            ProductAnalytics.record(ProductEvent(
                name = ProductEventName.COMMUNITY_TIME_SPENT,
                surface = ProductSurface.COMMUNITY,
                contentId = communityName,
                contentType = ProductContentType.COMMUNITY,
                durationMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L),
            ))
        }
    }
}

/** Android state adapter; layout, image policy and interactions are shared. */
@Composable
fun CommunityDetailHeader(
    viewModel: CommunityDetailViewModel,
    communityName: String,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onCreatePost: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SharedCommunityHeader(
        state = SharedCommunityHeaderState(
            detail = state.detail,
            refreshing = state.refreshing,
            membershipChanging = state.membershipChanging,
            error = state.error,
        ),
        communityName = communityName,
        onBack = onBack,
        onSearch = onSearch,
        onOptions = {},
        onToggleMembership = viewModel::toggleMembership,
        onRetry = viewModel::refresh,
        onCreatePost = onCreatePost,
        avatarRenderer = { url, description, imageModifier ->
            PlatformImage(
                request = PlatformImageRequest(
                    url = url,
                    cacheKey = "community-avatar:$url",
                    kind = PlatformImageKind.Avatar,
                ),
                contentDescription = description,
                contentScale = ContentScale.Crop,
                modifier = imageModifier,
            )
        },
        modifier = modifier,
    )
}

/** Temporary Android lifecycle adapter while the legacy detail implementation remains. */
@Composable
fun SharedCommunityDetailTelemetry(
    viewModel: SharedCommunityDetailViewModel,
    communityName: String,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CommonCommunityDetailTelemetry(state = state, communityName = communityName)
}

@Composable
fun SharedCommunityDetailHeader(
    viewModel: SharedCommunityDetailViewModel,
    communityName: String,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onCreatePost: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CommonPlatformCommunityDetailHeader(
        state = state,
        communityName = communityName,
        onBack = onBack,
        onSearch = onSearch,
        onToggleMembership = viewModel::toggleMembership,
        onRetry = viewModel::refresh,
        onCreatePost = onCreatePost,
        modifier = modifier,
    )
}
