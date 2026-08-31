package dev.readthat.shared

import dev.readthat.domain.WireAdMediaKind
import dev.readthat.domain.WireCell
import dev.readthat.domain.WireFeedPage

/** Compressed image bytes worth retaining after a database-first background feed refresh. */
data class BackgroundImagePrefetchRequest(
    val url: String,
    val cacheKey: String,
    val videoPreview: Boolean,
)

/** Native players may use this bounded candidate without moving HLS ownership into common code. */
data class BackgroundVideoPrefetchRequest(
    val hlsUrl: String?,
    val fallbackUrl: String?,
    val cacheKey: String,
)

/**
 * Platform-neutral warmup selection for a freshly committed feed page.
 *
 * Common code owns ordering, stable identities, first-frame poster normalization and bounds.
 * Platform schedulers retain OS constraints and native HLS behavior.
 */
data class BackgroundFeedMediaPlan(
    val images: List<BackgroundImagePrefetchRequest>,
    val videos: List<BackgroundVideoPrefetchRequest>,
)

fun WireFeedPage.backgroundFeedMediaPlan(
    maxImages: Int = DEFAULT_BACKGROUND_IMAGE_COUNT,
    maxVideos: Int = DEFAULT_BACKGROUND_VIDEO_COUNT,
): BackgroundFeedMediaPlan {
    require(maxImages >= 0)
    require(maxVideos >= 0)
    val images = buildList {
        groups.forEach { group ->
            group.cells.forEach { cell ->
                val cellKey = "${group.groupId}/${cell.cellId}"
                when (cell) {
                    is WireCell.Image -> cell.url?.let { url ->
                        add(BackgroundImagePrefetchRequest(
                            url = url,
                            cacheKey = cell.cacheKey ?: "feed-image:$cellKey",
                            videoPreview = false,
                        ))
                    }
                    is WireCell.ImageCarousel -> cell.items.forEachIndexed { page, image ->
                        image.url?.let { url ->
                            add(BackgroundImagePrefetchRequest(
                                url = url,
                                cacheKey = image.cacheKey
                                    ?: image.mediaId?.let { "image:$it" }
                                    ?: "feed-carousel:$cellKey:$page",
                                videoPreview = false,
                            ))
                        }
                    }
                    is WireCell.Video -> firstFrameVideoPreviewUrl(cell.posterUrl)?.let { url ->
                        val mediaKey = cell.cacheKey ?: "post:${group.groupId}"
                        add(BackgroundImagePrefetchRequest(
                            url = url,
                            cacheKey = videoPosterCacheKey(mediaKey, url),
                            videoPreview = true,
                        ))
                    }
                    is WireCell.AdMedia -> cell.items.forEach { item ->
                        val mediaKey = item.cacheKey ?: "ad:${cell.adId}:${item.creativeId}"
                        when (item.kind) {
                            WireAdMediaKind.Image -> item.imageUrl?.let { url ->
                                add(BackgroundImagePrefetchRequest(url, mediaKey, false))
                            }
                            WireAdMediaKind.Video -> firstFrameVideoPreviewUrl(item.posterUrl)?.let { url ->
                                add(BackgroundImagePrefetchRequest(
                                    url,
                                    videoPosterCacheKey(mediaKey, url),
                                    true,
                                ))
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
        .filter { it.url.isNotBlank() && it.cacheKey.isNotBlank() }
        .distinctBy { (if (it.videoPreview) "preview:" else "image:") + it.cacheKey }
        .take(maxImages)

    val videos = buildList {
        groups.forEach { group ->
            group.cells.forEach { cell ->
                when (cell) {
                    is WireCell.Video -> {
                        val fallback = cell.fallbackUrl ?: cell.url
                        val playable = cell.deliveryStatus == "ready" || fallback != null
                        if (playable && (cell.hlsUrl != null || fallback != null)) {
                            add(BackgroundVideoPrefetchRequest(
                                hlsUrl = cell.hlsUrl,
                                fallbackUrl = fallback,
                                cacheKey = cell.cacheKey ?: "post:${group.groupId}",
                            ))
                        }
                    }
                    is WireCell.AdMedia -> cell.items.forEach { item ->
                        if (item.kind == WireAdMediaKind.Video &&
                            (item.hlsUrl != null || item.fallbackUrl != null)
                        ) {
                            add(BackgroundVideoPrefetchRequest(
                                hlsUrl = item.hlsUrl,
                                fallbackUrl = item.fallbackUrl,
                                cacheKey = item.cacheKey ?: "ad:${cell.adId}:${item.creativeId}",
                            ))
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
        .filter { it.cacheKey.isNotBlank() }
        .distinctBy(BackgroundVideoPrefetchRequest::cacheKey)
        .take(maxVideos)

    return BackgroundFeedMediaPlan(images, videos)
}

const val DEFAULT_BACKGROUND_IMAGE_COUNT = 8
const val DEFAULT_BACKGROUND_VIDEO_COUNT = 1
