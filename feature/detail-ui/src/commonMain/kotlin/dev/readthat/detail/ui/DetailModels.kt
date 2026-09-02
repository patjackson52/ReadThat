package dev.readthat.detail.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Share
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import dev.readthat.client.CommentLoadState
import dev.readthat.client.DetailState
import dev.readthat.client.SharedDetailUiState
import dev.readthat.comments.domain.CommentRenderList
import dev.readthat.comments.domain.CommentSort
import dev.readthat.comments.domain.PostHeader
import dev.readthat.comments.domain.PostMedia
import dev.readthat.shared.PostTransitionPreview

enum class DetailLoadMoreState { Loading, Error }

enum class DetailPresentation { FullScreen, MediaBottomSheet }

internal val CommentSort.label: String
    get() = when (this) {
        CommentSort.Best -> "Best"
        CommentSort.Top -> "Top"
        CommentSort.Qa -> "Q&A"
        CommentSort.Controversial -> "Controversial"
        CommentSort.New -> "New"
        CommentSort.Old -> "Old"
    }

@Immutable
data class DetailCommunityHeader(
    val avatarUrl: String? = null,
    val isMember: Boolean,
    val canJoin: Boolean,
    val membershipChanging: Boolean = false,
)

@Immutable
data class DetailUiState(
    val render: CommentRenderList = CommentRenderList(emptyList(), 0, 0),
    val commentSort: CommentSort = CommentSort.Best,
    val header: PostHeader? = null,
    val transitionPreview: PostTransitionPreview? = null,
    val loadMoreStates: Map<String, DetailLoadMoreState> = emptyMap(),
    val isLoadingInitial: Boolean = true,
    val isLoadingFull: Boolean = false,
    val servedFromPrefetch: Boolean = false,
    val isSubmittingComment: Boolean = false,
    val interactionError: String? = null,
    val focusedCommentId: String? = null,
    val rootCommentId: String? = null,
    val canMutate: Boolean = true,
)

/**
 * Canonical projection from the shared offline-first detail state into the
 * platform-neutral comment UI contract. Hosts only supply native renderers and actions.
 */
fun DetailState.toDetailUiState(
    render: CommentRenderList,
    canMutate: Boolean,
    transitionPreview: PostTransitionPreview? = null,
): DetailUiState = DetailUiState(
    render = render,
    commentSort = commentSort,
    header = post,
    transitionPreview = transitionPreview,
    loadMoreStates = commentLoadStates.mapValues { (_, state) ->
        when (state) {
            CommentLoadState.Loading -> DetailLoadMoreState.Loading
            CommentLoadState.Error -> DetailLoadMoreState.Error
        }
    },
    isLoadingInitial = comments == null && refreshingComments,
    isLoadingFull = comments != null && refreshingComments,
    servedFromPrefetch = initialCacheTier == "room",
    isSubmittingComment = submittingComment,
    interactionError = error,
    focusedCommentId = focusedCommentId,
    rootCommentId = rootCommentId,
    canMutate = canMutate,
)

fun SharedDetailUiState.toDetailUiState(
    transitionPreview: PostTransitionPreview? = null,
): DetailUiState = detail.toDetailUiState(render, canMutate, transitionPreview)

fun DetailState.toDetailCommunityHeader(): DetailCommunityHeader? = community?.let {
    DetailCommunityHeader(
        avatarUrl = it.avatarUrl,
        isMember = it.isJoined,
        canJoin = it.canChangeMembership,
        membershipChanging = communityMembershipChanging,
    )
}

data class DetailIcons(
    val close: ImageVector? = Icons.Default.Close,
    val search: ImageVector? = Icons.Default.Search,
    val filter: ImageVector? = Icons.Default.FilterList,
    val more: ImageVector? = Icons.Default.MoreVert,
    val previousMatch: ImageVector? = Icons.Default.KeyboardArrowUp,
    val nextComment: ImageVector? = Icons.Default.KeyboardArrowDown,
    val reply: ImageVector? = Icons.AutoMirrored.Outlined.Reply,
    val upvote: ImageVector? = Icons.Outlined.ArrowUpward,
    val downvote: ImageVector? = Icons.Outlined.ArrowDownward,
    val comments: ImageVector? = Icons.Outlined.ChatBubbleOutline,
    val reshare: ImageVector? = Icons.Outlined.Repeat,
    val share: ImageVector? = Icons.Outlined.Share,
)

typealias DetailImageRenderer = @Composable (
    url: String,
    cacheKey: String,
    contentDescription: String?,
    modifier: Modifier,
) -> Unit

typealias DetailMediaRenderer = @Composable (
    mediaItems: List<PostMedia>,
    stableCacheKey: String,
    modifier: Modifier,
) -> Unit

val PostDetailChromeColor = Color(0xFF0B1416)
