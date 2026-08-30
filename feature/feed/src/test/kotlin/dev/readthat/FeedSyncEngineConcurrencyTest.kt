package dev.readthat

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.readthat.data.FeedRemoteSource
import dev.readthat.data.FeedSyncEngine
import dev.readthat.data.db.AppDatabase
import dev.readthat.data.db.RemoteKeyEntity
import dev.readthat.domain.WireFeedPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class FeedSyncEngineConcurrencyTest {
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
    fun `queued append uses cursor from competing worker refresh`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val requested = mutableListOf<String?>()
        val remote = object : FeedRemoteSource {
            override suspend fun loadPage(cursor: String?): WireFeedPage {
                val now = active.incrementAndGet()
                maxActive.updateAndGet { maxOf(it, now) }
                requested += cursor
                return try {
                    if (cursor == null) {
                        entered.complete(Unit)
                        release.await()
                        WireFeedPage(emptyList(), "new-cursor")
                    } else {
                        WireFeedPage(emptyList(), null)
                    }
                } finally {
                    active.decrementAndGet()
                }
            }
        }
        val paging = FeedSyncEngine(db, remote, Json)
        val worker = FeedSyncEngine(db, remote, Json)
        db.feedDao().putRemoteKey(RemoteKeyEntity("feed:home", "old-cursor", "account"))

        val first = async { worker.refresh("account", "feed:home") }
        entered.await()
        val second = async { paging.append("account", "feed:home", "old-cursor") }
        runCurrent()

        assertEquals(1, maxActive.get())
        release.complete(Unit)
        first.await()
        second.await()
        assertEquals(1, maxActive.get())
        assertEquals(listOf(null, "new-cursor"), requested)
    }
}
