package dev.readthat.client

import dev.readthat.communitydetail.domain.CommunityDetail
import dev.readthat.communitydetail.domain.CommunityRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
class SharedCommunityDetailControllerTest {
    @Test
    fun cachedDetailRemainsVisibleWhenNetworkRefreshFails() = runTest {
        val cached = detail(joined = true)
        val source = FakeCommunityDetailSource(cached).apply {
            cachedValue = cached
            loadError = IllegalStateException("offline")
        }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val controller = SharedCommunityDetailController(
            source = source,
            coroutineScope = scope,
            accountId = { "account-1" },
            onMembershipMutationQueued = {},
            onCommunityLoaded = { _, _ -> },
        )

        controller.open("r/kotlin")
        advanceUntilIdle()

        assertEquals(cached, controller.state.value.detail)
        assertEquals("room", controller.state.value.initialCacheTier)
        assertTrue(controller.state.value.offline)
        assertFalse(controller.state.value.refreshing)
        assertNull(controller.state.value.error)
        assertTrue(source.lastForce)
        scope.cancel()
    }

    @Test
    fun membershipCommitsBeforeDurableSyncHintAndPublishesOptimisticState() = runTest {
        val initial = detail(joined = true)
        val events = mutableListOf<String>()
        val source = FakeCommunityDetailSource(initial).apply {
            cachedValue = initial
            onMembershipCommit = { events += "room" }
        }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val controller = SharedCommunityDetailController(
            source = source,
            coroutineScope = scope,
            accountId = { "account-1" },
            onMembershipMutationQueued = { events += "schedule:$it" },
            onCommunityLoaded = { _, _ -> },
        )
        controller.open("kotlin")
        advanceUntilIdle()

        controller.setJoined(false)
        advanceUntilIdle()

        assertEquals(listOf("room", "schedule:account-1"), events)
        assertFalse(controller.state.value.detail?.isJoined ?: true)
        assertFalse(controller.state.value.membershipChanging)
        assertNull(controller.state.value.error)
        scope.cancel()
    }

    private fun detail(joined: Boolean) = CommunityDetail(
        id = "community:kotlin",
        name = "kotlin",
        displayName = "Kotlin",
        description = "Kotlin discussions",
        accessType = "public",
        viewerRole = if (joined) "subscriber" else null,
        subscriberCount = if (joined) 101 else 100,
        avatarUrl = null,
        rules = listOf(CommunityRule("respect", "Be respectful", "Discuss ideas.")),
        updatedAt = 1L,
    )
}

private class FakeCommunityDetailSource(initial: CommunityDetail?) : SharedCommunityDetailDataSource {
    private val observed = MutableStateFlow(initial)
    var cachedValue: CommunityDetail? = initial
    var hasCachedFeed = false
    var loadError: Throwable? = null
    var lastForce = false
    var onMembershipCommit: () -> Unit = {}

    override fun observe(name: String): Flow<CommunityDetail?> = observed
    override suspend fun cached(name: String): CommunityDetail? = cachedValue
    override suspend fun hasCachedFeed(name: String): Boolean = hasCachedFeed

    override suspend fun load(name: String, force: Boolean): CommunityDetail {
        lastForce = force
        loadError?.let { throw it }
        return requireNotNull(observed.value)
    }

    override suspend fun setJoined(
        name: String,
        joined: Boolean,
        onLocalCommit: () -> Unit,
    ): CommunityDetail {
        onMembershipCommit()
        val updated = requireNotNull(observed.value).copy(
            viewerRole = if (joined) "subscriber" else null,
            subscriberCount = (requireNotNull(observed.value).subscriberCount + if (joined) 1 else -1)
                .coerceAtLeast(0),
        )
        observed.value = updated
        onLocalCommit()
        return updated
    }
}
