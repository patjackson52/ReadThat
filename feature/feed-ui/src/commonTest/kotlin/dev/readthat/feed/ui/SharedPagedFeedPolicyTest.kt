package dev.readthat.feed.ui

import dev.readthat.domain.AdMediaItemUi
import dev.readthat.domain.AdMediaKind
import dev.readthat.domain.CellUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedPagedFeedPolicyTest {
    @Test
    fun leadingDestinationChromeDoesNotShiftMediaPrefetchIndex() {
        assertEquals(0, feedContentIndex(firstVisibleItemIndex = 0, hasListHeader = true))
        assertEquals(0, feedContentIndex(firstVisibleItemIndex = 1, hasListHeader = true))
        assertEquals(4, feedContentIndex(firstVisibleItemIndex = 5, hasListHeader = true))
        assertEquals(5, feedContentIndex(firstVisibleItemIndex = 5, hasListHeader = false))
    }

    @Test
    fun communityHeaderUsesOneCrossPlatformCountPolicy() {
        assertEquals("999", formatCommunityCount(999))
        assertEquals("1K", formatCommunityCount(1_000))
        assertEquals("12.5K", formatCommunityCount(12_500))
        assertEquals("1.3M", formatCommunityCount(1_250_000))
    }

    @Test
    fun autoplayChoosesMostlyVisibleVideoClosestToCenter() {
        val selected = selectSharedFeedVideo(
            visibleItems = listOf(
                SharedFeedVisibleItem("post-a/media", -20, 120),
                SharedFeedVisibleItem("post-b/media", 100, 160),
                SharedFeedVisibleItem("post-c/title", 260, 80),
            ),
            videoCellKeys = setOf("post-a/media", "post-b/media"),
            viewportStart = 0,
            viewportEnd = 320,
        )

        assertEquals("post-b/media", selected)
    }

    @Test
    fun autoplayPrefersGreaterVisibleAreaBeforeCenterDistance() {
        assertEquals(
            "full/media",
            selectSharedFeedVideo(
                visibleItems = listOf(
                    SharedFeedVisibleItem("center/media", 120, 100),
                    SharedFeedVisibleItem("full/media", 220, 180),
                ),
                videoCellKeys = setOf("center/media", "full/media"),
                viewportStart = 0,
                viewportEnd = 420,
            ),
        )
    }

    @Test
    fun autoplayRejectsVideoBelowHalfVisibility() {
        assertNull(
            selectSharedFeedVideo(
                visibleItems = listOf(SharedFeedVisibleItem("post/media", 280, 100)),
                videoCellKeys = setOf("post/media"),
                viewportStart = 0,
                viewportEnd = 320,
            ),
        )
    }

    @Test
    fun impressionsUseContentAndPromotedMediaButNotActionsOrChrome() {
        assertEquals(
            setOf("post-a", "ad-a"),
            settledVisibleFeedGroups(
                visibleItems = listOf(
                    SharedFeedVisibleItem("__feed_header__", 0, 40),
                    SharedFeedVisibleItem("post-a/title", 40, 100),
                    SharedFeedVisibleItem("post-a/actions", 140, 60),
                    SharedFeedVisibleItem("ad-a/header", 200, 40),
                    SharedFeedVisibleItem("ad-a/media", 240, 160),
                ),
                viewportStart = 0,
                viewportEnd = 400,
                promotedGroupIds = setOf("ad-a"),
            ),
        )
    }

    @Test
    fun feedAndPromotedVideosShareOneAutoplayKeyPolicy() {
        val still = CellUi.Media(
            key = "post-image/media",
            placeholderColor = 0L,
            aspectRatio = 1f,
            altText = "still",
            durationLabel = null,
        )
        val video = still.copy(
            key = "post-video/media",
            video = CellUi.VideoPlaybackUi(
                hlsUrl = "https://cdn.example/video.m3u8",
                dashUrl = null,
                posterUrl = null,
                fallbackUrl = null,
                deliveryStatus = "ready",
                processingProgress = 100,
            ),
        )
        val promotedVideo = CellUi.AdMedia(
            key = "ad-video/media",
            adId = "ad-video",
            items = listOf(
                AdMediaItemUi(
                    creativeId = "creative-video",
                    kind = AdMediaKind.Video,
                    placeholderColor = 0L,
                    aspectRatio = 16f / 9f,
                    altText = "promoted video",
                    imageUrl = null,
                    hlsUrl = "https://cdn.example/ad.m3u8",
                    dashUrl = null,
                    posterUrl = null,
                    fallbackUrl = null,
                    durationSeconds = 10,
                    cacheKey = "creative-video",
                ),
            ),
            destinationUrl = "https://example.com",
            displayDomain = "example.com",
            ctaLabel = "Learn more",
        )

        assertEquals(
            setOf("post-video/media", "ad-video/media"),
            listOf(still, video, promotedVideo).feedVideoCellKeys(),
        )
    }
}
