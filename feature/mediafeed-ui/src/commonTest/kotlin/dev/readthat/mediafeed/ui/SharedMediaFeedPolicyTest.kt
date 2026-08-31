package dev.readthat.mediafeed.ui

import androidx.compose.ui.geometry.Offset
import dev.readthat.mediafeed.domain.MediaFeedItem
import dev.readthat.mediafeed.domain.MediaFeedMedia
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedMediaFeedPolicyTest {
    @Test
    fun preloadPlanIncludesGalleryVideosAndFocusesSelectedMedia() {
        val first = item(
            "first",
            listOf(image("first-image"), video("first-video")),
        )
        val second = item("second", listOf(video("second-video")))

        val plan = mediaFeedPreloadPlan(
            items = listOf(first, second),
            currentPage = 0,
            selectedMedia = mapOf("first" to 1),
        )

        assertEquals(listOf("first-video", "second-video"), plan.videos.map { it.media.mediaId })
        assertEquals(0, plan.videoFocusIndex)
        assertEquals(
            setOf(
                first.allMedia[0].mediaFeedImageCacheKey(first.postId),
                first.allMedia[1].mediaFeedImageCacheKey(first.postId),
                second.allMedia[0].mediaFeedImageCacheKey(second.postId),
            ),
            plan.images.mapTo(linkedSetOf(), MediaFeedImagePreloadRequest::cacheKey),
        )
    }

    @Test
    fun imagePreloadWindowIsBoundedAroundCurrentPageAndDeduplicated() {
        val shared = image("shared")
        val items = (0..6).map { page -> item("post-$page", listOf(if (page in 2..4) shared else image("i-$page"))) }

        val plan = mediaFeedPreloadPlan(items, currentPage = 3, selectedMedia = emptyMap())

        assertEquals(setOf("image:shared", "image:i-5"), plan.images.mapTo(linkedSetOf()) { it.cacheKey })
    }

    @Test
    fun zoomOffsetIsClampedAndResetsAtOneX() {
        assertEquals(Offset.Zero, constrainMediaOffset(Offset(30f, 40f), 1f, 100, 200))
        assertEquals(Offset(50f, -100f), constrainMediaOffset(Offset(90f, -140f), 2f, 100, 200))
        assertTrue(constrainMediaOffset(Offset(1f, 1f), 2f, 0, 200) == Offset.Zero)
    }

    @Test
    fun playbackControlReflectsNativeStateAndExplicitIntent() {
        assertEquals(
            MediaFeedPrimaryPlaybackAction.Pause,
            mediaFeedPrimaryPlaybackAction(MediaFeedPlaybackState.Playing, playRequested = true),
        )
        assertEquals(
            MediaFeedPrimaryPlaybackAction.Play,
            mediaFeedPrimaryPlaybackAction(MediaFeedPlaybackState.Paused, playRequested = true),
        )
        assertEquals(
            MediaFeedPrimaryPlaybackAction.Play,
            mediaFeedPrimaryPlaybackAction(MediaFeedPlaybackState.Playing, playRequested = false),
        )
        assertEquals(
            MediaFeedPrimaryPlaybackAction.Replay,
            mediaFeedPrimaryPlaybackAction(MediaFeedPlaybackState.Ended, playRequested = true),
        )
        assertEquals(
            MediaFeedPrimaryPlaybackAction.Replay,
            mediaFeedPrimaryPlaybackAction(MediaFeedPlaybackState.Error, playRequested = false),
        )
    }

    @Test
    fun playbackScrubberClampsProgressAndSeekTargets() {
        assertEquals(0f, mediaFeedPlaybackFraction(-1L, 10_000L))
        assertEquals(.25f, mediaFeedPlaybackFraction(2_500L, 10_000L))
        assertEquals(1f, mediaFeedPlaybackFraction(20_000L, 10_000L))
        assertEquals(0f, mediaFeedPlaybackFraction(2_500L, null))

        assertEquals(0L, mediaFeedSeekPosition(-.5f, 10_000L))
        assertEquals(2_500L, mediaFeedSeekPosition(.25f, 10_000L))
        assertEquals(10_000L, mediaFeedSeekPosition(2f, 10_000L))
    }

    @Test
    fun playbackTimeUsesCompactElapsedFormat() {
        assertEquals("0:00", mediaFeedTimeLabel(-100L))
        assertEquals("0:03", mediaFeedTimeLabel(3_400L))
        assertEquals("1:05", mediaFeedTimeLabel(65_000L))
        assertEquals("1:02:03", mediaFeedTimeLabel(3_723_000L))
    }

    @Test
    fun interactionTelemetryUsesUniqueBoundedValues() {
        val values = MediaFeedInteraction.entries.map(MediaFeedInteraction::telemetryValue)

        assertEquals(values.size, values.toSet().size)
        assertFalse(values.any { it.length > 24 || it.any(Char::isUpperCase) })
        MediaFeedInteraction.entries.forEach { interaction ->
            assertEquals(
                mapOf("interaction_type" to interaction.telemetryValue),
                interaction.telemetryAttributes(),
            )
        }
    }

    private fun item(id: String, media: List<MediaFeedMedia>) = MediaFeedItem(
        postId = id,
        author = "reader",
        subreddit = "pics",
        title = id,
        score = 1,
        commentCount = 2,
        kind = if (media.first().isVideo) "video" else "image",
        media = media.first(),
        mediaItems = media,
    )

    private fun image(id: String) = MediaFeedMedia(
        mediaId = id,
        placeholderColor = 0xff000000,
        aspectRatio = 1f,
        isVideo = false,
        url = "https://example.test/$id.jpg",
        cacheKey = "image:$id",
    )

    private fun video(id: String) = MediaFeedMedia(
        mediaId = id,
        placeholderColor = 0xff000000,
        aspectRatio = 1f,
        isVideo = true,
        hlsUrl = "https://example.test/$id.m3u8",
        posterUrl = "https://example.test/$id-poster.jpg",
        cacheKey = "video:$id",
    )
}
