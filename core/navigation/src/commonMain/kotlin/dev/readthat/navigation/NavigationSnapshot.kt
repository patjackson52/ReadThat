package dev.readthat.navigation

import dev.readthat.domain.AdLaunchContext
import dev.readthat.domain.AdMediaKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Small platform-neutral state needed to recreate navigation after process termination. */
data class NavigationSnapshot(
    val current: AppDestination,
    /** Ordered oldest-to-newest, matching [DestinationHistory.snapshot]. */
    val history: List<AppDestination> = emptyList(),
)

/**
 * Versioned opaque codec shared by Android SavedStateHandle and iOS SceneStorage.
 *
 * Hosts never interpret routes. Invalid, oversized, or future snapshots fail closed to the
 * application root, while unknown history entries are skipped so one obsolete route does not
 * discard an otherwise restorable current destination.
 */
object NavigationSnapshotCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    fun encode(snapshot: NavigationSnapshot): String {
        val bounded = StoredNavigationState(
            current = snapshot.current.toStored(),
            history = snapshot.history.takeLast(MAX_HISTORY_DEPTH).map(AppDestination::toStored),
        )
        val encoded = runCatching { json.encodeToString(bounded) }.getOrNull()
        if (encoded.isRestorable()) return requireNotNull(encoded)

        val currentOnly = runCatching {
            json.encodeToString(StoredNavigationState(current = snapshot.current.toStored()))
        }.getOrNull()
        if (currentOnly.isRestorable()) return requireNotNull(currentOnly)
        return feedState
    }

    fun decode(encoded: String?): NavigationSnapshot? {
        if (encoded.isNullOrBlank() || encoded.length > MAX_ENCODED_CHARS) return null
        return runCatching {
            val stored = json.decodeFromString<StoredNavigationState>(encoded)
            if (stored.version != VERSION) return null
            val current = stored.current.toDestination() ?: return null
            NavigationSnapshot(
                current = current,
                history = stored.history.takeLast(MAX_HISTORY_DEPTH).mapNotNull { it.toDestination() },
            )
        }.getOrNull()
    }

    private val feedState: String by lazy {
        json.encodeToString(StoredNavigationState(current = AppDestination.Feed.toStored()))
    }

    private fun String?.isRestorable(): Boolean =
        this != null && length <= MAX_ENCODED_CHARS && decode(this) != null

    private const val VERSION = 1
    const val MAX_HISTORY_DEPTH = 32
    const val MAX_ENCODED_CHARS = 64 * 1024
}

@Serializable
private data class StoredNavigationState(
    val version: Int = 1,
    val current: StoredDestination,
    val history: List<StoredDestination> = emptyList(),
)

@Serializable
private data class StoredDestination(
    val type: String,
    val value: String? = null,
    val secondary: String? = null,
    val tertiary: String? = null,
    val ad: StoredAd? = null,
)

@Serializable
private data class StoredAd(
    val adId: String,
    val creativeId: String,
    val kind: String,
    val placeholderColor: Long,
    val aspectRatio: Float,
    val altText: String,
    val imageUrl: String? = null,
    val hlsUrl: String? = null,
    val posterUrl: String? = null,
    val fallbackUrl: String? = null,
    val cacheKey: String,
    val destinationUrl: String,
    val displayDomain: String,
    val ctaLabel: String,
    val selectedIndex: Int = 0,
    val restartAtBeginning: Boolean = false,
)

private fun AppDestination.toStored(): StoredDestination = when (this) {
    AppDestination.Feed -> StoredDestination("feed")
    AppDestination.Activity -> StoredDestination("activity")
    AppDestination.Search -> StoredDestination("search")
    AppDestination.Communities -> StoredDestination("communities")
    is AppDestination.CreatePost -> StoredDestination("create_post", subreddit)
    AppDestination.CreateCommunity -> StoredDestination("create_community")
    AppDestination.Profile -> StoredDestination("profile")
    AppDestination.Settings -> StoredDestination("settings")
    AppDestination.EditProfile -> StoredDestination("edit_profile")
    is AppDestination.PostDetail -> StoredDestination(
        "post_detail",
        postId,
        focusCommentId,
        rootCommentId,
    )
    is AppDestination.Community -> StoredDestination("community", name)
    is AppDestination.Media -> StoredDestination("media", postId, subreddit, snapshotId)
    is AppDestination.AdDetail -> StoredDestination(
        type = "ad_detail",
        ad = ad.let {
            StoredAd(
                adId = it.adId,
                creativeId = it.creativeId,
                kind = it.kind.name,
                placeholderColor = it.placeholderColor,
                aspectRatio = it.aspectRatio,
                altText = it.altText,
                imageUrl = it.imageUrl,
                hlsUrl = it.hlsUrl,
                posterUrl = it.posterUrl,
                fallbackUrl = it.fallbackUrl,
                cacheKey = it.cacheKey,
                destinationUrl = it.destinationUrl,
                displayDomain = it.displayDomain,
                ctaLabel = it.ctaLabel,
                selectedIndex = it.selectedIndex,
                restartAtBeginning = it.restartAtBeginning,
            )
        },
    )
    is AppDestination.PublicProfile -> StoredDestination("public_profile", username)
    is AppDestination.PendingPost -> StoredDestination("pending_post", mutationId)
    is AppDestination.PendingCommunity -> StoredDestination("pending_community", mutationId)
}

