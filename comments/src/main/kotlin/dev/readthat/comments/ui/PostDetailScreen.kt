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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.readthat.client.SharedDetailViewModel
import dev.readthat.comments.domain.CommentRenderList
import dev.readthat.comments.domain.CommentRow
import dev.readthat.comments.domain.PostHeader
import dev.readthat.shared.AppSettings
import dev.readthat.shared.PostTransitionPreview
import dev.readthat.core.ui.markdown.MarkdownText
import dev.readthat.core.ui.markdown.markdownPlainText
import dev.readthat.detail.ui.DetailCommunityHeader
import dev.readthat.detail.ui.DetailLoadMoreState
import dev.readthat.detail.ui.DetailPresentation
import dev.readthat.detail.ui.DetailUiState
import dev.readthat.detail.ui.SharedPostDetailMediaGallery
import dev.readthat.detail.ui.SharedPlatformPostDetailScreen
import dev.readthat.detail.ui.toDetailCommunityHeader
import dev.readthat.detail.ui.toDetailUiState
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

/** Compatibility names retained while the mature Android shell remains the reference app. */
typealias PostDetailPresentation = DetailPresentation
typealias PostCommunityHeaderState = DetailCommunityHeader

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
    onSharePost: (() -> Unit)? = null,
    onResharePost: ((communityName: String) -> Unit)? = null,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
    onCommentsInteractive: (fromPrefetch: Boolean, phase: String, successful: Boolean) -> Unit = { _, _, _ -> },
    settings: AppSettings = AppSettings(),
    transitionPreview: PostTransitionPreview? = null,
    detailContentModifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    presentation: PostDetailPresentation = PostDetailPresentation.FullScreen,
    productSurface: ProductSurface = ProductSurface.DETAIL,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PlatformPostDetailScreen(
        state = DetailUiState(
            render = state.render,
            header = state.header,
            transitionPreview = transitionPreview,
            loadMoreStates = state.loadMoreStates.mapValues { (_, value) ->
                when (value) {
                    LoadMoreState.Loading -> DetailLoadMoreState.Loading
                    LoadMoreState.Error -> DetailLoadMoreState.Error
                }
            },
            isLoadingInitial = state.isLoadingInitial,
            isLoadingFull = state.isLoadingFull,
            servedFromPrefetch = state.servedFromPrefetch,
            isSubmittingComment = state.isSubmittingComment,
            interactionError = state.interactionError,
            focusedCommentId = state.focusedCommentId,
            rootCommentId = state.rootCommentId,
            canMutate = true,
        ),
        onContinueThread = onContinueThread,
        onCommunityClick = onCommunityClick,
        communityHeader = communityHeader,
        onJoinCommunity = onJoinCommunity,
        onSharePost = onSharePost,
        onResharePost = onResharePost,
        onBack = onBack,
        modifier = modifier,
        onCommentsInteractive = onCommentsInteractive,
        settings = settings,
        detailContentModifier = detailContentModifier,
        containerColor = containerColor,
        presentation = presentation,
        productSurface = productSurface,
        onToggleComment = viewModel::toggleCollapse,
        onLoadMore = viewModel::loadMore,
        onViewport = viewModel::onViewport,
        onVoteComment = viewModel::voteComment,
        onVotePost = viewModel::votePost,
        onCreateComment = { parentId, body -> viewModel.createComment(parentId, body) },
        onClearError = viewModel::clearInteractionError,
    )
}

