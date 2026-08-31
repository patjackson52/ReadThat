package dev.readthat.client

import dev.readthat.domain.NormalFeedMediaContext
import dev.readthat.mediafeed.domain.MediaFeedItem
import dev.readthat.mediafeed.domain.MediaFeedMedia
import dev.readthat.shared.PostMedia
import dev.readthat.shared.PostTransitionPreview
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedMediaFeedPolicyTest {
    @Test
    fun scopeIdentityNormalizesCommunityAndSeparatesSnapshots() {
        assertEquals(
            "media:home:anchor:post-1",
            SharedMediaFeedScope("post-1").databaseId,
        )
        assertEquals(
            "media:subreddit:kotlin:anchor:post-1:snapshot:generation-2",
            SharedMediaFeedScope("post-1", "r/Kotlin", "generation-2").databaseId,
        )
    }

    @Test
    fun normalFeedHandoffPreservesMediaRankAndTranslatesCursor() {
        val context = NormalFeedMediaContext(
            snapshotId = "snapshot",
            sourceFeedId = "feed:home",
            anchorPostId = "anchor",
            items = listOf(
                preview("before", media = true),
                preview("anchor", media = true),
                preview("after", media = true),
            ),
            anchorIndex = 1,
            nextFeedCursor = "ranked-next",
        ).toSharedMediaFeedLaunchContext()

        assertEquals(listOf("before", "anchor", "after"), context.items.map(MediaFeedItem::postId))
        assertEquals(1, context.anchorIndex)
        assertEquals("ranked-feed-v1:ranked-next", context.continuationCursor)
    }

    @Test
    fun firstRenderCacheTierCannotBeRelabeledByBackgroundRefresh() {
        val tier = SharedMediaFeedCacheTier()

        tier.record("room")
        tier.record("network")

        assertEquals("room", tier.value.value)
    }

    @Test
    fun navigationSeedIsAvailableBeforePagingHydrates() {
        val tier = SharedMediaFeedCacheTier("navigation_seed")

        tier.record("room")

        assertEquals("navigation_seed", tier.value.value)
    }

    private fun preview(id: String, media: Boolean) = PostTransitionPreview(
        postId = id,
        title = "title-$id",
        author = "reader",
        subreddit = "pics",
        score = 3,
        commentCount = 2,
        media = if (media) PostMedia(
            placeholderColor = 0xff000000,
            aspectRatio = 1f,
            isVideo = false,
            url = "https://example.test/$id.jpg",
            cacheKey = "image:$id",
        ) else null,
    )
}
