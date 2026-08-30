package dev.readthat.flows

import app.cash.turbine.test
import dev.readthat.flows.model.Connectivity
import dev.readthat.flows.model.LoadState
import dev.readthat.flows.model.map
import dev.readthat.flows.model.Post
import dev.readthat.flows.repo.FeedRepository
import dev.readthat.flows.repo.UserRepository
import dev.readthat.flows.source.FakeRemoteSource
import dev.readthat.flows.source.LocalStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@kotlinx.coroutines.ExperimentalCoroutinesApi
class CombineAndRepositoryTest {

    private fun post(id: String, sub: String) = Post(id, sub, "title-$id", 10)

    // --- combine semantics ---------------------------------------------------

    @Test
    fun `combine waits for every input to emit at least once`() = runTest {
        val ready = MutableStateFlow(1)

        // One input never emits — the combination must produce nothing at all.
        combine(ready, emptyFlow<Int>()) { a, b -> a + b }.test {
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `combine re-emits when any single input changes`() = runTest {
        val a = MutableStateFlow(1)
        val b = MutableStateFlow(10)

        combine(a, b) { x, y -> x to y }.test {
            assertEquals(1 to 10, awaitItem())

            a.value = 2
            assertEquals(2 to 10, awaitItem())

            b.value = 20
            assertEquals(2 to 20, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- FeedRepository ------------------------------------------------------

    @Test
    fun `visiblePosts filters by blocked subreddits and reacts to settings changes`() = runTest {
        val local = LocalStore()
        val repo = FeedRepository(local, FakeRemoteSource())

        local.cachePosts(
            listOf(post("1", "androiddev"), post("2", "spam"), post("3", "Kotlin")),
        )

        repo.visiblePosts().test {
            assertEquals(3, awaitItem().size)

            local.blockSubreddit("spam")
            val filtered = awaitItem()
            assertEquals(2, filtered.size)
            assertTrue(filtered.none { it.subreddit == "spam" })

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `distinctUntilChanged suppresses a settings change that does not alter the result`() =
        runTest {
            val local = LocalStore()
            val repo = FeedRepository(local, FakeRemoteSource())
            local.cachePosts(listOf(post("1", "androiddev")))

            repo.visiblePosts().test {
                assertEquals(1, awaitItem().size)

                // Blocking a subreddit that isn't present changes Settings but not
                // the visible list -> no downstream emission.
                local.blockSubreddit("nothing-here")
                expectNoEvents()

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `three-way combine derives canAutoplay from settings and connectivity`() = runTest {
        val local = LocalStore()
        val repo = FeedRepository(local, FakeRemoteSource())
        val connectivity = MutableStateFlow(Connectivity.ONLINE)

        repo.feedSnapshot(connectivity).test {
            assertTrue(awaitItem().canAutoplay)

            connectivity.value = Connectivity.OFFLINE
            assertEquals(false, awaitItem().canAutoplay)

            connectivity.value = Connectivity.ONLINE
            assertTrue(awaitItem().canAutoplay)

            local.setAutoplay(false)
            assertEquals(false, awaitItem().canAutoplay)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `streamAndCache writes each page through to the local store`() = runTest {
        val local = LocalStore()
        val repo = FeedRepository(local, FakeRemoteSource(latencyMs = 0))

        repo.streamAndCache("androiddev").test {
            assertEquals(2, awaitItem().size)
            assertEquals(4, awaitItem().size)
            assertEquals(6, awaitItem().size)
            awaitComplete()
        }

        assertEquals("cache holds the final page", 6, local.cachedPosts.value.size)
    }

    // --- UserRepository: onStart / retryWhen / catch --------------------------

    @Test
    fun `user emits Loading then Success`() = runTest {
        val repo = UserRepository(
            FakeRemoteSource(failuresBeforeSuccess = 0),
            StandardTestDispatcher(testScheduler),
        )

        repo.user("patrick").test {
            assertTrue(awaitItem() is LoadState.Loading)
            val success = awaitItem()
            assertTrue(success is LoadState.Success)
            assertEquals("u/patrick", (success as LoadState.Success).data.displayName)
            awaitComplete()
        }
    }

    @Test
    fun `retryWhen recovers from transient failures with backoff`() = runTest {
        val remote = FakeRemoteSource(failuresBeforeSuccess = 2)
        val repo = UserRepository(remote, StandardTestDispatcher(testScheduler))

        repo.user("patrick").test {
            assertTrue(awaitItem() is LoadState.Loading)
            assertTrue(awaitItem() is LoadState.Success)
            awaitComplete()
        }

        // 2 failures + 1 success
        assertEquals(3, remote.attemptCount)
    }

    @Test
    fun `catch converts an exhausted retry into a Failure value instead of throwing`() = runTest {
        val remote = FakeRemoteSource(failuresBeforeSuccess = 99)
        val repo = UserRepository(remote, StandardTestDispatcher(testScheduler))

        repo.user("patrick").test {
            assertTrue(awaitItem() is LoadState.Loading)
            val failure = awaitItem()
            assertTrue(failure is LoadState.Failure)
            assertTrue((failure as LoadState.Failure).message.contains("transient failure"))
            // Terminal, but NOT an exception — the collector survives.
            awaitComplete()
        }

        assertEquals("initial attempt + MAX_RETRIES", 4, remote.attemptCount)
    }

    @Test
    fun `refresh re-runs the query through flatMapLatest`() = runTest {
        val remote = FakeRemoteSource(failuresBeforeSuccess = 0)
        val repo = UserRepository(remote, StandardTestDispatcher(testScheduler))

        repo.refreshableUser("patrick").test {
            assertTrue(awaitItem() is LoadState.Loading)
            assertTrue(awaitItem() is LoadState.Success)

            repo.refresh()

            assertTrue(awaitItem() is LoadState.Loading)
            assertTrue(awaitItem() is LoadState.Success)

            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(2, remote.attemptCount)
    }

    @Test
    fun `sharedUser runs the upstream once for two collectors`() = runTest {
        val remote = FakeRemoteSource(failuresBeforeSuccess = 0)
        val repo = UserRepository(remote, StandardTestDispatcher(testScheduler))

        val shared = repo.sharedUser("patrick", backgroundScope)

        shared.test {
            assertTrue(awaitItem() is LoadState.Loading)
            assertTrue(awaitItem() is LoadState.Success)
            cancelAndIgnoreRemainingEvents()
        }
        shared.test {
            // replay = 1 -> the late collector gets the last emission immediately.
            assertTrue(awaitItem() is LoadState.Success)
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals("one shared upstream, one network call", 1, remote.attemptCount)
    }

    @Test
    fun `plain user flow runs the upstream once per collector - the cold contrast`() = runTest {
        val remote = FakeRemoteSource(failuresBeforeSuccess = 0)
        val repo = UserRepository(remote, StandardTestDispatcher(testScheduler))

        // Collect twice, fully, and count the network calls.
        repeat(2) {
            repo.user("patrick").test {
                assertTrue(awaitItem() is LoadState.Loading)
                assertTrue(awaitItem() is LoadState.Success)
                awaitComplete()
            }
        }

        assertEquals("cold flow re-fetches once per collector", 2, remote.attemptCount)
    }

    @Test
    fun `LoadState map transforms Success and passes Loading and Failure through`() {
        val ok: LoadState<Int> = LoadState.Success(2)
        val bad: LoadState<Int> = LoadState.Failure("nope")
        val pending: LoadState<Int> = LoadState.Loading

        assertEquals(4, (ok.map { it * 2 } as LoadState.Success).data)
        assertTrue(bad.map { it * 2 } is LoadState.Failure)
        assertTrue(pending.map { it * 2 } is LoadState.Loading)
    }
}
