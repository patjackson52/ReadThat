package dev.readthat.comments.data

import dev.readthat.comments.data.cache.CommentsLocalCache
import dev.readthat.comments.domain.CommentNode
import dev.readthat.comments.domain.CommentTree
import dev.readthat.comments.domain.LoadMoreResponse
import dev.readthat.comments.domain.RawComment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.ConcurrentHashMap
import dev.readthat.core.post.PostInteractionRepository
import dev.readthat.observability.PerformanceSurface
import dev.readthat.shared.VoteSnapshot

/**
 * Transport boundary for post detail. The detail feature intentionally owns a
 * domain contract, not feed SDUI; an app can back this with the fixture server
 * or the Cloudflare API without changing merge, collapse, or rendering logic.
 */
interface CommentsRemoteSource {
    suspend fun fetchTree(
        postId: String,
        maxCount: Int,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
        rootCommentId: String? = null,
    ): CommentTree

    suspend fun fetchFocusedTree(
        postId: String,
        focusCommentId: String,
        maxCount: Int = SECOND_PHASE_COUNT,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
    ): CommentTree = fetchTree(postId, maxCount, maxDepth)

    fun peekPostHeader(postId: String): dev.readthat.comments.domain.PostHeader? = null

    suspend fun fetchPostHeader(postId: String): dev.readthat.comments.domain.PostHeader

    suspend fun loadMore(
        postId: String,
        cursor: CommentNode.LoadMore,
        limit: Int,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
    ): LoadMoreResponse

    suspend fun createComment(postId: String, parentId: String?, body: String): RawComment =
        throw UnsupportedOperationException("Comment creation is not configured")

    suspend fun voteComment(commentId: String, value: Int): CommentVoteResult =
        throw UnsupportedOperationException("Comment voting is not configured")

    suspend fun votePost(postId: String, value: Int): CommentVoteResult =
        throw UnsupportedOperationException("Post voting is not configured")

    companion object {
        const val FIRST_PHASE_COUNT = 8
        const val SECOND_PHASE_COUNT = 200
        const val DEFAULT_MAX_DEPTH = 10
    }
}

data class CommentVoteResult(val id: String, val value: Int, val score: Int)

/**
 * Two-phase comment loading, plus the feed-side prefetch — with the two properties
 * that make prefetching at Reddit's published scale (~40,000 extra req/s across
 * platforms) survivable rather than reckless:
 *
 *  1. **Coalescing.** A tap-through while the prefetch is still on the wire AWAITS
 *     that request instead of racing a duplicate. One request, two consumers.
 *
 *  2. **Retention.** The full (count=200) tree is kept per post. Revisiting a post
 *     renders instantly from memory with zero fetches. Reddit requests fixed
 *     (count, depth) sizes precisely so responses are cacheable server-side — a
 *     client that then throws every response away is leaving the benefit on the
 *     table client-side.
 */
