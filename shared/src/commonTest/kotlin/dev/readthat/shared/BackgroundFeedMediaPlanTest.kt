package dev.readthat.shared

import dev.readthat.domain.WireAdMediaItem
import dev.readthat.domain.WireAdMediaKind
import dev.readthat.domain.WireCell
import dev.readthat.domain.WireFeedPage
import dev.readthat.domain.WireGroup
import dev.readthat.domain.WireImageItem
import kotlin.test.Test
import kotlin.test.assertEquals

class BackgroundFeedMediaPlanTest {
    @Test
    fun planPreservesFeedOrderAndRuntimeCacheIdentities() {
        val page = WireFeedPage(
            groups = listOf(
                WireGroup("photo", listOf(WireCell.Image(
                    cellId = "media",
                    placeholderColor = 0,
                    aspectRatio = 1f,
                    altText = "photo",
                    url = "https://cdn/photo.jpg",
                ))),
                WireGroup("gallery", listOf(WireCell.ImageCarousel(
                    cellId = "carousel",
                    items = listOf(WireImageItem(
                        mediaId = "media-1",
                        placeholderColor = 0,
                        aspectRatio = 1f,
                        altText = "gallery",
                        url = "https://cdn/gallery.jpg",
                    )),
                ))),
                WireGroup("video", listOf(video(
                    poster = "https://stream.test/id/thumbnails/thumbnail.jpg?time=4s",
                ))),
                WireGroup("ad", listOf(WireCell.AdMedia(
                    cellId = "media",
                    adId = "ad-1",
                    items = listOf(WireAdMediaItem(
                        creativeId = "creative-1",
                        kind = WireAdMediaKind.Video,
                        placeholderColor = 0,
                        aspectRatio = 1f,
                        altText = "ad video",
                        hlsUrl = "https://cdn/ad.m3u8",
                        posterUrl = "https://cdn/ad.jpg",
                    )),
                    destinationUrl = "https://advertiser.test",
                    displayDomain = "advertiser.test",
                    ctaLabel = "Learn more",
                ))),
            ),
            nextCursor = null,
        )

        val plan = page.backgroundFeedMediaPlan(maxImages = 4, maxVideos = 2)

        assertEquals(
            listOf(
                BackgroundImagePrefetchRequest(
                    "https://cdn/photo.jpg",
                    "feed-image:photo/media",
                    false,
                ),
                BackgroundImagePrefetchRequest("https://cdn/gallery.jpg", "image:media-1", false),
                BackgroundImagePrefetchRequest(
                    "https://stream.test/id/thumbnails/thumbnail.jpg?time=0s",
                    videoPosterCacheKey(
                        "post:video",
                        "https://stream.test/id/thumbnails/thumbnail.jpg?time=0s",
                    ),
                    true,
                ),
                BackgroundImagePrefetchRequest(
                    "https://cdn/ad.jpg",
                    videoPosterCacheKey("ad:ad-1:creative-1", "https://cdn/ad.jpg"),
                    true,
                ),
            ),
            plan.images,
        )
        assertEquals(
            listOf(
                BackgroundVideoPrefetchRequest(
                    "https://cdn/video.m3u8",
                    "https://cdn/video.mp4",
                    "post:video",
                ),
                BackgroundVideoPrefetchRequest(
                    "https://cdn/ad.m3u8",
                    null,
                    "ad:ad-1:creative-1",
                ),
            ),
            plan.videos,
        )
    }

    @Test
    fun planIsBoundedAndDeduplicatesEqualRuntimeIdentities() {
        val duplicate = WireCell.Image(
            cellId = "media",
            placeholderColor = 0,
            aspectRatio = 1f,
            altText = "duplicate",
            url = "https://cdn/rotated-signature.jpg",
            cacheKey = "asset:v1",
        )
        val page = WireFeedPage(
            groups = listOf(
                WireGroup("one", listOf(duplicate)),
                WireGroup("two", listOf(duplicate.copy(url = "https://cdn/new-signature.jpg"))),
                WireGroup("video-one", listOf(video())),
                WireGroup("video-two", listOf(video().copy(hlsUrl = "https://cdn/two.m3u8"))),
            ),
            nextCursor = null,
        )

        val plan = page.backgroundFeedMediaPlan(maxImages = 1, maxVideos = 1)

        assertEquals(1, plan.images.size)
        assertEquals("https://cdn/rotated-signature.jpg", plan.images.single().url)
        assertEquals(1, plan.videos.size)
        assertEquals("post:video-one", plan.videos.single().cacheKey)
    }

    @Test
    fun hlsOnlyVideoWaitsForDeliveryButProcessingFallbackRemainsPlayable() {
        val page = WireFeedPage(
            groups = listOf(
                WireGroup("hls-processing", listOf(video().copy(
                    deliveryStatus = "processing",
                    fallbackUrl = null,
                ))),
                WireGroup("fallback-processing", listOf(video().copy(
                    deliveryStatus = "processing",
                    hlsUrl = null,
                ))),
            ),
            nextCursor = null,
        )

        assertEquals(
            listOf(BackgroundVideoPrefetchRequest(
                hlsUrl = null,
                fallbackUrl = "https://cdn/video.mp4",
                cacheKey = "post:fallback-processing",
            )),
            page.backgroundFeedMediaPlan(maxVideos = 2).videos,
        )
    }

    private fun video(
        poster: String = "https://cdn/video.jpg",
    ) = WireCell.Video(
        cellId = "media",
        placeholderColor = 0,
        aspectRatio = 16f / 9f,
        durationSeconds = 20,
        altText = "video",
        hlsUrl = "https://cdn/video.m3u8",
        posterUrl = poster,
        fallbackUrl = "https://cdn/video.mp4",
        deliveryStatus = "ready",
    )
}
