package dev.readthat.community.ui

import dev.readthat.client.SharedCommunityDetailState
import dev.readthat.communitydetail.domain.CommunityDetail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedCommunityDetailTest {
    @Test
    fun successfulRenderWaitsForAuthoritativeTier() {
        assertNull(communityDetailInitialRenderTier(
            state = SharedCommunityDetailState(),
            hasFeedContent = true,
            feedLoadFailed = false,
        ))
        assertEquals("network", communityDetailInitialRenderTier(
            state = SharedCommunityDetailState(
                detail = community(),
                initialCacheTier = "network",
            ),
            hasFeedContent = true,
            feedLoadFailed = false,
        ))
    }

    @Test
    fun cachedOfflineFeedAndTerminalErrorHaveDistinctTiers() {
        assertEquals("room_offline", communityDetailInitialRenderTier(
            state = SharedCommunityDetailState(offline = true),
            hasFeedContent = true,
            feedLoadFailed = true,
        ))
        assertEquals("error_state", communityDetailInitialRenderTier(
            state = SharedCommunityDetailState(refreshing = false, error = "offline"),
            hasFeedContent = false,
            feedLoadFailed = true,
        ))
    }

    @Test
    fun roomProvenanceWinsOverConcurrentRefreshFailure() {
        assertEquals("room", communityDetailInitialRenderTier(
            state = SharedCommunityDetailState(
                detail = community(),
                initialCacheTier = "room",
                offline = true,
            ),
            hasFeedContent = true,
            feedLoadFailed = true,
        ))
    }

    private fun community() = CommunityDetail(
        id = "community-1",
        name = "kmp",
        displayName = "KMP",
        description = "Shared Kotlin",
        accessType = "public",
        viewerRole = "subscriber",
        subscriberCount = 1,
        rules = emptyList(),
        avatarUrl = null,
        updatedAt = 0,
    )
}
