package dev.readthat.comments.ui

import android.graphics.Color as AndroidColor
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Reply
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.readthat.comments.domain.CommentRenderList
import dev.readthat.comments.domain.CommentRow
import dev.readthat.comments.domain.PostHeader
import dev.readthat.playback.AdaptiveVideoPlayer
import dev.readthat.playback.AdaptiveVideoSource
import dev.readthat.playback.VideoPlaybackRole
import dev.readthat.playback.rememberVideoPlaybackPolicy
import dev.readthat.shared.AppSettings
import dev.readthat.shared.PostTransitionPreview
import dev.readthat.shared.videoPosterCacheKey
import dev.readthat.core.ui.markdown.MarkdownText
import dev.readthat.core.ui.markdown.markdownPlainText
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import dev.readthat.observability.PerformanceMetric
import dev.readthat.observability.PerformanceSurface
import dev.readthat.observability.PerformanceTelemetry
import dev.readthat.observability.ProductAnalytics
import dev.readthat.observability.ProductContentType
import dev.readthat.observability.ProductEvent
import dev.readthat.observability.ProductEventName
import dev.readthat.observability.ProductSurface
import dev.readthat.observability.performanceTimer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val PostDetailChromeColor = Color(0xFF0B1416)
val PostDetailToolbarHeight = 58.dp

enum class PostDetailPresentation { FullScreen, MediaBottomSheet }

/** Community data stays owned by the community feature; post detail only renders this projection. */
@Immutable
data class PostCommunityHeaderState(
    val avatarUrl: String? = null,
    val isMember: Boolean,
    val canJoin: Boolean,
    val membershipChanging: Boolean = false,
)

/**
 * The post-detail screen: post header + comment tree.
 *
 * Nav-agnostic on purpose — callbacks only, no navigation dependency. A library
 * module that imports the app's nav graph is a library that can't be reused.
 */
