package dev.readthat.communities.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.readthat.communities.domain.CommunityDrawerPage
import dev.readthat.communities.domain.CommunityDrawerRemoteResult
import dev.readthat.communities.domain.CommunityDrawerRemoteSource
import dev.readthat.communities.domain.CommunityVisitCommand
import dev.readthat.communities.domain.DrawerCommunity
import dev.readthat.communities.domain.RecentCommunity
import dev.readthat.data.db.AppDatabase
import dev.readthat.shared.CreateCommunityDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommunityRepositoryTest {
    private lateinit var db: AppDatabase
    private val scopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() {
        scopes.forEach(CoroutineScope::cancel)
        db.close()
    }

    @Test
    fun `paged refresh commits one Room snapshot and a new repository restores L2`() = runBlocking {
        val remote = FakeRemote()
        val first = repository(remote)

        first.refresh()
        val refreshed = first.awaitSyncedCommunities("alpha", "beta", "gamma")

        assertEquals(listOf("alpha", "beta", "gamma"), refreshed.communities.map { it.name })
        assertEquals(listOf("beta"), first.awaitRecents("beta").recentlyVisited.map { it.name })
        assertEquals(2, remote.fetchCalls)

        val processRestart = repository(remote)
        val restored = processRestart.awaitCommunities("alpha", "beta", "gamma")
        assertEquals(first.snapshot.value, restored)

        processRestart.refresh(force = true)
        assertEquals("etag-1", remote.validators.last())
        assertEquals(3, remote.fetchCalls)
    }

    @Test
    fun `recent commands update offline immediately and coalesce without losing clear ordering`() = runBlocking {
        val remote = FakeRemote()
        val repository = repository(remote)

        repository.recordVisit("alpha")
        repository.recordVisit("alpha")
        assertEquals(listOf("alpha"), repository.awaitRecents("alpha").recentlyVisited.map { it.name })
        assertEquals(1, db.communityDrawerDao().pendingMutationCount(ACCOUNT))

        repository.removeVisit("alpha")
        assertTrue(repository.awaitRecents().recentlyVisited.isEmpty())
        assertEquals("remove", db.communityDrawerDao().pendingMutations(ACCOUNT).single().operation)

        repository.recordVisit("beta")
        repository.clearVisits()
        repository.recordVisit("gamma")
        val pending = db.communityDrawerDao().pendingMutations(ACCOUNT)
        assertEquals(listOf("clear", "visit"), pending.map { it.operation })
        assertEquals(listOf("gamma"), repository.awaitRecents("gamma").recentlyVisited.map { it.name })

        assertTrue(repository.flushVisitMutations())
        assertEquals(listOf("clear", "visit"), remote.synced.single().map { it.operation })
        assertEquals(0, db.communityDrawerDao().pendingMutationCount(ACCOUNT))
    }

    @Test
    fun `community creation is one optimistic Room transaction before scheduling`() = runBlocking {
        var scheduledMutation: String? = null
        val repository = CommunityRepository(
            db = db,
            remote = FakeRemote(),
            accountId = ACCOUNT,
            scope = newScope(),
            scheduleVisitSync = {},
            scheduleCommunityCreation = {
                scheduledMutation = it
                error("WorkManager is temporarily unavailable")
            },
            io = Dispatchers.IO,
            nowMillis = { 1234L },
            newId = { "00000000-0000-4000-8000-000000000001" },
        )

        val mutationId = repository.queueCommunity(
            CreateCommunityDraft(
                name = "offlinefirst",
                displayName = "Offline First",
                description = "Created without a network",
            ),
        )
        repository.awaitCommunities("offlinefirst")

        assertEquals(mutationId, scheduledMutation)
        assertNotNull(db.subredditOutboxDao().get(mutationId))
        assertEquals(listOf("offlinefirst"), repository.snapshot.value.communities.map { it.name })
    }

    private fun repository(remote: FakeRemote) = CommunityRepository(
        db = db,
        remote = remote,
        accountId = ACCOUNT,
        scope = newScope(),
        scheduleVisitSync = {},
        scheduleCommunityCreation = {},
        io = Dispatchers.IO,
    )

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also(scopes::add)

    private companion object { const val ACCOUNT = "account" }
}

private suspend fun CommunityRepository.awaitCommunities(vararg names: String) = withTimeout(5_000) {
    snapshot.first { value -> value.communities.map { it.name } == names.toList() }
}

private suspend fun CommunityRepository.awaitSyncedCommunities(vararg names: String) = withTimeout(5_000) {
    snapshot.first { value ->
        value.lastSuccessfulSyncAt != null && value.communities.map { it.name } == names.toList()
    }
}

private suspend fun CommunityRepository.awaitRecents(vararg names: String) = withTimeout(5_000) {
    snapshot.first { value -> value.recentlyVisited.map { it.name } == names.toList() }
}

private class FakeRemote : CommunityDrawerRemoteSource {
    var fetchCalls = 0
    val validators = mutableListOf<String?>()
    val synced = mutableListOf<List<CommunityVisitCommand>>()

    override suspend fun fetchDrawer(
        validator: String?,
        cursor: String?,
        limit: Int,
    ): CommunityDrawerRemoteResult {
        fetchCalls++
        validators += validator
        if (validator == "etag-1" && cursor == null) return CommunityDrawerRemoteResult.NotModified
        return if (cursor == null) {
            CommunityDrawerRemoteResult.Page(CommunityDrawerPage(
                communities = listOf(community("alpha"), community("beta")),
                recentlyVisited = listOf(RecentCommunity("beta", "beta", "Beta", 99)),
                nextCursor = "next",
                validator = "etag-1",
            ))
        } else {
            CommunityDrawerRemoteResult.Page(CommunityDrawerPage(
                communities = listOf(community("gamma")),
                nextCursor = null,
                validator = "etag-1",
            ))
        }
    }

    override suspend fun syncVisits(commands: List<CommunityVisitCommand>): Set<String> {
        synced += commands
        return commands.mapTo(linkedSetOf()) { it.id }
    }

    private fun community(name: String) = DrawerCommunity(
        id = name,
        name = name,
        displayName = name.replaceFirstChar(Char::uppercase),
        accessType = "public",
        role = "subscriber",
    )
}
