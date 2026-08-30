package dev.readthat.domain

/**
 * VIEW MODEL LAYER — what the UI renders.
 *
 * Deliberately separate from the wire model. The wire model is the server's
 * vocabulary and can change under us; the view model is the UI's vocabulary and is
 * shaped for rendering (pre-formatted strings, resolved colors, stable keys).
 *
 * Keeping these apart is what makes the converters pure, testable functions and
 * keeps server schema churn from leaking into Compose code.
 */
sealed interface CellUi {
    /**
     * Stable, unique key for this item across the whole flattened list.
     * Used for LazyColumn keys — required for correct scroll restoration and to
     * avoid needless recomposition when the list changes around an item.
     */
    val key: String

    data class Metadata(
        override val key: String,
        val line: String,
        val pinned: Boolean,
        val subreddit: String = "",
        val author: String = "",
        val postedAgo: String = "",
        val createdAt: Long = 0,
        val avatarUrl: String? = null,
    ) : CellUi

    data class Title(
        override val key: String,
        val text: String,
        val flair: PostFlairUi? = null,
    ) : CellUi

    data class Text(
        override val key: String,
        val body: String,
        val maxLines: Int,
    ) : CellUi

    data class Media(
        override val key: String,
        val placeholderColor: Long,
        val aspectRatio: Float,
        val altText: String,
        val sourceUrl: String? = null,
        /** Stable across hourly signed CDN URLs so Coil can reuse decoded/disk entries. */
        val cacheKey: String? = null,
        /** Formatted "3:14" for video, null for a still image. */
        val durationLabel: String?,
        val durationSeconds: Int? = null,
        val video: VideoPlaybackUi? = null,
    ) : CellUi

    data class ImageCarousel(
        override val key: String,
        val items: List<ImageMediaUi>,
    ) : CellUi

    data class VideoPlaybackUi(
        val hlsUrl: String?,
        val dashUrl: String?,
        val posterUrl: String?,
        val fallbackUrl: String?,
        val deliveryStatus: String,
        val processingProgress: Int,
    )

    data class AdHeader(
        override val key: String,
        val adId: String,
        val author: String,
        val avatarUrl: String?,
        val label: String,
    ) : CellUi

    data class AdTitle(
        override val key: String,
        val adId: String,
        val text: String,
    ) : CellUi

    data class AdMedia(
        override val key: String,
        val adId: String,
        val items: List<AdMediaItemUi>,
        val destinationUrl: String,
        val displayDomain: String,
        val ctaLabel: String,
    ) : CellUi

    data class AdSummary(
        override val key: String,
        val adId: String,
        val text: String,
        val disclosureLabel: String,
    ) : CellUi

    data class AdRelatedPosts(
        override val key: String,
        val adId: String,
        val posts: List<RelatedPostUi>,
        val disclosureLabel: String,
    ) : CellUi

    data class AdActionBar(
        override val key: String,
        val adId: String,
        val commentCount: Int,
    ) : CellUi

    data class Link(
        override val key: String,
        val url: String,
        val domain: String,
    ) : CellUi

    data class ActionBar(
        override val key: String,
        val scoreLabel: String,
        val commentLabel: String,
        val liked: Boolean = false,
        val viewerVote: Int = if (liked) 1 else 0,
        /** Group id — what a like writes against. Never the cell id. */
        val itemId: String = "",
        val score: Int = 0,
        val commentCount: Int = 0,
        val version: Int = 1,
    ) : CellUi

    data class Announcement(
        override val key: String,
        val text: String,
    ) : CellUi

    /** Visual separator emitted between groups by the flattener, not by the server. */
    data class GroupDivider(
        override val key: String,
    ) : CellUi
}

data class PostFlairUi(
    val id: String,
    val text: String,
    val backgroundColor: String,
    val textColor: String,
)

data class ImageMediaUi(
    val mediaId: String?,
    val placeholderColor: Long,
    val aspectRatio: Float,
    val altText: String,
    val sourceUrl: String?,
    val zoomUrl: String?,
    val cacheKey: String?,
    val width: Int?,
    val height: Int?,
)

enum class AdMediaKind { Image, Video }

data class AdMediaItemUi(
    val creativeId: String,
    val kind: AdMediaKind,
    val placeholderColor: Long,
    val aspectRatio: Float,
    val altText: String,
    val imageUrl: String?,
    val hlsUrl: String?,
    val dashUrl: String?,
    val posterUrl: String?,
    val fallbackUrl: String?,
    val durationSeconds: Int?,
    val cacheKey: String,
)

data class RelatedPostUi(
    val postId: String,
    val title: String,
    val subreddit: String,
    val score: Int,
)

/** Immutable navigation handoff from the feed card to the hybrid media + web detail. */
data class AdLaunchContext(
    val adId: String,
    val creativeId: String,
    val kind: AdMediaKind,
    val placeholderColor: Long,
    val aspectRatio: Float,
    val altText: String,
    val imageUrl: String?,
    val hlsUrl: String?,
    val posterUrl: String?,
    val fallbackUrl: String?,
    val cacheKey: String,
    val destinationUrl: String,
    val displayDomain: String,
    val ctaLabel: String,
    val restartAtBeginning: Boolean = false,
)

fun CellUi.AdMedia.launchContext(
    page: Int,
    restartAtBeginning: Boolean = false,
): AdLaunchContext {
    val media = items[page.coerceIn(items.indices)]
    return AdLaunchContext(
        adId = adId,
        creativeId = media.creativeId,
        kind = media.kind,
        placeholderColor = media.placeholderColor,
        aspectRatio = media.aspectRatio,
        altText = media.altText,
        imageUrl = media.imageUrl,
        hlsUrl = media.hlsUrl,
        posterUrl = media.posterUrl,
        fallbackUrl = media.fallbackUrl,
        cacheKey = media.cacheKey,
        destinationUrl = destinationUrl,
        displayDomain = displayDomain,
        ctaLabel = ctaLabel,
        restartAtBeginning = restartAtBeginning,
    )
}

/**
 * The flat, render-ready list plus the telemetry gathered while producing it.
 *
 * [droppedCellTypes] is a multiset of wire cell type names this build could not
 * render. In production this is the metric you alert on.
 */
data class RenderList(
    val items: List<CellUi>,
    val droppedCellTypes: Map<String, Int> = emptyMap(),
) {
    val droppedCount: Int get() = droppedCellTypes.values.sum()
}
