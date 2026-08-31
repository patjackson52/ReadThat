package dev.readthat.data.sync

import dev.readthat.domain.WireCell
import dev.readthat.domain.WireFeedPage
import dev.readthat.domain.WireGroup
import dev.readthat.shared.BackgroundImagePrefetchRequest
import dev.readthat.shared.BackgroundVideoPrefetchRequest
import dev.readthat.shared.backgroundFeedMediaPlan
import dev.readthat.shared.videoPosterCacheKey
import org.junit.Assert.assertEquals
import org.junit.Test

class FeedWorkerVideoPrefetchTest {
    @Test
    fun `periodic refresh selects one startup video with stable feed-detail key`() {
        val page = WireFeedPage(
            groups = listOf(
                WireGroup("text", listOf(WireCell.Text("body", "hello"))),
                WireGroup("video-1", listOf(video("https://cdn/one.m3u8"))),
                WireGroup("video-2", listOf(video("https://cdn/two.m3u8"))),
            ),
            nextCursor = null,
        )

        val plan = page.backgroundFeedMediaPlan()

        assertEquals(
            listOf(BackgroundVideoPrefetchRequest(
                "https://cdn/one.m3u8",
                "https://cdn/one.m3u8",
                "post:video-1",
            )),
            plan.videos,
        )
        assertEquals(
            listOf(
                BackgroundImagePrefetchRequest(
                    "https://cdn/one.jpg",
                    videoPosterCacheKey("post:video-1", "https://cdn/one.jpg"),
                    true,
                ),
                BackgroundImagePrefetchRequest(
                    "https://cdn/two.jpg",
                    videoPosterCacheKey("post:video-2", "https://cdn/two.jpg"),
                    true,
                ),
            ),
            plan.images,
        )
    }

    private fun video(url: String) = WireCell.Video(
        cellId = "media",
        placeholderColor = 0xff000000,
        aspectRatio = 16f / 9f,
        durationSeconds = 20,
        altText = "video",
        url = url,
        hlsUrl = url,
        posterUrl = url.removeSuffix(".m3u8") + ".jpg",
        deliveryStatus = "ready",
    )
}
