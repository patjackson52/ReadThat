package dev.readthat.data.db

import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
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
class ProductAnalyticsOutboxDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: ProductAnalyticsOutboxDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.productAnalyticsOutboxDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `dedupe key makes a feed impression unique across process restarts`() = runTest {
        val first = pending(id = "event-1")
        val duplicate = first.copy(id = "event-2", createdAt = 2L)

        assertTrue(dao.insert(first) >= 0L)
        assertEquals(-1L, dao.insert(duplicate))
        assertEquals(listOf(first), dao.oldestForScope(
            first.sessionId,
            first.installationId,
            first.accountId,
            50,
        ))
    }

    @Test
    fun `upload scope never mixes accounts or engagement sessions`() = runTest {
        val first = pending(id = "event-1", dedupeKey = null)
        val otherAccount = first.copy(id = "event-2", accountId = "account-b", createdAt = 2L)
        val otherSession = first.copy(id = "event-3", sessionId = "session-b", createdAt = 3L)
        dao.insert(first)
        dao.insert(otherAccount)
        dao.insert(otherSession)

        assertEquals(listOf(first), dao.oldestForScope(
            first.sessionId,
            first.installationId,
            first.accountId,
            50,
        ))
    }

    private fun pending(
        id: String,
        dedupeKey: String? = "session-a:post_impression:FEED:post-a",
    ) = PendingProductAnalyticsEventEntity(
        id = id,
        installationId = "install-a",
        sessionId = "session-a",
        accountId = "account-a",
        payloadJson = "{}",
        dedupeKey = dedupeKey,
        createdAt = 1L,
    )
}
