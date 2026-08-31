package dev.readthat.ui.app

import dev.readthat.domain.AdLaunchContext
import dev.readthat.domain.AdMediaKind
import dev.readthat.navigation.AppDestination
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidNavigationRoutesTest {
    @Test
    fun everySharedDestinationRoundTripsWithoutLosingHostState() {
        val destinations = listOf(
            AppDestination.Feed,
            AppDestination.Activity,
            AppDestination.Search,
            AppDestination.Communities,
            AppDestination.CreatePost("kotlin"),
            AppDestination.CreateCommunity,
            AppDestination.Profile,
            AppDestination.Settings,
            AppDestination.EditProfile,
            AppDestination.PostDetail("post", focusCommentId = "comment"),
            AppDestination.PostDetail("post", rootCommentId = "thread"),
            AppDestination.Community("androiddev"),
            AppDestination.Media("video", subreddit = "videos", snapshotId = "generation"),
            AppDestination.AdDetail(testAd()),
            AppDestination.PublicProfile("reader"),
            AppDestination.PendingPost("post-mutation"),
            AppDestination.PendingCommunity("community-mutation"),
        )

        destinations.forEach { destination ->
            assertEquals(destination, destination.toAndroidRoute().toSharedDestination())
        }
    }

    @Test
    fun everyAndroidRouteMapsToTheCanonicalSharedContract() {
        val routes = listOf<AndroidRoute>(
            HomeRoute,
            ActivityRoute,
            SearchRoute,
            CommunitiesRoute,
            CreateRoute("compose"),
            CreateCommunityRoute,
            ProfileRoute,
            SettingsRoute,
            EditProfileRoute,
            PostDetailRoute("post", "comment"),
            ThreadDetailRoute("post", "thread"),
            CommunityRoute("kotlin"),
            MediaFeedRoute("video", "media", "snapshot"),
            testAd().let { AppDestination.AdDetail(it).toAndroidRoute() },
            PublicProfileRoute("author"),
            PendingPostRoute("post-mutation"),
            CommunityCreationStatusRoute("community-mutation"),
        )

        routes.forEach { route ->
            assertEquals(route, route.toSharedDestination().toAndroidRoute())
        }
    }

    private fun testAd() = AdLaunchContext(
        adId = "ad",
        creativeId = "creative",
        kind = AdMediaKind.Video,
        placeholderColor = 0xFF112233,
        aspectRatio = 16f / 9f,
        altText = "Sponsored video",
        imageUrl = "https://cdn.example/ad.jpg",
        hlsUrl = "https://cdn.example/ad.m3u8",
        posterUrl = "https://cdn.example/poster.jpg",
        fallbackUrl = "https://cdn.example/ad.mp4",
        cacheKey = "ad:creative",
        destinationUrl = "https://example.com/offer",
        displayDomain = "example.com",
        ctaLabel = "Learn more",
        selectedIndex = 2,
        restartAtBeginning = true,
    )
}
