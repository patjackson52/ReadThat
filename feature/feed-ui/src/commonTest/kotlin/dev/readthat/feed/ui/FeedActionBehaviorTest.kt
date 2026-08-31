package dev.readthat.feed.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FeedActionBehaviorTest {
    @Test
    fun repeatedVoteTogglesOffAndOppositeVoteReplacesIt() {
        assertEquals(0, toggledVote(currentVote = 1, requestedVote = 1))
        assertEquals(0, toggledVote(currentVote = -1, requestedVote = -1))
        assertEquals(-1, toggledVote(currentVote = 1, requestedVote = -1))
        assertEquals(1, toggledVote(currentVote = -1, requestedVote = 1))
    }

    @Test
    fun invalidVoteValuesAreRejectedAtTheSharedUiBoundary() {
        assertFailsWith<IllegalArgumentException> { toggledVote(2, 1) }
        assertFailsWith<IllegalArgumentException> { toggledVote(0, 0) }
    }
}
