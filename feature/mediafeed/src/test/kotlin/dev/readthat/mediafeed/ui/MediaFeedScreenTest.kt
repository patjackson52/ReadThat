package dev.readthat.mediafeed.ui

import dev.readthat.mediafeed.domain.MediaFeedItem
import dev.readthat.mediafeed.domain.MediaFeedMedia
import dev.readthat.shared.videoPosterCacheKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaFeedScreenTest {
    @Test
    fun `empty paging snapshot returns the matching navigation item`() {
        val before = item("before")
        val anchor = item("anchor")

        assertEquals(anchor, mediaFeedItemAt(null, listOf(before, anchor), 1))
    }

    @Test
    fun `paging snapshot replaces navigation seed when available`() {
        val roomItem = item("room")

        assertEquals(roomItem, mediaFeedItemAt(roomItem, listOf(item("seed")), 0))
    }

    @Test
    fun `empty snapshot without seed remains a safe placeholder`() {
        assertNull(mediaFeedItemAt(null, emptyList(), 0))
        assertNull(mediaFeedItemAt(null, listOf(item("seed")), 1))
    }

    @Test
    fun `video preload ranks remain stable as the current page advances`() {
        val pages = listOf(item("image-0"), video("video-1"), item("image-2"), video("video-3"))

        val beforeFirst = mediaFeedVideoPreloadPlan(pages.size, 0, pages::get)
        val betweenVideos = mediaFeedVideoPreloadPlan(pages.size, 2, pages::get)

        assertEquals(listOf("video:video-1", "video:video-3"), beforeFirst?.sources?.map { it.cacheKey })
        assertEquals(0, beforeFirst?.focusIndex)
        assertEquals(listOf("video:video-1", "video:video-3"), betweenVideos?.sources?.map { it.cacheKey })
        assertEquals(1, betweenVideos?.focusIndex)
    }

    @Test
    fun `video poster renderer consumes the exact prefetched cache key`() {
        val media = video("poster-contract").media

        assertEquals(
            videoPosterCacheKey("video:poster-contract", media.posterUrl),
            media.prefetchKey("poster-contract"),
        )
    }

    private fun item(id: String) = MediaFeedItem(
        postId = id,
        subreddit = "android",
        author = "tester",
        title = id,
        body = null,
        score = 1,
        commentCount = 0,
        viewerVote = 0,
        kind = "image",
        postedAgo = "now",
        media = MediaFeedMedia(
            mediaId = "media-$id",
            placeholderColor = 0xFF000000,
            aspectRatio = 1f,
            isVideo = false,
            url = "https://example.test/$id.jpg",
        ),
    )

    private fun video(id: String) = MediaFeedItem(
        postId = id,
        subreddit = "videos",
        author = "tester",
        title = id,
        body = null,
        score = 1,
        commentCount = 0,
        viewerVote = 0,
        kind = "video",
        postedAgo = "now",
        media = MediaFeedMedia(
            mediaId = "media-$id",
            placeholderColor = 0xFF000000,
            aspectRatio = 1f,
            isVideo = true,
            posterUrl = "https://example.test/$id-poster.jpg",
            hlsUrl = "https://example.test/$id.m3u8",
            cacheKey = "video:$id",
        ),
    )
}
