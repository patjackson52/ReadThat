package dev.readthat.domain

import dev.readthat.shared.PostMedia
import dev.readthat.shared.PostFlair
import dev.readthat.shared.PostTransitionPreview

/**
 * Immutable handoff from one ranked normal-feed snapshot to MediaFeed.
 *
 * [nextFeedCursor] remains opaque to the feed feature. The MediaFeed network
 * adapter translates it into its own cursor namespace before persisting it.
 */
data class NormalFeedMediaContext(
    val snapshotId: String,
    val sourceFeedId: String,
    val anchorPostId: String,
    val items: List<PostTransitionPreview>,
    val anchorIndex: Int,
    val nextFeedCursor: String?,
) {
    init {
        require(items.isNotEmpty())
        require(anchorIndex in items.indices)
        require(items[anchorIndex].postId == anchorPostId)
        require(items.all { it.media != null })
    }
}

/** One conversion shared by the visible feed tap and the Room snapshot handoff. */
fun List<CellUi>.toPostTransitionPreview(postId: String): PostTransitionPreview {
    val media = filterIsInstance<CellUi.Media>().firstOrNull()
    val gallery = filterIsInstance<CellUi.ImageCarousel>().firstOrNull()?.items.orEmpty().map { image ->
        PostMedia(
            placeholderColor = image.placeholderColor,
            aspectRatio = image.aspectRatio,
            isVideo = false,
            url = image.sourceUrl,
            altText = image.altText,
            fallbackUrl = image.sourceUrl,
            cacheKey = image.cacheKey ?: image.mediaId?.let { "image:$it" } ?: "post:$postId",
            mediaId = image.mediaId,
            width = image.width,
            height = image.height,
            zoomUrl = image.zoomUrl ?: image.sourceUrl,
        )
    }
    val metadata = filterIsInstance<CellUi.Metadata>().firstOrNull()
    val title = filterIsInstance<CellUi.Title>().firstOrNull()
    val actions = filterIsInstance<CellUi.ActionBar>().firstOrNull()
    val primaryMedia = gallery.firstOrNull() ?: media?.let {
        PostMedia(
            placeholderColor = it.placeholderColor,
            aspectRatio = it.aspectRatio,
            isVideo = it.video != null,
            durationSeconds = it.durationSeconds,
            url = it.sourceUrl,
            altText = it.altText,
            hlsUrl = it.video?.hlsUrl,
            dashUrl = it.video?.dashUrl,
            posterUrl = it.video?.posterUrl,
            fallbackUrl = it.video?.fallbackUrl ?: it.sourceUrl,
            deliveryStatus = it.video?.deliveryStatus ?: "not_applicable",
            processingProgress = it.video?.processingProgress ?: 0,
            cacheKey = it.cacheKey ?: "post:$postId",
            zoomUrl = if (it.video == null) it.sourceUrl else null,
        )
    }
    return PostTransitionPreview(
        postId = postId,
        title = title?.text.orEmpty(),
        body = filterIsInstance<CellUi.Text>().firstOrNull()?.body,
        media = primaryMedia,
        linkUrl = filterIsInstance<CellUi.Link>().firstOrNull()?.url,
        author = metadata?.author.orEmpty(),
        subreddit = metadata?.subreddit.orEmpty(),
        score = actions?.score ?: 0,
        commentCount = actions?.commentCount ?: 0,
        viewerVote = actions?.viewerVote ?: 0,
        postedAgo = metadata?.postedAgo.orEmpty(),
        createdAt = metadata?.createdAt ?: 0,
        communityAvatarUrl = metadata?.avatarUrl,
        mediaItems = gallery,
        flair = title?.flair?.let { PostFlair(it.id, it.text, it.backgroundColor, it.textColor) },
    )
}
