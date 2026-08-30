package dev.readthat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dev.readthat.data.FeedRepository
import dev.readthat.domain.CellUi
import dev.readthat.domain.NormalFeedMediaContext
import dev.readthat.shared.PostTransitionPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * State holder for the feed.
 *
 * Note how little is left here compared with the hand-rolled version this
 * replaced. There is no page list, no cursor, no `loadMoreIfNeeded`, no
 * `isAppending` flag and no reducer — **Paging 3 owns all of it**, and the
 * DB owns the data. What remains is one flow and one intent.
 */
class FeedViewModel(
    private val repository: FeedRepository,
    private val prefetchCommentTree: suspend (String) -> Unit = {},
    private val resharePost: suspend (postId: String, subreddit: String) -> Unit = { _, _ -> },
) : ViewModel() {

    /**
     * `cachedIn(viewModelScope)` is not optional.
     *
     * Without it, every configuration change re-collects the Pager from
     * scratch — refetching page one and dropping the user's scroll position.
     * With it, the already-loaded pages survive rotation in the ViewModel.
     *
     * It also makes the flow shareable: two collectors get the same pages
     * rather than each starting their own Pager.
     */
    val feed: Flow<PagingData<CellUi>> = repository.feed().cachedIn(viewModelScope)

    /** Forward-compat telemetry, straight through from the repository. */
    val droppedCellTypes: StateFlow<Map<String, Int>> = repository.droppedCellTypes
    val initialCacheTier: StateFlow<String?> = repository.initialCacheTier

    fun markUserRefresh() = repository.markUserRefresh()

    fun markErrorRetry() = repository.markErrorRetry()

    suspend fun mediaLaunchContext(
        postId: String,
        visibleFallback: PostTransitionPreview,
    ): NormalFeedMediaContext = repository.mediaLaunchContext(postId, visibleFallback)

    /**
     * Optimistic like.
     *
     * Writes `item_state`; Room invalidates the PagingSource; Paging re-emits
     * the affected page. No manual list surgery, and nothing to keep in sync.
     */
    fun toggleLike(itemId: String) {
        if (itemId.isBlank()) return
        viewModelScope.launch { repository.toggleLike(itemId) }
    }

    fun vote(itemId: String, value: Int) {
        if (itemId.isBlank() || value !in -1..1) return
        viewModelScope.launch { repository.vote(itemId, value) }
    }

    /** Feed dwell is a UI intent; the ViewModel owns the prefetch side effect. */
    fun prefetchComments(postIds: Set<String>) {
        postIds.forEach { postId ->
            if (postId.isNotBlank()) viewModelScope.launch { prefetchCommentTree(postId) }
        }
    }

    fun reshare(postId: String, subreddit: String, onComplete: (String?) -> Unit) {
        if (postId.isBlank() || subreddit.trim().removePrefix("r/").isBlank()) return
        viewModelScope.launch {
            runCatching { resharePost(postId, subreddit) }
                .onSuccess { onComplete(null) }
                .onFailure { onComplete(it.message ?: "Could not reshare post") }
        }
    }
}
