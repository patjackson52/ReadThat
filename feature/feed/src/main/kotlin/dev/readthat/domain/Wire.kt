package dev.readthat.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * WIRE MODEL — what the "server" sends.
 *
 * This mirrors the shape Reddit describes in "Evolving Reddit's Feed Architecture":
 * instead of one fat polymorphic Post object that the client must interpret, the
 * server sends *the description of the exact UI elements the client will render*,
 * and controls their type and order.
 *
 * A feed page is a list of [WireGroup]. Each group is one logical unit (a post, an
 * announcement, a carousel) and holds an ordered array of [WireCell] — the atomic
 * renderable pieces.
 *
 * The client's job is to render, not to decide.
 */
@Serializable
data class WireFeedPage(
    val groups: List<WireGroup>,
    /** Opaque cursor for the next page. Null means end-of-feed. */
    val nextCursor: String?,
)

@Serializable
data class WireGroup(
    val groupId: String,
    val cells: List<WireCell>,
)

/**
 * The cell taxonomy.
 *
 * [Unknown] is the load-bearing member: it is what a *current* client produces when a
 * *newer* server sends a cell type this build has never heard of. Keeping it in the
 * type system — rather than throwing during parse — is what makes the feed
 * forward-compatible. See [dev.readthat.domain.CellConverterRegistry].
 */
@Serializable
sealed interface WireCell {
    val cellId: String

    @Serializable
    @SerialName("metadata")
    data class Metadata(
        override val cellId: String,
        val subreddit: String,
        val postedAgo: String,
        val pinned: Boolean = false,
        val author: String = "",
        val createdAt: Long = 0,
        val avatarUrl: String? = null,
    ) : WireCell

    @Serializable
    @SerialName("title")
    data class Title(
        override val cellId: String,
        val text: String,
        val flair: WirePostFlair? = null,
    ) : WireCell

    @Serializable
    @SerialName("text")
    data class Text(
        override val cellId: String,
        val body: String,
        val maxLines: Int = 3,
    ) : WireCell

    @Serializable
    @SerialName("image")
    data class Image(
        override val cellId: String,
        val placeholderColor: Long,
        val aspectRatio: Float,
        val altText: String,
        val url: String? = null,
        val cacheKey: String? = null,
    ) : WireCell

    @Serializable
    @SerialName("image_carousel")
    data class ImageCarousel(
        override val cellId: String,
        val items: List<WireImageItem>,
    ) : WireCell

    @Serializable
    @SerialName("video")
    data class Video(
        override val cellId: String,
        val placeholderColor: Long,
        val aspectRatio: Float,
        val durationSeconds: Int,
        val altText: String,
        val url: String? = null,
        val hlsUrl: String? = null,
        val dashUrl: String? = null,
        val posterUrl: String? = null,
        val previewUrl: String? = null,
        val fallbackUrl: String? = null,
        val deliveryStatus: String = "not_applicable",
        val processingProgress: Int = 0,
        val cachePolicy: String = "segments_only",
        val cacheKey: String? = null,
    ) : WireCell

    /** Header for a promoted feed group. The stable [adId] is the analytics identity. */
    @Serializable
    @SerialName("ad_header")
    data class AdHeader(
        override val cellId: String,
        val adId: String,
        val author: String,
        val avatarUrl: String? = null,
        val label: String = "Ad",
    ) : WireCell

    @Serializable
    @SerialName("ad_title")
    data class AdTitle(
        override val cellId: String,
        val adId: String,
        val text: String,
    ) : WireCell

    /**
     * One promoted media stage. A single item renders like a normal video/image;
     * multiple items turn the same cell into a horizontally snapping carousel.
     */
    @Serializable
    @SerialName("ad_media")
    data class AdMedia(
        override val cellId: String,
        val adId: String,
        val items: List<WireAdMediaItem>,
        val destinationUrl: String,
        val displayDomain: String,
        val ctaLabel: String,
    ) : WireCell

    @Serializable
    @SerialName("ad_summary")
    data class AdSummary(
        override val cellId: String,
        val adId: String,
        val text: String,
        val disclosureLabel: String = "AI summary",
    ) : WireCell

    @Serializable
    @SerialName("ad_related_posts")
    data class AdRelatedPosts(
        override val cellId: String,
        val adId: String,
        val posts: List<WireRelatedPost>,
        val disclosureLabel: String = "About ReadThat Highlights",
    ) : WireCell

    @Serializable
    @SerialName("ad_actionbar")
    data class AdActionBar(
        override val cellId: String,
        val adId: String,
        val commentCount: Int = 0,
    ) : WireCell

    @Serializable
    @SerialName("link")
    data class Link(
        override val cellId: String,
        val url: String,
        val domain: String,
    ) : WireCell

    @Serializable
    @SerialName("actionbar")
    data class ActionBar(
        override val cellId: String,
        val score: Int,
        val commentCount: Int,
        /**
         * Viewer state. The server sends its view of it; the client overwrites
         * it from `item_state` at merge time, which is what makes an optimistic
         * like possible without rewriting the payload blob.
         */
        val liked: Boolean = false,
        /** Full three-state vote. Kept beside liked for old cached payloads. */
        val vote: Int = if (liked) 1 else 0,
        val version: Int = 1,
    ) : WireCell

    @Serializable
    @SerialName("announcement")
    data class Announcement(
        override val cellId: String,
        val text: String,
    ) : WireCell

    /**
     * A cell type this client build does not understand.
     *
     * [typeName] is retained for telemetry so the platform team can measure how much
     * of the feed a given app version is failing to render — the signal that tells
     * you when it is safe to stop supporting an old build.
     */
    @Serializable
    @SerialName("unknown")
    data class Unknown(
        override val cellId: String,
        val typeName: String,
    ) : WireCell
}

@Serializable
data class WirePostFlair(
    val id: String,
    val text: String,
    val backgroundColor: String,
    val textColor: String,
)

@Serializable
data class WireImageItem(
    val mediaId: String? = null,
    val placeholderColor: Long,
    val aspectRatio: Float,
    val altText: String,
    val url: String? = null,
    val zoomUrl: String? = null,
    val cacheKey: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
enum class WireAdMediaKind {
    @SerialName("image") Image,
    @SerialName("video") Video,
}

@Serializable
data class WireAdMediaItem(
    val creativeId: String,
    val kind: WireAdMediaKind,
    val placeholderColor: Long,
    val aspectRatio: Float,
    val altText: String,
    val imageUrl: String? = null,
    val hlsUrl: String? = null,
    val dashUrl: String? = null,
    val posterUrl: String? = null,
    val fallbackUrl: String? = null,
    val durationSeconds: Int? = null,
    val cacheKey: String? = null,
)

@Serializable
data class WireRelatedPost(
    val postId: String,
    val title: String,
    val subreddit: String,
    val score: Int,
)
