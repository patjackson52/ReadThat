package dev.readthat.navigation

import dev.readthat.domain.AdLaunchContext

/**
 * Platform-neutral application destinations.
 *
 * Platform hosts may use their own serializable route representation, but they
 * must map to this contract instead of owning a second navigation policy.
 */
sealed interface AppDestination {
    data object Feed : AppDestination
    data object Activity : AppDestination
    data object Search : AppDestination
    data object Communities : AppDestination
    data class CreatePost(val subreddit: String = "") : AppDestination
    data object CreateCommunity : AppDestination
    data object Profile : AppDestination
    data object Settings : AppDestination
    data object EditProfile : AppDestination
    data class PostDetail(
        val postId: String,
        val focusCommentId: String? = null,
        val rootCommentId: String? = null,
    ) : AppDestination {
        init {
            require(focusCommentId == null || rootCommentId == null) {
                "A post destination cannot focus a comment and isolate a thread simultaneously"
            }
        }
    }
    data class Community(val name: String) : AppDestination
    data class Media(
        val postId: String,
        val subreddit: String? = null,
        val snapshotId: String? = null,
    ) : AppDestination
    data class AdDetail(val ad: AdLaunchContext) : AppDestination
    data class PublicProfile(val username: String) : AppDestination
    data class PendingPost(val mutationId: String) : AppDestination
    data class PendingCommunity(val mutationId: String) : AppDestination
}

/** Bounded platform-neutral history so every host applies identical root-pop behavior. */
class DestinationHistory(private val maxDepth: Int = DEFAULT_MAX_DEPTH) {
    private val entries = ArrayDeque<AppDestination>()

    init {
        require(maxDepth > 0) { "maxDepth must be positive" }
    }

    fun record(current: AppDestination, next: AppDestination) {
        if (next in AppNavigationPolicy.persistentRootDestinations) {
            entries.clear()
        } else {
            entries.addLast(current)
            if (entries.size > maxDepth) entries.removeFirst()
        }
    }

    fun popOrFeed(): AppDestination = entries.removeLastOrNull() ?: AppDestination.Feed

    /** Ordered oldest-to-newest so a restored stack preserves exact Back behavior. */
    fun snapshot(): List<AppDestination> = entries.toList()

    fun restore(restored: Iterable<AppDestination>) {
        entries.clear()
        restored.toList().takeLast(maxDepth).forEach(entries::addLast)
    }

    fun clear() = entries.clear()

    private companion object {
        const val DEFAULT_MAX_DEPTH = 32

    }
}
