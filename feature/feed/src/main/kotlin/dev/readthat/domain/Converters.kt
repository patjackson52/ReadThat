package dev.readthat.domain

/**
 * CONVERTERS — wire model -> view model.
 *
 * Reddit's iOS pipeline is described as Services -> Converters -> Diffing Engine,
 * where converters "work in parallel, each feed element is transformed into an
 * appropriate view model by the first converter that can handle it."
 *
 * That "first converter that can handle it" rule is modelled here literally: the
 * registry walks an ordered list and takes the first non-null result. Registration
 * order is therefore meaningful and is part of the contract.
 *
 * Every converter is a PURE FUNCTION. That is the whole point:
 *   - trivially unit-testable with no Android, no coroutines, no fakes
 *   - safe to run off the main thread
 *   - safe to run in parallel across items
 */
fun interface CellConverter {
    /** Return null to decline this cell and let the next converter try. */
    fun convert(cell: WireCell, key: String): CellUi?
}

object Converters {

    val metadata = CellConverter { cell, key ->
        (cell as? WireCell.Metadata)?.let {
            CellUi.Metadata(
                key = key,
                line = "r/${it.subreddit} · ${it.postedAgo}",
                pinned = it.pinned,
                subreddit = it.subreddit,
                author = it.author,
                postedAgo = it.postedAgo,
                createdAt = it.createdAt,
                avatarUrl = it.avatarUrl,
            )
        }
    }

    val title = CellConverter { cell, key ->
        (cell as? WireCell.Title)?.let {
            CellUi.Title(
                key = key,
                text = it.text,
                flair = it.flair?.let { flair ->
                    PostFlairUi(
                        id = flair.id,
                        text = flair.text,
                        backgroundColor = flair.backgroundColor,
                        textColor = flair.textColor,
                    )
                },
            )
        }
    }

    val text = CellConverter { cell, key ->
        (cell as? WireCell.Text)?.let { CellUi.Text(key, it.body, it.maxLines) }
    }

    val image = CellConverter { cell, key ->
        (cell as? WireCell.Image)?.let {
            CellUi.Media(
                key = key,
                placeholderColor = it.placeholderColor,
                aspectRatio = it.aspectRatio,
                altText = it.altText,
                sourceUrl = it.url,
                cacheKey = it.cacheKey,
                durationLabel = null,
            )
        }
    }

    val imageCarousel = CellConverter { cell, key ->
        (cell as? WireCell.ImageCarousel)?.takeIf { it.items.isNotEmpty() }?.let { carousel ->
            CellUi.ImageCarousel(
                key = key,
                items = carousel.items.map { image ->
                    ImageMediaUi(
                        mediaId = image.mediaId,
                        placeholderColor = image.placeholderColor,
                        aspectRatio = image.aspectRatio,
                        altText = image.altText,
                        sourceUrl = image.url,
                        zoomUrl = image.zoomUrl,
                        cacheKey = image.cacheKey,
                        width = image.width,
                        height = image.height,
                    )
                },
            )
        }
    }

    val video = CellConverter { cell, key ->
        (cell as? WireCell.Video)?.let {
            CellUi.Media(
                key = key,
                placeholderColor = it.placeholderColor,
                aspectRatio = it.aspectRatio,
                altText = it.altText,
                sourceUrl = it.url,
                durationLabel = formatDuration(it.durationSeconds),
                durationSeconds = it.durationSeconds,
                // Feed, MediaFeed, and detail address the media asset rather than
                // the post that happens to contain it. Crossposts then share the
                // same player handoff and disk-cache identity.
                cacheKey = it.cacheKey
                    ?: "post:${key.substringBefore(FeedFlattener.KEY_SEPARATOR)}",
                video = CellUi.VideoPlaybackUi(
                    hlsUrl = it.hlsUrl,
                    dashUrl = it.dashUrl,
                    posterUrl = it.posterUrl,
                    fallbackUrl = it.fallbackUrl ?: it.url,
                    deliveryStatus = it.deliveryStatus,
                    processingProgress = it.processingProgress,
                ),
            )
        }
    }

    val adHeader = CellConverter { cell, key ->
        (cell as? WireCell.AdHeader)?.let {
            CellUi.AdHeader(key, it.adId, it.author, it.avatarUrl, it.label)
        }
    }

    val adTitle = CellConverter { cell, key ->
        (cell as? WireCell.AdTitle)?.let { CellUi.AdTitle(key, it.adId, it.text) }
    }

