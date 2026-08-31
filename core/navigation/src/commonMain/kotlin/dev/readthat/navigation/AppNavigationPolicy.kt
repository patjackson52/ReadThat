package dev.readthat.navigation

import dev.readthat.deeplink.ReadThatDeepLink

/**
 * Cross-platform product navigation and root-information-architecture policy.
 *
 * Platform hosts retain their native back-stack/state-registry adapters, but they must not decide
 * independently which destinations are roots, show persistent chrome, or accept the drawer.
 */
object AppNavigationPolicy {
    val primaryNavigationDestinations: List<AppDestination> = listOf(
        AppDestination.Feed,
        AppDestination.CreatePost(),
        AppDestination.Activity,
        AppDestination.Profile,
    )

    val persistentRootDestinations: Set<AppDestination> = setOf(
        AppDestination.Feed,
        AppDestination.Activity,
        AppDestination.Profile,
    )

    fun showsBottomNavigation(destination: AppDestination?): Boolean =
        destination in persistentRootDestinations

    fun allowsCommunityDrawer(destination: AppDestination?): Boolean =
        destination == AppDestination.Feed

    fun usesDetailSystemBars(destination: AppDestination?): Boolean =
        destination is AppDestination.PostDetail || destination is AppDestination.Media

    fun isImmersive(destination: AppDestination?): Boolean =
        destination is AppDestination.Media || destination is AppDestination.AdDetail

    fun communityDestination(rawName: String): AppDestination.Community? = rawName
        .trim()
        .removePrefix("r/")
        .lowercase()
        .takeIf(String::isNotBlank)
        ?.let { name -> AppDestination.Community(name) }
}

fun ReadThatDeepLink.toAppDestination(): AppDestination.PostDetail = when (this) {
    is ReadThatDeepLink.Post -> AppDestination.PostDetail(postId)
    is ReadThatDeepLink.Comment -> AppDestination.PostDetail(postId, focusCommentId = commentId)
}