/** Shared Room 3/KMP state owner with the same Android-native media and image rendering edge. */
@Composable
fun PostDetailScreen(
    viewModel: SharedDetailViewModel,
    onContinueThread: (parentCommentId: String) -> Unit,
    onCommunityClick: (communityName: String) -> Unit,
    communityHeader: PostCommunityHeaderState? = null,
    onJoinCommunity: () -> Unit = {},
    onSharePost: (() -> Unit)? = null,
    onResharePost: ((communityName: String) -> Unit)? = null,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
    onCommentsInteractive: (fromCache: Boolean, phase: String, successful: Boolean) -> Unit = { _, _, _ -> },
    settings: AppSettings = AppSettings(),
    transitionPreview: PostTransitionPreview? = null,
    detailContentModifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    presentation: PostDetailPresentation = PostDetailPresentation.FullScreen,
    productSurface: ProductSurface = ProductSurface.DETAIL,
) {
    val shared by viewModel.uiState.collectAsStateWithLifecycle()
    val resolvedCommunityHeader = communityHeader ?: shared.detail.toDetailCommunityHeader()
    PlatformPostDetailScreen(
        state = shared.toDetailUiState(transitionPreview),
        onContinueThread = onContinueThread,
        onCommunityClick = onCommunityClick,
        communityHeader = resolvedCommunityHeader,
        onJoinCommunity = if (communityHeader == null) {
            { viewModel.setCommunityJoined(true); Unit }
        } else {
            onJoinCommunity
        },
        onSharePost = onSharePost,
        onResharePost = onResharePost,
        onBack = onBack,
        modifier = modifier,
        onCommentsInteractive = onCommentsInteractive,
        settings = settings,
        detailContentModifier = detailContentModifier,
        containerColor = containerColor,
        presentation = presentation,
        productSurface = productSurface,
        onToggleComment = viewModel::toggleCommentCollapsed,
        onLoadMore = viewModel::loadMoreComments,
        onViewport = viewModel::onCommentsViewport,
        onVoteComment = viewModel::voteComment,
        onVotePost = viewModel::votePost,
        onCreateComment = viewModel::createComment,
        onClearError = viewModel::clearError,
    )
}

@Composable
private fun PlatformPostDetailScreen(
    state: DetailUiState,
    onContinueThread: (String) -> Unit,
    onCommunityClick: (String) -> Unit,
    communityHeader: PostCommunityHeaderState?,
    onJoinCommunity: () -> Unit,
    onSharePost: (() -> Unit)?,
    onResharePost: ((String) -> Unit)?,
    onBack: () -> Unit,
    modifier: Modifier,
    onCommentsInteractive: (Boolean, String, Boolean) -> Unit,
    settings: AppSettings,
    detailContentModifier: Modifier,
    containerColor: Color,
    presentation: PostDetailPresentation,
    productSurface: ProductSurface,
    onToggleComment: (String) -> Unit,
    onLoadMore: (String) -> Unit,
    onViewport: (Int, Int) -> Unit,
    onVoteComment: (String, Int) -> Unit,
    onVotePost: (Int) -> Unit,
    onCreateComment: (String?, String) -> Unit,
    onClearError: () -> Unit,
) {
    var firstCommentReported by remember { mutableStateOf(false) }
    val interactionScope = rememberCoroutineScope()
    val analyticsPostId = state.header?.postId ?: state.transitionPreview?.postId
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
            onCommentsInteractive(state.servedFromPrefetch, phase, state.interactionError == null)
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
    SharedPlatformPostDetailScreen(
        state = state,
        onContinueThread = onContinueThread,
        onCommunityClick = { community ->
            interaction("open_community") { onCommunityClick(community) }
        },
        onJoinCommunity = { interaction("join_community", onJoinCommunity) },
        onBack = onBack,
        onToggleComment = { id -> interaction("comment_collapse") { onToggleComment(id) } },
        onLoadMore = onLoadMore,
        onViewport = onViewport,
        onVoteComment = { id, value -> interaction("vote") { onVoteComment(id, value) } },
        onVotePost = { value -> interaction("vote") { onVotePost(value) } },
        onCreateComment = { parentId, body ->
            interaction("comment_submit") { onCreateComment(parentId, body) }
        },
        onClearError = onClearError,
        mediaRenderer = { mediaItems, stableCacheKey, mediaModifier ->
            SharedPostDetailMediaGallery(
                mediaItems = mediaItems,
                stableCacheKey = stableCacheKey,
                settings = settings,
                modifier = mediaModifier,
            )
        },
        onSharePost = onSharePost,
        onResharePost = onResharePost,
        modifier = modifier,
        communityHeader = communityHeader,
        detailContentModifier = detailContentModifier,
        containerColor = containerColor,
        presentation = presentation,
    )
}

internal fun expansionDescendantKeys(rows: List<CommentRow>, parentKey: String?): Set<String> =
    dev.readthat.detail.ui.expansionDescendantKeys(rows, parentKey)

internal fun collapsedCommentCountLabel(count: Int): String? =
    dev.readthat.detail.ui.collapsedCommentCountLabel(count)

internal fun nextRootCommentListIndex(
    rows: List<CommentRow>,
    firstVisibleListIndex: Int,
): Int? = dev.readthat.detail.ui.nextRootCommentListIndex(rows, firstVisibleListIndex)
