package dev.readthat.compose

import dev.readthat.navigation.AppDestination
import dev.readthat.observability.PerformanceSurface

/**
 * One cross-platform destination-to-observability contract for the shared application host.
 * Keeping the exhaustive mapping outside composition prevents Android and iOS route telemetry
 * from silently drifting when a destination is added.
 */
internal fun AppDestination.performanceSurface(): PerformanceSurface = when (this) {
    AppDestination.Feed, AppDestination.Activity -> PerformanceSurface.FEED
    is AppDestination.CreatePost, is AppDestination.PendingPost -> PerformanceSurface.CREATE_POST
    AppDestination.CreateCommunity,
    is AppDestination.PendingCommunity,
    AppDestination.Communities,
    is AppDestination.Community,
    -> PerformanceSurface.COMMUNITY
    AppDestination.Profile,
    AppDestination.EditProfile,
    is AppDestination.PublicProfile,
    AppDestination.Settings,
    AppDestination.Search,
    is AppDestination.AdDetail,
    -> PerformanceSurface.APP
    is AppDestination.PostDetail -> PerformanceSurface.DETAIL
    is AppDestination.Media -> PerformanceSurface.MEDIA
}
