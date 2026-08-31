package dev.readthat.search.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.paging.compose.LazyPagingItems
import dev.readthat.client.SharedSearchUiState
import dev.readthat.image.ui.PlatformImage
import dev.readthat.image.ui.PlatformImageByteLoader
import dev.readthat.image.ui.PlatformImageKind
import dev.readthat.image.ui.PlatformImageRequest
import dev.readthat.search.domain.SearchItem
import dev.readthat.search.domain.SearchSort
import dev.readthat.search.domain.SearchTime
import dev.readthat.search.domain.SearchType

/** Search route with the common HTTPS/stable-key image adapter already installed. */
@Composable
fun SharedPlatformSearchRoute(
    state: SharedSearchUiState,
    pagedResults: LazyPagingItems<SearchItem>,
    onQueryChanged: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onClearQuery: () -> Unit,
    onBack: () -> Unit,
    onSelectType: (SearchType) -> Unit,
    onSelectSort: (SearchSort) -> Unit,
    onSelectTime: (SearchTime) -> Unit,
    onToggleSafe: () -> Unit,
    onDeleteRecent: (String) -> Unit,
    onClearRecent: () -> Unit,
    onRetryAll: () -> Unit,
    onPost: (String) -> Unit,
    onComment: (postId: String, commentId: String) -> Unit,
    onCommunity: (String) -> Unit,
    onProfile: (String) -> Unit,
    imageByteLoader: PlatformImageByteLoader? = null,
    modifier: Modifier = Modifier,
) {
    SharedSearchRoute(
        state = state,
        pagedResults = pagedResults,
        onQueryChanged = onQueryChanged,
        onSubmit = onSubmit,
        onClearQuery = onClearQuery,
        onBack = onBack,
        onSelectType = onSelectType,
        onSelectSort = onSelectSort,
        onSelectTime = onSelectTime,
        onToggleSafe = onToggleSafe,
        onDeleteRecent = onDeleteRecent,
        onClearRecent = onClearRecent,
        onRetryAll = onRetryAll,
        onPost = onPost,
        onComment = onComment,
        onCommunity = onCommunity,
        onProfile = onProfile,
        imageRenderer = { url, cacheKey, videoPreview, description, contentScale, imageModifier ->
            PlatformImage(
                request = searchImageRequest(url, cacheKey, videoPreview),
                byteLoader = imageByteLoader,
                contentDescription = description,
                contentScale = contentScale,
                modifier = imageModifier,
            )
        },
        modifier = modifier,
    )
}

internal fun searchImageRequest(
    url: String,
    cacheKey: String,
    videoPreview: Boolean,
) = PlatformImageRequest(
    url = url,
    cacheKey = cacheKey,
    kind = if (videoPreview) PlatformImageKind.VideoPreview else PlatformImageKind.Still,
)
