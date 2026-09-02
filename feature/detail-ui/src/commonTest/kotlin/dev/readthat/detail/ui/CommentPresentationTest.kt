package dev.readthat.detail.ui

import dev.readthat.comments.domain.CommentRow
import dev.readthat.comments.domain.CommentSort
import kotlin.test.Test
import kotlin.test.assertEquals

class CommentPresentationTest {
    @Test
    fun collapsedCountLabelIsConciseAndHandlesPluralization() {
        assertEquals(null, collapsedCommentCountLabel(0))
        assertEquals("Show 1 hidden reply", collapsedCommentCountLabel(1))
        assertEquals("Show 15 hidden replies", collapsedCommentCountLabel(15))
    }

    @Test
    fun expansionDescendantsStopAtNextSibling() {
        val rows = listOf(
            comment("root", 0),
            comment("child", 1),
            comment("grandchild", 2),
            comment("sibling", 0),
        )

        assertEquals(setOf("child", "grandchild"), expansionDescendantKeys(rows, "root"))
    }

    @Test
    fun nextRootAccountsForHeaderAndSkipsCurrentSubtree() {
        val rows = listOf(
            comment("root-a", 0),
            comment("child", 1),
            comment("root-b", 0),
        )

        assertEquals(1, nextRootCommentListIndex(rows, 0))
        assertEquals(3, nextRootCommentListIndex(rows, 1))
        assertEquals(3, nextRootCommentListIndex(rows, 2))
    }

    @Test
    fun reshareCommunityNormalizationAcceptsDrawerAndBareNames() {
        assertEquals("kotlin", normalizedReshareCommunity("  r/kotlin  "))
        assertEquals("compose", normalizedReshareCommunity("compose"))
    }

    @Test
    fun sortLabelsCoverEveryServerAuthoredOrder() {
        assertEquals(
            listOf("Best", "Top", "Q&A", "Controversial", "New", "Old"),
            CommentSort.entries.map { it.label },
        )
    }

    @Test
    fun commentSearchMatchesBodyAndBothAuthorRepresentations() {
        val rows = listOf(
            comment("first", 0, body = "Compose on iOS"),
            comment("second", 0, author = "space_beth"),
            comment("third", 0, displayName = "Bird Person"),
        )

        assertEquals(listOf(0), commentSearchMatchIndices(rows, "compose"))
        assertEquals(listOf(1), commentSearchMatchIndices(rows, "BETH"))
        assertEquals(listOf(2), commentSearchMatchIndices(rows, "bird"))
        assertEquals(emptyList(), commentSearchMatchIndices(rows, "   "))
    }

    private fun comment(
        key: String,
        depth: Int,
        score: Int = 1,
        ageMinutes: Int = 1,
        author: String = key,
        displayName: String = key,
        body: String = key,
    ) = CommentRow.Comment(
        key = key,
        renderDepth = depth,
        author = author,
        authorDisplayName = displayName,
        authorAvatarUrl = null,
        body = body,
        score = score,
        scoreLabel = score.toString(),
        createdAgoMinutes = ageMinutes,
        ageLabel = "${ageMinutes}m",
        isEdited = false,
        isCollapsed = false,
        hasChildren = false,
        collapsedDescendants = 0,
    )
}
