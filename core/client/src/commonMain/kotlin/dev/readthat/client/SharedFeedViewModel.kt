package dev.readthat.client

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.flatMap
import dev.readthat.data.db.AppDatabase
import dev.readthat.data.db.CacheScope
import dev.readthat.domain.CellUi
import dev.readthat.domain.NormalFeedMediaContext
import dev.readthat.observability.PerformanceSurface
import dev.readthat.shared.PostTransitionPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Lifecycle-independent feed controller shared by focused feature ViewModels and the application
 * ViewModel. Navigation and native rendering stay at the host edge; Room Paging, refresh
 * classification, mutations, comment prefetch and media handoff have one implementation.
 */
internal class SharedFeedController(
    private val repository: OfflineFirstRepository,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val feedId: String,
    subreddit: String? = null,
    private val status: MutableStateFlow<FeedState> = MutableStateFlow(FeedState()),
) {
    val normalizedSubreddit: String? = subreddit?.trim()?.removePrefix("r/")?.lowercase()
    private val performanceSurface = if (normalizedSubreddit == null) {
        PerformanceSurface.FEED
    } else {
        PerformanceSurface.COMMUNITY
    }

    val cards: Flow<PagingData<FeedCard>> = repository.pagedFeedFor(
        feedId = feedId,
        subreddit = normalizedSubreddit,
        state = status,
    )

    val feed: Flow<PagingData<CellUi>> = cards.map { paging ->
        paging.flatMap { card -> card.cells }
    }

    val state: StateFlow<FeedState> = status

    val initialCacheTier: Flow<String?> = status.map { it.initialCacheTier }

    fun markUserRefresh() = repository.markFeedLoad(feedId, "User Refresh")

    fun markErrorRetry() = repository.markFeedLoad(feedId, "Error Retry")

    fun matchesCommunity(name: String): Boolean = normalizedSubreddit ==
        name.trim().removePrefix("r/").lowercase()

    suspend fun mediaLaunchContext(
        postId: String,
        visibleFallback: PostTransitionPreview,
    ): NormalFeedMediaContext = repository.mediaLaunchContext(feedId, postId, visibleFallback)

    fun toggleLike(itemId: String) = vote(itemId, 1)

    fun vote(itemId: String, value: Int) {
        if (itemId.isBlank() || value !in -1..1) return
        scope.launch { repository.votePost(itemId, value, performanceSurface) }
    }

    /** Feed dwell is already gated in common UI; cap speculative trees and persist successes. */
    fun prefetchComments(postIds: Set<String>) {
        postIds.asSequence().filter(String::isNotBlank).take(MAX_COMMENT_PREFETCH).forEach { postId ->
            scope.launch {
                try {
                    repository.prefetchComments(postId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // Speculative work must never replace cached feed pixels with an error.
                }
            }
        }
    }

    fun reshare(postId: String, subreddit: String, onComplete: (String?) -> Unit) {
        val target = subreddit.trim().removePrefix("r/")
        if (postId.isBlank() || target.isBlank()) return
        scope.launch {
            try {
                repository.reshare(postId, target)
                onComplete(null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                onComplete(error.message ?: "Could not reshare post")
            }
        }
    }

    private companion object { const val MAX_COMMENT_PREFETCH = 3 }
}

/** Lifecycle wrapper used by host navigation while the controller remains reusable. */
class SharedFeedViewModel(
    client: ReadThatClient,
    database: AppDatabase,
    accountId: String,
    feedId: String = CacheScope.HOME_FEED_ID,
    subreddit: String? = null,
    onVoteQueued: () -> Unit = {},
) : ViewModel() {
    private val repository = OfflineFirstRepository(
        client = client,
        database = database,
        scope = viewModelScope,
        accountIdOverride = accountId,
        onVoteQueued = onVoteQueued,
        maintainGlobalState = false,
    )
    private val controller = SharedFeedController(
        repository = repository,
        scope = viewModelScope,
        feedId = feedId,
        subreddit = subreddit,
    )

    val feed: Flow<PagingData<CellUi>> = controller.feed.cachedIn(viewModelScope)
    val initialCacheTier: StateFlow<String?> = controller.initialCacheTier.stateIn(
        viewModelScope,
        kotlinx.coroutines.flow.SharingStarted.Eagerly,
        null,
    )

    fun markUserRefresh() = controller.markUserRefresh()
    fun markErrorRetry() = controller.markErrorRetry()

    suspend fun mediaLaunchContext(
        postId: String,
        visibleFallback: PostTransitionPreview,
    ): NormalFeedMediaContext = controller.mediaLaunchContext(postId, visibleFallback)

    fun toggleLike(itemId: String) = controller.toggleLike(itemId)
    fun vote(itemId: String, value: Int) = controller.vote(itemId, value)
    fun prefetchComments(postIds: Set<String>) = controller.prefetchComments(postIds)
    fun reshare(postId: String, subreddit: String, onComplete: (String?) -> Unit) =
        controller.reshare(postId, subreddit, onComplete)
}
