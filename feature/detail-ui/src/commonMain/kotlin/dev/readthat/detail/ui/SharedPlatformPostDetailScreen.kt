package dev.readthat.detail.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import dev.readthat.comments.domain.CommentSort
import dev.readthat.image.ui.PlatformImage
import dev.readthat.image.ui.PlatformImageByteLoader
import dev.readthat.image.ui.PlatformImageKind
import dev.readthat.image.ui.PlatformImageRequest

/** Post-detail/comments route with the common HTTPS avatar/image adapter installed. */
@Composable
fun SharedPlatformPostDetailScreen(
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
    mediaRenderer: DetailMediaRenderer,
    imageByteLoader: PlatformImageByteLoader? = null,
    onSharePost: (() -> Unit)? = null,
    onResharePost: ((communityName: String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    communityHeader: DetailCommunityHeader? = null,
    icons: DetailIcons = DetailIcons(),
    detailContentModifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    presentation: DetailPresentation = DetailPresentation.FullScreen,
) {
    SharedPostDetailScreen(
        state = state,
        onContinueThread = onContinueThread,
        onCommunityClick = onCommunityClick,
        onJoinCommunity = onJoinCommunity,
        onBack = onBack,
        onToggleComment = onToggleComment,
        onLoadMore = onLoadMore,
        onViewport = onViewport,
        onVoteComment = onVoteComment,
        onVotePost = onVotePost,
        onCreateComment = onCreateComment,
        onCommentSortChanged = onCommentSortChanged,
        onClearError = onClearError,
        imageRenderer = { url, cacheKey, description, imageModifier ->
            PlatformImage(
                request = detailAvatarImageRequest(url, cacheKey),
                byteLoader = imageByteLoader,
                contentDescription = description,
                contentScale = ContentScale.Crop,
                modifier = imageModifier,
            )
        },
        mediaRenderer = mediaRenderer,
        onSharePost = onSharePost,
        onResharePost = onResharePost,
        modifier = modifier,
        communityHeader = communityHeader,
        icons = icons,
        detailContentModifier = detailContentModifier,
        containerColor = containerColor,
        presentation = presentation,
    )
}

internal fun detailAvatarImageRequest(
    url: String,
    cacheKey: String,
) = PlatformImageRequest(
    url = url,
    cacheKey = cacheKey,
    kind = PlatformImageKind.Avatar,
)
