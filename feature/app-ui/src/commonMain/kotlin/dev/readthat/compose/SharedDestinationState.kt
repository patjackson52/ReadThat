package dev.readthat.compose

import dev.readthat.navigation.AppDestination
import dev.readthat.navigation.AppNavigationPolicy

/**
 * Stable, saveable identity for destination-local Compose state.
 *
 * Length-prefixed segments avoid delimiter collisions without putting complete promoted URLs or
 * other large server payloads into the platform state registry. The fields retained here are the
 * same fields that make two destinations behaviorally distinct to the shared navigation model.
 */
internal fun AppDestination.saveableStateKey(): String = when (this) {
    AppDestination.Feed -> "feed"
    AppDestination.Activity -> "activity"
    AppDestination.Search -> "search"
    AppDestination.Communities -> "communities"
    is AppDestination.CreatePost -> stateKey("create_post", subreddit)
    AppDestination.CreateCommunity -> "create_community"
    AppDestination.Profile -> "profile"
    AppDestination.Settings -> "settings"
    AppDestination.EditProfile -> "edit_profile"
    is AppDestination.PostDetail -> stateKey("post_detail", postId, focusCommentId, rootCommentId)
    is AppDestination.Community -> stateKey("community", name)
    is AppDestination.Media -> stateKey("media", postId, subreddit, snapshotId)
    is AppDestination.AdDetail -> stateKey(
        "ad_detail",
        ad.adId,
        ad.creativeId,
        ad.cacheKey,
        ad.selectedIndex.toString(),
        ad.restartAtBeginning.toString(),
    )
    is AppDestination.PublicProfile -> stateKey("public_profile", username)
    is AppDestination.PendingPost -> stateKey("pending_post", mutationId)
    is AppDestination.PendingCommunity -> stateKey("pending_community", mutationId)
}

private fun stateKey(type: String, vararg values: String?): String = buildString {
    append(type)
    values.forEach { value ->
        append('|')
        if (value == null) {
            append('-')
        } else {
            append(value.length)
            append(':')
            append(value)
        }
    }
}

/**
 * Bounds disposed destination state while keeping the persistent IA roots available across long
 * detail/thread journeys. Evicting UI state never evicts Room/controller state; revisiting an old
 * route simply reconstructs its local scroll/pager state from durable content.
 */
internal class BoundedDestinationStateRegistry(
    private val maximumTransientStates: Int = DEFAULT_MAXIMUM_TRANSIENT_STATES,
) {
    private val transientKeys = ArrayDeque<String>()

    init {
        require(maximumTransientStates > 0) { "maximumTransientStates must be positive" }
    }

    fun touch(destination: AppDestination): List<String> {
        if (destination in AppNavigationPolicy.persistentRootDestinations) return emptyList()

        val key = destination.saveableStateKey()
        transientKeys.remove(key)
        transientKeys.addLast(key)
        return buildList {
            while (transientKeys.size > maximumTransientStates) {
                add(transientKeys.removeFirst())
            }
        }
    }

    internal fun transientSnapshot(): List<String> = transientKeys.toList()

    private companion object {
        const val DEFAULT_MAXIMUM_TRANSIENT_STATES = 12
    }
}