    val adMedia = CellConverter { cell, key ->
        (cell as? WireCell.AdMedia)?.let { media ->
            CellUi.AdMedia(
                key = key,
                adId = media.adId,
                items = media.items.map { item ->
                    AdMediaItemUi(
                        creativeId = item.creativeId,
                        kind = when (item.kind) {
                            WireAdMediaKind.Image -> AdMediaKind.Image
                            WireAdMediaKind.Video -> AdMediaKind.Video
                        },
                        placeholderColor = item.placeholderColor,
                        aspectRatio = item.aspectRatio,
                        altText = item.altText,
                        imageUrl = item.imageUrl,
                        hlsUrl = item.hlsUrl,
                        dashUrl = item.dashUrl,
                        posterUrl = item.posterUrl,
                        fallbackUrl = item.fallbackUrl,
                        durationSeconds = item.durationSeconds,
                        cacheKey = item.cacheKey ?: "ad:${media.adId}:${item.creativeId}",
                    )
                },
                destinationUrl = media.destinationUrl,
                displayDomain = media.displayDomain,
                ctaLabel = media.ctaLabel,
            )
        }
    }

    val adSummary = CellConverter { cell, key ->
        (cell as? WireCell.AdSummary)?.let {
            CellUi.AdSummary(key, it.adId, it.text, it.disclosureLabel)
        }
    }

    val adRelatedPosts = CellConverter { cell, key ->
        (cell as? WireCell.AdRelatedPosts)?.let { related ->
            CellUi.AdRelatedPosts(
                key = key,
                adId = related.adId,
                posts = related.posts.map {
                    RelatedPostUi(it.postId, it.title, it.subreddit, it.score)
                },
                disclosureLabel = related.disclosureLabel,
            )
        }
    }

    val adActionBar = CellConverter { cell, key ->
        (cell as? WireCell.AdActionBar)?.let {
            CellUi.AdActionBar(key, it.adId, it.commentCount)
        }
    }

    val link = CellConverter { cell, key ->
        (cell as? WireCell.Link)?.let { CellUi.Link(key, it.url, it.domain) }
    }

    val actionBar = CellConverter { cell, key ->
        (cell as? WireCell.ActionBar)?.let {
            CellUi.ActionBar(
                key = key,
                scoreLabel = compactCount(it.score),
                commentLabel = compactCount(it.commentCount),
                liked = it.liked,
                viewerVote = it.vote,
                // The flattener builds keys as "groupId/cellId"; the group id is
                // the stable item identity a write targets.
                itemId = key.substringBefore(FeedFlattener.KEY_SEPARATOR),
                score = it.score,
                commentCount = it.commentCount,
                version = it.version,
            )
        }
    }

    val announcement = CellConverter { cell, key ->
        (cell as? WireCell.Announcement)?.let { CellUi.Announcement(key, it.text) }
    }

    /** Default registration order. */
    val default: List<CellConverter> = listOf(
        metadata, title, text, image, imageCarousel, video,
        adHeader, adTitle, adMedia, adSummary, adRelatedPosts, adActionBar,
        link, actionBar, announcement,
    )

    internal fun formatDuration(totalSeconds: Int): String {
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return "$m:" + s.toString().padStart(2, '0')
    }

    internal fun compactCount(n: Int): String = when {
        n >= 1_000_000 -> trimZero(n / 100_000.0 / 10.0) + "M"
        n >= 1_000 -> trimZero(n / 100.0 / 10.0) + "k"
        else -> n.toString()
    }

    private fun trimZero(v: Double): String {
        val oneDp = kotlin.math.round(v * 10.0) / 10.0
        return if (oneDp % 1.0 == 0.0) oneDp.toInt().toString() else oneDp.toString()
    }
}

/**
 * Resolves a wire cell to a view model using an ordered converter list.
 *
 * Unhandled cells (including [WireCell.Unknown]) return null. The caller decides
 * policy — here, [FeedFlattener] drops them and counts them. Dropping rather than
 * throwing is what keeps an old client usable against a newer server.
 */
class CellConverterRegistry(
    private val converters: List<CellConverter> = Converters.default,
) {
    fun convert(cell: WireCell, key: String): CellUi? {
        for (converter in converters) {
            val result = converter.convert(cell, key)
            if (result != null) return result
        }
        return null
    }
}
