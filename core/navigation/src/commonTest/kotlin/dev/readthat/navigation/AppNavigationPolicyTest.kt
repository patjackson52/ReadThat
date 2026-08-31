package dev.readthat.navigation

import dev.readthat.deeplink.ReadThatDeepLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppNavigationPolicyTest {
    @Test
    fun rootChromeAndDrawerRulesAreCanonical() {
        AppNavigationPolicy.persistentRootDestinations.forEach { destination ->
            assertTrue(AppNavigationPolicy.showsBottomNavigation(destination))
        }
        assertFalse(AppNavigationPolicy.showsBottomNavigation(AppDestination.CreatePost()))
        assertFalse(AppNavigationPolicy.showsBottomNavigation(AppDestination.Settings))
        assertTrue(AppNavigationPolicy.allowsCommunityDrawer(AppDestination.Feed))
        assertFalse(AppNavigationPolicy.allowsCommunityDrawer(AppDestination.Activity))
        assertTrue(AppNavigationPolicy.usesDetailSystemBars(AppDestination.PostDetail("post")))
        assertTrue(AppNavigationPolicy.usesDetailSystemBars(AppDestination.Media("post")))
        assertTrue(AppNavigationPolicy.isImmersive(AppDestination.AdDetail(testAd())))
        assertFalse(AppNavigationPolicy.isImmersive(AppDestination.PostDetail("post")))
    }

    @Test
    fun deepLinksAndCommunityNamesMapIdenticallyForEveryHost() {
        assertEquals(
            AppDestination.PostDetail("post"),
            ReadThatDeepLink.Post("post").toAppDestination(),
        )
        assertEquals(
            AppDestination.PostDetail("post", focusCommentId = "comment"),
            ReadThatDeepLink.Comment("post", "comment").toAppDestination(),
        )
        assertEquals(
            AppDestination.Community("kotlin"),
            AppNavigationPolicy.communityDestination("  r/Kotlin  "),
        )
        assertNull(AppNavigationPolicy.communityDestination(" r/ "))
    }

    private fun testAd() = dev.readthat.domain.AdLaunchContext(
        adId = "ad",
        creativeId = "creative",
        kind = dev.readthat.domain.AdMediaKind.Image,
        placeholderColor = 0,
        aspectRatio = 1f,
        altText = "ad",
        imageUrl = "https://cdn.example/ad.jpg",
        hlsUrl = null,
        posterUrl = null,
        fallbackUrl = null,
        cacheKey = "ad",
        destinationUrl = "https://example.com",
        displayDomain = "example.com",
        ctaLabel = "Open",
    )
}
