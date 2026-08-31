package dev.readthat.mediafeed.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import dev.readthat.client.SharedMediaFeedViewModel
import dev.readthat.mediafeed.domain.MediaFeedItem
import dev.readthat.observability.PerformanceTimer
import dev.readthat.shared.AppSettings

/**
 * Android host for the canonical KMP media-feed surface.
 *
 * This file owns only Android image decoding and Media3 playback/preloading. Paging geometry,
 * gallery state, gestures, chrome, actions, telemetry and preload selection live in the shared
 * feature module. [LegacyMediaFeedScreen] remains compiled beside this host as migration reference.
 */
@Composable
fun MediaFeedScreen(
    viewModel: SharedMediaFeedViewModel,
    settings: AppSettings,
    onClose: () -> Unit,
    onOpenDetails: (MediaFeedItem) -> Unit,
    onOpenCommunity: (String) -> Unit,
    onOpenUser: (String) -> Unit,
    onShare: (MediaFeedItem) -> Unit = {},
    interactionTimer: PerformanceTimer? = null,
    modifier: Modifier = Modifier,
) {
    val items = viewModel.feed.collectAsLazyPagingItems()
    val navigationItems by viewModel.navigationItems.collectAsStateWithLifecycle()
    val initialCacheTier by viewModel.initialCacheTier.collectAsStateWithLifecycle()
    SharedPlatformMediaFeedRoute(
        items = items,
        navigationItems = navigationItems,
        restoredPage = remember(viewModel) { viewModel.restoredPage },
        onCurrentPageChanged = viewModel::setCurrentPage,
        onNavigationHydrated = viewModel::releaseNavigationFallback,
        onClose = onClose,
        onOpenDetails = onOpenDetails,
        onOpenCommunity = onOpenCommunity,
        onOpenUser = onOpenUser,
        onVote = viewModel::vote,
        onShare = onShare,
        settings = settings,
        initialCacheTier = initialCacheTier,
        interactionTimer = interactionTimer,
        modifier = modifier,
    )
}
