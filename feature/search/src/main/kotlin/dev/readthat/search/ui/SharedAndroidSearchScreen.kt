package dev.readthat.search.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems

/** Thin Android host: shared UI/state, process-wide Coil decoder and app navigation callbacks. */
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onBack: () -> Unit,
    onPost: (String) -> Unit,
    onComment: (postId: String, commentId: String) -> Unit,
    onCommunity: (String) -> Unit,
    onProfile: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val paged = viewModel.pagedResults.collectAsLazyPagingItems()
    SharedPlatformSearchRoute(
        state = state,
        pagedResults = paged,
        onQueryChanged = viewModel::onQueryChanged,
        onSubmit = viewModel::submit,
        onClearQuery = viewModel::clearQuery,
        onBack = onBack,
        onSelectType = viewModel::selectType,
        onSelectSort = viewModel::selectSort,
        onSelectTime = viewModel::selectTime,
        onToggleSafe = viewModel::toggleSafe,
        onDeleteRecent = viewModel::deleteRecent,
        onClearRecent = viewModel::clearRecent,
        onRetryAll = viewModel::retryAll,
        onPost = onPost,
        onComment = onComment,
        onCommunity = onCommunity,
        onProfile = onProfile,
    )
}
