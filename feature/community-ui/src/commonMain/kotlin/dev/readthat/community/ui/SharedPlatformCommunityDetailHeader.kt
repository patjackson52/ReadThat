package dev.readthat.community.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import dev.readthat.client.SharedCommunityDetailState
import dev.readthat.image.ui.PlatformImage
import dev.readthat.image.ui.PlatformImageByteLoader
import dev.readthat.image.ui.PlatformImageKind
import dev.readthat.image.ui.PlatformImageRequest

/** Community-detail chrome with the common HTTPS/stable-key avatar adapter installed. */
@Composable
fun SharedPlatformCommunityDetailHeader(
    state: SharedCommunityDetailState,
    communityName: String,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onOptions: () -> Unit = {},
    onToggleMembership: () -> Unit,
    onRetry: () -> Unit,
    onCreatePost: () -> Unit,
    imageByteLoader: PlatformImageByteLoader? = null,
    modifier: Modifier = Modifier,
) {
    SharedCommunityDetailHeader(
        state = state,
        communityName = communityName,
        onBack = onBack,
        onSearch = onSearch,
        onOptions = onOptions,
        onToggleMembership = onToggleMembership,
        onRetry = onRetry,
        onCreatePost = onCreatePost,
        avatarRenderer = { url, description, imageModifier ->
            PlatformImage(
                request = communityAvatarImageRequest(state, communityName, url),
                byteLoader = imageByteLoader,
                contentDescription = description,
                contentScale = ContentScale.Crop,
                modifier = imageModifier,
            )
        },
        modifier = modifier,
    )
}

internal fun communityAvatarImageRequest(
    state: SharedCommunityDetailState,
    communityName: String,
    avatarUrl: String,
) = PlatformImageRequest(
    url = avatarUrl,
    cacheKey = buildString {
        append("community-avatar:")
        append(state.detail?.id ?: communityName.trim().removePrefix("r/").lowercase())
        append(':')
        append(state.detail?.updatedAt ?: 0L)
        append(':')
        append(avatarUrl.substringBefore('?').hashCode())
    },
    kind = PlatformImageKind.Avatar,
)