open class CommentsRepository(
    private val api: CommentsRemoteSource,
    private val local: CommentsLocalCache = CommentsLocalCache.None,
    private val accountId: () -> String = { DEFAULT_ACCOUNT_ID },
    private val postInteractions: PostInteractionRepository? = null,
) {
    /** Completed phase-1 trees, keyed by post — consumed on first tap-through. */
    private val prefetched = BoundedLruCache<CacheKey, CommentTree>(MAX_PREFETCHED_TREES)

    /** Phase-1 requests currently on the wire, shared by prefetch and load. */
    private val inFlight = ConcurrentHashMap<CacheKey, CompletableDeferred<CommentTree>>()

    /** Last full tree per post — the revisit cache. */
    private val fullTrees = BoundedLruCache<CacheKey, CommentTree>(MAX_FULL_TREES)
    private val headers = BoundedLruCache<String, dev.readthat.comments.domain.PostHeader>(MAX_HEADERS)

    /** Test hook. */
    val prefetchedPostIds: Set<String>
        get() = prefetched.snapshot().keys.mapTo(mutableSetOf()) { it.postId }

    /**
     * Called from the FEED, not the post screen, after a post has been on screen
     * long enough to suggest intent. Only ever fetches the small tree.
     */
    open suspend fun prefetch(postId: String) {
        val key = cacheKey(postId)
        if (prefetched[key] != null || fullTrees[key] != null) return
        local.readTree(key.accountId, postId, null)?.let { cached ->
            putTree(key, cached)
            return
        }
        val deferred = CompletableDeferred<CommentTree>()
        if (inFlight.putIfAbsent(key, deferred) != null) return // already on the wire
        try {
            val tree = api.fetchTree(
                postId = postId,
                maxCount = CommentsRemoteSource.FIRST_PHASE_COUNT,
                maxDepth = CommentsRemoteSource.DEFAULT_MAX_DEPTH,
            )
            local.writeTree(key.accountId, tree, null)
            prefetched.put(key, tree)     // visible to load() BEFORE the deferred fires
            deferred.complete(tree)
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            throw t
        } finally {
            inFlight.remove(key)
        }
    }

    /**
     * Phase 1 then phase 2 — except on a revisit, where the retained full tree is
     * both phases at once: one emission, zero network.
     */
    fun load(postId: String): Flow<Phase> = flow {
        val key = cacheKey(postId)
        fullTrees[key]?.let { cached ->
            emit(Phase.Initial(tree = cached, fromPrefetch = true))
            return@flow
        }

        val disk = local.readTree(key.accountId, postId, null)?.also { putTree(key, it) }
        if (disk != null && disk.requestedCount >= CommentsRemoteSource.SECOND_PHASE_COUNT) {
            emit(Phase.Initial(tree = disk, fromPrefetch = true))
            return@flow
        }

        val awaited = inFlight[key]?.let { runCatching { it.await() }.getOrNull() }
        val cached = prefetched.remove(key) ?: awaited ?: disk
        val small = cached ?: api.fetchTree(
            postId = postId,
            maxCount = CommentsRemoteSource.FIRST_PHASE_COUNT,
            maxDepth = CommentsRemoteSource.DEFAULT_MAX_DEPTH,
        )
        local.writeTree(key.accountId, small, null)
        emit(Phase.Initial(tree = small, fromPrefetch = cached != null))

        val large = api.fetchTree(
            postId = postId,
            maxCount = CommentsRemoteSource.SECOND_PHASE_COUNT,
            maxDepth = CommentsRemoteSource.DEFAULT_MAX_DEPTH,
        )
        local.writeTree(key.accountId, large, null)
        fullTrees.put(key, large)
        emit(Phase.Full(tree = large))
    }

    /**
     * The permalink / "continue this thread" fetch. Single phase: a fresh screen
     * rooted at one comment has no already-rendered rows to protect, so there is
     * nothing for a second pass to refine — and no merge, so no flicker contract.
     */
    fun loadRooted(postId: String, rootCommentId: String): Flow<Phase> = flow {
        val key = cacheKey(postId, rootCommentId)
        fullTrees[key]?.let {
            emit(Phase.Initial(tree = it, fromPrefetch = true))
            return@flow
        }
        local.readTree(key.accountId, postId, rootCommentId)?.let {
            fullTrees.put(key, it)
            emit(Phase.Initial(tree = it, fromPrefetch = true))
            return@flow
        }
        val tree = api.fetchTree(
            postId = postId,
            maxCount = CommentsRemoteSource.SECOND_PHASE_COUNT,
            maxDepth = CommentsRemoteSource.DEFAULT_MAX_DEPTH,
            rootCommentId = rootCommentId,
        )
        local.writeTree(key.accountId, tree, rootCommentId)
        fullTrees.put(key, tree)
        emit(Phase.Initial(tree = tree, fromPrefetch = false))
    }

    /** Search-result permalink: include and focus the match, then its replies. */
    fun loadFocused(postId: String, focusCommentId: String): Flow<Phase> = flow {
        val storageKey = "focus:$focusCommentId"
        val key = cacheKey(postId, storageKey)
        fullTrees[key]?.let {
            emit(Phase.Initial(tree = it, fromPrefetch = true))
            return@flow
        }
        local.readTree(key.accountId, postId, storageKey)?.let {
            fullTrees.put(key, it)
            emit(Phase.Initial(tree = it, fromPrefetch = true))
            return@flow
        }
        val tree = api.fetchFocusedTree(postId, focusCommentId)
        local.writeTree(key.accountId, tree, storageKey)
        fullTrees.put(key, tree)
        emit(Phase.Initial(tree = tree, fromPrefetch = false))
    }

    /** Synchronous catalog lookup — see [FakeCommentsApi.peekPostHeader]. */
    open fun peekHeader(postId: String): dev.readthat.comments.domain.PostHeader? =
        headers[headerKey(accountId(), postId)] ?: api.peekPostHeader(postId)

    open suspend fun postHeader(postId: String): dev.readthat.comments.domain.PostHeader {
        val account = accountId()
        val key = headerKey(account, postId)
        headers[key]?.let { return it }
        local.readHeader(account, postId)?.let {
            headers.put(key, it)
            return it
        }
        return api.fetchPostHeader(postId).also {
            local.writeHeader(account, it)
            headers.put(key, it)
        }
    }

    /** Persists the ViewModel's optimistic/merged tree back into both tiers. */
    open suspend fun persistTree(
        postId: String,
        tree: CommentTree,
        rootCommentId: String? = null,
    ) {
        val key = cacheKey(postId, rootCommentId)
        putTree(key, tree)
        local.writeTree(key.accountId, tree, rootCommentId)
    }

    open suspend fun persistHeader(header: dev.readthat.comments.domain.PostHeader) {
        val account = accountId()
        headers.put(headerKey(account, header.postId), header)
        local.writeHeader(account, header)
    }

    /** The /api/morechildren call. Dedupe and state live in the ViewModel. */
    open suspend fun loadMore(
        postId: String,
        cursor: CommentNode.LoadMore,
        limit: Int = LOAD_MORE_LIMIT,
    ): LoadMoreResponse = api.loadMore(postId, cursor, limit)

    open suspend fun createComment(postId: String, parentId: String?, body: String): RawComment {
        val created = api.createComment(postId, parentId, body)
        val key = cacheKey(postId)
        prefetched.remove(key)
        fullTrees.remove(key)
        return created
    }

    open suspend fun voteComment(commentId: String, value: Int): CommentVoteResult =
        api.voteComment(commentId, value)

    open suspend fun votePost(postId: String, value: Int): CommentVoteResult {
        val interactions = postInteractions ?: return api.votePost(postId, value)
        val cachedHeader = headers[headerKey(accountId(), postId)] ?: api.peekPostHeader(postId)
        val result = interactions.setVote(
            postId = postId,
            desiredValue = value,
            surface = PerformanceSurface.DETAIL,
            baseline = cachedHeader?.let { VoteSnapshot(it.score, it.viewerVote) },
        )
        return CommentVoteResult(postId, result.viewerVote, result.score)
    }

    sealed interface Phase {
        val tree: CommentTree

        data class Initial(
            override val tree: CommentTree,
            val fromPrefetch: Boolean,
        ) : Phase

        data class Full(override val tree: CommentTree) : Phase
    }

    companion object {
        /** One fixed size, same cache-hit logic as the 8/200 pair. */
        const val LOAD_MORE_LIMIT = 100
        const val DEFAULT_ACCOUNT_ID = "local-default"
        private const val MAX_PREFETCHED_TREES = 12
        private const val MAX_FULL_TREES = 12
        private const val MAX_HEADERS = 32
    }

    private data class CacheKey(
        val accountId: String,
        val postId: String,
        val rootCommentId: String? = null,
    )

    private fun cacheKey(postId: String, rootCommentId: String? = null) =
        CacheKey(accountId(), postId, rootCommentId)

    private fun putTree(key: CacheKey, tree: CommentTree) {
        if (tree.requestedCount >= CommentsRemoteSource.SECOND_PHASE_COUNT) {
            fullTrees.put(key, tree)
            prefetched.remove(key)
        } else {
            prefetched.put(key, tree)
        }
    }

    private fun headerKey(accountId: String, postId: String) = "$accountId/$postId"
}
