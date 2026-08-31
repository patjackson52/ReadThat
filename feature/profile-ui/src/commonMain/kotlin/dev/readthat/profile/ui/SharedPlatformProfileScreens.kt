package dev.readthat.profile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.readthat.image.ui.PlatformImage
import dev.readthat.image.ui.PlatformImageByteLoader
import dev.readthat.image.ui.PlatformImageKind
import dev.readthat.image.ui.PlatformImageRequest
import dev.readthat.shared.UserProfile

typealias ProfileLocalPreviewRenderer = @Composable (modifier: Modifier) -> Unit

/** Own-profile route with the common HTTPS/stable-key avatar adapter installed. */
@Composable
fun SharedPlatformOwnProfileScreen(
    user: UserProfile,
    onEdit: () -> Unit,
    onSettings: () -> Unit,
    imageByteLoader: PlatformImageByteLoader? = null,
    modifier: Modifier = Modifier,
) {
    SharedOwnProfileScreen(
        user = user,
        onEdit = onEdit,
        onSettings = onSettings,
        avatarRenderer = { profile, size, avatarModifier ->
            SharedPlatformProfileAvatar(
                user = profile,
                sizeDp = size,
                imageByteLoader = imageByteLoader,
                modifier = avatarModifier,
            )
        },
        modifier = modifier,
    )
}

/** Public-profile route with the common HTTPS/stable-key avatar adapter installed. */
@Composable
fun SharedPlatformPublicProfileScreen(
    user: UserProfile?,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onRetry: (() -> Unit)? = null,
    imageByteLoader: PlatformImageByteLoader? = null,
    modifier: Modifier = Modifier,
) {
    SharedPublicProfileScreen(
        user = user,
        loading = loading,
        error = error,
        onBack = onBack,
        onRetry = onRetry,
        avatarRenderer = { profile, size, avatarModifier ->
            SharedPlatformProfileAvatar(
                user = profile,
                sizeDp = size,
                imageByteLoader = imageByteLoader,
                modifier = avatarModifier,
            )
        },
        modifier = modifier,
    )
}

/**
 * Profile editor with common remote-avatar behavior. Only picking and decoding a newly staged local
 * file remain platform-owned; staged previews intentionally never enter the network cache.
 */
@Composable
fun SharedPlatformEditProfileScreen(
    user: UserProfile,
    state: ProfileEditorUiState,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onDisplayNameChanged: (String) -> Unit,
    onBioChanged: (String) -> Unit,
    onRemoveAvatar: () -> Unit,
    avatarPicker: ProfileAvatarPicker,
    avatarUrl: String? = user.avatarUrl,
    localPreviewRenderer: ProfileLocalPreviewRenderer? = null,
    imageByteLoader: PlatformImageByteLoader? = null,
    modifier: Modifier = Modifier,
) {
    SharedEditProfileScreen(
        user = user,
        state = state,
        onBack = onBack,
        onSave = onSave,
        onDisplayNameChanged = onDisplayNameChanged,
        onBioChanged = onBioChanged,
        onRemoveAvatar = onRemoveAvatar,
        avatarPicker = avatarPicker,
        avatarRenderer = { profile, size, avatarModifier ->
            SharedPlatformProfileAvatar(
                user = profile,
                sizeDp = size,
                avatarUrl = avatarUrl,
                localPreviewRenderer = localPreviewRenderer,
                imageByteLoader = imageByteLoader,
                modifier = avatarModifier,
            )
        },
        modifier = modifier,
    )
}

@Composable
fun SharedPlatformProfileAvatar(
    user: UserProfile,
    sizeDp: Int,
    avatarUrl: String? = user.avatarUrl,
    localPreviewRenderer: ProfileLocalPreviewRenderer? = null,
    imageByteLoader: PlatformImageByteLoader? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.size(sizeDp.dp).clip(CircleShape).background(ProfileAvatarBackground),
        contentAlignment = Alignment.Center,
    ) {
        when {
            localPreviewRenderer != null -> localPreviewRenderer(Modifier.fillMaxSize())
            !avatarUrl.isNullOrBlank() -> PlatformImage(
                request = profileAvatarImageRequest(user, avatarUrl),
                byteLoader = imageByteLoader,
                contentDescription = "${user.displayName}'s profile image",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            else -> Text(
                profileInitial(user.displayName),
                color = Color(0xFF0B1416),
                fontWeight = FontWeight.Black,
                fontSize = (sizeDp / 2.5f).sp,
            )
        }
    }
}

internal fun profileAvatarImageRequest(
    user: UserProfile,
    avatarUrl: String,
) = PlatformImageRequest(
    url = avatarUrl,
    cacheKey = "avatar:${user.id}:${user.updatedAt}:${avatarUrl.substringBefore('?').hashCode()}",
    kind = PlatformImageKind.Avatar,
)

internal fun profileInitial(displayName: String): String =
    displayName.trim().firstOrNull()?.uppercase() ?: "U"

private val ProfileAvatarBackground = Color(0xFF86A8F7)
