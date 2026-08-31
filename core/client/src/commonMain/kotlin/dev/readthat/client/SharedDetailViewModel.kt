package dev.readthat.client

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.readthat.comments.domain.CommentFlattener
import dev.readthat.comments.domain.CommentRenderList
import dev.readthat.data.db.AppDatabase
import dev.readthat.observability.PerformanceSurface
import dev.readthat.shared.PostHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SharedDetailUiState(
    val detail: DetailState = DetailState(),
    val render: CommentRenderList = CommentRenderList(emptyList(), 0, 0),
    val canMutate: Boolean = true,
)

/** Expensive tree flattening is shared too, and never re-runs for loading/error flag churn. */
internal fun Flow<DetailState>.commentRenderLists(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): Flow<CommentRenderList> = map { state ->
    DetailStructure(
        tree = state.comments,
        collapsed = state.collapsedCommentIds + state.autoCollapsedCommentIds,
    )
}.distinctUntilChanged().map { structure ->
    structure.tree?.let { CommentFlattener.flatten(it, structure.collapsed) }
        ?: CommentRenderList(emptyList(), 0, 0)
}.flowOn(dispatcher)

/** Focused KMP detail/comments ViewModel shared by the mature Android and shared app shells. */
class SharedDetailViewModel(
    client: ReadThatClient,
    database: AppDatabase,
    accountId: String,
    postId: String,
    rootCommentId: String? = null,
    focusedCommentId: String? = null,
    projectedHeader: PostHeader? = null,
    canMutate: Boolean = true,
    onVoteQueued: () -> Unit = {},
    flattenDispatcher: CoroutineDispatcher = Dispatchers.Default,
    performanceSurface: PerformanceSurface = PerformanceSurface.DETAIL,
) : ViewModel() {
    private val repository = OfflineFirstRepository(
        client = client,
        database = database,
        scope = viewModelScope,
        accountIdOverride = accountId,
        onVoteQueued = onVoteQueued,
        maintainGlobalState = false,
    )
    private val controller = DetailController(repository, viewModelScope, performanceSurface)

    val detail: StateFlow<DetailState> = controller.state

    private val render = detail.commentRenderLists(flattenDispatcher)

    val uiState: StateFlow<SharedDetailUiState> = combine(detail, render) { state, rows ->
        SharedDetailUiState(state, rows, canMutate)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        SharedDetailUiState(
            detail = DetailState(
                postId = postId,
                post = projectedHeader,
                rootCommentId = rootCommentId,
                focusedCommentId = focusedCommentId,
            ),
            canMutate = canMutate,
        ),
    )

    init {
        controller.open(postId, rootCommentId, focusedCommentId, projectedHeader)
    }

    fun toggleCommentCollapsed(commentId: String) = controller.toggleCommentCollapsed(commentId)
    fun expandAllComments() = controller.expandAllComments()
    fun loadMoreComments(cursorId: String) = controller.loadMoreComments(cursorId)
    fun onCommentsViewport(firstVisibleItemIndex: Int, lastVisibleItemIndex: Int) =
        controller.onCommentsViewport(
            uiState.value.render.rows,
            firstVisibleItemIndex,
            lastVisibleItemIndex,
        )
    fun voteComment(commentId: String, value: Int) = controller.voteComment(commentId, value)
    fun votePost(value: Int) = controller.votePost(value)
    fun createComment(parentId: String?, body: String) = controller.createComment(parentId, body)
    fun setCommunityJoined(joined: Boolean) = controller.setCommunityJoined(joined)
    fun clearError() = controller.clearError()

    /** Uses the same authenticated KMP client, TLS pool and offline-first repository as detail. */
    fun reshare(subreddit: String, onComplete: (String?) -> Unit = {}) {
        val postId = detail.value.postId.orEmpty()
        val target = subreddit.trim().removePrefix("r/")
        if (postId.isBlank() || target.length < MIN_COMMUNITY_NAME_LENGTH) {
            onComplete("Choose a valid community")
            return
        }
        viewModelScope.launch {
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

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val MIN_COMMUNITY_NAME_LENGTH = 3
    }
}

private data class DetailStructure(
    val tree: dev.readthat.comments.domain.CommentTree?,
    val collapsed: Set<String>,
)
