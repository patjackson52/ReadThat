package dev.readthat.client

import dev.readthat.search.domain.DiscoverCommunity
import dev.readthat.search.domain.SearchDiscover
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SharedCommunityDiscoveryControllerTest {
    @Test
    fun cachedDiscoveryStaysVisibleAndIsMarkedOfflineWhenRefreshFails() = runTest {
        val cached = discovery("cached")
        val source = FakeDiscoverySource(cached, IllegalStateException("offline"))
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val controller = SharedCommunityDiscoveryController(source, scope)

        controller.load()
        advanceUntilIdle()

        assertEquals(cached, controller.state.value.discover)
        assertEquals("room", controller.state.value.initialCacheTier)
        assertTrue(controller.state.value.offline)
        assertFalse(controller.state.value.loading)
        assertFalse(controller.state.value.refreshing)
        assertNull(controller.state.value.error)
        scope.cancel()
    }

    @Test
    fun networkDiscoveryBecomesTheHotPresentationSnapshot() = runTest {
        val fresh = discovery("fresh")
        val source = FakeDiscoverySource(cached = null, failure = null, fresh = fresh)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val controller = SharedCommunityDiscoveryController(source, scope)

        controller.load()
        advanceUntilIdle()

        assertEquals(fresh, controller.state.value.discover)
        assertEquals("network", controller.state.value.initialCacheTier)
        assertFalse(controller.state.value.offline)
        assertNull(controller.state.value.error)
        scope.cancel()
    }

    private fun discovery(suffix: String) = SearchDiscover(
        communities = listOf(DiscoverCommunity("id-$suffix", "kotlin", "Kotlin", 42)),
    )
}

private class FakeDiscoverySource(
    private val cached: SearchDiscover?,
    private val failure: Throwable?,
    private val fresh: SearchDiscover = SearchDiscover(),
) : SharedCommunityDiscoveryDataSource {
    override suspend fun cached(): SearchDiscover? = cached
    override suspend fun refresh(): SearchDiscover {
        failure?.let { throw it }
        return fresh
    }
}
