package dev.readthat.data.db

import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubredditOutboxDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: SubredditOutboxDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.subredditOutboxDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `enqueue atomically exposes command and optimistic community`() = runTest {
        val pending = pending()

        dao.enqueue(pending, pending.optimistic())

        assertEquals(pending, dao.observe(pending.mutationId).first())
        assertEquals(pending.mutationId, database.subredditDao().get(pending.accountId, pending.name)?.id)
        assertEquals(listOf(pending), dao.resumable(pending.accountId))
        assertTrue(dao.resumable("another-account").isEmpty())
    }

    @Test
    fun `complete replaces optimistic row and removes command from resumable work`() = runTest {
        val pending = pending()
        dao.enqueue(pending, pending.optimistic())
        val remote = pending.optimistic().copy(id = "server-community-id", subscriberCount = 2)

        dao.complete(pending.mutationId, remote)

        assertEquals(remote, database.subredditDao().get(pending.accountId, pending.name))
        val stored = requireNotNull(dao.get(pending.mutationId))
        assertEquals("completed", stored.state)
        assertEquals(remote.id, stored.remoteSubredditId)
        assertTrue(dao.resumable(pending.accountId).isEmpty())
    }

    @Test
    fun `failure rolls back only the matching optimistic row and retry restores it`() = runTest {
        val pending = pending()
        dao.enqueue(pending, pending.optimistic())

        dao.fail(pending, "name already exists")

        assertNull(database.subredditDao().get(pending.accountId, pending.name))
        assertEquals("failed", dao.get(pending.mutationId)?.state)

        dao.retry(requireNotNull(dao.get(pending.mutationId)), pending.optimistic())

        assertEquals(pending.mutationId, database.subredditDao().get(pending.accountId, pending.name)?.id)
        assertEquals("queued", dao.get(pending.mutationId)?.state)
        assertEquals(listOf(pending), dao.resumable(pending.accountId))
    }

    @Test
    fun `late failure cannot delete a server reconciled community`() = runTest {
        val pending = pending()
        dao.enqueue(pending, pending.optimistic())
        val remote = pending.optimistic().copy(id = "server-community-id")
        database.subredditDao().upsert(remote)

        dao.fail(pending, "late response")

        assertEquals(remote, database.subredditDao().get(pending.accountId, pending.name))
    }

    private fun pending() = PendingSubredditEntity(
        mutationId = "5f80e05f-6a1c-4d5e-b929-4f5374f734b8",
        accountId = "account-a",
        name = "offlinefirst",
        displayName = "Offline First",
        description = "Durable writes",
        accessType = "restricted",
        state = "queued",
        remoteSubredditId = null,
        lastError = null,
        createdAt = 1234L,
    )

    private fun PendingSubredditEntity.optimistic() = SubredditEntity(
        accountId = accountId,
        id = mutationId,
        name = name,
        displayName = displayName,
        description = description,
        accessType = accessType,
        viewerRole = "owner",
        subscriberCount = 1,
        updatedAt = createdAt,
    )
}
