package dev.readthat.ui.app

import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import dev.readthat.domain.AdLaunchContext
import dev.readthat.domain.AdMediaKind
import dev.readthat.navigation.AppDestination
import kotlinx.serialization.Serializable

/**
 * Android's typed, SavedState-restorable representation of the shared destination contract.
 * The route types stay host-local; all product navigation meaning lives in [AppDestination].
 */
internal sealed interface AndroidRoute

@Serializable internal data object HomeRoute : AndroidRoute
@Serializable internal data class CreateRoute(val subreddit: String = "") : AndroidRoute
@Serializable internal data object CreateCommunityRoute : AndroidRoute
@Serializable internal data class CommunityCreationStatusRoute(val mutationId: String) : AndroidRoute
@Serializable internal data class PendingPostRoute(val mutationId: String) : AndroidRoute
@Serializable internal data object ActivityRoute : AndroidRoute
@Serializable internal data object ProfileRoute : AndroidRoute
@Serializable internal data object SettingsRoute : AndroidRoute
@Serializable internal data object EditProfileRoute : AndroidRoute
@Serializable internal data class PostDetailRoute(
    val postId: String,
    val focusCommentId: String? = null,
) : AndroidRoute
@Serializable internal data class ThreadDetailRoute(
    val postId: String,
    val rootCommentId: String,
) : AndroidRoute
@Serializable internal data object SearchRoute : AndroidRoute
@Serializable internal data object CommunitiesRoute : AndroidRoute
@Serializable internal data class CommunityRoute(val name: String) : AndroidRoute
@Serializable internal data class PublicProfileRoute(val username: String) : AndroidRoute
@Serializable internal data class MediaFeedRoute(
    val postId: String,
    val subreddit: String? = null,
    /** Small durable pointer to the Room-backed normal-feed generation. */
    val snapshotId: String? = null,
) : AndroidRoute
@Serializable internal data class AdDetailRoute(
    val adId: String,
    val creativeId: String,
    val mediaKind: String,
    val placeholderColor: Long,
    val aspectRatio: Float,
    val altText: String,
    val imageUrl: String? = null,
    val hlsUrl: String? = null,
    val posterUrl: String? = null,
    val fallbackUrl: String? = null,
    val cacheKey: String,
    val destinationUrl: String,
    val displayDomain: String,
    val ctaLabel: String,
    val selectedIndex: Int = 0,
    val restartAtBeginning: Boolean = false,
) : AndroidRoute

internal fun AndroidRoute.toSharedDestination(): AppDestination = when (this) {
    HomeRoute -> AppDestination.Feed
    is CreateRoute -> AppDestination.CreatePost(subreddit)
    CreateCommunityRoute -> AppDestination.CreateCommunity
    is CommunityCreationStatusRoute -> AppDestination.PendingCommunity(mutationId)
    is PendingPostRoute -> AppDestination.PendingPost(mutationId)
    ActivityRoute -> AppDestination.Activity
    ProfileRoute -> AppDestination.Profile
    SettingsRoute -> AppDestination.Settings
    EditProfileRoute -> AppDestination.EditProfile
    is PostDetailRoute -> AppDestination.PostDetail(postId, focusCommentId = focusCommentId)
    is ThreadDetailRoute -> AppDestination.PostDetail(postId, rootCommentId = rootCommentId)
    SearchRoute -> AppDestination.Search
    CommunitiesRoute -> AppDestination.Communities
    is CommunityRoute -> AppDestination.Community(name)
    is PublicProfileRoute -> AppDestination.PublicProfile(username)
    is MediaFeedRoute -> AppDestination.Media(postId, subreddit, snapshotId)
    is AdDetailRoute -> AppDestination.AdDetail(toLaunchContext())
}

/** Reads a restored Navigation Compose entry back into the canonical cross-platform model. */
internal fun NavBackStackEntry.toSharedDestinationOrNull(): AppDestination? = when {
    destination.hasRoute<HomeRoute>() -> HomeRoute
    destination.hasRoute<CreateRoute>() -> toRoute<CreateRoute>()
    destination.hasRoute<CreateCommunityRoute>() -> CreateCommunityRoute
    destination.hasRoute<CommunityCreationStatusRoute>() -> toRoute<CommunityCreationStatusRoute>()
    destination.hasRoute<PendingPostRoute>() -> toRoute<PendingPostRoute>()
    destination.hasRoute<ActivityRoute>() -> ActivityRoute
    destination.hasRoute<ProfileRoute>() -> ProfileRoute
    destination.hasRoute<SettingsRoute>() -> SettingsRoute
    destination.hasRoute<EditProfileRoute>() -> EditProfileRoute
    destination.hasRoute<PostDetailRoute>() -> toRoute<PostDetailRoute>()
    destination.hasRoute<ThreadDetailRoute>() -> toRoute<ThreadDetailRoute>()
    destination.hasRoute<SearchRoute>() -> SearchRoute
    destination.hasRoute<CommunitiesRoute>() -> CommunitiesRoute
    destination.hasRoute<CommunityRoute>() -> toRoute<CommunityRoute>()
    destination.hasRoute<PublicProfileRoute>() -> toRoute<PublicProfileRoute>()
    destination.hasRoute<MediaFeedRoute>() -> toRoute<MediaFeedRoute>()
    destination.hasRoute<AdDetailRoute>() -> toRoute<AdDetailRoute>()
    else -> null
}?.toSharedDestination()

