package dev.readthat.client

import dev.readthat.comments.domain.CommentNode
import dev.readthat.comments.domain.CommentRow
import dev.readthat.comments.domain.CommentSort
import dev.readthat.comments.domain.CommentTree
import dev.readthat.comments.domain.CommentTreeEditor
import dev.readthat.communitydetail.domain.CommunityDetail
import dev.readthat.observability.PerformanceSurface
import dev.readthat.observability.ProductAnalytics
import dev.readthat.observability.ProductContentType
import dev.readthat.observability.ProductEvent
import dev.readthat.observability.ProductEventName
import dev.readthat.observability.ProductSurface
import dev.readthat.shared.PostHeader
import dev.readthat.shared.VoteSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The platform-neutral detail/comments state machine.
 *
 * It deliberately knows nothing about navigation, Compose, image decoding or media playback. Both
 * application shells can therefore share Room observation, two-phase refresh, optimistic mutation,
 * collapse policy and cursor prefetch while retaining their platform adapters at the rendering edge.
 */
internal class DetailController(
    private val repository: OfflineFirstRepository,
    private val scope: CoroutineScope,
    private val performanceSurface: PerformanceSurface = PerformanceSurface.DETAIL,
) {
    val state = MutableStateFlow(DetailState())

    private var observation: Job? = null
    private var generation = 0L
    private val commentVoteGenerations = mutableMapOf<String, Long>()
    private val commentVoteMutexes = mutableMapOf<String, Mutex>()

    fun open(
        postId: String,
        rootCommentId: String? = null,
        focusedCommentId: String? = null,
        projectedHeader: PostHeader? = null,
    ) = openInternal(
        postId = postId,
        rootCommentId = rootCommentId,
        focusedCommentId = focusedCommentId,
        projectedHeader = projectedHeader,
        sort = CommentSort.Best,
        previous = null,
    )

    fun selectCommentSort(sort: CommentSort) {
        val current = state.value
        val postId = current.postId ?: return
        if (sort == current.commentSort) return
        openInternal(
            postId = postId,
            rootCommentId = current.rootCommentId,
            focusedCommentId = current.focusedCommentId,
            projectedHeader = current.post,
            sort = sort,
            previous = current,
        )
    }

    private fun openInternal(
        postId: String,
        rootCommentId: String?,
        focusedCommentId: String?,
        projectedHeader: PostHeader?,
        sort: CommentSort,
        previous: DetailState?,
    ) {
        require(postId.isNotBlank())
        require(rootCommentId == null || focusedCommentId == null) {
            "A comment view cannot be both rooted and focused"
        }
        observation?.cancel()
        generation += 1L
        val activeGeneration = generation
        val refreshChrome = previous == null
        state.value = previous?.copy(
            commentSort = sort,
            autoCollapsedCommentIds = emptySet(),
            commentLoadStates = emptyMap(),
            initialCacheTier = null,
            refreshingComments = true,
            error = null,
        ) ?: DetailState(
            postId = postId,
            post = projectedHeader,
            rootCommentId = rootCommentId,
            focusedCommentId = focusedCommentId,
            commentSort = sort,
        )
        observation = scope.launch {
            var loadedCommunityName: String? = null
            var commentsWereCached = false

            suspend fun loadCommunityHeader(rawName: String) {
                val name = rawName.trim().removePrefix("r/").lowercase()
                if (name.isBlank() || loadedCommunityName == name) return
                loadedCommunityName = name
                repository.cachedCommunity(name)?.let { cached ->
                    updateIfCurrent(activeGeneration) { copy(community = cached) }
                }
                try {
                    val refreshed = repository.community(name, force = true)
                    updateIfCurrent(activeGeneration) { copy(community = refreshed) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // Community chrome is independently cached; post/comments remain usable.
                }
            }

            projectedHeader?.let { launch { repository.persistPostHeader(it) } }
            if (refreshChrome) projectedHeader?.subreddit?.let { launch { loadCommunityHeader(it) } }

            repository.cachedComments(postId, rootCommentId, focusedCommentId, sort)?.let { cached ->
                commentsWereCached = true
                updateIfCurrent(activeGeneration) {
                    copy(comments = cached, initialCacheTier = "room")
                }
            }
            launch {
                repository.observePost(postId).collect { post ->
                    if (generation != activeGeneration) return@collect
                    // A null first Room emission must not erase the feed-shaped hand-off header.
                    if (post != null || state.value.post == null) {
                        state.value = state.value.copy(post = post)
                    }
                    if (refreshChrome) {
                        post?.subreddit?.let { subreddit -> launch { loadCommunityHeader(subreddit) } }
                    }
                }
            }
            launch {
                repository.observeComments(postId, rootCommentId, focusedCommentId, sort).collect { comments ->
                    if (generation != activeGeneration) return@collect
                    // While changing sort, retain the previous ranked tree until
                    // the new sort has actual cached or network content to show.
                    if (comments != null || state.value.comments?.sort == sort) {
                        state.value = state.value.copy(
                            comments = comments,
                            initialCacheTier = state.value.initialCacheTier ?: comments?.let {
                                if (commentsWereCached) "room" else "network"
                            },
                        )
                    }
                }
            }
            updateIfCurrent(activeGeneration) {
                copy(loading = refreshChrome, refreshingComments = true, error = null)
            }
            if (refreshChrome) {
                launch {
                    try {
                        repository.refreshPost(postId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        updateIfCurrent(activeGeneration) {
                            copy(
                                postNotFound = error is ReadThatHttpException && error.status == 404,
                                error = error.message ?: "Unable to refresh post",
                            )
                        }
                    } finally {
                        updateIfCurrent(activeGeneration) { copy(loading = false) }
                    }
                }
            }
            launch {
                try {
                    repository.refreshComments(postId, rootCommentId, focusedCommentId, sort)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    updateIfCurrent(activeGeneration) {
                        copy(
                            // A sort switch deliberately keeps the old tree painted. If the new
                            // tree cannot be loaded, keep the control truthful about that content.
                            commentSort = comments?.sort ?: sort,
                            error = error.message ?: "Unable to refresh comments",
                        )
                    }
                } finally {
                    updateIfCurrent(activeGeneration) { copy(refreshingComments = false) }
                }
            }
        }
    }

    fun close() {
        generation += 1L
        observation?.cancel()
        observation = null
        commentVoteGenerations.clear()
        commentVoteMutexes.clear()
        state.value = DetailState()
    }

    fun setCommentDraft(value: String) {
        state.value = state.value.copy(commentDraft = value.take(MAX_COMMENT_LENGTH), error = null)
    }

    fun replyTo(commentId: String?) {
        state.value = state.value.copy(replyingToId = commentId)
    }

    fun toggleCommentCollapsed(commentId: String) {
        val current = state.value
        val next = progressiveCommentCollapse(
            roots = current.comments?.roots.orEmpty(),
            commentId = commentId,
            userCollapsed = current.collapsedCommentIds,
            autoCollapsed = current.autoCollapsedCommentIds,
        )
        state.value = current.copy(
            collapsedCommentIds = next.userCollapsed,
            autoCollapsedCommentIds = next.autoCollapsed,
        )
    }

    fun expandAllComments() {
        state.value = state.value.copy(
            collapsedCommentIds = emptySet(),
            autoCollapsedCommentIds = emptySet(),
        )
    }

    fun submitComment(postId: String = state.value.postId.orEmpty()) = scope.launch {
        val body = state.value.commentDraft.trim()
        if (postId.isBlank() || body.isEmpty() || state.value.submittingComment) return@launch
        val pendingId = platformMutationId("pending-comment")
        val parentId = state.value.replyingToId ?: state.value.rootCommentId
        val sort = state.value.commentSort
        val originalCount = state.value.post?.commentCount
        state.value = state.value.copy(
            commentDraft = "",
            replyingToId = null,
            submittingComment = true,
            error = null,
            post = state.value.post?.let { it.copy(commentCount = it.commentCount + 1) },
        )
        ProductAnalytics.record(ProductEvent(
            name = ProductEventName.COMMENT_CREATE,
            surface = ProductSurface.COMMENTS,
            contentId = postId,
            contentType = ProductContentType.POST,
        ))
        try {
            repository.createComment(
                postId,
                parentId,
                body,
                state.value.rootCommentId,
                state.value.focusedCommentId,
                sort = sort,
                pendingId = pendingId,
            )
            state.value = state.value.copy(submittingComment = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            state.value = state.value.copy(
                submittingComment = false,
                error = error.message ?: "Unable to comment",
                post = originalCount?.let { count -> state.value.post?.copy(commentCount = count) }
                    ?: state.value.post,
            )
        }
    }

    fun createComment(parentId: String?, body: String) {
        if (body.isBlank() || body.length > MAX_COMMENT_LENGTH) return
        replyTo(parentId)
        setCommentDraft(body)
        submitComment()
    }

    fun loadMoreComments(cursorId: String) {
        val postId = state.value.postId ?: return
        val current = state.value
        if (current.refreshingComments || current.commentLoadStates[cursorId] == CommentLoadState.Loading) return
        state.value = current.copy(
            commentLoadStates = current.commentLoadStates + (cursorId to CommentLoadState.Loading),
        )
        scope.launch {
            try {
                repository.loadMoreComments(
                    postId,
                    cursorId,
                    state.value.rootCommentId,
                    state.value.focusedCommentId,
                    current.commentSort,
                )
                state.value = state.value.copy(
                    commentLoadStates = state.value.commentLoadStates - cursorId,
                )
            } catch (cancelled: CancellationException) {
                state.value = state.value.copy(
                    commentLoadStates = state.value.commentLoadStates - cursorId,
                )
                throw cancelled
            } catch (error: Throwable) {
                state.value = state.value.copy(
                    commentLoadStates = state.value.commentLoadStates + (cursorId to CommentLoadState.Error),
                    error = error.message ?: "Unable to load replies",
                )
            }
        }
    }

    fun onCommentsViewport(
        rows: List<CommentRow>,
        firstVisibleItemIndex: Int,
        lastVisibleItemIndex: Int,
    ) {
        if (firstVisibleItemIndex < 0 || lastVisibleItemIndex < firstVisibleItemIndex) return
        val current = state.value
        if (current.refreshingComments) return
        if (current.commentLoadStates.values.any { it == CommentLoadState.Loading }) return
        if (current.comments == null) return
        val cursorId = nextCommentCursorKey(
            rows,
            current.commentLoadStates,
            firstVisibleItemIndex,
            lastVisibleItemIndex,
            headerItemCount = COMMENT_HEADER_ITEM_COUNT,
            prefetchDistance = COMMENT_PREFETCH_DISTANCE,
        ) ?: return
        loadMoreComments(cursorId)
    }

    fun clearError() {
        state.value = state.value.copy(error = null)
    }

    fun setCommunityJoined(joined: Boolean) = scope.launch {
        val name = state.value.post?.subreddit?.trim()?.removePrefix("r/")
            ?.takeIf(String::isNotBlank) ?: return@launch
        if (state.value.communityMembershipChanging) return@launch
        state.value = state.value.copy(communityMembershipChanging = true, error = null)
        try {
            val community = repository.setCommunityJoined(name, joined)
            state.value = state.value.copy(
                community = community,
                communityMembershipChanging = false,
            )
            ProductAnalytics.record(ProductEvent(
                name = if (joined) ProductEventName.COMMUNITY_JOIN else ProductEventName.COMMUNITY_LEAVE,
                surface = ProductSurface.DETAIL,
                contentId = name,
                contentType = ProductContentType.COMMUNITY,
            ))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            state.value = state.value.copy(
                communityMembershipChanging = false,
                error = error.message ?: "Membership change failed",
            )
        }
    }

    fun voteComment(commentId: String, value: Int) = scope.launch {
        if (value !in -1..1) return@launch
        val postId = state.value.postId ?: return@launch
        val tree = state.value.comments ?: return@launch
        val current = findDetailComment(tree, commentId) ?: return@launch
        val activeGeneration = (commentVoteGenerations[commentId] ?: 0L) + 1L
        commentVoteGenerations[commentId] = activeGeneration
        state.value = state.value.copy(
            comments = tree.copy(
                roots = CommentTreeEditor.updateVote(
                    tree.roots,
                    commentId,
                    value,
                    current.score - current.viewerVote + value,
                ),
            ),
        )
        try {
            commentVoteMutexes.getOrPut(commentId) { Mutex() }.withLock {
                if (commentVoteGenerations[commentId] != activeGeneration) return@withLock
                repository.voteComment(
                    postId,
                    commentId,
                    value,
                    state.value.rootCommentId,
                    state.value.focusedCommentId,
                    tree.sort,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (commentVoteGenerations[commentId] == activeGeneration) {
                state.value.comments?.let { latest ->
                    state.value = state.value.copy(
                        comments = latest.copy(
                            roots = CommentTreeEditor.updateVote(
                                latest.roots,
                                commentId,
                                current.viewerVote,
                                current.score,
                            ),
                        ),
                        error = error.message ?: "Unable to vote",
                    )
                }
            }
        }
    }

    fun votePost(value: Int, onApplied: (VoteSnapshot) -> Unit = {}) = scope.launch {
        val postId = state.value.postId ?: return@launch
        val current = state.value.post ?: return@launch
        val optimistic = VoteSnapshot(current.score, current.viewerVote).optimistic(value)
        applyPostVote(optimistic)
        repository.votePost(postId, value, performanceSurface)?.let {
            applyPostVote(it)
            onApplied(it)
        }
    }

    fun applyPostVote(vote: VoteSnapshot) {
        state.value = state.value.copy(
            post = state.value.post?.copy(score = vote.score, viewerVote = vote.viewerVote),
        )
    }

    private inline fun updateIfCurrent(
        activeGeneration: Long,
        transform: DetailState.() -> DetailState,
    ) {
        if (generation == activeGeneration) state.value = state.value.transform()
    }

    private companion object {
        const val MAX_COMMENT_LENGTH = 10_000
        const val COMMENT_PREFETCH_DISTANCE = 6
        const val COMMENT_HEADER_ITEM_COUNT = 1
    }
}

private fun findDetailComment(tree: CommentTree, commentId: String): CommentNode.Comment? {
    val stack = ArrayDeque<CommentNode>()
    tree.roots.forEach(stack::addLast)
    while (stack.isNotEmpty()) {
        when (val node = stack.removeLast()) {
            is CommentNode.Comment -> {
                if (node.id == commentId) return node
                node.children.forEach(stack::addLast)
            }
            is CommentNode.LoadMore -> Unit
        }
    }
    return null
}