@Composable
fun PostDetailScreen(
    viewModel: CommentsViewModel,
    onContinueThread: (parentCommentId: String) -> Unit,
    onCommunityClick: (communityName: String) -> Unit,
    communityHeader: PostCommunityHeaderState? = null,
    onJoinCommunity: () -> Unit = {},
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
    onCommentsInteractive: (fromPrefetch: Boolean, phase: String, successful: Boolean) -> Unit = { _, _, _ -> },
    settings: AppSettings = AppSettings(),
    transitionPreview: PostTransitionPreview? = null,
    /** Applied to all detail content below the fixed toolbar. Navigation supplies any motion. */
    detailContentModifier: Modifier = Modifier,
    /** Transparent during feed handoff so the retained source remains visible above the surface. */
    containerColor: Color = MaterialTheme.colorScheme.surface,
    presentation: PostDetailPresentation = PostDetailPresentation.FullScreen,
    productSurface: ProductSurface = ProductSurface.DETAIL,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var composerOpen by remember { mutableStateOf(false) }
    var replyParent by remember { mutableStateOf<CommentRow.Comment?>(null) }
    var firstCommentReported by remember { mutableStateOf(false) }
    var nextCommentScrolling by remember { mutableStateOf(false) }
    val commentListState = rememberLazyListState()
    val latestRenderRows by rememberUpdatedState(state.render.rows)
    val interactionScope = rememberCoroutineScope()
    val analyticsPostId = state.header?.postId ?: transitionPreview?.postId

    val performanceSurface = if (presentation == PostDetailPresentation.MediaBottomSheet) {
        PerformanceSurface.MEDIA
    } else {
        PerformanceSurface.DETAIL
    }
    LaunchedEffect(performanceSurface) { PerformanceTelemetry.enterSurface(performanceSurface) }
    LaunchedEffect(analyticsPostId) {
        val postId = analyticsPostId ?: return@LaunchedEffect
        ProductAnalytics.record(ProductEvent(
            name = ProductEventName.POST_DETAIL_VIEW,
            surface = productSurface,
            contentId = postId,
            contentType = ProductContentType.POST,
        ))
        ProductAnalytics.record(ProductEvent(
            name = ProductEventName.COMMENTS_VIEW,
            surface = productSurface,
            contentId = postId,
            contentType = ProductContentType.POST,
        ))
    }
    LaunchedEffect(state.isLoadingInitial, state.render.rows, state.interactionError) {
        if (!firstCommentReported && !state.isLoadingInitial) {
            withFrameNanos { }
            firstCommentReported = true
            val phase = when {
                state.interactionError != null -> "error_state"
                state.render.rows.any { it is CommentRow.Comment } -> "initial_comments"
                else -> "empty_state"
            }
            onCommentsInteractive(
                state.servedFromPrefetch,
                phase,
                state.interactionError == null,
            )
        }
    }

    fun interaction(type: String, action: () -> Unit) {
        val timer = performanceTimer()
        action()
        interactionScope.launch {
            withFrameNanos { }
            PerformanceTelemetry.duration(
                PerformanceMetric.INTERACTION_TO_NEXT_FRAME,
                timer,
                surface = performanceSurface,
                attributes = mapOf("interaction_type" to type),
            )
        }
    }

    Column(modifier.fillMaxSize().background(containerColor)) {
        if (presentation == PostDetailPresentation.FullScreen) {
            DetailTopBar(onBack)
        }
        // Clip at the toolbar edge so a post captured partially above the feed viewport cannot
        // draw over fixed navigation while its destination content settles into place.
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .then(detailContentModifier),
            ) {
                // Phase 2 arriving is a background refinement, not a blocking spinner — the
                // user is already reading the first 8 comments.
                if (state.isLoadingFull) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                // The header skeleton occupies its final list slot immediately. Room can
                // populate cached content on the next frame while refresh continues in
                // the background; an app-open or tap-through never blocks on a spinner.
                CommentList(
                    modifier = Modifier.weight(1f),
                    listState = commentListState,
                    header = state.header,
                    transitionPreview = transitionPreview,
                    settings = settings,
                    render = state.render,
                    focusedCommentId = state.focusedCommentId,
                    loadMoreStates = state.loadMoreStates,
                    onToggle = { id -> interaction("comment_collapse") { viewModel.toggleCollapse(id) } },
                    onLoadMore = viewModel::loadMore,
                    onViewport = viewModel::onViewport,
                    onContinueThread = onContinueThread,
                    onReply = { row -> interaction("open_reply") { replyParent = row; composerOpen = true } },
                    onVoteComment = { id, value -> interaction("vote") { viewModel.voteComment(id, value) } },
                    onVotePost = { value -> interaction("vote") { viewModel.votePost(value) } },
                    onRootComment = { interaction("open_composer") { replyParent = null; composerOpen = true } },
                    onCommunityClick = { communityName -> interaction("open_community") {
                        onCommunityClick(communityName)
                    } },
                    communityHeader = communityHeader,
                    onJoinCommunity = { interaction("join_community") { onJoinCommunity() } },
                    showPostMedia = presentation == PostDetailPresentation.FullScreen,
                )
                state.interactionError?.let { error ->
                    Text(
                        error,
                        Modifier.fillMaxWidth().clickable { viewModel.clearInteractionError() }.padding(10.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                RootCommentBar(
                    onComment = { replyParent = null; composerOpen = true },
                    onNextComment = {
                        interaction("next_comment") {
                            if (!nextCommentScrolling) {
                                nextCommentScrolling = true
                                interactionScope.launch {
                                    try {
                                        scrollToNextRootComment(commentListState) { latestRenderRows }
                                    } finally {
                                        nextCommentScrolling = false
                                    }
                                }
                            }
                        }
                    },
                    nextCommentEnabled = !nextCommentScrolling,
                )
            }
        }
    }
    if (composerOpen) {
        CommentComposer(
            reply = replyParent,
            submitting = state.isSubmittingComment,
            onDismiss = { if (!state.isSubmittingComment) composerOpen = false },
            onPost = { body ->
                interaction("comment_submit") {
                    viewModel.createComment(replyParent?.key, body) { composerOpen = false }
                }
            },
        )
    }
}

/**
 * Extracted so the list's inputs are exactly (render, loadMoreStates, callbacks):
 * the isLoadingFull / servedFromPrefetch flags above can flip without this scope
 * ever recomposing. That read-scoping — not lambda memoization, which strong
 * skipping already does — is what the extraction buys.
 */
@Composable
private fun CommentList(
    modifier: Modifier = Modifier,
    listState: LazyListState,
    header: PostHeader?,
    transitionPreview: PostTransitionPreview?,
    settings: AppSettings,
    render: CommentRenderList,
    focusedCommentId: String?,
    loadMoreStates: Map<String, LoadMoreState>,
    onToggle: (String) -> Unit,
    onLoadMore: (String) -> Unit,
    onViewport: (firstVisibleItemIndex: Int, lastVisibleItemIndex: Int) -> Unit,
    onContinueThread: (String) -> Unit,
    onReply: (CommentRow.Comment) -> Unit,
    onVoteComment: (String, Int) -> Unit,
    onVotePost: (Int) -> Unit,
    onRootComment: () -> Unit,
    onCommunityClick: (String) -> Unit,
    communityHeader: PostCommunityHeaderState?,
    onJoinCommunity: () -> Unit,
    showPostMedia: Boolean,
) {
    var scrolledToFocus by remember(focusedCommentId) { mutableStateOf(false) }
    var expandingCommentKey by remember { mutableStateOf<String?>(null) }
    val expandingDescendantKeys = remember(render.rows, expandingCommentKey) {
        expansionDescendantKeys(render.rows, expandingCommentKey)
    }
    val listEntries = remember(render.rows, expandingCommentKey, expandingDescendantKeys) {
        commentListEntries(render.rows, expandingCommentKey, expandingDescendantKeys)
    }
    LaunchedEffect(expandingCommentKey, expandingDescendantKeys) {
        if (expandingCommentKey != null && expandingDescendantKeys.isNotEmpty()) {
            delay(COMMENT_COLLAPSE_DURATION_MS.toLong())
            // Swap the completed temporary subtree back to individually keyed lazy rows only after
            // the final expansion frame has been presented. The two layouts are pixel-equivalent.
            withFrameNanos { }
            expandingCommentKey = null
        }
    }
    LaunchedEffect(render.rows, focusedCommentId) {
        if (!scrolledToFocus && focusedCommentId != null) {
            val rowIndex = render.rows.indexOfFirst { it.key == focusedCommentId }
            if (rowIndex >= 0) {
                listState.scrollToItem(rowIndex + 1)
                scrolledToFocus = true
            }
        }
    }
    // Keep pagination as a UDF intent: Compose observes only viewport geometry;
    // the ViewModel chooses whether an idle nested cursor is eligible. Restart
    // when rows or per-cursor state change so a splice immediately evaluates the
    // newly exposed continuation without waiting for another scroll event.
    LaunchedEffect(listState, render.rows, loadMoreStates, expandingCommentKey) {
        if (expandingCommentKey != null) return@LaunchedEffect
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            visible.firstOrNull()?.index to visible.lastOrNull()?.index
        }
            .distinctUntilChanged()
            .collect { (first, last) ->
                if (first != null && last != null) onViewport(first, last)
            }
    }
    LazyColumn(state = listState, modifier = modifier.fillMaxSize().clipToBounds()) {
        // The header slot exists from the FIRST frame, skeleton included. If it
        // appeared only when loaded, LazyColumn would anchor scroll to the first
        // comment and the late-arriving header would insert invisibly above the
        // fold — the classic list-insertion anchor trap.
        item(key = "__post_header__", contentType = TYPE_HEADER) {
            PostHeaderItem(
                header,
                transitionPreview,
                settings,
                onVotePost,
                onRootComment,
                onCommunityClick,
                communityHeader,
                onJoinCommunity,
                showPostMedia,
            )
        }
        items(
            items = listEntries,
            key = { it.key },
            // Constants, not it::class.simpleName — reflection metadata plus a
            // String allocation per item is the wrong price for a type tag.
            contentType = { entry ->
                when (entry) {
                    is CommentListEntry.Single -> commentRowContentType(entry.row)
                    is CommentListEntry.ExpandingThread -> TYPE_COMMENT
                }
            },
        ) { entry ->
            // animateItem is why the stable keys exist: expansion slides children
            // in, collapse closes the list up, spliced replies animate into place.
            // Without keyed identity none of this motion is attributable.
            // During expansion the whole descendant subtree is temporarily one lazy item. A
            // single top-anchored clip can therefore reveal its already-correct final layout;
            // independently expanding flattened rows would compress and overlap one another.
            when (entry) {
                is CommentListEntry.Single -> CommentRowContent(
                    row = entry.row,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface)
                        .animateItem(
                            fadeInSpec = null,
                            fadeOutSpec = null,
                            placementSpec = tween(
                                COMMENT_COLLAPSE_DURATION_MS,
                                easing = FastOutSlowInEasing,
                            ),
                        ),
                    focusedCommentId = focusedCommentId,
                    loadMoreStates = loadMoreStates,
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
                    onToggle = { key -> onToggle(key) },
                    onReply = onReply,
                    onVote = onVoteComment,
                    highlighted = entry.owner.key == focusedCommentId,
                    animateBodyChanges = false,
                    expansionContent = {
                        entry.rows.forEach { row ->
                            CommentRowContent(
                                row = row,
                                focusedCommentId = focusedCommentId,
                                loadMoreStates = loadMoreStates,
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
    }
}

@Composable
private fun ExpansionReveal(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val visibility = remember {
        MutableTransitionState(false).apply { targetState = true }
    }
    AnimatedVisibility(
        visibleState = visibility,
        modifier = modifier.fillMaxWidth().clipToBounds(),
        enter = commentExpandTransition(),
        exit = commentCollapseTransition(),
    ) {
        content()
    }
}

private sealed interface CommentListEntry {
    val key: String

    data class Single(val row: CommentRow) : CommentListEntry {
        override val key: String = row.key
    }

    data class ExpandingThread(
        val owner: CommentRow.Comment,
        val rows: List<CommentRow>,
    ) : CommentListEntry {
        override val key: String = owner.key
    }
}

private fun commentListEntries(
    rows: List<CommentRow>,
    parentKey: String?,
    descendantKeys: Set<String>,
): List<CommentListEntry> {
    if (parentKey == null || descendantKeys.isEmpty()) {
        return rows.map(CommentListEntry::Single)
    }
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

private fun commentRowContentType(row: CommentRow): String = when (row) {
    is CommentRow.Comment -> TYPE_COMMENT
    is CommentRow.LoadMore -> TYPE_LOAD_MORE
    is CommentRow.ContinueThread -> TYPE_CONTINUE
}

@Composable
private fun CommentRowContent(
    row: CommentRow,
    focusedCommentId: String?,
    loadMoreStates: Map<String, LoadMoreState>,
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
            row,
            onToggle = { key -> onToggle(row, key) },
            onReply = onReply,
            onVote = onVoteComment,
            modifier = modifier,
            highlighted = row.key == focusedCommentId,
            animateBodyChanges = animateCommentBody,
        )
        is CommentRow.LoadMore -> LoadMoreItem(
            row,
            loadMoreStates[row.key],
            onLoadMore,
            modifier,
        )
        is CommentRow.ContinueThread -> ContinueThreadItem(
            row,
            onContinueThread,
            modifier,
        )
    }
}

internal fun expansionDescendantKeys(
    rows: List<CommentRow>,
    parentKey: String?,
): Set<String> {
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

@Composable
private fun PostHeaderItem(
    header: PostHeader?,
    transitionPreview: PostTransitionPreview?,
    settings: AppSettings,
    onVote: (Int) -> Unit,
    onComment: () -> Unit,
    onCommunityClick: (String) -> Unit,
    communityHeader: PostCommunityHeaderState?,
    onJoinCommunity: () -> Unit,
    showMedia: Boolean,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        val title = header?.title ?: transitionPreview?.title
        if (title == null) {
            Text(
                "…",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        } else {
            val subreddit = header?.subreddit ?: transitionPreview?.subreddit.orEmpty()
            val author = header?.author ?: transitionPreview?.author.orEmpty()
            if (subreddit.isNotBlank()) {
                CommunityIdentityLine(
                    subreddit = subreddit,
                    author = author,
                    communityHeader = communityHeader,
                    onCommunityClick = onCommunityClick,
                    onJoinCommunity = onJoinCommunity,
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(
                        start = 12.dp,
                        end = 12.dp,
                        top = if (subreddit.isBlank()) 12.dp else 0.dp,
                    ),
            )
            (header?.flair ?: transitionPreview?.flair)?.let { flair ->
                Surface(
                    color = flair.backgroundColor.detailColor(MaterialTheme.colorScheme.surfaceVariant),
                    contentColor = flair.textColor.detailColor(MaterialTheme.colorScheme.onSurface),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(start = 12.dp, top = 6.dp),
                ) {
                    Text(
                        flair.text,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            // The post itself — this is a DETAIL page, not a comments page. Body
            // renders in full (the feed truncates at 3 lines); media renders the
            // same placeholder the feed drew for continuity during navigation.
            (header?.body ?: transitionPreview?.body)?.let { body ->
                MarkdownText(
                    markdown = body,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(start = 12.dp, end = 12.dp, top = 6.dp),
                )
            }
            (header?.media ?: transitionPreview?.media)?.takeIf { showMedia }?.let { media ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 12.dp),
                ) {
                    PostMediaBox(
                        media,
                        Modifier,
                        media.cacheKey ?: "post:${header?.postId ?: transitionPreview?.postId}",
                        settings,
                    )
                }
            }
            (header?.linkUrl ?: transitionPreview?.linkUrl)?.let { url ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 12.dp),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) { Text(url, Modifier.padding(14.dp), maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
            }
            header?.let {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VoteControls(
                        score = dev.readthat.comments.domain.CommentFlattener.compactScore(it.score),
                        vote = it.viewerVote,
                        onVote = onVote,
                    )
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.clickable(onClick = onComment),
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(18.dp))
                            Text("  ${it.commentCount}", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp))
    }
}

private fun String.detailColor(fallback: Color): Color = runCatching {
    Color(AndroidColor.parseColor(this))
}.getOrDefault(fallback)

/** Indentation is derived from renderDepth — the payload the flattener carries down. */
private const val INDENT_UNIT_DP = 12
private fun indentFor(depth: Int) = (depth.coerceAtMost(MAX_INDENT_LEVELS) * INDENT_UNIT_DP).dp

@Composable
private fun CommentItem(
    row: CommentRow.Comment,
    onToggle: (String) -> Unit,
    onReply: (CommentRow.Comment) -> Unit,
    onVote: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    animateBodyChanges: Boolean = true,
    expansionContent: (@Composable () -> Unit)? = null,
) {
    val collapseLabel = if (row.isCollapsed) "Expand" else "Collapse"
    val collapsedPreview = remember(row.body) { markdownPlainText(row.body) }
    Column(
        modifier.fillMaxWidth(),
    ) {
        if (row.renderDepth == 0) {
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .height(ROOT_COMMENT_GAP_DP.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
            )
        }
        val highlightColor = if (highlighted) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
        } else {
            Color.Transparent
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clipToBounds()
            // One rail PER ANCESTOR level, drawn, not laid out. Rails are paint,
            // not structure: no nested composables, no intrinsic passes, and the
            // lines run continuously through the row's full height (padding
            // included) so each thread reads as one unbroken line.
                .threadRails(row.renderDepth),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(highlightColor, RoundedCornerShape(8.dp))
                    .clickable(onClick = { onToggle(row.key) })
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        if (row.isCollapsed) {
                            stateDescription = if (row.collapsedDescendants > 0) {
                                "Collapsed, ${row.collapsedDescendants} replies hidden"
                            } else {
                                "Collapsed"
                            }
                        }
                        customActions = listOf(
                            CustomAccessibilityAction(collapseLabel) { onToggle(row.key); true },
                        )
                    }
                    // Root comments still need the page gutter. Descendants add their
                    // thread indent to that stable baseline instead of starting at x=0.
                    .padding(
                        start = 12.dp + indentFor(row.renderDepth),
                        top = 6.dp,
                        end = 12.dp,
                        bottom = 6.dp,
                    ),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CommentAvatar(row)
                Text(
                    row.authorDisplayName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (row.isCollapsed) {
                        Modifier.widthIn(max = COLLAPSED_AUTHOR_MAX_WIDTH_DP.dp)
                    } else {
                        Modifier.weight(1f, fill = false)
                    },
                )
                Text("· ${row.ageLabel}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (row.isCollapsed && collapsedPreview.isNotEmpty()) {
                    Text(
                        "· $collapsedPreview",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (row.isEdited) {
                    Text("· Edited", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (expansionContent != null && !row.isCollapsed) {
                ExpansionReveal {
                    Column(Modifier.fillMaxWidth()) {
                        CommentBodyContainer(row, highlightColor, onReply, onVote)
                        expansionContent()
                    }
                }
            } else if (animateBodyChanges) {
                AnimatedVisibility(
                    visible = !row.isCollapsed,
                    enter = commentExpandTransition(),
                    exit = commentCollapseTransition(),
                    modifier = Modifier.fillMaxWidth().clipToBounds(),
                ) {
                    CommentBodyContainer(row, highlightColor, onReply, onVote)
                }
            } else if (!row.isCollapsed) {
                CommentBodyContainer(row, highlightColor, onReply, onVote)
            }
        }
    }
}

@Composable
private fun CommentBodyContainer(
    row: CommentRow.Comment,
    highlightColor: Color,
    onReply: (CommentRow.Comment) -> Unit,
    onVote: (String, Int) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(highlightColor, RoundedCornerShape(8.dp))
            .padding(
                start = 12.dp + indentFor(row.renderDepth),
                end = 12.dp,
                bottom = 6.dp,
            ),
    ) {
        MarkdownText(
            markdown = row.body,
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 5.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { onReply(row) }) {
                Icon(Icons.Outlined.Reply, null, Modifier.size(18.dp))
                Text(" Reply")
            }
            VoteControls(row.scoreLabel, row.viewerVote) { onVote(row.key, it) }
        }
    }
}

@Composable
private fun CommentAvatar(row: CommentRow.Comment) {
    val initial = row.authorDisplayName.trim().firstOrNull()?.uppercase() ?: "?"
    Box(
        Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initial,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        row.authorAvatarUrl?.let { avatarUrl ->
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(avatarUrl)
                    .memoryCacheKey("comment-avatar:$avatarUrl")
                    .diskCacheKey("comment-avatar:$avatarUrl")
                    .build(),
                contentDescription = "${row.authorDisplayName} avatar",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun PostMediaBox(
    media: dev.readthat.comments.domain.PostMedia,
    modifier: Modifier,
    stableCacheKey: String,
    settings: AppSettings,
) {
    val playbackPolicy = rememberVideoPlaybackPolicy(settings)
    Box(
        modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .aspectRatio(media.aspectRatio)
            .background(Color(media.placeholderColor)),
        contentAlignment = Alignment.BottomEnd,
    ) {
        if (media.isVideo) {
            if (media.hlsUrl != null || media.fallbackUrl != null || media.url != null) {
                media.posterUrl?.let { poster ->
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(poster)
                            .memoryCacheKey(videoPosterCacheKey(stableCacheKey, poster))
                            .diskCacheKey(videoPosterCacheKey(stableCacheKey, poster))
                            .build(),
                        contentDescription = media.altText,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                AdaptiveVideoPlayer(
                    source = AdaptiveVideoSource(media.hlsUrl, media.fallbackUrl ?: media.url, stableCacheKey),
                    policy = playbackPolicy,
                    autoplay = false,
                    muted = false,
                    showControls = true,
                    role = VideoPlaybackRole.Detail,
                    continueExistingPlayback = true,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            media.url?.let { url ->
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(url)
                        .memoryCacheKey("$stableCacheKey:image")
                        .diskCacheKey("$stableCacheKey:image")
                        .build(),
                    contentDescription = media.altText,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        media.durationSeconds?.let { secs ->
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.padding(8.dp),
            ) {
                Text(
                    "%d:%02d".format(secs / 60, secs % 60),
                    Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
    }
}

/** One vertical line per ancestor depth, at the same x the indent reserves. */
@Composable
private fun Modifier.threadRails(renderDepth: Int): Modifier {
    if (renderDepth == 0) return this
    val color = MaterialTheme.colorScheme.outlineVariant
    val density = LocalDensity.current
    return drawBehind {
        val step = with(density) { INDENT_UNIT_DP.dp.toPx() }
        val stroke = with(density) { 2.dp.toPx() }
        val levels = minOf(renderDepth, MAX_INDENT_LEVELS)
        for (level in 1..levels) {
            val x = step * level - step / 2
            drawRect(color = color, topLeft = androidx.compose.ui.geometry.Offset(x, 0f),
                size = androidx.compose.ui.geometry.Size(stroke, size.height))
        }
    }
}

/**
 * "X more replies" — a state machine on a row, never a blocking spinner.
 * Idle -> tappable · Loading -> inline spinner, taps deduped upstream ·
 * Error -> the row itself is the retry affordance.
 */
@Composable
private fun LoadMoreItem(
    row: CommentRow.LoadMore,
    state: LoadMoreState?,
    onLoadMore: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val base = modifier
        .fillMaxWidth()
        .threadRails(row.renderDepth)
        .padding(start = indentFor(row.renderDepth) + 8.dp, top = 6.dp, bottom = 6.dp)
    when (state) {
        is LoadMoreState.Loading -> Row(base, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.height(14.dp).width(14.dp))
            Text("Loading replies…", style = MaterialTheme.typography.labelLarge)
        }

        is LoadMoreState.Error -> Text(
            "Couldn't load replies · Retry",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.error,
            modifier = base
                .clickable { onLoadMore(row.key) }
                .semantics { liveRegion = LiveRegionMode.Polite },
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
    modifier: Modifier = Modifier,
) {
    Text(
        "Continue this thread →",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = row.parentId != null) { row.parentId?.let(onContinueThread) }
            .threadRails(row.renderDepth)
            .padding(start = indentFor(row.renderDepth) + 8.dp, top = 6.dp, bottom = 6.dp),
    )
}

@Composable
private fun DetailTopBar(onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(PostDetailToolbarHeight)
            .background(PostDetailChromeColor)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.Default.Close, "Close post", tint = Color.White) }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = {}) { Icon(Icons.Default.Search, "Search comments", tint = Color.White) }
        IconButton(onClick = {}) { Icon(Icons.Default.FilterList, "Sort comments", tint = Color.White) }
        IconButton(onClick = {}) { Icon(Icons.Default.MoreVert, "Post menu", tint = Color.White) }
    }
}

@Composable
private fun CommunityIdentityLine(
    subreddit: String,
    author: String,
    communityHeader: PostCommunityHeaderState?,
    onCommunityClick: (String) -> Unit,
    onJoinCommunity: () -> Unit,
) {
    val communityName = subreddit.trim().removePrefix("r/")
    val displayName = communityName.takeIf(String::isNotBlank)?.let { "r/$it" }.orEmpty()
    val displayAuthor = author.trim().takeIf(String::isNotBlank)?.let {
        if (it.startsWith("u/")) it else "u/$it"
    }.orEmpty()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CommunityAvatar(
            communityName = communityName,
            avatarUrl = communityHeader?.avatarUrl,
            onCommunityClick = onCommunityClick,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(6.dp))
                .clickable(
                    enabled = communityName.isNotBlank(),
                    onClickLabel = "Open r/$communityName",
                    role = Role.Button,
                ) { onCommunityClick(communityName) }
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                displayName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                } else {
                    Text("Join", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun CommunityAvatar(
    communityName: String,
    avatarUrl: String?,
    onCommunityClick: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(
                enabled = communityName.isNotBlank(),
                onClickLabel = "Open r/$communityName",
                role = Role.Button,
            ) { onCommunityClick(communityName) },
        contentAlignment = Alignment.Center,
    ) {
        if (!avatarUrl.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(avatarUrl)
                    .memoryCacheKey("community-avatar:$avatarUrl")
                    .diskCacheKey("community-avatar:$avatarUrl")
                    .build(),
                contentDescription = "r/$communityName community avatar",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                communityName.take(1).uppercase(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Returns the LazyColumn index of the next top-level comment. Index zero belongs to the post
 * header, so a header-visible viewport advances to the first root while a viewport anchored
 * anywhere inside a root's subtree advances past that entire subtree.
 */
internal fun nextRootCommentListIndex(
    rows: List<CommentRow>,
    firstVisibleListIndex: Int,
): Int? {
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
    // Comment expansion briefly coalesces a subtree into one keyed lazy item. Wait for the stable
    // one-header-plus-render-rows shape so render indices map exactly to LazyColumn indices.
    val rows = snapshotFlow { rowsProvider() to listState.layoutInfo.totalItemsCount }
        .first { (latestRows, itemCount) ->
            itemCount == latestRows.size + POST_HEADER_ITEM_COUNT
        }
        .first

    if (!listState.canScrollForward) return
    val targetIndex = nextRootCommentListIndex(rows, listState.firstVisibleItemIndex)
    if (targetIndex != null) {
        val visibleTarget = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == targetIndex }
        if (visibleTarget != null) {
            val distance = visibleTarget.offset - listState.layoutInfo.viewportStartOffset
            listState.animateScrollBy(
                distance.toFloat(),
                tween(NEXT_COMMENT_SCROLL_DURATION_MS, easing = FastOutSlowInEasing),
            )
        } else {
            // animateScrollToItem handles long, virtualized distances without composing every row
            // on the path and pins the target to offset zero immediately below the app bar.
            listState.animateScrollToItem(targetIndex, scrollOffset = 0)
        }
        return
    }

    // The last root can be taller than the viewport. First virtualize directly to its item, then
    // animate the precisely measured remainder. Once canScrollForward is false, later taps no-op.
    val lastIndex = listState.layoutInfo.totalItemsCount - 1
    if (lastIndex < 0) return
    listState.animateScrollToItem(lastIndex, scrollOffset = 0)
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

@Composable
private fun RootCommentBar(
    onComment: () -> Unit,
    onNextComment: () -> Unit,
    nextCommentEnabled: Boolean,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        // The bar owns the navigation/gesture inset: its surface continues to the physical edge,
        // while only the interactive field stays above the unsafe region.
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
                    IconButton(
                        onClick = onNextComment,
                        enabled = nextCommentEnabled,
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = "Next comment",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
        }
    }
}

@Composable
private fun VoteControls(score: String, vote: Int, onVote: (Int) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.ArrowUpward,
                "Upvote",
                Modifier.clickable { onVote(1) }.padding(8.dp).size(18.dp),
                tint = if (vote == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(score, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Icon(
                Icons.Outlined.ArrowDownward,
                "Downvote",
                Modifier.clickable { onVote(-1) }.padding(8.dp).size(18.dp),
                tint = if (vote == -1) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun CommentComposer(
    reply: CommentRow.Comment?,
    submitting: Boolean,
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
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
                    Text(if (reply == null) "Add comment" else "Reply", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
                            Text(it.author, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            MarkdownText(
                                markdown = it.body,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = body,
                    onValueChange = { if (it.length <= 10_000) body = it },
                    placeholder = { Text("Join the conversation") },
                    modifier = Modifier.fillMaxSize().padding(18.dp),
                    shape = RoundedCornerShape(18.dp),
                )
            }
        }
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

/** Client-side indent cap. Same default as the server's depth cap by COINCIDENCE —
 *  they are different knobs owned by different sides. */
private const val MAX_INDENT_LEVELS = 10
private const val COLLAPSED_AUTHOR_MAX_WIDTH_DP = 108
private const val ROOT_COMMENT_GAP_DP = 8
private const val COMMENT_COLLAPSE_DURATION_MS = 220
private const val COMMENT_CONTENT_FADE_MS = 90
private const val NEXT_COMMENT_SCROLL_DURATION_MS = 240
private const val POST_HEADER_ITEM_COUNT = 1

private const val TYPE_HEADER = "header"
private const val TYPE_COMMENT = "comment"
private const val TYPE_LOAD_MORE = "load_more"
private const val TYPE_CONTINUE = "continue_thread"
