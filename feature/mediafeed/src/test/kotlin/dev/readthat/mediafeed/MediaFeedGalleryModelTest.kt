package dev.readthat.mediafeed

import dev.readthat.mediafeed.domain.MediaFeedItem
import dev.readthat.mediafeed.domain.MediaFeedMedia
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaFeedGalleryModelTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `old cached singleton payload falls back to primary media`() {
        val decoded = json.decodeFromString<MediaFeedItem>(
            """{"postId":"old","author":"a","subreddit":"pics","title":"old","score":1,"commentCount":0,"kind":"image","media":{"mediaId":"only","placeholderColor":4278190080,"aspectRatio":1.0,"isVideo":false}}""",
        )

        assertEquals(listOf("only"), decoded.allMedia.map { it.mediaId })
    }

    @Test
    fun `gallery survives transition preview round trip in order`() {
        val gallery = listOf(media("one"), media("two"), media("three"))
        val item = MediaFeedItem(
            postId = "gallery",
            author = "author",
            subreddit = "pics",
            title = "Gallery",
            score = 1,
            commentCount = 2,
            kind = "image",
            media = gallery.first(),
            mediaItems = gallery,
        )

        val restored = requireNotNull(MediaFeedItem.fromPreview(item.toTransitionPreview()))

        assertEquals(listOf("one", "two", "three"), restored.allMedia.map { it.mediaId })
        assertEquals("one", restored.media.mediaId)
    }

    private fun media(id: String) = MediaFeedMedia(
        mediaId = id,
        placeholderColor = 0xff000000,
        aspectRatio = 1f,
        isVideo = false,
        url = "https://example.test/$id.jpg",
        zoomUrl = "https://example.test/$id-detail.jpg",
        cacheKey = "image:$id",
    )
}
