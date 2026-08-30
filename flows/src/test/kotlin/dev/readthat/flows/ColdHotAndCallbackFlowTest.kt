package dev.readthat.flows

import app.cash.turbine.test
import dev.readthat.flows.model.Connectivity
import dev.readthat.flows.source.FakeConnectivityManager
import dev.readthat.flows.source.LocalStore
import dev.readthat.flows.source.asFlow
import dev.readthat.flows.source.ticker
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@kotlinx.coroutines.ExperimentalCoroutinesApi
class ColdHotAndCallbackFlowTest {

    // --- cold ----------------------------------------------------------------

    @Test
    fun `cold flow restarts for every collector`() = runTest {
        val flow = ticker(intervalMs = 10, count = 3)

        val first = flow.toList()
        val second = flow.toList()

        // Both collectors ran the producer from scratch.
        assertEquals(listOf(0, 1, 2), first)
        assertEquals(listOf(0, 1, 2), second)
    }

    @Test
    fun `shareIn multicasts a single upstream to many collectors`() = runTest {
        var producerRuns = 0
        val shared = kotlinx.coroutines.flow.flow {
            producerRuns++
            emit(1)
            emit(2)
        }.shareIn(backgroundScope, SharingStarted.Eagerly, replay = 2)

        runCurrent()

        shared.test {
            assertEquals(1, awaitItem())
            assertEquals(2, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        shared.test {
            assertEquals(1, awaitItem())
            assertEquals(2, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals("upstream must run exactly once", 1, producerRuns)
    }

    // --- hot -----------------------------------------------------------------

    @Test
    fun `StateFlow replays its current value to a late collector`() = runTest {
        val store = LocalStore()
        store.setDarkMode(true)

        // Subscribed AFTER the write, still sees it.
        store.settings.test {
            assertTrue(awaitItem().darkMode)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `StateFlow conflates equal values`() = runTest {
        val store = LocalStore()

        store.settings.test {
            assertEquals(false, awaitItem().darkMode)

            store.setDarkMode(true)
            assertEquals(true, awaitItem().darkMode)

            // Same value again — StateFlow deduplicates by equality, so no emission.
            store.setDarkMode(true)
            expectNoEvents()

            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- callbackFlow --------------------------------------------------------

    @Test
    fun `callbackFlow emits an initial value then listener updates`() = runTest {
        val manager = FakeConnectivityManager()

        manager.asFlow(initial = Connectivity.ONLINE).test {
            assertEquals(Connectivity.ONLINE, awaitItem())

            manager.emit(Connectivity.OFFLINE)
            assertEquals(Connectivity.OFFLINE, awaitItem())

            manager.emit(Connectivity.ONLINE)
            assertEquals(Connectivity.ONLINE, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `callbackFlow unregisters its listener on cancellation - the awaitClose contract`() =
        runTest {
            val manager = FakeConnectivityManager()
            assertEquals(0, manager.listenerCount)

            manager.asFlow().test {
                awaitItem()
                assertEquals("listener registered while collecting", 1, manager.listenerCount)
                cancelAndIgnoreRemainingEvents()
            }

            runCurrent()
            assertEquals("awaitClose must unregister — otherwise this leaks", 0, manager.listenerCount)
        }

    @Test
    fun `virtual time makes delay-based flows instant`() = runTest {
        val emissions = mutableListOf<Int>()

        backgroundScope.launch {
            ticker(intervalMs = 1_000, count = 3).collect { emissions += it }
        }

        // Two seconds of delay pass instantly — the test scheduler fast-forwards.
        advanceTimeBy(2_001)
        runCurrent()

        assertEquals(listOf(0, 1, 2), emissions)
    }
}