private fun StoredDestination.toDestination(): AppDestination? = runCatching {
    when (type) {
        "feed" -> AppDestination.Feed
        "activity" -> AppDestination.Activity
        "search" -> AppDestination.Search
        "communities" -> AppDestination.Communities
        "create_post" -> AppDestination.CreatePost(value.boundedOrEmpty(MAX_NAME_CHARS))
        "create_community" -> AppDestination.CreateCommunity
        "profile" -> AppDestination.Profile
        "settings" -> AppDestination.Settings
        "edit_profile" -> AppDestination.EditProfile
        "post_detail" -> AppDestination.PostDetail(
            postId = value.required(MAX_ID_CHARS),
            focusCommentId = secondary.optional(MAX_ID_CHARS),
            rootCommentId = tertiary.optional(MAX_ID_CHARS),
        )
        "community" -> AppDestination.Community(value.required(MAX_NAME_CHARS))
        "media" -> AppDestination.Media(
            postId = value.required(MAX_ID_CHARS),
            subreddit = secondary.optional(MAX_NAME_CHARS),
            snapshotId = tertiary.optional(MAX_ID_CHARS),
        )
        "ad_detail" -> AppDestination.AdDetail(ad?.toDomain() ?: error("Missing ad"))
        "public_profile" -> AppDestination.PublicProfile(value.required(MAX_NAME_CHARS))
        "pending_post" -> AppDestination.PendingPost(value.required(MAX_ID_CHARS))
        "pending_community" -> AppDestination.PendingCommunity(value.required(MAX_ID_CHARS))
        else -> return null
    }
}.getOrNull()

private fun StoredAd.toDomain(): AdLaunchContext {
    require(aspectRatio.isFinite() && aspectRatio > 0f)
    require(selectedIndex in 0..MAX_AD_PAGE)
    return AdLaunchContext(
        adId = adId.required(MAX_ID_CHARS),
        creativeId = creativeId.required(MAX_ID_CHARS),
        kind = AdMediaKind.entries.firstOrNull { it.name == kind } ?: error("Unknown ad kind"),
        placeholderColor = placeholderColor,
        aspectRatio = aspectRatio,
        altText = altText.boundedOrEmpty(MAX_TEXT_CHARS),
        imageUrl = imageUrl.optional(MAX_URL_CHARS),
        hlsUrl = hlsUrl.optional(MAX_URL_CHARS),
        posterUrl = posterUrl.optional(MAX_URL_CHARS),
        fallbackUrl = fallbackUrl.optional(MAX_URL_CHARS),
        cacheKey = cacheKey.required(MAX_ID_CHARS),
        destinationUrl = destinationUrl.required(MAX_URL_CHARS),
        displayDomain = displayDomain.required(MAX_NAME_CHARS),
        ctaLabel = ctaLabel.boundedOrEmpty(MAX_TEXT_CHARS),
        selectedIndex = selectedIndex,
        restartAtBeginning = restartAtBeginning,
    )
}

private fun String?.required(max: Int): String = requireNotNull(this)
    .also { require(it.isNotBlank() && it.length <= max) }

private fun String?.optional(max: Int): String? = this
    ?.also { require(it.length <= max) }
    ?.takeIf(String::isNotBlank)

private fun String?.boundedOrEmpty(max: Int): String = orEmpty().also { require(it.length <= max) }

private const val MAX_ID_CHARS = 512
private const val MAX_NAME_CHARS = 256
private const val MAX_TEXT_CHARS = 2_048
private const val MAX_URL_CHARS = 16_384
private const val MAX_AD_PAGE = 100
