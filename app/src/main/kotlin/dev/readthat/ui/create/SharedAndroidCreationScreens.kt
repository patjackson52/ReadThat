package dev.readthat.ui.create

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.readthat.client.SharedCreationOutcome
import dev.readthat.client.SharedCreationViewModel
import dev.readthat.media.acquisition.ui.rememberPlatformCameraLauncher
import dev.readthat.media.acquisition.ui.rememberPlatformMediaPickerLauncher
import dev.readthat.creation.ui.SharedCreateCommunityScreen
import dev.readthat.creation.ui.SharedCreatePostScreen
import dev.readthat.creation.ui.SharedPendingCommunityScreen
import dev.readthat.creation.ui.SharedPendingPostScreen
import dev.readthat.shared.PostKind
import java.io.File

/** Android host adapter for the shared composer. File acquisition is the only native concern. */
@Composable
fun SharedAndroidCreatePostScreen(
    viewModel: SharedCreationViewModel,
    onBack: () -> Unit,
    onQueued: (SharedCreationOutcome.PostQueued) -> Unit,
) {
    val state by viewModel.create.collectAsStateWithLifecycle()
    val imagePicker = rememberPlatformMediaPickerLauncher(
        kind = PostKind.Image,
        onPicked = viewModel::addPickedMedia,
        onError = viewModel::reportError,
    )
    val videoPicker = rememberPlatformMediaPickerLauncher(
        kind = PostKind.Video,
        onPicked = viewModel::addPickedMedia,
        onError = viewModel::reportError,
    )
    val camera = rememberPlatformCameraLauncher(
        onPicked = viewModel::addPickedMedia,
        onError = viewModel::reportError,
    )

    SharedCreatePostScreen(
        state = state,
        onBack = onBack,
        onSubmit = { viewModel.submit { outcome -> (outcome as? SharedCreationOutcome.PostQueued)?.let(onQueued) } },
        onCommunityChanged = viewModel::setPostCommunity,
        onTitleChanged = viewModel::setPostTitle,
        onBodyChanged = viewModel::setPostBody,
        onLinkChanged = viewModel::setPostLink,
        onKindChanged = viewModel::setPostKind,
        onFlairChanged = viewModel::setPostFlair,
        onRemoveMedia = viewModel::removePickedMedia,
        onRefreshCommunities = viewModel::refreshCommunities,
        onPickImages = imagePicker,
        onPickVideo = videoPicker,
        onTakePhoto = camera,
        stagedMediaRenderer = { media, kind, modifier ->
            if (kind == PostKind.Image) {
                AsyncImage(
                    model = File(media.localPath),
                    contentDescription = media.name,
                    modifier = modifier,
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PlayArrow, "Selected video", Modifier.size(54.dp), tint = Color.White)
                }
            }
        },
    )
}

@Composable
fun SharedAndroidCreateCommunityScreen(
    viewModel: SharedCreationViewModel,
    onBack: () -> Unit,
    onQueued: (SharedCreationOutcome.CommunityQueued) -> Unit,
) {
    val state by viewModel.create.collectAsStateWithLifecycle()
    SharedCreateCommunityScreen(
        state = state,
        onBack = onBack,
        onSubmit = {
            viewModel.submit { outcome ->
                (outcome as? SharedCreationOutcome.CommunityQueued)?.let(onQueued)
            }
        },
        onNameChanged = viewModel::setCommunityName,
        onDisplayNameChanged = viewModel::setCommunityDisplayName,
        onDescriptionChanged = viewModel::setCommunityDescription,
        onAccessChanged = viewModel::setCommunityAccess,
    )
}

@Composable
fun SharedAndroidPendingPostScreen(
    viewModel: SharedCreationViewModel,
    onBack: () -> Unit,
    onCommitted: (String) -> Unit,
) {
    val state by viewModel.status.collectAsStateWithLifecycle()
    SharedPendingPostScreen(
        state = state,
        onBack = onBack,
        onRetry = { state.post?.mutationId?.let(viewModel::retryPost) },
        onCommitted = onCommitted,
    )
}

@Composable
fun SharedAndroidPendingCommunityScreen(
    viewModel: SharedCreationViewModel,
    onBack: () -> Unit,
    onCreatePost: (String) -> Unit,
    onOpenCommunity: (String) -> Unit,
) {
    val state by viewModel.status.collectAsStateWithLifecycle()
    SharedPendingCommunityScreen(
        state = state,
        onBack = onBack,
        onRetry = { state.community?.mutationId?.let(viewModel::retryCommunity) },
        onCreatePost = onCreatePost,
        onOpenCommunity = onOpenCommunity,
    )
}
