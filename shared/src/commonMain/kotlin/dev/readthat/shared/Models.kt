package dev.readthat.shared

import kotlinx.serialization.Serializable

@Serializable
data class UserProfile(
    val id: String,
    val username: String,
    val displayName: String,
    val bio: String = "",
    val avatarUrl: String? = null,
    val karma: Int = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

sealed interface SessionState {
    data object Restoring : SessionState
    data object SignedOut : SessionState
    data class SignedIn(val user: UserProfile) : SessionState
}

enum class AuthMode { Register, Login }

data class AuthForm(
    val mode: AuthMode = AuthMode.Register,
    val username: String = "",
    val displayName: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
) {
    val usernameError: String? get() = Validators.username(username)
    val passwordError: String? get() = Validators.password(password)
    val displayNameError: String? get() = if (mode == AuthMode.Register) Validators.displayName(displayName) else null
    val canSubmit: Boolean get() = !submitting && usernameError == null && passwordError == null && displayNameError == null
}

sealed interface AuthAction {
    data class SetMode(val mode: AuthMode) : AuthAction
    data class SetUsername(val value: String) : AuthAction
    data class SetDisplayName(val value: String) : AuthAction
    data class SetPassword(val value: String) : AuthAction
    data object TogglePasswordVisibility : AuthAction
    data object Submit : AuthAction
    data class Failed(val message: String) : AuthAction
}

fun reduceAuth(state: AuthForm, action: AuthAction): AuthForm = when (action) {
    is AuthAction.SetMode -> state.copy(mode = action.mode, error = null, submitting = false)
    is AuthAction.SetUsername -> state.copy(username = action.value, error = null)
    is AuthAction.SetDisplayName -> state.copy(displayName = action.value, error = null)
    is AuthAction.SetPassword -> state.copy(password = action.value, error = null)
    AuthAction.TogglePasswordVisibility -> state.copy(passwordVisible = !state.passwordVisible)
    AuthAction.Submit -> if (state.canSubmit) state.copy(submitting = true, error = null) else state
    is AuthAction.Failed -> state.copy(submitting = false, error = action.message)
}

object Validators {
    private val usernamePattern = Regex("^[A-Za-z0-9_]{3,24}$")

    fun username(value: String): String? = when {
        value.isBlank() -> "Enter a username"
        !usernamePattern.matches(value) -> "Use 3–24 letters, numbers, or underscores"
        else -> null
    }

    fun password(value: String): String? = when {
        value.isBlank() -> "Enter a password"
        value.length < 10 -> "Use at least 10 characters"
        value.length > 128 -> "Use no more than 128 characters"
        else -> null
    }

    fun displayName(value: String): String? = when {
        value.isBlank() -> "Enter a display name"
        value.length > 50 -> "Use no more than 50 characters"
        else -> null
    }

    fun bio(value: String): String? = if (value.length > 500) "Use no more than 500 characters" else null
    fun comment(value: String): String? = when {
        value.isBlank() -> "Write a comment"
        value.length > 10_000 -> "Use no more than 10,000 characters"
        else -> null
    }
}

enum class PostKind { Text, Image, Video, Link }

@Serializable
data class PostFlair(
    val id: String,
    val text: String,
    val backgroundColor: String,
    val textColor: String,
)

data class LocalPostMedia(
    val name: String,
    val mimeType: String,
    val localPath: String,
    val byteSize: Long,
    val width: Int? = null,
    val height: Int? = null,
    val durationSeconds: Int? = null,
)

data class CreateCommunityDraft(
    val name: String = "",
    val displayName: String = "",
    val description: String = "",
    val accessType: String = "public",
    val submitting: Boolean = false,
    val error: String? = null,
) {
    val normalizedName: String get() = name.trim().removePrefix("r/").lowercase()
    val canSubmit: Boolean get() = !submitting &&
        normalizedName.matches(Regex("^[a-z0-9_]{3,21}$")) &&
        displayName.trim().isNotEmpty() && displayName.length <= 100 &&
        description.length <= 1_000 &&
        accessType in setOf("public", "restricted", "private")
}

data class CreatePostDraft(
    val subreddit: String = "",
    val kind: PostKind = PostKind.Text,
    val title: String = "",
    val body: String = "",
    val linkUrl: String = "",
    val localMediaName: String? = null,
    val localMediaMimeType: String? = null,
    val localMediaPath: String? = null,
    val localMediaByteSize: Long? = null,
    val mediaWidth: Int? = null,
    val mediaHeight: Int? = null,
    val mediaDurationSeconds: Int? = null,
    /** Ordered gallery. Legacy scalar fields mirror the first item during rollout. */
    val localMediaItems: List<LocalPostMedia> = emptyList(),
    val flair: PostFlair? = null,
    val preparingMedia: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
) {
    val normalizedSubreddit: String get() = subreddit.trim().removePrefix("r/")
    val canSubmit: Boolean get() = !submitting && normalizedSubreddit.matches(Regex("^[A-Za-z0-9_]{3,21}$")) &&
        title.trim().isNotEmpty() && title.length <= 300 && when (kind) {
            PostKind.Text -> body.length <= 40_000
            PostKind.Link -> linkUrl.startsWith("https://") || linkUrl.startsWith("http://")
            PostKind.Image, PostKind.Video ->
                (localMediaItems.isNotEmpty() || (localMediaName != null && localMediaPath != null)) && !preparingMedia
        }
}

@Serializable
data class Subreddit(
    val id: String,
    val name: String,
    val displayName: String,
    val description: String = "",
    val accessType: String = "public",
    val viewerRole: String? = null,
    val subscriberCount: Int = 0,
)

@Serializable
data class CreatedPost(
    val id: String,
    val subreddit: String,
    val author: String,
    val title: String,
    val body: String? = null,
    val linkUrl: String? = null,
    val score: Int = 0,
    val commentCount: Int = 0,
    val viewerVote: Int = 0,
    val flair: PostFlair? = null,
)

/** Client-known post detail contract shared across feed and comments features. */
data class PostHeader(
    val postId: String,
    val title: String,
    val author: String,
    val subreddit: String,
    val score: Int,
    val commentCount: Int,
    val body: String? = null,
    val media: PostMedia? = null,
    val viewerVote: Int = 0,
    val kind: String = "text",
    val linkUrl: String? = null,
    /** Ordered gallery; empty means use [media] for an older cached/network payload. */
    val mediaItems: List<PostMedia> = emptyList(),
    val flair: PostFlair? = null,
)

data class PostMedia(
    val placeholderColor: Long,
    val aspectRatio: Float,
    val isVideo: Boolean,
    val durationSeconds: Int? = null,
    val url: String? = null,
    val altText: String = "",
    val hlsUrl: String? = null,
    val dashUrl: String? = null,
    val posterUrl: String? = null,
    val fallbackUrl: String? = null,
    val deliveryStatus: String = "not_applicable",
    val processingProgress: Int = 0,
    /** Stable across signed URL rotation and shared by feed/detail playback. */
    val cacheKey: String? = null,
    val mediaId: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** Full-resolution image delivery URL; may rotate while [cacheKey] remains stable. */
    val zoomUrl: String? = null,
)

/**
 * Ephemeral navigation handoff. It gives the detail destination final-shaped
 * hero pixels before its API/Room header arrives; it is never persisted or sent.
 */
data class PostTransitionPreview(
    val postId: String,
    val title: String,
    val body: String? = null,
    val media: PostMedia? = null,
    val linkUrl: String? = null,
    val author: String = "",
    val subreddit: String = "",
    val score: Int = 0,
    val commentCount: Int = 0,
    val viewerVote: Int = 0,
    val postedAgo: String = "",
    val createdAt: Long = 0,
    val communityAvatarUrl: String? = null,
    val mediaItems: List<PostMedia> = emptyList(),
    val flair: PostFlair? = null,
)

data class VoteSnapshot(val score: Int, val viewerVote: Int) {
    fun optimistic(nextVote: Int): VoteSnapshot {
        require(nextVote in -1..1)
        return copy(score = score - viewerVote + nextVote, viewerVote = nextVote)
    }
}

data class AppSettings(
    val darkTheme: Boolean = false,
    val compactPosts: Boolean = false,
    val autoplayVideo: Boolean = true,
    val autoplayOnMetered: Boolean = false,
    val reduceDataOnMetered: Boolean = true,
    val reduceAnimations: Boolean = false,
    val blurMatureMedia: Boolean = true,
)
