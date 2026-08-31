package dev.readthat.compose

import dev.readthat.domain.AdLaunchContext
import dev.readthat.domain.AdMediaKind
import dev.readthat.navigation.AppDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SharedDestinationStateTest {
    @Test
    fun routeArgumentsProduceDistinctCollisionSafeKeys() {
        assertNotEquals(
            AppDestination.PostDetail("post", focusCommentId = "a|1:b").saveableStateKey(),
            AppDestination.PostDetail("post", rootCommentId = "a|1:b").saveableStateKey(),
        )
        assertNotEquals(
            AppDestination.Community("ab|1:c").saveableStateKey(),
            AppDestination.Community("ab|1:d").saveableStateKey(),
        )
        assertNotEquals(
            AppDestination.Media("post", snapshotId = "one").saveableStateKey(),
            AppDestination.Media("post", snapshotId = "two").saveableStateKey(),
        )
    }

    @Test
    fun promotedStateIdentityUsesCreativeAndSelectedPageButNotLargeUrls() {
        val first = AppDestination.AdDetail(ad(selectedIndex = 0, destinationUrl = "https://one.example"))
        val sameIdentity = AppDestination.AdDetail(ad(selectedIndex = 0, destinationUrl = "https://two.example"))
        val nextPage = AppDestination.AdDetail(ad(selectedIndex = 1, destinationUrl = "https://one.example"))

        assertEquals(first.saveableStateKey(), sameIdentity.saveableStateKey())
        assertNotEquals(first.saveableStateKey(), nextPage.saveableStateKey())
    }

    @Test
    fun transientStateIsLruBoundedWhilePersistentRootsStayOutsideTheBudget() {
        val registry = BoundedDestinationStateRegistry(maximumTransientStates = 2)
        val first = AppDestination.PostDetail("first")
        val second = AppDestination.Community("second")
        val third = AppDestination.PublicProfile("third")

        assertEquals(emptyList(), registry.touch(AppDestination.Feed))
        assertEquals(emptyList(), registry.touch(first))
        assertEquals(emptyList(), registry.touch(second))
        assertEquals(emptyList(), registry.touch(first))
        assertEquals(listOf(second.saveableStateKey()), registry.touch(third))
        assertEquals(
            listOf(first.saveableStateKey(), third.saveableStateKey()),
            registry.transientSnapshot(),
        )
    }

    @Test
    fun transientBudgetMustBePositive() {
        assertFailsWith<IllegalArgumentException> { BoundedDestinationStateRegistry(0) }
    }

    @Test
    fun onlyHomeDelegatesBackDirectlyToTheOperatingSystem() {
        assertFalse(AppDestination.Feed.handlesPlatformSystemBack())
        assertTrue(AppDestination.Activity.handlesPlatformSystemBack())
        assertTrue(AppDestination.Profile.handlesPlatformSystemBack())
        assertTrue(AppDestination.PostDetail("post").handlesPlatformSystemBack())
        assertTrue(AppDestination.Media("post").handlesPlatformSystemBack())
        assertTrue(AppDestination.CreatePost().handlesPlatformSystemBack())
    }

    private fun ad(selectedIndex: Int, destinationUrl: String) = AdLaunchContext(
        adId = "ad",
        creativeId = "creative",
        kind = AdMediaKind.Image,
        placeholderColor = 0xFF000000,
        aspectRatio = 1f,
        altText = "Ad",
        imageUrl = "https://cdn.example/ad.jpg",
        hlsUrl = null,
        posterUrl = null,
        fallbackUrl = null,
        cacheKey = "ad-key",
        destinationUrl = destinationUrl,
        displayDomain = "example.com",
        ctaLabel = "Open",
        selectedIndex = selectedIndex,
    )
}
