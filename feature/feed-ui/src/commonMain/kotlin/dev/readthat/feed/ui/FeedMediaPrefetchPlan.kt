package dev.readthat.feed.ui

import dev.readthat.domain.AdMediaKind
import dev.readthat.domain.CellUi
import dev.readthat.shared.feedImageCacheKey
import dev.readthat.shared.feedVideoPosterCacheKey
import dev.readthat.shared.videoPosterCacheKey

enum class FeedImagePrefetchKind { Still, VideoPoster }

data class FeedImagePrefetchRequest(
    val cellIndex: Int,
    val url: String,
    val cacheKey: String,
    val kind: FeedImagePrefetchKind,
) {
    val videoPreview: Boolean get() = kind == FeedImagePrefetchKind.VideoPoster
    val decodedKey: String get() = (if (videoPreview) "preview:" else "image:") + cacheKey
}

data class FeedVideoPrefetchRequest(
    val cellIndex: Int,
    val cellKey: String,
    val placeholderColor: Long,
    val aspectRatio: Float,
    val durationSeconds: Int?,
    val url: String?,
    val altText: String,
    val hlsUrl: String?,
    val dashUrl: String?,
    val posterUrl: String?,
    val fallbackUrl: String?,
    val deliveryStatus: String,
    val processingProgress: Int,
    val cacheKey: String,
    val posterCacheKey: String,
)

data class FeedMediaPrefetchPlan(
    /** All currently paged videos; native players apply their bounded source preload strategy. */
    val videos: List<FeedVideoPrefetchRequest>,
    val videoFocusIndex: Int,
    /** Bounded decoded/disk-cache work around the viewport. */
    val stillImages: List<FeedImagePrefetchRequest>,
    val videoPosters: List<FeedImagePrefetchRequest>,
) {
    val decodedImages: List<FeedImagePrefetchRequest> = (stillImages + videoPosters)
        .distinctBy(FeedImagePrefetchRequest::decodedKey)
        .sortedBy(FeedImagePrefetchRequest::cellIndex)
        .take(MAX_DECODED_REQUESTS)
}

/** Snapshot-stable index: build once per Paging snapshot, then plan cheaply as the viewport moves. */
data class FeedMediaPrefetchCatalog(
    val videos: List<FeedVideoPrefetchRequest>,
    val stillImages: List<FeedImagePrefetchRequest>,
) {
    fun plan(firstVisibleCellIndex: Int): FeedMediaPrefetchPlan {
        val videoFocus = videos.focusIndex(firstVisibleCellIndex, FeedVideoPrefetchRequest::cellIndex)
        val posterWindow = videos.window(videoFocus, VIDEO_BEHIND, VIDEO_AHEAD).mapNotNull { video ->
            video.posterUrl?.let { url ->
                FeedImagePrefetchRequest(
                    cellIndex = video.cellIndex,
                    url = url,
                    cacheKey = video.posterCacheKey,
                    kind = FeedImagePrefetchKind.VideoPoster,
                )
            }
        }
        val imageFocus = stillImages.focusIndex(firstVisibleCellIndex, FeedImagePrefetchRequest::cellIndex)
        return FeedMediaPrefetchPlan(
            videos = videos,
            videoFocusIndex = videoFocus.coerceAtLeast(0),
            stillImages = stillImages.window(imageFocus, IMAGE_BEHIND, IMAGE_AHEAD),
            videoPosters = posterWindow,
        )
    }
}