internal fun AppDestination.toAndroidRoute(): AndroidRoute = when (this) {
    AppDestination.Feed -> HomeRoute
    is AppDestination.CreatePost -> CreateRoute(subreddit)
    AppDestination.CreateCommunity -> CreateCommunityRoute
    is AppDestination.PendingCommunity -> CommunityCreationStatusRoute(mutationId)
    is AppDestination.PendingPost -> PendingPostRoute(mutationId)
    AppDestination.Activity -> ActivityRoute
    AppDestination.Profile -> ProfileRoute
    AppDestination.Settings -> SettingsRoute
    AppDestination.EditProfile -> EditProfileRoute
    is AppDestination.PostDetail -> rootCommentId?.let { ThreadDetailRoute(postId, it) }
        ?: PostDetailRoute(postId, focusCommentId)
    AppDestination.Search -> SearchRoute
    AppDestination.Communities -> CommunitiesRoute
    is AppDestination.Community -> CommunityRoute(name)
    is AppDestination.PublicProfile -> PublicProfileRoute(username)
    is AppDestination.Media -> MediaFeedRoute(postId, subreddit, snapshotId)
    is AppDestination.AdDetail -> ad.toAndroidRoute()
}

/**
 * Keeps the call site's destination platform-neutral while dispatching with each concrete route
 * type so Navigation Compose uses the exact serializer registered by the matching composable.
 */
internal fun NavHostController.navigateShared(
    destination: AppDestination,
    builder: NavOptionsBuilder.() -> Unit = {},
) {
    when (val route = destination.toAndroidRoute()) {
        HomeRoute -> navigate(HomeRoute, builder)
        is CreateRoute -> navigate(route, builder)
        CreateCommunityRoute -> navigate(CreateCommunityRoute, builder)
        is CommunityCreationStatusRoute -> navigate(route, builder)
        is PendingPostRoute -> navigate(route, builder)
        ActivityRoute -> navigate(ActivityRoute, builder)
        ProfileRoute -> navigate(ProfileRoute, builder)
        SettingsRoute -> navigate(SettingsRoute, builder)
        EditProfileRoute -> navigate(EditProfileRoute, builder)
        is PostDetailRoute -> navigate(route, builder)
        is ThreadDetailRoute -> navigate(route, builder)
        SearchRoute -> navigate(SearchRoute, builder)
        CommunitiesRoute -> navigate(CommunitiesRoute, builder)
        is CommunityRoute -> navigate(route, builder)
        is PublicProfileRoute -> navigate(route, builder)
        is MediaFeedRoute -> navigate(route, builder)
        is AdDetailRoute -> navigate(route, builder)
    }
}

private fun AdLaunchContext.toAndroidRoute() = AdDetailRoute(
    adId = adId,
    creativeId = creativeId,
    mediaKind = kind.name,
    placeholderColor = placeholderColor,
    aspectRatio = aspectRatio,
    altText = altText,
    imageUrl = imageUrl,
    hlsUrl = hlsUrl,
    posterUrl = posterUrl,
    fallbackUrl = fallbackUrl,
    cacheKey = cacheKey,
    destinationUrl = destinationUrl,
    displayDomain = displayDomain,
    ctaLabel = ctaLabel,
    selectedIndex = selectedIndex,
    restartAtBeginning = restartAtBeginning,
)

internal fun AdDetailRoute.toLaunchContext() = AdLaunchContext(
    adId = adId,
    creativeId = creativeId,
    kind = AdMediaKind.valueOf(mediaKind),
    placeholderColor = placeholderColor,
    aspectRatio = aspectRatio,
    altText = altText,
    imageUrl = imageUrl,
    hlsUrl = hlsUrl,
    posterUrl = posterUrl,
    fallbackUrl = fallbackUrl,
    cacheKey = cacheKey,
    destinationUrl = destinationUrl,
    displayDomain = displayDomain,
    ctaLabel = ctaLabel,
    selectedIndex = selectedIndex,
    restartAtBeginning = restartAtBeginning,
)
