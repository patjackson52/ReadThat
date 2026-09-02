package dev.readthat.detail.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.readthat.comments.domain.CommentFlattener
import dev.readthat.comments.domain.CommentRenderList
import dev.readthat.comments.domain.CommentRow
import dev.readthat.comments.domain.CommentSort
import dev.readthat.comments.domain.PostHeader
import dev.readthat.core.ui.markdown.MarkdownText
import dev.readthat.core.ui.markdown.markdownPlainText
import dev.readthat.observability.PerformanceMetric
import dev.readthat.observability.PerformanceSurface
import dev.readthat.observability.PerformanceTelemetry
import dev.readthat.observability.performanceTimer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

val PostDetailToolbarHeight = 58.dp

/**
 * Canonical post-detail/comments presentation shared by every host.
 *
 * Image decoding and media playback remain injected so Android can retain Coil/Media3 and iOS can
 * retain its shared NSURLSession decoder/AVPlayer pipeline. Navigation and state mutation also stay
 * outside this module; list geometry, comment interaction semantics, animation and accessibility do
 * not.
 */
@Composable
fun SharedPostDetailScreen(
    state: DetailUiState,
    onContinueThread: (String) -> Unit,
    onCommunityClick: (String) -> Unit,
    onJoinCommunity: () -> Unit,
    onBack: () -> Unit,
    onToggleComment: (String) -> Unit,
    onLoadMore: (String) -> Unit,
    onViewport: (firstVisibleItemIndex: Int, lastVisibleItemIndex: Int) -> Unit,
    onVoteComment: (commentId: String, value: Int) -> Unit,
    onVotePost: (Int) -> Unit,
    onCreateComment: (parentId: String?, body: String) -> Unit,
    onCommentSortChanged: (CommentSort) -> Unit,
    onClearError: () -> Unit,
    imageRenderer: DetailImageRenderer,
    mediaRenderer: DetailMediaRenderer,
    onSharePost: (() -> Unit)? = null,
    onResharePost: ((communityName: String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    communityHeader: DetailCommunityHeader? = null,
    icons: DetailIcons = DetailIcons(),
    detailContentModifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    presentation: DetailPresentation = DetailPresentation.FullScreen,
) {
    var composerOpen by remember { mutableStateOf(false) }
    var replyParent by remember { mutableStateOf<CommentRow.Comment?>(null) }
    var nextCommentScrolling by remember { mutableStateOf(false) }
    val postIdentity = state.header?.postId ?: state.transitionPreview?.postId
    var reshareOpen by remember(postIdentity) { mutableStateOf(false) }
    var reshareTarget by remember(postIdentity) { mutableStateOf("") }
    var commentSearchOpen by remember(postIdentity) { mutableStateOf(false) }
    var commentSearchQuery by remember(postIdentity) { mutableStateOf("") }
    var activeSearchMatch by remember(postIdentity) { mutableStateOf(0) }
    val commentListState = rememberLazyListState()
    val interactionScope = rememberCoroutineScope()
    val presentedRows = state.render.rows
    val searchMatches = remember(presentedRows, commentSearchQuery) {
        commentSearchMatchIndices(presentedRows, commentSearchQuery)
    }
    val latestRows by rememberUpdatedState(presentedRows)

    LaunchedEffect(state.commentSort) {
        commentListState.scrollToItem(0)
    }

    LaunchedEffect(commentSearchOpen, commentSearchQuery, searchMatches) {
        activeSearchMatch = 0
        if (commentSearchOpen && commentSearchQuery.isNotBlank()) {
            searchMatches.firstOrNull()?.let { rowIndex ->
                commentListState.animateScrollToItem(rowIndex + POST_HEADER_ITEM_COUNT)
            }
        }
    }

    fun moveSearchMatch(delta: Int) {
        if (searchMatches.isEmpty()) return
        activeSearchMatch = (activeSearchMatch + delta + searchMatches.size) % searchMatches.size
        val rowIndex = searchMatches[activeSearchMatch]
        interactionScope.launch {
            commentListState.animateScrollToItem(rowIndex + POST_HEADER_ITEM_COUNT)
        }
    }

    fun toolbarInteraction(type: String, action: () -> Unit) {
        val timer = performanceTimer()
        action()
        interactionScope.launch {
            withFrameNanos { }
            PerformanceTelemetry.duration(
                PerformanceMetric.INTERACTION_TO_NEXT_FRAME,
                timer,
                PerformanceSurface.DETAIL,
                attributes = mapOf("interaction_type" to type),
            )
        }
    }

    Column(modifier.fillMaxSize().background(containerColor)) {
        if (presentation == DetailPresentation.FullScreen) {
            DetailTopBar(
                onBack = onBack,
                icons = icons,
                searchOpen = commentSearchOpen,
                searchQuery = commentSearchQuery,
                searchMatchCount = searchMatches.size,
                activeSearchMatch = activeSearchMatch,
                commentSort = state.commentSort,
                communityName = state.header?.subreddit,
                onOpenSearch = {
                    toolbarInteraction("comment_search_open") { commentSearchOpen = true }
                },
                onCloseSearch = {
                    commentSearchOpen = false
                    commentSearchQuery = ""
                },
                onSearchQueryChanged = { commentSearchQuery = it.take(MAX_COMMENT_SEARCH_LENGTH) },
                onPreviousSearchMatch = {
                    toolbarInteraction("comment_search_previous") { moveSearchMatch(-1) }
                },
                onNextSearchMatch = {
                    toolbarInteraction("comment_search_next") { moveSearchMatch(1) }
                },
                onCommentSortChanged = { sort ->
                    toolbarInteraction("comment_sort") { onCommentSortChanged(sort) }
                },
                onSharePost = onSharePost,
                onCommunityClick = onCommunityClick,
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            Column(Modifier.fillMaxSize().then(detailContentModifier)) {
                if (state.isLoadingFull) LinearProgressIndicator(Modifier.fillMaxWidth())
                CommentList(
                    modifier = Modifier.weight(1f),
                    listState = commentListState,
                    state = state,
                    rows = presentedRows,
                    sourceRows = state.render.rows,
                    searchQuery = commentSearchQuery,
                    communityHeader = communityHeader,
                    icons = icons,
                    imageRenderer = imageRenderer,
                    mediaRenderer = mediaRenderer,
                    showPostMedia = presentation == DetailPresentation.FullScreen,
                    onToggle = onToggleComment,
                    onLoadMore = onLoadMore,
                    onViewport = onViewport,
                    onContinueThread = onContinueThread,
                    onReply = { row ->
                        if (state.canMutate) {
                            replyParent = row
                            composerOpen = true
                        }
                    },
                    onVoteComment = onVoteComment,
                    onVotePost = onVotePost,
                    onSharePost = onSharePost,
                    onResharePost = onResharePost?.let { { reshareOpen = true } },
                    onRootComment = {
                        if (state.canMutate) {
                            replyParent = null
                            composerOpen = true
                        }
                    },
                    onCommunityClick = onCommunityClick,
                    onJoinCommunity = onJoinCommunity,
                )
                state.interactionError?.let { error ->
                    Text(
                        error,
                        Modifier.fillMaxWidth().clickable(onClick = onClearError).padding(10.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (state.canMutate) {
                    RootCommentBar(
                        onComment = {
                            replyParent = null
                            composerOpen = true
                        },
                        onNextComment = {
                            if (!nextCommentScrolling) {
                                nextCommentScrolling = true
                                interactionScope.launch {
                                    try {
                                        scrollToNextRootComment(commentListState) { latestRows }
                                    } finally {
                                        nextCommentScrolling = false
                                    }
                                }
                            }
                        },
                        nextCommentEnabled = !nextCommentScrolling,
                        nextCommentIcon = icons.nextComment,
                    )
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 6.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column {
                            Text(
                                "Sign in to vote or join the conversation.",
                                Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
                        }
                    }
                }
            }
        }
    }
    if (composerOpen) {
        CommentComposer(
            reply = replyParent,
            submitting = state.isSubmittingComment,
            closeIcon = icons.close,
            onDismiss = { if (!state.isSubmittingComment) composerOpen = false },
            onPost = { body ->
                onCreateComment(replyParent?.key ?: state.rootCommentId, body)
                composerOpen = false
            },
        )
    }
    if (reshareOpen && onResharePost != null) {
        val normalizedTarget = normalizedReshareCommunity(reshareTarget)
        AlertDialog(
            onDismissRequest = { reshareOpen = false },
            title = { Text("Reshare to a community") },
            text = {
                OutlinedTextField(
                    value = reshareTarget,
                    onValueChange = { reshareTarget = it.take(MAX_COMMUNITY_INPUT_LENGTH) },
                    label = { Text("r/community") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = normalizedTarget.length >= MIN_COMMUNITY_NAME_LENGTH,
                    onClick = {
                        onResharePost(normalizedTarget)
                        reshareOpen = false
                        reshareTarget = ""
                    },
                ) { Text("Reshare") }
            },
            dismissButton = {
                TextButton(onClick = { reshareOpen = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun CommentList(
    modifier: Modifier,
    listState: LazyListState,
    state: DetailUiState,
    rows: List<CommentRow>,
    sourceRows: List<CommentRow>,
    searchQuery: String,
    communityHeader: DetailCommunityHeader?,
    icons: DetailIcons,
    imageRenderer: DetailImageRenderer,
    mediaRenderer: DetailMediaRenderer,
    showPostMedia: Boolean,
    onToggle: (String) -> Unit,
    onLoadMore: (String) -> Unit,
    onViewport: (Int, Int) -> Unit,
    onContinueThread: (String) -> Unit,
    onReply: (CommentRow.Comment) -> Unit,
    onVoteComment: (String, Int) -> Unit,
    onVotePost: (Int) -> Unit,
    onSharePost: (() -> Unit)?,
    onResharePost: (() -> Unit)?,
    onRootComment: () -> Unit,
    onCommunityClick: (String) -> Unit,
    onJoinCommunity: () -> Unit,
) {
    var scrolledToFocus by remember(state.focusedCommentId) { mutableStateOf(false) }
    var expandingCommentKey by remember { mutableStateOf<String?>(null) }
    val expandingDescendantKeys = remember(rows, expandingCommentKey) {
        expansionDescendantKeys(rows, expandingCommentKey)
    }
    val entries = remember(rows, expandingCommentKey, expandingDescendantKeys) {
        commentListEntries(rows, expandingCommentKey, expandingDescendantKeys)
    }
    LaunchedEffect(expandingCommentKey, expandingDescendantKeys) {
        if (expandingCommentKey != null && expandingDescendantKeys.isNotEmpty()) {
            delay(COMMENT_COLLAPSE_DURATION_MS.toLong())
            withFrameNanos { }
            expandingCommentKey = null
        }
    }
    LaunchedEffect(rows, state.focusedCommentId) {
        if (!scrolledToFocus && state.focusedCommentId != null) {
            val rowIndex = rows.indexOfFirst { it.key == state.focusedCommentId }
            if (rowIndex >= 0) {
                listState.scrollToItem(rowIndex + POST_HEADER_ITEM_COUNT)
                scrolledToFocus = true
            }
        }
    }
    val sourceIndexes = remember(sourceRows) {
        sourceRows.mapIndexed { index, row -> row.key to index }.toMap()
    }
    LaunchedEffect(listState, rows, sourceIndexes, state.loadMoreStates, expandingCommentKey) {
        if (expandingCommentKey != null) return@LaunchedEffect
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            visible.mapNotNull { item ->
                when (item.index) {
                    0 -> 0
                    else -> rows.getOrNull(item.index - POST_HEADER_ITEM_COUNT)?.key
                        ?.let(sourceIndexes::get)
                        ?.plus(POST_HEADER_ITEM_COUNT)
                }
            }
        }.distinctUntilChanged().collect { mappedIndexes ->
            if (mappedIndexes.isNotEmpty()) {
                onViewport(
                    mappedIndexes.minOrNull() ?: return@collect,
                    mappedIndexes.maxOrNull() ?: return@collect,
                )
            }
        }
    }
    LazyColumn(state = listState, modifier = modifier.fillMaxSize().clipToBounds()) {
        item(key = "__post_header__", contentType = TYPE_HEADER) {
            PostHeaderItem(
                state = state,
                communityHeader = communityHeader,
                icons = icons,
                imageRenderer = imageRenderer,
                mediaRenderer = mediaRenderer,
                showMedia = showPostMedia,
                onVote = onVotePost,
                onComment = onRootComment,
                onShare = onSharePost,
                onReshare = onResharePost,
                onCommunityClick = onCommunityClick,
                onJoinCommunity = onJoinCommunity,
                modifier = Modifier.animateItem(
                    fadeInSpec = null,
                    fadeOutSpec = null,
                    placementSpec = tween(
                        COMMENT_COLLAPSE_DURATION_MS,
                        easing = FastOutSlowInEasing,
                    ),
                ),
            )
        }
        items(
            items = entries,
            key = CommentListEntry::key,
            contentType = { entry ->
                when (entry) {
                    is CommentListEntry.Single -> commentRowContentType(entry.row)
                    is CommentListEntry.ExpandingThread -> TYPE_COMMENT
                }
            },
        ) { entry ->
            when (entry) {
                is CommentListEntry.Single -> CommentRowContent(
                    row = entry.row,
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface).animateItem(
                        fadeInSpec = null,
                        fadeOutSpec = null,
                        placementSpec = tween(COMMENT_COLLAPSE_DURATION_MS, easing = FastOutSlowInEasing),
                    ),
                    focusedCommentId = state.focusedCommentId,
                    searchQuery = searchQuery,
                    loadMoreStates = state.loadMoreStates,
                    icons = icons,
                    canMutate = state.canMutate,
                    imageRenderer = imageRenderer,
                    onToggle = { row, key ->
                        expandingCommentKey = if (row.isCollapsed) key else null
                        onToggle(key)
                    },
                    onLoadMore = onLoadMore,
                    onContinueThread = onContinueThread,
                    onReply = onReply,
                    onVoteComment = onVoteComment,
                )
                is CommentListEntry.ExpandingThread -> CommentItem(
                    row = entry.owner,
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface),
                    focused = entry.owner.key == state.focusedCommentId ||
                        entry.owner.matchesCommentSearch(searchQuery),
                    icons = icons,
                    canMutate = state.canMutate,
                    imageRenderer = imageRenderer,
                    onToggle = onToggle,
                    onReply = onReply,
                    onVote = onVoteComment,
                    animateBodyChanges = false,
                    expansionContent = {
                        entry.rows.forEach { row ->
                            CommentRowContent(
                                row = row,
                                focusedCommentId = state.focusedCommentId,
                                searchQuery = searchQuery,
                                loadMoreStates = state.loadMoreStates,
                                icons = icons,
                                canMutate = state.canMutate,
                                imageRenderer = imageRenderer,
                                onToggle = { comment, key ->
                                    expandingCommentKey = if (comment.isCollapsed) key else null
                                    onToggle(key)
                                },
                                onLoadMore = onLoadMore,
                                onContinueThread = onContinueThread,
                                onReply = onReply,
                                onVoteComment = onVoteComment,
                                animateCommentBody = false,
                            )
                        }
                    },
                )
            }
        }
        if (!state.isLoadingInitial && rows.isEmpty()) {
            item(key = "__comments_empty__", contentType = TYPE_EMPTY) {
                Text(
                    "No comments yet. Start the conversation.",
                    Modifier.fillMaxWidth().padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private sealed interface CommentListEntry {
    val key: String
    data class Single(val row: CommentRow) : CommentListEntry { override val key = row.key }
    data class ExpandingThread(
        val owner: CommentRow.Comment,
        val rows: List<CommentRow>,
    ) : CommentListEntry { override val key = owner.key }
}

private fun commentListEntries(
    rows: List<CommentRow>,
    parentKey: String?,
    descendantKeys: Set<String>,
): List<CommentListEntry> {
    if (parentKey == null || descendantKeys.isEmpty()) return rows.map(CommentListEntry::Single)
    val owner = rows.firstOrNull { it.key == parentKey } as? CommentRow.Comment
        ?: return rows.map(CommentListEntry::Single)
    val descendants = rows.filter { it.key in descendantKeys }
    if (descendants.isEmpty()) return rows.map(CommentListEntry::Single)
    return buildList(rows.size - descendants.size + 1) {
        rows.forEach { row ->
            when {
                row.key == parentKey -> add(CommentListEntry.ExpandingThread(owner, descendants))
                row.key !in descendantKeys -> add(CommentListEntry.Single(row))
            }
        }
    }
}

fun expansionDescendantKeys(rows: List<CommentRow>, parentKey: String?): Set<String> {
    if (parentKey == null) return emptySet()
    val parentIndex = rows.indexOfFirst { it.key == parentKey }
    if (parentIndex < 0) return emptySet()
    val parentDepth = rows[parentIndex].renderDepth
    return buildSet {
        for (index in parentIndex + 1 until rows.size) {
            val candidate = rows[index]
            if (candidate.renderDepth <= parentDepth) break
            add(candidate.key)
        }
    }
}

private fun commentRowContentType(row: CommentRow): String = when (row) {
    is CommentRow.Comment -> TYPE_COMMENT
    is CommentRow.LoadMore -> TYPE_LOAD_MORE
    is CommentRow.ContinueThread -> TYPE_CONTINUE
}

@Composable
private fun CommentRowContent(
    row: CommentRow,
    focusedCommentId: String?,
    searchQuery: String,
    loadMoreStates: Map<String, DetailLoadMoreState>,
    icons: DetailIcons,
    canMutate: Boolean,
    imageRenderer: DetailImageRenderer,
    onToggle: (CommentRow.Comment, String) -> Unit,
    onLoadMore: (String) -> Unit,
    onContinueThread: (String) -> Unit,
    onReply: (CommentRow.Comment) -> Unit,
    onVoteComment: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
    animateCommentBody: Boolean = true,
) {
    when (row) {
        is CommentRow.Comment -> CommentItem(
            row = row,
            focused = row.key == focusedCommentId || row.matchesCommentSearch(searchQuery),
            modifier = modifier,
            icons = icons,
            canMutate = canMutate,
            imageRenderer = imageRenderer,
            onToggle = { onToggle(row, it) },
            onReply = onReply,
            onVote = onVoteComment,
            animateBodyChanges = animateCommentBody,
        )
        is CommentRow.LoadMore -> LoadMoreItem(row, loadMoreStates[row.key], onLoadMore, modifier)
        is CommentRow.ContinueThread -> ContinueThreadItem(row, onContinueThread, modifier)
    }
}

@Composable
private fun ExpansionReveal(content: @Composable () -> Unit) {
    val visibility = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = visibility,
        modifier = Modifier.fillMaxWidth().clipToBounds(),
        enter = commentExpandTransition(),
        exit = commentCollapseTransition(),
    ) { content() }
}

@Composable
private fun PostHeaderItem(
    state: DetailUiState,
    communityHeader: DetailCommunityHeader?,
    icons: DetailIcons,
    imageRenderer: DetailImageRenderer,
    mediaRenderer: DetailMediaRenderer,
    showMedia: Boolean,
    onVote: (Int) -> Unit,
    onComment: () -> Unit,
    onShare: (() -> Unit)?,
    onReshare: (() -> Unit)?,
    onCommunityClick: (String) -> Unit,
    onJoinCommunity: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val header = state.header
    val preview = state.transitionPreview
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        if (state.rootCommentId != null || state.focusedCommentId != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    if (state.focusedCommentId != null) "Showing the comment you opened"
                    else "Continuing this thread at depth zero",
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        val title = header?.title ?: preview?.title
        if (title == null) {
            Text("…", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val subreddit = header?.subreddit ?: preview?.subreddit.orEmpty()
            val author = header?.author ?: preview?.author.orEmpty()
            if (subreddit.isNotBlank()) {
                CommunityIdentityLine(
                    subreddit = subreddit,
                    author = author,
                    communityHeader = communityHeader,
                    imageRenderer = imageRenderer,
                    onCommunityClick = onCommunityClick,
                    onJoinCommunity = onJoinCommunity,
                )
            }
            Text(
                title,
                Modifier.fillMaxWidth().padding(
                    start = 12.dp,
                    end = 12.dp,
                    top = if (subreddit.isBlank()) 12.dp else 0.dp,
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            (header?.flair ?: preview?.flair)?.let { flair ->
                Surface(
                    color = flair.backgroundColor.detailColor(MaterialTheme.colorScheme.surfaceVariant),
                    contentColor = flair.textColor.detailColor(MaterialTheme.colorScheme.onSurface),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(start = 12.dp, top = 6.dp),
                ) {
                    Text(
                        flair.text,
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            (header?.body ?: preview?.body)?.let { body ->
                MarkdownText(
                    markdown = body,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp),
                )
            }
            val mediaItems = header?.mediaItems?.ifEmpty { listOfNotNull(header.media) }
                ?: preview?.mediaItems?.ifEmpty { listOfNotNull(preview.media) }.orEmpty()
            if (showMedia && mediaItems.isNotEmpty()) {
                mediaRenderer(
                    mediaItems,
                    mediaItems.first().cacheKey ?: "post:${header?.postId ?: preview?.postId}",
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
            (header?.linkUrl ?: preview?.linkUrl)?.let { url ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                ) { Text(url, Modifier.padding(14.dp), maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            val score = header?.score ?: preview?.score
            val vote = header?.viewerVote ?: preview?.viewerVote
            val comments = header?.commentCount ?: preview?.commentCount
            if (score != null && vote != null && comments != null) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VoteControls(
                        CommentFlattener.compactScore(score),
                        vote,
                        state.canMutate,
                        icons,
                        onVote,
                    )
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.clickable(enabled = state.canMutate, onClick = onComment),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DetailGlyph(icons.comments, "○", "Comments", Modifier.size(18.dp))
                            Text("  $comments", fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    onReshare?.let { reshare ->
                        IconButton(onClick = reshare) {
                            DetailGlyph(icons.reshare, "↻", "Reshare post", Modifier.size(20.dp))
                        }
                    }
                    onShare?.let { share ->
                        IconButton(onClick = share) {
                            DetailGlyph(icons.share, "↗", "Share post", Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp))
    }
}

@Composable
private fun CommentItem(
    row: CommentRow.Comment,
    focused: Boolean,
    icons: DetailIcons,
    canMutate: Boolean,
    imageRenderer: DetailImageRenderer,
    onToggle: (String) -> Unit,
    onReply: (CommentRow.Comment) -> Unit,
    onVote: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
    animateBodyChanges: Boolean = true,
    expansionContent: (@Composable () -> Unit)? = null,
) {
    val collapseLabel = if (row.isCollapsed) "Expand" else "Collapse"
    val collapsedPreview = remember(row.body) { markdownPlainText(row.body) }
    Column(modifier.fillMaxWidth()) {
        if (row.renderDepth == 0) {
            Spacer(
                Modifier.fillMaxWidth().height(ROOT_COMMENT_GAP_DP.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)),
            )
        }
        val highlight = if (focused) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .65f)
            else Color.Transparent
        Column(Modifier.fillMaxWidth().clipToBounds().threadRails(row.renderDepth)) {
            Row(
                Modifier.fillMaxWidth().background(highlight, RoundedCornerShape(8.dp))
                    .clickable(onClick = { onToggle(row.key) })
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        if (row.isCollapsed) {
                            stateDescription = if (row.collapsedDescendants > 0) {
                                "Collapsed, ${row.collapsedDescendants} replies hidden"
                            } else "Collapsed"
                        }
                        customActions = listOf(
                            CustomAccessibilityAction(collapseLabel) { onToggle(row.key); true },
                        )
                    }
                    .padding(
                        start = 12.dp + indentFor(row.renderDepth),
                        top = 6.dp,
                        end = 12.dp,
                        bottom = 6.dp,
                    ),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CommentAvatar(row, imageRenderer)
                Text(
                    row.authorDisplayName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (row.isCollapsed) Modifier.widthIn(max = COLLAPSED_AUTHOR_MAX_WIDTH_DP.dp)
                    else Modifier.weight(1f, fill = false),
                )
                Text(
                    "· ${row.ageLabel}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (row.isCollapsed && collapsedPreview.isNotEmpty()) {
                    Text(
                        "· $collapsedPreview",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (row.isEdited) {
                    Text(
                        "· Edited",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            collapsedCommentCountLabel(row.collapsedDescendants)?.takeIf { row.isCollapsed }?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .padding(
                            start = 43.dp + indentFor(row.renderDepth),
                            bottom = 6.dp,
                        )
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable(
                            onClickLabel = label,
                            role = Role.Button,
                        ) { onToggle(row.key) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            if (expansionContent != null && !row.isCollapsed) {
                ExpansionReveal {
                    Column(Modifier.fillMaxWidth()) {
                        CommentBody(row, highlight, icons, canMutate, onToggle, onReply, onVote)
                        expansionContent()
                    }
                }
            } else if (animateBodyChanges) {
                AnimatedVisibility(
                    visible = !row.isCollapsed,
                    enter = commentExpandTransition(),
                    exit = commentCollapseTransition(),
                    modifier = Modifier.fillMaxWidth().clipToBounds(),
                ) { CommentBody(row, highlight, icons, canMutate, onToggle, onReply, onVote) }
            } else if (!row.isCollapsed) {
                CommentBody(row, highlight, icons, canMutate, onToggle, onReply, onVote)
            }
        }
    }
}

fun collapsedCommentCountLabel(count: Int): String? = when (count) {
    1 -> "Show 1 hidden reply"
    in 2..Int.MAX_VALUE -> "Show $count hidden replies"
    else -> null
}

@Composable
private fun CommentBody(
    row: CommentRow.Comment,
    highlight: Color,
    icons: DetailIcons,
    canMutate: Boolean,
    onToggle: (String) -> Unit,
    onReply: (CommentRow.Comment) -> Unit,
    onVote: (String, Int) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .background(highlight, RoundedCornerShape(8.dp))
            .clickable { onToggle(row.key) }
            .padding(
                start = 12.dp + indentFor(row.renderDepth),
                end = 12.dp,
                bottom = 6.dp,
            ),
    ) {
        MarkdownText(row.body, style = MaterialTheme.typography.bodyMedium)
        Row(
            Modifier.fillMaxWidth().padding(top = 5.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { onReply(row) }, enabled = canMutate) {
                DetailGlyph(icons.reply, "↩", null, Modifier.size(18.dp))
                Text(" Reply")
            }
            VoteControls(row.scoreLabel, row.viewerVote, canMutate, icons) { onVote(row.key, it) }
        }
    }
}

@Composable
private fun CommentAvatar(row: CommentRow.Comment, imageRenderer: DetailImageRenderer) {
    val initial = row.authorDisplayName.trim().firstOrNull()?.uppercase() ?: "?"
    Box(
        Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initial,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        row.authorAvatarUrl?.let { url ->
            imageRenderer(url, "comment-avatar:$url", "${row.authorDisplayName} avatar", Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun LoadMoreItem(
    row: CommentRow.LoadMore,
    state: DetailLoadMoreState?,
    onLoadMore: (String) -> Unit,
    modifier: Modifier,
) {
    val base = modifier.fillMaxWidth().threadRails(row.renderDepth)
        .padding(start = indentFor(row.renderDepth) + 8.dp, top = 6.dp, bottom = 6.dp)
    when (state) {
        DetailLoadMoreState.Loading -> Row(base, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.height(14.dp).width(14.dp))
            Text("Loading replies…", style = MaterialTheme.typography.labelLarge)
        }
        DetailLoadMoreState.Error -> Text(
            "Couldn't load replies · Retry",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.error,
            modifier = base.clickable { onLoadMore(row.key) }.semantics {
                liveRegion = LiveRegionMode.Polite
            },
        )
        null -> Text(
            row.label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = base.clickable { onLoadMore(row.key) },
        )
    }
}

@Composable
private fun ContinueThreadItem(
    row: CommentRow.ContinueThread,
    onContinueThread: (String) -> Unit,
    modifier: Modifier,
) {
    Text(
        "Continue this thread →",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = modifier.fillMaxWidth()
            .clickable(enabled = row.parentId != null) { row.parentId?.let(onContinueThread) }
            .threadRails(row.renderDepth)
            .padding(start = indentFor(row.renderDepth) + 8.dp, top = 6.dp, bottom = 6.dp),
    )
}

@Composable
private fun DetailTopBar(
    onBack: () -> Unit,
    icons: DetailIcons,
    searchOpen: Boolean,
    searchQuery: String,
    searchMatchCount: Int,
    activeSearchMatch: Int,
    commentSort: CommentSort,
    communityName: String?,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onPreviousSearchMatch: () -> Unit,
    onNextSearchMatch: () -> Unit,
    onCommentSortChanged: (CommentSort) -> Unit,
    onSharePost: (() -> Unit)?,
    onCommunityClick: (String) -> Unit,
) {
    var sortExpanded by remember { mutableStateOf(false) }
    var moreExpanded by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val normalizedCommunity = communityName?.trim()?.removePrefix("r/")?.takeIf(String::isNotBlank)
    LaunchedEffect(searchOpen) {
        if (searchOpen) focusRequester.requestFocus()
    }
    Row(
        Modifier.fillMaxWidth().height(PostDetailToolbarHeight).background(PostDetailChromeColor)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = if (searchOpen) onCloseSearch else onBack) {
            DetailGlyph(icons.close, "×", if (searchOpen) "Close comment search" else "Close post", Modifier.size(24.dp), Color.White)
        }
        if (searchOpen) {
            TextField(
                value = searchQuery,
                onValueChange = onSearchQueryChanged,
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                singleLine = true,
                placeholder = { Text("Search comments") },
                colors = TextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = Color.White,
                    focusedPlaceholderColor = Color.White.copy(alpha = 0.65f),
                    unfocusedPlaceholderColor = Color.White.copy(alpha = 0.65f),
                ),
            )
            if (searchQuery.isNotBlank()) {
                Text(
                    if (searchMatchCount == 0) "0/0" else "${activeSearchMatch + 1}/$searchMatchCount",
                    color = Color.White.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            IconButton(onClick = onPreviousSearchMatch, enabled = searchMatchCount > 0) {
                DetailGlyph(
                    icons.previousMatch,
                    "↑",
                    "Previous comment search result",
                    Modifier.size(22.dp),
                    if (searchMatchCount > 0) Color.White else Color.White.copy(alpha = 0.35f),
                )
            }
            IconButton(onClick = onNextSearchMatch, enabled = searchMatchCount > 0) {
                DetailGlyph(
                    icons.nextComment,
                    "↓",
                    "Next comment search result",
                    Modifier.size(22.dp),
                    if (searchMatchCount > 0) Color.White else Color.White.copy(alpha = 0.35f),
                )
            }
        } else {
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onOpenSearch) {
                DetailGlyph(icons.search, "⌕", "Search comments", Modifier.size(22.dp), Color.White)
            }
            Box {
                IconButton(onClick = { sortExpanded = true }) {
                    DetailGlyph(
                        icons.filter,
                        "≡",
                        "Sort comments, ${commentSort.label}",
                        Modifier.size(22.dp),
                        Color.White,
                    )
                }
                DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                    CommentSort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    option.label,
                                    fontWeight = if (option == commentSort) FontWeight.Bold else FontWeight.Normal,
                                )
                            },
                            onClick = {
                                onCommentSortChanged(option)
                                sortExpanded = false
                            },
                        )
                    }
                }
            }
            if (onSharePost != null || normalizedCommunity != null) {
                Box {
                    IconButton(onClick = { moreExpanded = true }) {
                        DetailGlyph(icons.more, "⋯", "Post menu", Modifier.size(22.dp), Color.White)
                    }
                    DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                        onSharePost?.let { share ->
                            DropdownMenuItem(
                                text = { Text("Share post") },
                                onClick = {
                                    moreExpanded = false
                                    share()
                                },
                            )
                        }
                        normalizedCommunity?.let { community ->
                            DropdownMenuItem(
                                text = { Text("Open r/$community") },
                                onClick = {
                                    moreExpanded = false
                                    onCommunityClick(community)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommunityIdentityLine(
    subreddit: String,
    author: String,
    communityHeader: DetailCommunityHeader?,
    imageRenderer: DetailImageRenderer,
    onCommunityClick: (String) -> Unit,
    onJoinCommunity: () -> Unit,
) {
    val communityName = subreddit.trim().removePrefix("r/")
    val displayName = communityName.takeIf(String::isNotBlank)?.let { "r/$it" }.orEmpty()
    val displayAuthor = author.trim().takeIf(String::isNotBlank)?.let {
        if (it.startsWith("u/")) it else "u/$it"
    }.orEmpty()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(enabled = communityName.isNotBlank()) { onCommunityClick(communityName) },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                communityName.take(1).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            communityHeader?.avatarUrl?.takeIf(String::isNotBlank)?.let { url ->
                imageRenderer(url, "community-avatar:$url", "r/$communityName community avatar", Modifier.fillMaxSize())
            }
        }
        Column(
            Modifier.weight(1f).clip(RoundedCornerShape(6.dp))
                .clickable(enabled = communityName.isNotBlank(), role = Role.Button) {
                    onCommunityClick(communityName)
                }.padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(displayName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (displayAuthor.isNotBlank()) {
                Text(
                    displayAuthor,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (communityHeader?.isMember == false && communityHeader.canJoin) {
            Button(
                onClick = onJoinCommunity,
                enabled = !communityHeader.membershipChanging,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PostDetailChromeColor,
                    contentColor = Color.White,
                ),
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.height(36.dp),
            ) {
                if (communityHeader.membershipChanging) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
                } else Text("Join", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun RootCommentBar(
    onComment: () -> Unit,
    onNextComment: () -> Unit,
    nextCommentEnabled: Boolean,
    nextCommentIcon: ImageVector?,
) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
        Column {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).clickable(onClick = onComment),
                ) {
                    Text(
                        "Join the conversation",
                        Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape,
                    modifier = Modifier.padding(start = 10.dp).size(48.dp),
                ) {
                    IconButton(onClick = onNextComment, enabled = nextCommentEnabled) {
                        DetailGlyph(nextCommentIcon, "↓", "Next comment", Modifier.size(22.dp))
                    }
                }
            }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
        }
    }
}

@Composable
private fun VoteControls(
    score: String,
    vote: Int,
    enabled: Boolean,
    icons: DetailIcons,
    onVote: (Int) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DetailGlyph(
                icons.upvote,
                "▲",
                "Upvote",
                Modifier.clickable(enabled = enabled) { onVote(if (vote == 1) 0 else 1) }
                    .padding(8.dp).size(18.dp),
                if (vote == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(score, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            DetailGlyph(
                icons.downvote,
                "▼",
                "Downvote",
                Modifier.clickable(enabled = enabled) { onVote(if (vote == -1) 0 else -1) }
                    .padding(8.dp).size(18.dp),
                if (vote == -1) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun DetailGlyph(
    icon: ImageVector?,
    fallback: String,
    description: String?,
    modifier: Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    if (icon != null) Icon(icon, description, modifier, tint = tint)
    else Text(fallback, modifier, color = tint, fontWeight = FontWeight.Bold)
}

@Composable
private fun CommentComposer(
    reply: CommentRow.Comment?,
    submitting: Boolean,
    closeIcon: ImageVector?,
    onDismiss: () -> Unit,
    onPost: (String) -> Unit,
) {
    var body by remember(reply?.key) { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        DetailGlyph(closeIcon, "×", "Close", Modifier.size(24.dp))
                    }
                    Text(
                        if (reply == null) "Add comment" else "Reply",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { onPost(body) },
                        enabled = body.isNotBlank() && body.length <= 10_000 && !submitting,
                    ) { Text(if (submitting) "Posting…" else "Post", fontWeight = FontWeight.Bold) }
                }
                reply?.let {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(it.authorDisplayName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            MarkdownText(
                                it.body,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                OutlinedTextField(
                    body,
                    { if (it.length <= 10_000) body = it },
                    placeholder = { Text("Join the conversation") },
                    modifier = Modifier.fillMaxSize().padding(18.dp),
                    shape = RoundedCornerShape(18.dp),
                )
            }
        }
    }
}

@Composable
private fun Modifier.threadRails(renderDepth: Int): Modifier {
    if (renderDepth == 0) return this
    val color = MaterialTheme.colorScheme.outlineVariant
    return drawBehind {
        val step = INDENT_UNIT_DP.dp.toPx()
        val stroke = 2.dp.toPx()
        for (level in 1..minOf(renderDepth, MAX_INDENT_LEVELS)) {
            val x = step * level - step / 2
            drawRect(
                color = color,
                topLeft = Offset(x, 0f),
                size = Size(stroke, size.height),
            )
        }
    }
}

private fun indentFor(depth: Int) = (depth.coerceAtMost(MAX_INDENT_LEVELS) * INDENT_UNIT_DP).dp

internal fun commentSearchMatchIndices(rows: List<CommentRow>, query: String): List<Int> {
    val normalized = query.trim()
    if (normalized.isEmpty()) return emptyList()
    return rows.mapIndexedNotNull { index, row ->
        index.takeIf { (row as? CommentRow.Comment)?.matchesCommentSearch(normalized) == true }
    }
}

private fun CommentRow.Comment.matchesCommentSearch(query: String): Boolean {
    val normalized = query.trim()
    if (normalized.isEmpty()) return false
    return author.contains(normalized, ignoreCase = true) ||
        authorDisplayName.contains(normalized, ignoreCase = true) ||
        body.contains(normalized, ignoreCase = true)
}

fun nextRootCommentListIndex(rows: List<CommentRow>, firstVisibleListIndex: Int): Int? {
    val firstVisibleRenderIndex = firstVisibleListIndex - POST_HEADER_ITEM_COUNT
    return rows.indices.firstOrNull { index ->
        val row = rows[index]
        index > firstVisibleRenderIndex && row is CommentRow.Comment && row.renderDepth == 0
    }?.plus(POST_HEADER_ITEM_COUNT)
}

private suspend fun scrollToNextRootComment(
    listState: LazyListState,
    rowsProvider: () -> List<CommentRow>,
) {
    if (rowsProvider().isEmpty()) return
    val rows = snapshotFlow { rowsProvider() to listState.layoutInfo.totalItemsCount }
        .first { (latestRows, itemCount) -> itemCount == latestRows.size + POST_HEADER_ITEM_COUNT }
        .first
    if (!listState.canScrollForward) return
    val targetIndex = nextRootCommentListIndex(rows, listState.firstVisibleItemIndex)
    if (targetIndex != null) {
        val visibleTarget = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
        if (visibleTarget != null) {
            val distance = visibleTarget.offset - listState.layoutInfo.viewportStartOffset
            listState.animateScrollBy(
                distance.toFloat(),
                tween(NEXT_COMMENT_SCROLL_DURATION_MS, easing = FastOutSlowInEasing),
            )
        } else listState.animateScrollToItem(targetIndex)
        return
    }
    val lastIndex = listState.layoutInfo.totalItemsCount - 1
    if (lastIndex < 0) return
    listState.animateScrollToItem(lastIndex)
    val lastItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == lastIndex }
    val remaining = lastItem?.let {
        (it.offset + it.size - listState.layoutInfo.viewportEndOffset).coerceAtLeast(0)
    } ?: 0
    if (remaining > 0 && listState.canScrollForward) {
        listState.animateScrollBy(
            remaining.toFloat(),
            tween(NEXT_COMMENT_SCROLL_DURATION_MS, easing = FastOutSlowInEasing),
        )
    }
}

private fun String.detailColor(fallback: Color): Color {
    val raw = trim().removePrefix("#")
    val value = raw.toLongOrNull(16) ?: return fallback
    return when (raw.length) {
        6 -> Color(0xFF000000L or value)
        8 -> Color(value)
        else -> fallback
    }
}

private fun commentExpandTransition() = expandVertically(
    animationSpec = tween(COMMENT_COLLAPSE_DURATION_MS, easing = FastOutSlowInEasing),
    expandFrom = Alignment.Top,
    clip = true,
) + fadeIn(tween(COMMENT_CONTENT_FADE_MS))

private fun commentCollapseTransition() = shrinkVertically(
    animationSpec = tween(COMMENT_COLLAPSE_DURATION_MS, easing = FastOutSlowInEasing),
    shrinkTowards = Alignment.Top,
    clip = true,
) + fadeOut(tween(COMMENT_CONTENT_FADE_MS))

private const val INDENT_UNIT_DP = 12
private const val MAX_INDENT_LEVELS = 10
private const val COLLAPSED_AUTHOR_MAX_WIDTH_DP = 108
private const val ROOT_COMMENT_GAP_DP = 8
private const val COMMENT_COLLAPSE_DURATION_MS = 220
private const val COMMENT_CONTENT_FADE_MS = 90
private const val NEXT_COMMENT_SCROLL_DURATION_MS = 240
private const val POST_HEADER_ITEM_COUNT = 1
private const val MAX_COMMUNITY_INPUT_LENGTH = 64
private const val MAX_COMMENT_SEARCH_LENGTH = 100
private const val MIN_COMMUNITY_NAME_LENGTH = 3
private const val TYPE_HEADER = "header"
private const val TYPE_COMMENT = "comment"
private const val TYPE_LOAD_MORE = "load_more"
private const val TYPE_CONTINUE = "continue_thread"
private const val TYPE_EMPTY = "empty"

internal fun normalizedReshareCommunity(value: String): String =
    value.trim().removePrefix("r/").trim()
