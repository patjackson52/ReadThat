package dev.readthat.profile.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import dev.readthat.shared.UserProfile
import dev.readthat.shared.LocalPostMedia
import dev.readthat.client.ProfileState
import java.io.File

@Composable
fun ProfileScreen(
    user: UserProfile,
    onEdit: () -> Unit,
    onSettings: () -> Unit,
) {
    SharedPlatformOwnProfileScreen(
        user = user,
        onEdit = onEdit,
        onSettings = onSettings,
    )
}

@Composable
fun PublicProfileScreen(
    state: ProfileState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    SharedPlatformPublicProfileScreen(
        user = state.publicProfile,
        loading = state.loading,
        error = state.error,
        onBack = onBack,
        onRetry = onRetry,
    )
}

@Composable
fun EditProfileScreen(
    user: UserProfile,
    state: ProfileState,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onDisplayNameChanged: (String) -> Unit,
    onBioChanged: (String) -> Unit,
    onAvatarSelected: (List<LocalPostMedia>) -> Unit,
    onRemoveAvatar: () -> Unit,
    onError: (String) -> Unit,
) {
    SharedPlatformEditProfileScreen(
        user = user,
        state = ProfileEditorUiState(
            displayName = state.displayName,
            bio = state.bio,
            saving = state.saving,
            hasAvatar = state.avatar != null || (!state.removeAvatar && user.avatarUrl != null),
            error = state.error,
        ),
        onBack = onBack,
        onSave = onSave,
        onDisplayNameChanged = onDisplayNameChanged,
        onBioChanged = onBioChanged,
        onRemoveAvatar = onRemoveAvatar,
        avatarUrl = user.avatarUrl.takeUnless { state.removeAvatar },
        localPreviewRenderer = state.avatar?.let { avatar ->
            { modifier ->
                AsyncImage(
                    model = File(avatar.localPath),
                    contentDescription = "Selected profile image",
                    contentScale = ContentScale.Crop,
                    modifier = modifier,
                )
            }
        },
        avatarPicker = { enabled, hasAvatar ->
            SharedAvatarPickerButton(
                enabled = enabled,
                hasAvatar = hasAvatar,
                onPicked = onAvatarSelected,
                onError = onError,
            )
        },
    )
}
