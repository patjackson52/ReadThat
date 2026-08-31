package dev.readthat.client

import dev.readthat.mediafeed.domain.MediaFeedItem
import dev.readthat.mediafeed.domain.MediaFeedMedia
import dev.readthat.mediafeed.domain.MediaFeedPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaPreviewPrefetchTest {
    @Test
    fun `preview planner is bounded and deduplicates stable identities`() {
        val page = MediaFeedPage(
            items = (0 until 10).map { itemIndex ->
                item(
                    itemIndex,
                    (0 until 5).map { mediaIndex ->
                        image(
                            key = if (itemIndex < 2) "shared:$mediaIndex" else "$itemIndex:$mediaIndex",
                            url = "https://images.example/$itemIndex/$mediaIndex.jpg",
                        )
                    },
                )
            },
            nextCursor = null,
            snapshotAt = 1,
            anchorIncluded = true,
        )

        val requests = mediaPreviewPrefetchRequests(page)

        assertEquals(24, requests.size)
        assertEquals(requests.size, requests.distinctBy { it.videoPreview to it.cacheKey }.size)
        assertFalse(requests.any { it.cacheKey.startsWith("8:") || it.cacheKey.startsWith("9:") })
    }

    @Test
    fun `videos warm first frame previews while still images warm display urls`() {
        val page = MediaFeedPage(
            items = listOf(item(0, listOf(
                video(key = "video-with-poster", poster = "https://images.example/poster.jpg"),
                video(key = "video-without-poster", poster = null),
                image(key = "image", url = "https://images.example/image.jpg"),
            ))),
            nextCursor = null,
            snapshotAt = 1,
            anchorIncluded = true,
        )

        val requests = mediaPreviewPrefetchRequests(page)

        assertEquals(2, requests.size)
        assertTrue(requests.single { it.videoPreview }.cacheKey.startsWith("video-with-poster:poster:v3:"))
        assertFalse(requests.single { it.cacheKey == "image" }.videoPreview)
    }

    private fun item(index: Int, media: List<MediaFeedMedia>) = MediaFeedItem(
        postId = "post-$index",
        author = "reader",
        subreddit = "readthat",
        title = "Post $index",
        score = 0,
        commentCount = 0,
        kind = if (media.first().isVideo) "video" else "image",
        media = media.first(),
        mediaItems = media,
    )

    private fun image(key: String, url: String) = MediaFeedMedia(
        placeholderColor = 0,
        aspectRatio = 1f,
        isVideo = false,
        url = url,
        cacheKey = key,
    )

    private fun video(key: String, poster: String?) = MediaFeedMedia(
        placeholderColor = 0,
        aspectRatio = 1f,
        isVideo = true,
        hlsUrl = "https://stream.example/$key.m3u8",
        posterUrl = poster,
        cacheKey = key,
    )
}
