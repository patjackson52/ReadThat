package dev.readthat.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DestinationHistoryTest {
    @Test
    fun nestedDestinationsPopInReverseOrder() {
        val history = DestinationHistory()
        val search = AppDestination.Search
        val detail = AppDestination.PostDetail("post", focusCommentId = "comment")
        val thread = AppDestination.PostDetail("post", rootCommentId = "parent")

        history.record(AppDestination.Feed, search)
        history.record(search, detail)
        history.record(detail, thread)

        assertEquals(detail, history.popOrFeed())
        assertEquals(search, history.popOrFeed())
        assertEquals(AppDestination.Feed, history.popOrFeed())
        assertEquals(AppDestination.Feed, history.popOrFeed())
    }

    @Test
    fun rootNavigationClearsNestedHistory() {
        val history = DestinationHistory()
        history.record(AppDestination.Feed, AppDestination.Search)
        history.record(AppDestination.Search, AppDestination.PostDetail("post"))
        history.record(AppDestination.PostDetail("post"), AppDestination.Profile)

        assertEquals(AppDestination.Feed, history.popOrFeed())
    }

    @Test
    fun historyIsBounded() {
        val history = DestinationHistory(maxDepth = 2)
        history.record(AppDestination.Feed, AppDestination.Search)
        history.record(AppDestination.Search, AppDestination.Communities)
        history.record(AppDestination.Communities, AppDestination.CreatePost())

        assertEquals(AppDestination.Communities, history.popOrFeed())
        assertEquals(AppDestination.Search, history.popOrFeed())
        assertEquals(AppDestination.Feed, history.popOrFeed())
    }

    @Test
    fun restoredHistoryKeepsNewestBoundedEntriesAndExactBackOrder() {
        val history = DestinationHistory(maxDepth = 2)
        history.restore(listOf(
            AppDestination.Search,
            AppDestination.Communities,
            AppDestination.Community("kotlin"),
        ))

        assertEquals(
            listOf(AppDestination.Communities, AppDestination.Community("kotlin")),
            history.snapshot(),
        )
        assertEquals(AppDestination.Community("kotlin"), history.popOrFeed())
        assertEquals(AppDestination.Communities, history.popOrFeed())
    }

    @Test
    fun nonPositiveDepthIsRejected() {
        assertFailsWith<IllegalArgumentException> { DestinationHistory(0) }
    }

    @Test
    fun postDestinationCannotEncodeTwoConflictingCommentModes() {
        assertFailsWith<IllegalArgumentException> {
            AppDestination.PostDetail(
                postId = "post",
                focusCommentId = "focus",
                rootCommentId = "thread",
            )
        }
    }
}
