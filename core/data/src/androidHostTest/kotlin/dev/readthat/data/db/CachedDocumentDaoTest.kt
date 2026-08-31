package dev.readthat.data.db

import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CachedDocumentDaoTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `documents are reactive durable and account scoped`() = runTest {
        val dao = database.cachedDocumentDao()
        val document = CachedDocumentEntity("account-a", "comments:post-1", "{\"roots\":[]}", 42)

        dao.upsert(document)

        assertEquals(document, dao.observe("account-a", "comments:post-1").first())
        assertNull(dao.get("account-b", "comments:post-1"))
        dao.delete("account-a", "comments:post-1")
        assertNull(dao.get("account-a", "comments:post-1"))
    }
}
