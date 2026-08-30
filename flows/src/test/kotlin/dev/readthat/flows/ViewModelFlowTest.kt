package dev.readthat.flows

import app.cash.turbine.test
import dev.readthat.flows.model.Connectivity
import dev.readthat.flows.model.LoadState
import dev.readthat.flows.repo.FeedRepository
import dev.readthat.flows.repo.UserRepository
import dev.readthat.flows.source.FakeRemoteSource
import dev.readthat.flows.source.LocalStore
import dev.readthat.flows.vm.DashboardEvent
import dev.readthat.flows.vm.DashboardViewModel
import dev.readthat.flows.vm.SearchViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@kotlinx.coroutines.ExperimentalCoroutinesApi
class ViewModelFlowTest {

    private fun dashboard(
        scope: kotlinx.coroutines.CoroutineScope,
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        connectivity: MutableStateFlow<Connectivity> = MutableStateFlow(Connectivity.ONLINE),
        local: LocalStore = LocalStore(),
    ): DashboardViewModel {
        val remote = FakeRemoteSource()
        return DashboardViewModel(
            userRepository = UserRepository(remote, StandardTestDispatcher(scheduler)),
            feedRepository = FeedRepository(local, remote),
            connectivity = connectivity,
            scope = scope,
        )
    }

    // --- combined UI state ---------------------------------------------------

    @Test
    fun `uiState starts at its initial value before upstream produces anything`() = runTest {
        val vm = dashboard(backgroundScope, testScheduler)

        // stateIn always has a value immediately — no null, no loading flag needed.
        assertTrue(vm.uiState.value.user is LoadState.Loading)
        assertTrue(vm.uiState.value.posts.isEmpty())
    }

    @Test
    fun `uiState collapses several streams into one atomic object`() = runTest {
        val local = LocalStore()
        val connectivity = MutableStateFlow(Connectivity.ONLINE)
        val vm = dashboard(backgroundScope, testScheduler, connectivity, local)

        vm.uiState.test {
            skipItems(1) // initial

            // Drain until the user has loaded.
            var state = awaitItem()
            while (state.user !is LoadState.Success) state = awaitItem()

            assertEquals("u/patrick", state.userName)
            assertTrue(state.canAutoplay)

            connectivity.value = Connectivity.OFFLINE
            var offline = awaitItem()
            while (!offline.isOffline) offline = awaitItem()

            assertTrue(offline.isOffline)
            assertEquals("offline must disable autoplay", false, offline.canAutoplay)
            // The user is still present in the SAME object — no torn state.
            assertEquals("u/patrick", offline.userName)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- one-shot events -----------------------------------------------------

    @Test
    fun `Channel events are delivered once and never replayed`() = runTest {
        val vm = dashboard(backgroundScope, testScheduler)

        vm.events.test {
            vm.signOut()
            assertTrue(awaitItem() is DashboardEvent.NavigateToLogin)
            cancelAndIgnoreRemainingEvents()
        }

        // A second collector (think: rotation) must NOT see the earlier event again.
        vm.events.test {
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Channel buffers events emitted while nobody is collecting`() = runTest {
        val vm = dashboard(backgroundScope, testScheduler)

        vm.signOut() // no collector attached yet

        vm.events.test {
            // Buffered, so it arrives when a collector shows up.
            assertTrue(awaitItem() is DashboardEvent.NavigateToLogin)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `SharedFlow with replay 0 drops events emitted with no collector - the contrast`() =
        runTest {
            val vm = dashboard(backgroundScope, testScheduler)

            vm.emitBroadcast("lost")
            runCurrent()

            vm.broadcasts.test {
                expectNoEvents() // "lost" is gone forever
                vm.emitBroadcast("seen")
                assertEquals("seen", awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    // --- search pipeline -----------------------------------------------------

    @Test
    fun `debounce collapses rapid keystrokes into a single query`() = runTest {
        val remote = CountingRemote()
        val vm = SearchViewModel(remote, backgroundScope, debounceMs = 300)

        vm.results.test {
            skipItems(1) // initial empty

            vm.onQueryChanged("k")
            advanceTimeBy(50)
            vm.onQueryChanged("ko")
            advanceTimeBy(50)
            vm.onQueryChanged("kot")
            advanceTimeBy(50)
            vm.onQueryChanged("kotlin")

            advanceTimeBy(400)
            runCurrent()

            var item = awaitItem()
            while (item !is LoadState.Success) item = awaitItem()
            assertEquals(3, (item as LoadState.Success).data.size)

            assertEquals("only the settled query should hit the network", 1, remote.searchCalls)
            assertEquals("kotlin", remote.lastQuery)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `distinctUntilChanged skips a repeated query`() = runTest {
        val remote = CountingRemote()
        val vm = SearchViewModel(remote, backgroundScope, debounceMs = 100)

        vm.results.test {
            skipItems(1)

            vm.onQueryChanged("compose")
            advanceTimeBy(200); runCurrent()
            var item = awaitItem()
            while (item !is LoadState.Success) item = awaitItem()

            // Same text again (e.g. cursor moved) — must not re-query.
            vm.onQueryChanged("compose")
            advanceTimeBy(200); runCurrent()
            expectNoEvents()

            assertEquals(1, remote.searchCalls)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `blank query short-circuits without touching the network`() = runTest {
        val remote = CountingRemote()
        val vm = SearchViewModel(remote, backgroundScope, debounceMs = 100)

        vm.results.test {
            skipItems(1)
            vm.onQueryChanged("   ")
            advanceTimeBy(200); runCurrent()
            expectNoEvents()
            assertEquals(0, remote.searchCalls)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a failing search does not kill the pipeline`() = runTest {
        val remote = CountingRemote(failOn = "boom")
        val vm = SearchViewModel(remote, backgroundScope, debounceMs = 100)

        vm.results.test {
            skipItems(1)

            vm.onQueryChanged("boom")
            advanceTimeBy(200); runCurrent()
            var failure = awaitItem()
            while (failure !is LoadState.Failure) failure = awaitItem()
            assertTrue(failure is LoadState.Failure)

            // The pipeline is still alive for the next keystroke — this is what the
            // inner-vs-outer `catch` placement buys.
            vm.onQueryChanged("recovered")
            advanceTimeBy(200); runCurrent()
            var success = awaitItem()
            while (success !is LoadState.Success) success = awaitItem()
            assertEquals(3, (success as LoadState.Success).data.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Records call counts so the debounce/distinct assertions have something to check. */
    private class CountingRemote(private val failOn: String? = null) : FakeRemoteSource(latencyMs = 0) {
        var searchCalls = 0
            private set
        var lastQuery: String? = null
            private set

        override suspend fun search(query: String): List<dev.readthat.flows.model.Post> {
            if (query.isBlank()) return emptyList()
            searchCalls++
            lastQuery = query
            if (query == failOn) throw IllegalStateException("search exploded")
            return super.search(query)
        }
    }
}
