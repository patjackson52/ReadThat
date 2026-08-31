package dev.readthat.client

import dev.readthat.communities.domain.CommunityDrawerSnapshot
import dev.readthat.communities.domain.DrawerCommunity
import dev.readthat.communities.domain.RecentCommunity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SharedCommunityDrawerControllerTest {
    @Test
    fun drawerPresentationStateAndRefreshAreSharedPolicies() = runTest {
        val source = FakeCommunityDrawerDataSource()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val controller = SharedCommunityDrawerController(source, scope)

        controller.showAllRecents()
        controller.toggleCommunities()
        controller.onOpened()
        advanceUntilIdle()

        assertTrue(controller.state.value.showAllRecents)
        assertFalse(controller.state.value.communitiesExpanded)
        assertFalse(controller.state.value.refreshing)
        assertEquals(listOf(false), source.refreshes)
        assertNull(controller.state.value.error)
        scope.cancel()
    }

    @Test
    fun roomFirstRecentCommandsAlwaysHintDurablePlatformSync() = runTest {
        val source = FakeCommunityDrawerDataSource()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var queued = 0
        val controller = SharedCommunityDrawerController(
            source = source,
            coroutineScope = scope,
            mutationAccountId = { "account" },
            onVisitMutationQueued = { queued++ },
        )

        controller.record("kotlin")
        controller.removeRecent("android")
        controller.clearRecent()
        advanceUntilIdle()

        assertEquals(listOf("record:kotlin", "remove:android", "clear"), source.mutations)
        assertEquals(3, queued)
        scope.cancel()
    }
}

private class FakeCommunityDrawerDataSource : SharedCommunityDrawerDataSource {
    private val mutableSnapshot = MutableStateFlow(CommunityDrawerSnapshot(
        communities = listOf(DrawerCommunity("1", "kotlin", "Kotlin", "public", "subscriber")),
        recentlyVisited = listOf(RecentCommunity("2", "android", "Android", 1L)),
    ))
    override val snapshot: StateFlow<CommunityDrawerSnapshot> = mutableSnapshot
    val refreshes = mutableListOf<Boolean>()
    val mutations = mutableListOf<String>()

    override suspend fun refresh(force: Boolean) {
        refreshes += force
    }

    override suspend fun recordVisit(name: String, displayName: String?) {
        mutations += "record:$name"
    }

    override suspend fun removeVisit(name: String) {
        mutations += "remove:$name"
    }

    override suspend fun clearVisits() {
        mutations += "clear"
    }
}
