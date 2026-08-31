package dev.readthat.comments.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.readthat.comments.data.CommentsRepository
import dev.readthat.comments.domain.CommentFlattener
import dev.readthat.comments.domain.CommentNode
import dev.readthat.comments.domain.CommentRenderList
import dev.readthat.comments.domain.CommentRow
import dev.readthat.comments.domain.CommentTreeMerger
import dev.readthat.comments.domain.CommentTreeSplicer
import dev.readthat.comments.domain.CommentTreeEditor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import dev.readthat.observability.PerformanceMetric
import dev.readthat.observability.PerformanceOutcome
import dev.readthat.observability.PerformanceSurface
import dev.readthat.observability.PerformanceTelemetry
import dev.readthat.observability.ProductAnalytics
import dev.readthat.observability.ProductContentType
import dev.readthat.observability.ProductEvent
import dev.readthat.observability.ProductEventName
import dev.readthat.observability.ProductSurface
import dev.readthat.observability.performanceTimer
import java.util.UUID

/** Per-cursor load-more state. Absence from the map means idle. */
sealed interface LoadMoreState {
    data object Loading : LoadMoreState
    data object Error : LoadMoreState
}

@Immutable
data class CommentsUiState(
    val render: CommentRenderList = CommentRenderList(emptyList(), 0, 0),
    val header: dev.readthat.comments.domain.PostHeader? = null,
    val loadMoreStates: Map<String, LoadMoreState> = emptyMap(),
    val isLoadingInitial: Boolean = true,
    val isLoadingFull: Boolean = false,
    val servedFromPrefetch: Boolean = false,
    val totalComments: Int = 0,
    val isSubmittingComment: Boolean = false,
    val interactionError: String? = null,
    val focusedCommentId: String? = null,
    val rootCommentId: String? = null,
) {
    val isEmpty: Boolean get() = render.rows.isEmpty()
}

/**
 * MVVM with `combine(...).stateIn(...)` — Reddit's published "single stream of
 * UiState" idiom — but derived in TWO stages, because the inputs have two speeds:
 *
 *   render  = combine(tree, userCollapsed, autoCollapsed) -> flatten     EXPENSIVE
 *   uiState = combine(render, loading, loadMoreStates)    -> assemble    CHEAP
 *
 * Flattening is a full tree walk; loading flags and per-cursor spinners are
 * booleans. One-stage derivation re-walks the tree every time a spinner ticks.
 * Splitting means transient state can never trigger the expensive path — and the
 * `flowOn` puts the walk on a background dispatcher, off the main thread, which a
 * single-stage `stateIn(viewModelScope)` quietly doesn't.
 *
 * Collapse is TWO sets, deliberately:
 *  - [userCollapsed] — intent. Persisted via [SavedStateHandle], announced to
 *    accessibility as "Collapsed", survives process death.
 *  - [autoCollapsed] — anti-flicker artifacts from [CommentTreeMerger]. Ephemeral,
 *    never persisted, cleared the moment the user expands the node.
 * Flatten sees the union; nothing else ever conflates them.
 */
