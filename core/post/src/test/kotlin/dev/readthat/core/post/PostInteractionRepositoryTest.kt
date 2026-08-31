package dev.readthat.core.post

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import dev.readthat.observability.PerformanceSurface
import dev.readthat.data.db.AppDatabase
import dev.readthat.data.db.ItemStateEntity
import dev.readthat.shared.VoteSnapshot
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PostInteractionRepositoryTest {
    private lateinit var db: AppDatabase
    private val account = "account"

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
    fun `local state and durable outbox commit before a slow acknowledgement`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        db.feedDao().putState(ItemStateEntity("post", 10, liked = false, accountId = account))
        val repository = repository { _, value, _ ->
            entered.complete(Unit)
            release.await()
            ConfirmedPostVote(11, value)
        }

        val vote = async { repository.vote("post", 1, PerformanceSurface.FEED) }
        entered.await()

        assertEquals(11, db.feedDao().stateFor("post", account)?.likeCount)
        assertEquals(1, db.feedDao().pendingVote("post", account)?.value)
        release.complete(Unit)
        assertEquals(VoteSnapshot(11, 1), vote.await())
        assertNull(db.feedDao().pendingVote("post", account))
    }

    @Test
    fun `cancellation leaves the latest intent queued for WorkManager`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val repository = repository { _, _, _ -> entered.complete(Unit); never.await(); error("unreachable") }

        val vote = async { repository.vote("post", 1, PerformanceSurface.MEDIA, VoteSnapshot(20, 0)) }
        entered.await()
        vote.cancelAndJoin()

        assertEquals(21, db.feedDao().stateFor("post", account)?.likeCount)
        assertNotNull(db.feedDao().pendingVote("post", account))
    }

    @Test
    fun `a late acknowledgement cannot overwrite a newer rapid vote`() = runTest {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        db.feedDao().putState(ItemStateEntity("post", 10, liked = false, accountId = account))
        val repository = repository { _, value, _ ->
            if (value == 1) {
                firstEntered.complete(Unit)
                releaseFirst.await()
                ConfirmedPostVote(11, 1)
            } else {
                ConfirmedPostVote(9, -1)
            }
        }

        val first = async { repository.vote("post", 1, PerformanceSurface.FEED) }
        firstEntered.await()
        val second = async { repository.vote("post", -1, PerformanceSurface.MEDIA) }
        runCurrent()
        assertEquals(VoteSnapshot(9, -1), second.await())
        releaseFirst.complete(Unit)
        first.await()

        val final = db.feedDao().stateFor("post", account)
        assertEquals(9, final?.likeCount)
        assertEquals(true, final?.downvoted)
        assertNull(db.feedDao().pendingVote("post", account))
    }

    @Test
    fun `network failure returns optimistic state and retains outbox`() = runTest {
        val repository = repository { _, _, _ -> throw IOException("offline") }

        val result = repository.vote(
            "post",
            1,
            PerformanceSurface.DETAIL,
            baseline = VoteSnapshot(50, 0),
        )

        assertEquals(VoteSnapshot(51, 1), result)
        assertNotNull(db.feedDao().pendingVote("post", account))
    }

    @Test
    fun `resolved detail vote does not toggle when Room already has the desired value`() = runTest {
        db.feedDao().putState(ItemStateEntity("post", 11, liked = true, accountId = account))
        val repository = repository { _, value, _ -> ConfirmedPostVote(11, value) }

        val result = repository.setVote(
            "post",
            1,
            PerformanceSurface.DETAIL,
            baseline = VoteSnapshot(10, 0),
        )

        assertEquals(VoteSnapshot(11, 1), result)
        assertEquals(true, db.feedDao().stateFor("post", account)?.liked)
    }

    private fun repository(remote: PostVoteRemoteSource) = PostInteractionRepository(
        db = db,
        remote = remote,
        accountId = { account },
    )
}