fun feedMediaPrefetchCatalog(cells: List<CellUi>): FeedMediaPrefetchCatalog {
    val videos = buildList {
        cells.forEachIndexed { cellIndex, item ->
            when (item) {
                is CellUi.Media -> item.video?.let { video ->
                    val cacheKey = item.cacheKey ?: "post:${item.feedGroupId()}"
                    add(FeedVideoPrefetchRequest(
                        cellIndex = cellIndex,
                        cellKey = item.key,
                        placeholderColor = item.placeholderColor,
                        aspectRatio = item.aspectRatio,
                        durationSeconds = item.durationSeconds,
                        url = item.sourceUrl,
                        altText = item.altText,
                        hlsUrl = video.hlsUrl,
                        dashUrl = video.dashUrl,
                        posterUrl = video.posterUrl,
                        fallbackUrl = video.fallbackUrl ?: item.sourceUrl,
                        deliveryStatus = video.deliveryStatus,
                        processingProgress = video.processingProgress,
                        cacheKey = cacheKey,
                        posterCacheKey = item.feedVideoPosterCacheKey(),
                    ))
                }
                is CellUi.AdMedia -> item.items.forEach { media ->
                    if (media.kind == AdMediaKind.Video) {
                        add(FeedVideoPrefetchRequest(
                            cellIndex = cellIndex,
                            cellKey = item.key,
                            placeholderColor = media.placeholderColor,
                            aspectRatio = media.aspectRatio,
                            durationSeconds = media.durationSeconds,
                            url = media.imageUrl,
                            altText = media.altText,
                            hlsUrl = media.hlsUrl,
                            dashUrl = media.dashUrl,
                            posterUrl = media.posterUrl,
                            fallbackUrl = media.fallbackUrl,
                            deliveryStatus = "not_applicable",
                            processingProgress = 0,
                            cacheKey = media.cacheKey,
                            posterCacheKey = videoPosterCacheKey(media.cacheKey, media.posterUrl),
                        ))
                    }
                }
                else -> Unit
            }
        }
    }
    val images = buildList {
        cells.forEachIndexed { cellIndex, item ->
            when (item) {
                is CellUi.Media -> if (item.video == null) {
                    item.sourceUrl?.let { url ->
                        add(FeedImagePrefetchRequest(
                            cellIndex, url, item.feedImageCacheKey(), FeedImagePrefetchKind.Still,
                        ))
                    }
                }
                is CellUi.ImageCarousel -> item.items.forEachIndexed { page, image ->
                    image.sourceUrl?.let { url ->
                        add(FeedImagePrefetchRequest(
                            cellIndex,
                            url,
                            image.feedImageCacheKey(item.key, page),
                            FeedImagePrefetchKind.Still,
                        ))
                    }
                }
                is CellUi.AdMedia -> item.items.forEach { media ->
                    if (media.kind == AdMediaKind.Image) {
                        media.imageUrl?.let { url ->
                            add(FeedImagePrefetchRequest(
                                cellIndex, url, media.cacheKey, FeedImagePrefetchKind.Still,
                            ))
                        }
                    }
                }
                else -> Unit
            }
        }
    }
    return FeedMediaPrefetchCatalog(
        // Preserve cell positions even when the same stable asset appears more than once. Native
        // executors deduplicate work by cache key, while viewport focus still follows the nearest
        // rendered occurrence.
        videos = videos,
        stillImages = images,
    )
}

fun feedMediaPrefetchPlan(
    cells: List<CellUi>,
    firstVisibleCellIndex: Int,
): FeedMediaPrefetchPlan = feedMediaPrefetchCatalog(cells).plan(firstVisibleCellIndex)

private fun <T> List<T>.window(focus: Int, behind: Int, ahead: Int): List<T> {
    if (isEmpty() || focus !in indices) return emptyList()
    return subList((focus - behind).coerceAtLeast(0), (focus + ahead + 1).coerceAtMost(size))
}

private inline fun <T> List<T>.focusIndex(
    firstVisibleCellIndex: Int,
    cellIndex: (T) -> Int,
): Int = indexOfFirst { cellIndex(it) >= firstVisibleCellIndex }
    .takeIf { it >= 0 } ?: lastIndex

private const val VIDEO_BEHIND = 2
private const val VIDEO_AHEAD = 6
private const val IMAGE_BEHIND = 3
private const val IMAGE_AHEAD = 12
private const val MAX_DECODED_REQUESTS = 24
