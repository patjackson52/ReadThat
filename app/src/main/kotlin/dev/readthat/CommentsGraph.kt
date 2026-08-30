package dev.readthat

import android.content.Context
import dev.readthat.comments.data.CommentsRepository
import dev.readthat.comments.data.cache.RoomCommentsCache
import dev.readthat.data.backend.BackendGraph
import dev.readthat.data.db.CacheScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manual DI for the comments stack — one instance, shared between the feed and
 * every detail screen. This is not a style choice: the feed's dwell-prefetch only
 * pays off if the detail screen reads the SAME repository. A per-screen instance
 * silently turns prefetch into dead code.
 *
 * Production builds use the HTTP remote source; repository tests inject the
 * deterministic fake so prefetch/cold-load timing remains testable.
 */
object CommentsGraph {
    @Volatile private var repositoryInstance: CommentsRepository? = null

    val repository: CommentsRepository
        get() = checkNotNull(repositoryInstance) { "CommentsGraph.initialize must run before Compose" }

    fun initialize(context: Context) {
        if (repositoryInstance != null) return
        synchronized(this) {
            if (repositoryInstance == null) {
                val appContext = context.applicationContext
                repositoryInstance = CommentsRepository(
                    api = BackendGraph.comments(appContext),
                    local = RoomCommentsCache(appContext),
                    accountId = {
                        BackendGraph.repository(appContext).activeAccountId
                            ?: CacheScope.DEFAULT_ACCOUNT_ID
                    },
                    postInteractions = PostInteractionGraph.repository(appContext),
                )
            }
        }
    }

    private val _warmPosts = MutableStateFlow(0)
    /** How many posts currently sit in the prefetch cache — the feed shows this. */
    val warmPosts: StateFlow<Int> = _warmPosts.asStateFlow()

    suspend fun prefetch(postId: String) {
        repository.prefetch(postId)
        _warmPosts.value = repository.prefetchedPostIds.size
    }
}