class CommentsViewModel(
    private val repository: CommentsRepository,
    private val savedStateHandle: SavedStateHandle,
    flattenDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val postId: String = checkNotNull(savedStateHandle[KEY_POST_ID]) {
        "CommentsViewModel requires a \"$KEY_POST_ID\" nav argument"
    }

    /** Non-null = this is a "continue this thread" screen, re-rooted at that comment. */
    private val rootCommentId: String? = savedStateHandle[KEY_ROOT_COMMENT_ID]
    private val focusCommentId: String? = savedStateHandle[KEY_FOCUS_COMMENT_ID]

    private val tree = MutableStateFlow<dev.readthat.comments.domain.CommentTree?>(null)
    private val userCollapsed = MutableStateFlow<Set<String>>(
        savedStateHandle.get<ArrayList<String>>(KEY_COLLAPSED)?.toSet() ?: emptySet(),
    )
    private val autoCollapsed = MutableStateFlow<Set<String>>(emptySet())
    private val loading = MutableStateFlow(LoadingFlags())
    private val loadMoreStates = MutableStateFlow<Map<String, LoadMoreState>>(emptyMap())
    // Seeded SYNCHRONOUSLY from the catalog so the header is present in the very
    // first content frame; the async fetch below only fills the fallback case.
    private val header = MutableStateFlow(repository.peekHeader(savedStateHandle[KEY_POST_ID] ?: ""))

    // Stage 1 — the expensive derivation. Runs only when structure or collapse
    // actually changes, and runs off the main thread.
    private val render = combine(tree, userCollapsed, autoCollapsed) { t, user, auto ->
        t?.let { CommentFlattener.flatten(it, user + auto) }
            ?: CommentRenderList(emptyList(), 0, 0)
    }.flowOn(flattenDispatcher)

    // Stage 2 — cheap assembly. Flag churn re-executes THIS, never the flatten;
    // the render instance passes through untouched, so LazyColumn's inputs stay
    // referentially stable across spinner ticks.
    val uiState: StateFlow<CommentsUiState> =
        combine(render, tree, loading, loadMoreStates, header) { r, t, flags, more, h ->
            CommentsUiState(
                render = r,
                header = h,
                loadMoreStates = more,
                isLoadingInitial = flags.initial,
                isLoadingFull = flags.full,
                servedFromPrefetch = flags.fromPrefetch,
                totalComments = t?.commentCount ?: 0,
                isSubmittingComment = flags.commentSubmitting,
                interactionError = flags.interactionError,
                focusedCommentId = focusCommentId,
                rootCommentId = rootCommentId,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = CommentsUiState(),
        )

    init {
        load()
        if (header.value == null) {
            viewModelScope.launch { header.value = repository.postHeader(postId) }
        }
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val source = when {
                    focusCommentId != null -> repository.loadFocused(postId, focusCommentId)
                    rootCommentId != null -> repository.loadRooted(postId, rootCommentId)
                    else -> repository.load(postId)
                }
                source.collect { phase -> when (phase) {
                    is CommentsRepository.Phase.Initial -> {
                        tree.value = phase.tree
                        // full tree already (revisit cache / rooted fetch)? then no
                        // second phase is coming and the bar should not show one.
                        val isFull = phase.tree.requestedCount >= 200
                        loading.value = LoadingFlags(
                            initial = false,
                            full = !isFull,
                            fromPrefetch = phase.fromPrefetch,
                        )
                    }

                    is CommentsRepository.Phase.Full -> {
                        // update {} — the splice path writes this flow too, and a
                        // plain read-modify-write here is a lost-update race.
                        var auto: Set<String> = emptySet()
                        tree.update { existing ->
                            val result = CommentTreeMerger.merge(existing, phase.tree)
                            auto = result.autoCollapsedIds
                            result.tree
                        }
                        autoCollapsed.update { it + auto }
                        loading.update { it.copy(full = false) }
                    }
                } }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                loading.update {
                    it.copy(
                        initial = false,
                        full = false,
                        interactionError = error.message ?: "Could not load comments",
                    )
                }
            }
        }
    }

    /**
     * The "x more replies" tap.
     *
     * SERIALIZED behind phase 2 by design: [CommentTreeMerger] rebuilds from the
     * incoming tree, so a splice that lands during the merge window would be
     * silently discarded — worse than a disabled row for one emission. The honest
     * cheap fix is to not race them; the row renders as non-loading and the tap
     * re-arms once the merge lands.
     */
    fun loadMore(cursorId: String) {
        if (loading.value.full || loading.value.initial) return
        when (loadMoreStates.value[cursorId]) {
            is LoadMoreState.Loading -> return // in-flight dedupe: double-taps are the norm
            else -> Unit                       // idle or Error (retry) both proceed
        }
        val cursor = findCursor(tree.value?.roots, cursorId) ?: return

        loadMoreStates.update { it + (cursorId to LoadMoreState.Loading) }
        viewModelScope.launch {
            try {
                val response = repository.loadMore(postId, cursor)
                tree.update { current ->
                    current?.let { CommentTreeSplicer.splice(it, cursorId, response) } ?: current
                }
                tree.value?.let { repository.persistTree(postId, it, rootCommentId) }
                loadMoreStates.update { it - cursorId }
            } catch (e: CancellationException) {
                // finally-style cleanup: a cancelled fetch must not wedge the row
                // in Loading with dedupe blocking every future tap.
                loadMoreStates.update { it - cursorId }
                throw e
            } catch (t: Throwable) {
                loadMoreStates.update { it + (cursorId to LoadMoreState.Error) }
            }
        }
    }

    /**
     * Nested-tree equivalent of PagingConfig.prefetchDistance.
     *
     * The LazyColumn reports one header row before the flattened comment rows.
     * When an idle continuation cursor is visible or within the look-ahead
     * window, load one bounded chunk. Automatic prefetch never retries an error
     * and never fans out multiple branches concurrently; explicit taps retain
     * both retry and parallel-interaction semantics through [loadMore].
     */
    fun onViewport(firstVisibleItemIndex: Int, lastVisibleItemIndex: Int) {
        if (firstVisibleItemIndex < 0 || lastVisibleItemIndex < firstVisibleItemIndex) return
        if (loading.value.initial || loading.value.full) return
        if (loadMoreStates.value.values.any { it is LoadMoreState.Loading }) return

        val rows = uiState.value.render.rows
        if (rows.isEmpty()) return
        val firstRow = (firstVisibleItemIndex - POST_HEADER_ITEM_COUNT).coerceAtLeast(0)
        val lastRow = (lastVisibleItemIndex - POST_HEADER_ITEM_COUNT + COMMENT_PREFETCH_DISTANCE)
            .coerceAtMost(rows.lastIndex)
        if (firstRow > lastRow) return

        val cursor = rows.subList(firstRow, lastRow + 1)
            .filterIsInstance<CommentRow.LoadMore>()
            .firstOrNull { loadMoreStates.value[it.key] == null }
            ?: return
        loadMore(cursor.key)
    }

    /**
     * Collapse is intent. Expansion reveals one reply level at a time: immediate
     * children open, while comment grandchildren become ephemeral collapse
     * boundaries. That keeps a tap on a large thread bounded and matches Reddit's
     * progressive disclosure instead of exploding an arbitrary-depth subtree.
     *
     * Auto-collapse boundaries are not persisted as user intent. Explicit child
     * collapses remain untouched even when an ancestor opens.
     */
    fun toggleCollapse(commentId: String) {
        val isCollapsed = commentId in userCollapsed.value || commentId in autoCollapsed.value
        if (!isCollapsed) {
            userCollapsed.update { it + commentId }
            savedStateHandle[KEY_COLLAPSED] = ArrayList(userCollapsed.value)
            return
        }

        val comment = findComment(tree.value?.roots, commentId)
        val directChildIds = comment?.children
            ?.filterIsInstance<CommentNode.Comment>()
            ?.mapTo(mutableSetOf()) { it.id }
            .orEmpty()
        val grandchildIds = comment?.children
            ?.filterIsInstance<CommentNode.Comment>()
            ?.flatMapTo(mutableSetOf()) { child ->
                child.children.filterIsInstance<CommentNode.Comment>().map { it.id }
            }
            .orEmpty()

        userCollapsed.update { it - commentId }
        autoCollapsed.update { current ->
            (current - commentId - directChildIds) + grandchildIds
        }
        savedStateHandle[KEY_COLLAPSED] = ArrayList(userCollapsed.value)
    }

    fun expandAll() {
        autoCollapsed.value = emptySet()
        userCollapsed.value = emptySet()
        savedStateHandle[KEY_COLLAPSED] = ArrayList<String>()
    }

    fun createComment(parentId: String?, body: String, onCreated: () -> Unit = {}) {
        if (body.isBlank() || body.length > 10_000 || loading.value.commentSubmitting) return
        val timer = performanceTimer()
        val pendingId = "pending:${UUID.randomUUID()}"
        val pending = CommentNode.Comment(
            id = pendingId,
            author = "you",
            authorDisplayName = "You",
            body = body.trim(),
            score = 1,
            createdAgoMin = 0,
            viewerVote = 1,
        )
        loading.update { it.copy(commentSubmitting = true, interactionError = null) }
        tree.update { current ->
            current?.copy(roots = CommentTreeEditor.insert(current.roots, parentId, pending))
        }
        header.update { it?.copy(commentCount = it.commentCount + 1) }
        PerformanceTelemetry.duration(
            PerformanceMetric.MUTATION_LOCAL_COMMIT,
            timer,
            surface = PerformanceSurface.DETAIL,
            attributes = mapOf("mutation_type" to "comment_create", "cache_tier" to "memory"),
        )
        // Capture the user's offline-first action in the engagement session
        // where it happened. Server acknowledgement can arrive after a later
        // foreground boundary and is measured separately by performance telemetry.
        ProductAnalytics.record(ProductEvent(
            name = ProductEventName.COMMENT_CREATE,
            surface = ProductSurface.COMMENTS,
            contentId = postId,
            contentType = ProductContentType.POST,
        ))
        // The composer closes on the optimistic L1 commit; the server response
        // reconciles the temporary row in place using its stable local key.
        onCreated()
        viewModelScope.launch {
            tree.value?.let { repository.persistTree(postId, it, rootCommentId) }
            runCatching { repository.createComment(postId, parentId, body.trim()) }
                .onSuccess { created ->
                    val node = CommentNode.Comment(
                        id = created.id,
                        author = created.author,
                        body = created.body,
                        score = created.score,
                        createdAgoMin = created.createdAgoMin,
                        viewerVote = created.viewerVote,
                        authorDisplayName = created.authorDisplayName,
                        authorAvatarUrl = created.authorAvatarUrl,
                        isEdited = created.isEdited,
                    )
                    tree.update { current ->
                        current?.copy(roots = CommentTreeEditor.replace(current.roots, pendingId, node))
                    }
                    PerformanceTelemetry.duration(
                        PerformanceMetric.MUTATION_SERVER_ACK,
                        timer,
                        surface = PerformanceSurface.DETAIL,
                        attributes = mapOf("mutation_type" to "comment_create"),
                    )
                }
                .onFailure { error ->
                    tree.update { current ->
                        current?.copy(roots = CommentTreeEditor.remove(current.roots, pendingId))
                    }
                    header.update { it?.copy(commentCount = (it.commentCount - 1).coerceAtLeast(0)) }
                    loading.update { it.copy(interactionError = error.message ?: "Could not post comment") }
                    PerformanceTelemetry.duration(
                        PerformanceMetric.MUTATION_SERVER_ACK,
                        timer,
                        surface = PerformanceSurface.DETAIL,
                        outcome = PerformanceOutcome.FAILURE,
                        attributes = mapOf("mutation_type" to "comment_create"),
                    )
                }
            // Reconcile L2 after both success and rollback. Persisting only the
            // optimistic row would resurrect a failed comment after process death.
            tree.value?.let { repository.persistTree(postId, it, rootCommentId) }
            header.value?.let { repository.persistHeader(it) }
            loading.update { it.copy(commentSubmitting = false) }
        }
    }

    fun voteComment(commentId: String, requestedValue: Int) {
        if (requestedValue !in -1..1) return
        val timer = performanceTimer()
        val current = findComment(tree.value?.roots, commentId) ?: return
        val next = if (current.viewerVote == requestedValue) 0 else requestedValue
        tree.update {
            it?.copy(
                roots = CommentTreeEditor.updateVote(
                    it.roots,
                    commentId,
                    next,
                    current.score - current.viewerVote + next,
                ),
            )
        }
        val mutationType = when (next) {
            1 -> "comment_upvote"
            -1 -> "comment_downvote"
            else -> "comment_vote_clear"
        }
        PerformanceTelemetry.duration(
            PerformanceMetric.MUTATION_LOCAL_COMMIT,
            timer,
            surface = PerformanceSurface.DETAIL,
            attributes = mapOf("mutation_type" to mutationType, "cache_tier" to "memory"),
        )
        viewModelScope.launch {
            tree.value?.let { repository.persistTree(postId, it, rootCommentId) }
            runCatching { repository.voteComment(commentId, next) }
                .onSuccess { confirmed ->
                    tree.update {
                        it?.copy(
                            roots = CommentTreeEditor.updateVote(
                                it.roots,
                                commentId,
                                confirmed.value,
                                confirmed.score,
                            ),
                        )
                    }
                    PerformanceTelemetry.duration(
                        PerformanceMetric.MUTATION_SERVER_ACK,
                        timer,
                        surface = PerformanceSurface.DETAIL,
                        attributes = mapOf("mutation_type" to mutationType),
                    )
                }
                .onFailure { error ->
                    tree.update {
                        it?.copy(
                            roots = CommentTreeEditor.updateVote(
                                it.roots,
                                commentId,
                                current.viewerVote,
                                current.score,
                            ),
                        )
                    }
                    loading.update { it.copy(interactionError = error.message ?: "Could not vote") }
                    PerformanceTelemetry.duration(
                        PerformanceMetric.MUTATION_SERVER_ACK,
                        timer,
                        surface = PerformanceSurface.DETAIL,
                        outcome = PerformanceOutcome.FAILURE,
                        attributes = mapOf("mutation_type" to mutationType),
                    )
                }
            tree.value?.let { repository.persistTree(postId, it, rootCommentId) }
        }
    }

    fun votePost(requestedValue: Int) {
        val current = header.value ?: return
        val next = if (current.viewerVote == requestedValue) 0 else requestedValue
        header.value = current.copy(score = current.score - current.viewerVote + next, viewerVote = next)
        viewModelScope.launch {
            runCatching { repository.votePost(postId, next) }
                .onSuccess { confirmed ->
                    header.update { it?.copy(score = confirmed.score, viewerVote = confirmed.value) }
                }
                .onFailure { error ->
                    header.value = current
                    loading.update { it.copy(interactionError = error.message ?: "Could not vote") }
                }
            header.value?.let { repository.persistHeader(it) }
        }
    }

    fun clearInteractionError() = loading.update { it.copy(interactionError = null) }

    private fun findComment(nodes: List<CommentNode>?, id: String): CommentNode.Comment? {
        val stack = ArrayDeque(nodes ?: return null)
        while (stack.isNotEmpty()) {
            when (val node = stack.removeLast()) {
                is CommentNode.Comment -> {
                    if (node.id == id) return node
                    node.children.forEach(stack::addLast)
                }
                is CommentNode.LoadMore -> Unit
            }
        }
        return null
    }

    private fun findCursor(roots: List<CommentNode>?, id: String): CommentNode.LoadMore? {
        val stack = ArrayDeque(roots ?: return null)
        while (stack.isNotEmpty()) {
            when (val n = stack.removeLast()) {
                is CommentNode.Comment -> n.children.forEach(stack::addLast)
                is CommentNode.LoadMore -> if (n.id == id) return n
            }
        }
        return null
    }

    private data class LoadingFlags(
        val initial: Boolean = true,
        val full: Boolean = false,
        val fromPrefetch: Boolean = false,
        val commentSubmitting: Boolean = false,
        val interactionError: String? = null,
    )

    companion object {
        const val KEY_POST_ID = "postId"
        const val KEY_ROOT_COMMENT_ID = "rootCommentId"
        const val KEY_FOCUS_COMMENT_ID = "focusCommentId"
        const val KEY_COLLAPSED = "collapsedIds"
        const val STOP_TIMEOUT_MS = 5_000L
        const val COMMENT_PREFETCH_DISTANCE = 6
        private const val POST_HEADER_ITEM_COUNT = 1
    }
}
