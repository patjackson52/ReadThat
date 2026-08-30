package dev.readthat.flows.repo

import dev.readthat.flows.model.Connectivity
import dev.readthat.flows.model.Post
import dev.readthat.flows.model.Settings
import dev.readthat.flows.source.FakeRemoteSource
import dev.readthat.flows.source.LocalStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/**
 * Where streams get *combined*.
 *
 * The rule that matters: `combine` emits whenever ANY input emits, pairing the new
 * value with the latest of the others. It therefore does not emit at all until every
 * input has produced at least one value — which is why every input here is either a
 * StateFlow (always has a value) or a flow that emits an initial value up front.
 * One never-emitting input silently stalls the whole combination, and that is the
 * single most common `combine` bug.
 */
class FeedRepository(
    private val local: LocalStore,
    private val remote: FakeRemoteSource,
) {

    /**
     * Cached posts filtered by the user's blocked-subreddit setting.
     *
     * Both inputs are StateFlows, so this emits immediately and again on any change
     * to either. `distinctUntilChanged` stops a settings change that doesn't affect
     * the visible result from churning the UI.
     */
    fun visiblePosts(): Flow<List<Post>> =
        combine(local.cachedPosts, local.settings) { posts, settings ->
            posts.filterNot { it.subreddit in settings.blockedSubreddits }
        }.distinctUntilChanged()

    /**
     * Three-way combine into a single view of the world.
     *
     * Note `connectivity` is passed in rather than owned here — the repository
     * shouldn't care whether it came from a real ConnectivityManager or a fake.
     */
    fun feedSnapshot(connectivity: Flow<Connectivity>): Flow<FeedSnapshot> =
        combine(
            visiblePosts(),
            local.settings,
            connectivity,
        ) { posts, settings, network ->
            FeedSnapshot(
                posts = posts,
                settings = settings,
                connectivity = network,
                // A derived field computed in one place instead of in every collector.
                canAutoplay = settings.autoplayVideo && network == Connectivity.ONLINE,
            )
        }.distinctUntilChanged()

    /**
     * Stream posts for a subreddit and write each page through to the local cache.
     *
     * `map` with a side effect on the way past is how "network writes to cache,
     * UI reads from cache" gets wired when you don't want a full offline layer.
     * (`onEach` would be the more idiomatic spot for a pure side effect.)
     */
    fun streamAndCache(subreddit: String): Flow<List<Post>> =
        remote.streamPosts(subreddit)
            .map { page ->
                local.cachePosts(page)
                page
            }
}

data class FeedSnapshot(
    val posts: List<Post>,
    val settings: Settings,
    val connectivity: Connectivity,
    val canAutoplay: Boolean,
)
