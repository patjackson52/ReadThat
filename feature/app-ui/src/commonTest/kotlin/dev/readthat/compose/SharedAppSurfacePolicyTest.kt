package dev.readthat.compose

import dev.readthat.navigation.AppDestination
import dev.readthat.observability.PerformanceSurface
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedAppSurfacePolicyTest {
    @Test
    fun rootDestinationsUseStableCrossPlatformTelemetrySurfaces() {
        assertEquals(PerformanceSurface.FEED, AppDestination.Feed.performanceSurface())
        assertEquals(PerformanceSurface.FEED, AppDestination.Activity.performanceSurface())
        assertEquals(PerformanceSurface.APP, AppDestination.Search.performanceSurface())
        assertEquals(PerformanceSurface.APP, AppDestination.Profile.performanceSurface())
        assertEquals(PerformanceSurface.APP, AppDestination.Settings.performanceSurface())
    }

    @Test
    fun featureDestinationsUseTheirFunctionalSurface() {
        assertEquals(
            PerformanceSurface.CREATE_POST,
            AppDestination.CreatePost("kotlin").performanceSurface(),
        )
        assertEquals(
            PerformanceSurface.CREATE_POST,
            AppDestination.PendingPost("mutation").performanceSurface(),
        )
        assertEquals(
            PerformanceSurface.COMMUNITY,
            AppDestination.Community("kotlin").performanceSurface(),
        )
        assertEquals(
            PerformanceSurface.DETAIL,
            AppDestination.PostDetail("post").performanceSurface(),
        )
        assertEquals(
            PerformanceSurface.MEDIA,
            AppDestination.Media("post").performanceSurface(),
        )
    }
}
