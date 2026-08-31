package dev.readthat

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import dev.readthat.data.FakeFeedRemoteSource
import dev.readthat.data.FeedRepository
import dev.readthat.data.db.AppDatabase
import dev.readthat.data.db.GroupEntity
import dev.readthat.data.db.ItemStateEntity
import dev.readthat.data.db.RemoteKeyEntity
import dev.readthat.domain.WireCell
import dev.readthat.domain.WireGroup
import dev.readthat.domain.CellUi
import dev.readthat.domain.ImageMediaUi
import dev.readthat.domain.toPostTransitionPreview
import dev.readthat.shared.PostMedia
import dev.readthat.shared.PostTransitionPreview
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaLaunchContextTest {
    private lateinit var db: AppDatabase
    private val json = Json { encodeDefaults = true }

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
    fun `media launch preserves ranked feed adjacency anchor and cursor atomically`() = runTest {
        val account = "viewer"
        val feed = "feed:home"
        // Insert out of order to prove sortIndex, not insertion order, owns adjacency.
        db.feedDao().upsertGroups(listOf(
            entity(account, feed, mediaGroup("after"), 3),
            entity(account, feed, textGroup("text-only"), 1),
            entity(account, feed, mediaGroup("before"), 0),
            entity(account, feed, mediaGroup("anchor"), 2),
        ))
        db.feedDao().putState(ItemStateEntity("anchor", 42, liked = true, accountId = account))
        db.feedDao().putRemoteKey(RemoteKeyEntity(feed, "normal-next", account))
        val repository = FeedRepository(db, FakeFeedRemoteSource(), account, feed)

        val context = repository.mediaLaunchContext("anchor", fallback("anchor"))

        assertEquals(listOf("before", "anchor", "after"), context.items.map { it.postId })
        assertEquals(1, context.anchorIndex)
        assertEquals(42, context.items[1].score)
        assertEquals(1, context.items[1].viewerVote)
        assertEquals("normal-next", context.nextFeedCursor)
        assertEquals(feed, context.sourceFeedId)
        assertEquals("https://example.test/pics.png", context.items[1].communityAvatarUrl)
    }

    @Test
    fun `normal feed transition preserves every carousel photo in order`() {
        val preview = listOf<CellUi>(
            CellUi.Title("gallery/title", "Gallery"),
            CellUi.ImageCarousel(
                "gallery/media",
                listOf(
                    image("one"),
                    image("two"),
                    image("three"),
                ),
            ),
        ).toPostTransitionPreview("gallery")

        assertEquals(listOf("one", "two", "three"), preview.mediaItems.map { it.mediaId })
        assertEquals("one", preview.media?.mediaId)
    }

    private fun entity(account: String, feed: String, group: WireGroup, index: Int) = GroupEntity(
        accountId = account,
        feedId = feed,
        groupId = group.groupId,
        sortIndex = index,
        payloadJson = json.encodeToString(WireGroup.serializer(), group),
    )

    private fun mediaGroup(id: String) = WireGroup(
        groupId = id,
        cells = listOf(
            WireCell.Metadata(
                "metadata",
                "pics",
                "now",
                author = "author-$id",
                avatarUrl = "https://example.test/pics.png",
            ),
            WireCell.Title("title", "title-$id"),
            WireCell.Image("image", 0xff000000, 1f, "image-$id", "https://example.test/$id.jpg"),
            WireCell.ActionBar("actions", score = 3, commentCount = 4),
        ),
    )

    private fun textGroup(id: String) = WireGroup(
        groupId = id,
        cells = listOf(
            WireCell.Metadata("metadata", "text", "now"),
            WireCell.Title("title", "title-$id"),
            WireCell.Text("body", "body"),
        ),
    )

    private fun fallback(id: String) = PostTransitionPreview(
        postId = id,
        title = id,
        media = PostMedia(0xff000000, 1f, isVideo = false),
    )

    private fun image(id: String) = ImageMediaUi(
        mediaId = id,
        placeholderColor = 0xff000000,
        aspectRatio = 1f,
        altText = id,
        sourceUrl = "https://example.test/$id.jpg",
        zoomUrl = "https://example.test/$id-detail.jpg",
        cacheKey = "image:$id",
        width = 100,
        height = 100,
    )
}
