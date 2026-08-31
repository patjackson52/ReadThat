package dev.readthat.shared

import dev.readthat.domain.CellUi
import dev.readthat.domain.FeedFlattener
import dev.readthat.domain.ImageMediaUi

fun CellUi.Media.feedImageCacheKey(): String = cacheKey ?: "feed-image:$key"

fun CellUi.Media.feedVideoPosterCacheKey(): String = videoPosterCacheKey(
    mediaKey = cacheKey ?: "post:${key.substringBefore(FeedFlattener.KEY_SEPARATOR)}",
    posterUrl = video?.posterUrl,
)

fun ImageMediaUi.feedImageCacheKey(parentKey: String, page: Int): String =
    cacheKey ?: mediaId?.let { "image:$it" } ?: "feed-carousel:$parentKey:$page"
