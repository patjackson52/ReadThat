package dev.readthat.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PostOutboxDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: PostOutboxDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.postOutboxDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `queued post is observable and resumable only by its account`() = runTest {
        val pending = pending()

        dao.upsert(pending)

        assertEquals(pending, dao.observe(pending.mutationId).first())
        assertEquals(listOf(pending), dao.resumable(pending.accountId))
        assertEquals(listOf(pending), dao.resumableForSubreddit(pending.accountId, pending.subreddit))
        assertTrue(dao.resumable("another-account").isEmpty())
        assertTrue(dao.resumableForSubreddit(pending.accountId, "another-community").isEmpty())
    }

    @Test
    fun `uploaded media id survives retry and completion removes work from resume`() = runTest {
        val pending = pending().copy(kind = "Image", localPath = "/no-backup/pending.jpg")
        dao.upsert(pending)

        dao.updateProgress(pending.mutationId, "retrying", "media-id", "network changed")
        dao.retry(pending.mutationId)

        val retried = requireNotNull(dao.get(pending.mutationId))
        assertEquals("queued", retried.state)
        assertEquals("media-id", retried.mediaId)
        assertEquals(null, retried.lastError)
        assertEquals(listOf(pending.copy(mediaId = "media-id")), dao.resumable(pending.accountId))

        dao.complete(pending.mutationId, "server-post-id")

        val completed = requireNotNull(dao.get(pending.mutationId))
        assertEquals("completed", completed.state)
        assertEquals("server-post-id", completed.remotePostId)
        assertTrue(dao.resumable(pending.accountId).isEmpty())
    }

    @Test
    fun `failed post is durable but excluded until explicit retry`() = runTest {
        val pending = pending()
        dao.upsert(pending)

        dao.fail(pending.mutationId, "community creation failed")

        assertEquals("failed", dao.get(pending.mutationId)?.state)
        assertTrue(dao.resumable(pending.accountId).isEmpty())

        dao.retry(pending.mutationId)

        assertEquals("queued", dao.get(pending.mutationId)?.state)
        assertEquals(listOf(pending), dao.resumable(pending.accountId))
    }

    @Test
    fun `ordered gallery upload progress is durable across retries`() = runTest {
        val pending = pending().copy(kind = "Image", mediaItemsJson = "[{\"remoteMediaId\":null}]")
        dao.upsert(pending)

        val progress = "[{\"remoteMediaId\":\"first\"},{\"remoteMediaId\":null}]"
        dao.updateMediaProgress(pending.mutationId, "uploading", "first", progress, null)
        dao.retry(pending.mutationId)

        val restored = requireNotNull(dao.get(pending.mutationId))
        assertEquals("first", restored.mediaId)
        assertEquals(progress, restored.mediaItemsJson)
        assertEquals("queued", restored.state)
    }

    private fun pending() = PendingPostEntity(
        mutationId = "83d2f284-c908-43e4-b10c-d4e4d087bde8",
        accountId = "account-a",
        subreddit = "offlinefirst",
        kind = "Text",
        title = "A durable post",
        body = "Written while offline",
        linkUrl = "",
        localPath = null,
        contentType = null,
        byteSize = null,
        width = null,
        height = null,
        durationSeconds = null,
        mediaId = null,
        state = "queued",
        remotePostId = null,
        lastError = null,
        createdAt = 2345L,
    )
}
