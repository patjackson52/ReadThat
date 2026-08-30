package dev.readthat.mediafeed.domain

import dev.readthat.shared.PostHeader
import dev.readthat.shared.PostFlair
import dev.readthat.shared.PostMedia
import dev.readthat.shared.PostTransitionPreview
import kotlinx.serialization.Serializable

@Serializable
data class MediaFeedPage(
    val items: List<MediaFeedItem>,
    val nextCursor: String?,
    val snapshotAt: Long,
    val anchorIncluded: Boolean,
)

/**
 * A client-side ranked snapshot supplied by the normal feed. It is intentionally
 * not a navigation argument: large post payloads stay out of Bundles and are
 * persisted into Room before Paging starts.
 */
data class MediaFeedLaunchContext(
    val snapshotId: String,
    val sourceFeedId: String,
    val anchorPostId: String,
    val items: List<MediaFeedItem>,
    val anchorIndex: Int,
    /** Cursor already translated into the MediaFeed remote-source namespace. */
    val continuationCursor: String?,
) {
    init {
        require(items.isNotEmpty())
        require(anchorIndex in items.indices)
        require(items[anchorIndex].postId == anchorPostId)
    }
}

@Serializable
data class MediaFeedItem(
    val postId: String,
    val author: String,
    val subreddit: String,
    val title: String,
    val body: String? = null,
    val score: Int,
    val commentCount: Int,
    val viewerVote: Int = 0,
    val kind: String,
    val createdAt: Long = 0,
    val postedAgo: String = "",
    val media: MediaFeedMedia,
    val communityAvatarUrl: String? = null,
    /** Empty on old Room payloads; [allMedia] falls back to the primary item. */
    val mediaItems: List<MediaFeedMedia> = emptyList(),
    val flair: PostFlair? = null,
) {
    val allMedia: List<MediaFeedMedia> get() = mediaItems.ifEmpty { listOf(media) }

    fun toPostHeader() = PostHeader(
        postId = postId,
        title = title,
        author = author.withPrefix("u/"),
        subreddit = subreddit.withPrefix("r/"),
        score = score,
        commentCount = commentCount,
        body = body,
        media = media.toPostMedia(),
        viewerVote = viewerVote,
        kind = kind,
        mediaItems = allMedia.map(MediaFeedMedia::toPostMedia),
        flair = flair,
    )

    fun toTransitionPreview() = PostTransitionPreview(
        postId = postId,
        title = title,
        body = body,
        media = media.toPostMedia(),
        author = author,
        subreddit = subreddit,
        score = score,
        commentCount = commentCount,
        viewerVote = viewerVote,
        postedAgo = postedAgo,
        createdAt = createdAt,
        communityAvatarUrl = communityAvatarUrl,
        mediaItems = allMedia.map(MediaFeedMedia::toPostMedia),
        flair = flair,
    )

    companion object {
        fun fromPreview(preview: PostTransitionPreview): MediaFeedItem? {
            val media = preview.media ?: return null
            val mediaItems = preview.mediaItems.ifEmpty { listOf(media) }.map(MediaFeedMedia::from)
            return MediaFeedItem(
                postId = preview.postId,
                author = preview.author.removePrefix("u/"),
                subreddit = preview.subreddit.removePrefix("r/"),
                title = preview.title,
                body = preview.body,
                score = preview.score,
                commentCount = preview.commentCount,
                viewerVote = preview.viewerVote,
                kind = if (media.isVideo) "video" else "image",
                createdAt = preview.createdAt,
                postedAgo = preview.postedAgo,
                media = mediaItems.first(),
                communityAvatarUrl = preview.communityAvatarUrl,
                mediaItems = mediaItems,
                flair = preview.flair,
            )
        }
    }
}

@Serializable
data class MediaFeedMedia(
    val mediaId: String? = null,
    val placeholderColor: Long,
    val aspectRatio: Float,
    val isVideo: Boolean,
    val width: Int? = null,
    val height: Int? = null,
    val durationSeconds: Int? = null,
    val url: String? = null,
    val zoomUrl: String? = null,
    val altText: String = "",
    val hlsUrl: String? = null,
    val dashUrl: String? = null,
    val posterUrl: String? = null,
    val fallbackUrl: String? = null,
    val deliveryStatus: String = "not_applicable",
    val processingProgress: Int = 0,
    val cacheKey: String? = null,
) {
    fun toPostMedia() = PostMedia(
        placeholderColor = placeholderColor,
        aspectRatio = aspectRatio,
        isVideo = isVideo,
        durationSeconds = durationSeconds,
        url = url,
        altText = altText,
        hlsUrl = hlsUrl,
        dashUrl = dashUrl,
        posterUrl = posterUrl,
        fallbackUrl = fallbackUrl,
        deliveryStatus = deliveryStatus,
        processingProgress = processingProgress,
        cacheKey = cacheKey,
        mediaId = mediaId,
        width = width,
        height = height,
        zoomUrl = zoomUrl,
    )

    companion object {
        fun from(media: PostMedia) = MediaFeedMedia(
            mediaId = media.mediaId,
            placeholderColor = media.placeholderColor,
            aspectRatio = media.aspectRatio,
            isVideo = media.isVideo,
            width = media.width,
            height = media.height,
            durationSeconds = media.durationSeconds,
            url = media.url,
            zoomUrl = media.zoomUrl,
            altText = media.altText,
            hlsUrl = media.hlsUrl,
            dashUrl = media.dashUrl,
            posterUrl = media.posterUrl,
            fallbackUrl = media.fallbackUrl,
            deliveryStatus = media.deliveryStatus,
            processingProgress = media.processingProgress,
            cacheKey = media.cacheKey,
        )
    }
}

private fun String.withPrefix(prefix: String): String = if (startsWith(prefix)) this else "$prefix$this"
