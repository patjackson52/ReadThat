package dev.readthat.navigation

import dev.readthat.domain.AdLaunchContext
import dev.readthat.domain.AdMediaKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NavigationSnapshotCodecTest {
    @Test
    fun everyDestinationAndHistoryFieldRoundTrips() {
        val destinations = listOf(
            AppDestination.Feed,
            AppDestination.Activity,
            AppDestination.Search,
            AppDestination.Communities,
            AppDestination.CreatePost("Kotlin"),
            AppDestination.CreateCommunity,
            AppDestination.Profile,
            AppDestination.Settings,
            AppDestination.EditProfile,
            AppDestination.PostDetail("post", focusCommentId = "comment"),
            AppDestination.PostDetail("post", rootCommentId = "thread"),
            AppDestination.Community("kotlin"),
            AppDestination.Media("post", "kotlin", "snapshot"),
            AppDestination.PublicProfile("reader"),
            AppDestination.PendingPost("mutation-post"),
            AppDestination.PendingCommunity("mutation-community"),
        )
        val current = AppDestination.AdDetail(AdLaunchContext(
            adId = "ad",
            creativeId = "creative",
            kind = AdMediaKind.Video,
            placeholderColor = 0xff112233,
            aspectRatio = 16f / 9f,
            altText = "Promoted video",
            imageUrl = null,
            hlsUrl = "https://cdn.test/video.m3u8?signature=rotated",
            posterUrl = "https://cdn.test/poster.jpg",
            fallbackUrl = "https://cdn.test/video.mp4",
            cacheKey = "ad:creative",
            destinationUrl = "https://advertiser.test/landing",
            displayDomain = "advertiser.test",
            ctaLabel = "Learn more",
            selectedIndex = 2,
            restartAtBeginning = true,
        ))
        val snapshot = NavigationSnapshot(current, destinations)

        assertEquals(snapshot, NavigationSnapshotCodec.decode(NavigationSnapshotCodec.encode(snapshot)))
    }

    @Test
    fun corruptFutureAndUnknownCurrentSnapshotsFailClosed() {
        assertNull(NavigationSnapshotCodec.decode("not-json"))
        assertNull(NavigationSnapshotCodec.decode(
            """{"version":2,"current":{"type":"feed"},"history":[]}""",
        ))
        assertNull(NavigationSnapshotCodec.decode(
            """{"version":1,"current":{"type":"future"},"history":[]}""",
        ))
        assertNull(NavigationSnapshotCodec.decode("x".repeat(NavigationSnapshotCodec.MAX_ENCODED_CHARS + 1)))
    }

    @Test
    fun unknownHistoryEntriesAreSkippedAndHistoryIsBounded() {
        val encoded = NavigationSnapshotCodec.encode(NavigationSnapshot(
            current = AppDestination.PostDetail("current"),
            history = (0..40).map { AppDestination.PostDetail("post-$it") },
        ))
        val decoded = requireNotNull(NavigationSnapshotCodec.decode(encoded))

        assertEquals(NavigationSnapshotCodec.MAX_HISTORY_DEPTH, decoded.history.size)
        assertEquals(AppDestination.PostDetail("post-9"), decoded.history.first())
        assertEquals(AppDestination.PostDetail("post-40"), decoded.history.last())

        val withUnknownHistory =
            """{"version":1,"current":{"type":"feed"},"history":[{"type":"future"},{"type":"search"}]}"""
        assertEquals(
            NavigationSnapshot(AppDestination.Feed, listOf(AppDestination.Search)),
            NavigationSnapshotCodec.decode(withUnknownHistory),
        )
    }

    @Test
    fun oversizedCurrentDestinationFallsBackToRootSnapshot() {
        val encoded = NavigationSnapshotCodec.encode(NavigationSnapshot(
            AppDestination.CreatePost("x".repeat(NavigationSnapshotCodec.MAX_ENCODED_CHARS)),
        ))

        assertEquals(
            NavigationSnapshot(AppDestination.Feed),
            NavigationSnapshotCodec.decode(encoded),
        )

        val invalidButSmall = NavigationSnapshotCodec.encode(NavigationSnapshot(
            AppDestination.CreatePost("x".repeat(300)),
        ))
        assertEquals(
            NavigationSnapshot(AppDestination.Feed),
            NavigationSnapshotCodec.decode(invalidButSmall),
        )
    }
}
