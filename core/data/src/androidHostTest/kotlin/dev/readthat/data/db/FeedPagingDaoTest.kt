package dev.readthat.data.db

import android.content.Context
import androidx.paging.PagingSource
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
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
class FeedPagingDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: FeedDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.feedDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `room paging source preserves rank and joins optimistic state`() = runTest {
        dao.upsertGroups(listOf(
            GroupEntity("later", 1, "{\"groupId\":\"later\"}"),
            GroupEntity("first", 0, "{\"groupId\":\"first\"}"),
        ))
        dao.putState(ItemStateEntity("first", likeCount = 42, liked = true))

        val result = dao.pagingSource().load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 20,
                placeholdersEnabled = false,
            ),
        ) as PagingSource.LoadResult.Page

        assertEquals(listOf("first", "later"), result.data.map(GroupWithState::groupId))
        assertEquals(42, result.data.first().likeCount)
        assertEquals(true, result.data.first().liked)
        assertEquals(2, dao.observeGroupCount().first())
    }
}
