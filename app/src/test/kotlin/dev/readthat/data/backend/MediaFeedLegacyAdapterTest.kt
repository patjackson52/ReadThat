package dev.readthat.data.backend

import dev.readthat.domain.WireCell
import dev.readthat.domain.WireGroup
import dev.readthat.domain.WireImageItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFeedLegacyAdapterTest {
    private val metadata = WireCell.Metadata(
        cellId = "meta",
        subreddit = "photos",
        postedAgo = "2m",
        author = "camera_user",
        createdAt = 123,
        avatarUrl = "https://example.test/photos.png",
    )
    private val title = WireCell.Title("title", "A photo")
    private val actions = WireCell.ActionBar("actions", 42, 7, vote = -1)

    @Test
    fun `image SDUI group projects to a typed media item`() {
        val item = WireGroup(
            groupId = "post-1",
            cells = listOf(
                metadata,
                title,
                WireCell.Text("body", "caption"),
                WireCell.Image("media", 0xff123456, 1.5f, "alt", "https://image", "image:key"),
                actions,
            ),
        ).toMediaFeedItemOrNull()

        requireNotNull(item)
        assertEquals("post-1", item.postId)
        assertEquals("camera_user", item.author)
        assertEquals("photos", item.subreddit)
        assertEquals("https://example.test/photos.png", item.communityAvatarUrl)
        assertEquals("caption", item.body)
        assertEquals(42, item.score)
        assertEquals(7, item.commentCount)
        assertEquals(-1, item.viewerVote)
        assertFalse(item.media.isVideo)
        assertEquals("https://image", item.media.zoomUrl)
        assertEquals("image:key", item.media.cacheKey)
    }

    @Test
    fun `video SDUI group retains adaptive playback fields`() {
        val item = WireGroup(
            groupId = "post-2",
            cells = listOf(
                metadata,
                title,
                WireCell.Video(
                    cellId = "media",
                    placeholderColor = 0xff000000,
                    aspectRatio = 9f / 16f,
                    durationSeconds = 12,
                    altText = "clip",
                    hlsUrl = "https://hls",
                    dashUrl = "https://dash",
                    posterUrl = "https://poster",
                    deliveryStatus = "ready",
                    cacheKey = "video:key",
                ),
                actions,
            ),
        ).toMediaFeedItemOrNull()

        requireNotNull(item)
        assertTrue(item.media.isVideo)
        assertEquals("https://hls", item.media.hlsUrl)
        assertEquals("https://dash", item.media.dashUrl)
        assertEquals("https://poster", item.media.posterUrl)
        assertEquals("video:key", item.media.cacheKey)
    }

    @Test
    fun `gallery SDUI group projects every photo in order`() {
        val item = WireGroup(
            groupId = "post-gallery",
            cells = listOf(
                metadata,
                title,
                WireCell.ImageCarousel(
                    "media",
                    listOf(
                        WireImageItem("one", 0xff000000, 1f, "one", "https://one", cacheKey = "image:one"),
                        WireImageItem("two", 0xff111111, 1.5f, "two", "https://two", cacheKey = "image:two"),
                    ),
                ),
                actions,
            ),
        ).toMediaFeedItemOrNull()

        requireNotNull(item)
        assertEquals(listOf("one", "two"), item.allMedia.map { it.mediaId })
        assertEquals("one", item.media.mediaId)
        assertEquals("https://two", item.allMedia[1].zoomUrl)
    }

    @Test
    fun `non media SDUI group is ignored`() {
        val item = WireGroup(
            groupId = "post-3",
            cells = listOf(metadata, title, WireCell.Text("body", "text only"), actions),
        ).toMediaFeedItemOrNull()

        assertNull(item)
    }
}
