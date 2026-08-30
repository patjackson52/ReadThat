package dev.readthat

import dev.readthat.data.FakeFeedApi
import dev.readthat.domain.WireCell
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The coherence contract between the two fakes: for every post the feed emits,
 * headerFor(postId) must report the SAME title, subreddit, score, and comment
 * count the wire cells carry. If this drifts, the detail screen contradicts the
 * feed the user just tapped.
 */
class FeedCatalogCoherenceTest {

    @Test
    fun `headerFor matches every post on every page`() = runTest {
        val api = FakeFeedApi(latencyMs = 0)
        var cursor: String? = null
        var checked = 0
        do {
            val page = api.loadPage(cursor)
            for (group in page.groups) {
                if (!group.groupId.startsWith("post_")) continue
                val header = FakeFeedApi.headerFor(group.groupId)!!
                val title = group.cells.filterIsInstance<WireCell.Title>().single()
                val meta = group.cells.filterIsInstance<WireCell.Metadata>().single()
                val actions = group.cells.filterIsInstance<WireCell.ActionBar>().single()
                assertEquals(title.text, header.title)
                assertEquals("r/${meta.subreddit}", header.subreddit)
                assertEquals(actions.score, header.score)
                assertEquals(actions.commentCount, header.commentCount)
                // CONTENT coherence: the detail page shows the post itself, so the
                // catalog must carry the same body / media the feed cells describe.
                val bodyCell = group.cells.filterIsInstance<WireCell.Text>().firstOrNull()
                assertEquals(bodyCell?.body, header.body)
                val image = group.cells.filterIsInstance<WireCell.Image>().firstOrNull()
                val video = group.cells.filterIsInstance<WireCell.Video>().firstOrNull()
                when {
                    image != null -> {
                        assertEquals(image.placeholderColor, header.media?.placeholderColor)
                        assertEquals(false, header.media?.isVideo)
                    }
                    video != null -> {
                        assertEquals(video.placeholderColor, header.media?.placeholderColor)
                        assertEquals(true, header.media?.isVideo)
                        assertEquals(video.durationSeconds, header.media?.durationSeconds)
                    }
                    else -> assertEquals(null, header.media)
                }
                checked++
            }
            cursor = page.nextCursor
        } while (cursor != null)
        assertEquals(30, checked)
    }
}
