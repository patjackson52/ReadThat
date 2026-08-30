package dev.readthat.mediafeed

import androidx.paging.testing.asSnapshot
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.readthat.mediafeed.data.MediaFeedRemoteSource
import dev.readthat.mediafeed.data.MediaFeedRepository
import dev.readthat.mediafeed.data.MediaFeedScope
import dev.readthat.mediafeed.domain.MediaFeedItem
import dev.readthat.mediafeed.domain.MediaFeedLaunchContext
import dev.readthat.mediafeed.domain.MediaFeedMedia
import dev.readthat.mediafeed.domain.MediaFeedPage
import dev.readthat.data.db.AppDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaFeedRepositoryTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `normal feed snapshot seeds exact order and resumes from translated cursor`() = runTest {
        val remoteCursors = mutableListOf<String?>()
        val remote = object : MediaFeedRemoteSource {
            override suspend fun loadPage(cursor: String?, anchorPostId: String?, subreddit: String?): MediaFeedPage {
                remoteCursors += cursor
                assertEquals("ranked-feed-v1:next", cursor)
                assertEquals(null, anchorPostId)
                return MediaFeedPage(listOf(item("network-after")), null, 1, anchorIncluded = false)
            }
        }
        val launch = MediaFeedLaunchContext(
            snapshotId = "snapshot",
            sourceFeedId = "feed:home",
            anchorPostId = "anchor",
            items = listOf(item("before"), item("anchor"), item("after")),
            anchorIndex = 1,
            continuationCursor = "ranked-feed-v1:next",
        )
        val scope = MediaFeedScope("anchor", snapshotId = launch.snapshotId)
        val repository = MediaFeedRepository(
            db = db,
            remote = remote,
            scope = scope,
            launchContext = launch,
            accountId = "viewer",
        )

        val loaded = repository.feed().asSnapshot { scrollTo(2) }

        assertEquals(1, repository.initialPage)
        assertEquals(listOf("before", "anchor", "after", "network-after"), loaded.map { it.postId })
        assertEquals(
            listOf("before", "anchor", "after", "network-after"),
            db.mediaFeedDao().postIds("viewer", scope.databaseId),
        )
        assertEquals(null, db.mediaFeedDao().remoteKey("viewer", scope.databaseId)?.nextCursor)
        assertEquals(listOf("ranked-feed-v1:next"), remoteCursors)
    }

    private fun item(id: String) = MediaFeedItem(
        postId = id,
        author = "author",
        subreddit = "pics",
        title = id,
        score = 1,
        commentCount = 2,
        kind = "image",
        media = MediaFeedMedia(
            placeholderColor = 0xff000000,
            aspectRatio = 1f,
            isVideo = false,
            url = "https://example.test/$id.jpg",
        ),
    )
}
