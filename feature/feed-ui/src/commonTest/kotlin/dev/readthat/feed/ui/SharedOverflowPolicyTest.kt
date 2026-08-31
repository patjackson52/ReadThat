package dev.readthat.feed.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedOverflowPolicyTest {
    @Test
    fun postOverflowKeepsAllExistingSharedActionsInStableOrder() {
        assertEquals(
            listOf(
                FeedPostOverflowAction.OpenPost,
                FeedPostOverflowAction.Reshare,
                FeedPostOverflowAction.Share,
                FeedPostOverflowAction.OpenCommunity,
            ),
            feedPostOverflowActions("r/kotlin"),
        )
    }

    @Test
    fun malformedCommunityDoesNotExposeAnUnusableNavigationAction() {
        val actions = feedPostOverflowActions("  r/  ")

        assertFalse(FeedPostOverflowAction.OpenCommunity in actions)
        assertTrue(FeedPostOverflowAction.OpenPost in actions)
        assertTrue(FeedPostOverflowAction.Reshare in actions)
        assertTrue(FeedPostOverflowAction.Share in actions)
    }

    @Test
    fun communityOverflowUsesMembershipCapabilityInsteadOfPlatform() {
        assertEquals(
            listOf(
                CommunityOverflowAction.Refresh,
                CommunityOverflowAction.CreatePost,
                CommunityOverflowAction.ToggleMembership,
            ),
            communityOverflowActions(canChangeMembership = true),
        )
        assertEquals(
            listOf(
                CommunityOverflowAction.Refresh,
                CommunityOverflowAction.CreatePost,
            ),
            communityOverflowActions(canChangeMembership = false),
        )
    }
}
