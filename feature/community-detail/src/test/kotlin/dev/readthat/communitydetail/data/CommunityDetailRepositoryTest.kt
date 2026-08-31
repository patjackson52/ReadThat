package dev.readthat.communitydetail.data

import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import dev.readthat.communitydetail.domain.CommunityDetail
import dev.readthat.communitydetail.domain.CommunityDetailRemoteSource
import dev.readthat.communitydetail.domain.CommunityRule
import dev.readthat.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommunityDetailRepositoryTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test
    fun `cached aggregate renders first and refresh cannot overwrite a pending join`() = runBlocking {
        val remote = FakeRemote(joined = false)
        val repository = repository(remote)
        repository.refresh()
        assertFalse(repository.awaitDetail().isJoined)

        repository.setJoined(true)
        val optimistic = repository.awaitDetail { it.isJoined }
        assertEquals(101, optimistic.subscriberCount)
        assertEquals(1, db.communityDetailDao().pendingCount(ACCOUNT))

        // This response represents a request that began before the local tap.
        repository.refresh()
        assertTrue(repository.awaitDetail().isJoined)
        assertEquals(101, repository.awaitDetail().subscriberCount)

        val processRestart = repository(remote)
        assertTrue(processRestart.cached()?.isJoined == true)
    }

    @Test
    fun `membership commands coalesce offline and acknowledgement updates detail plus drawer`() = runBlocking {
        val remote = FakeRemote(joined = true)
        val repository = repository(remote)
        repository.refresh()

        repository.setJoined(false)
        assertFalse(repository.awaitDetail { !it.isJoined }.isJoined)
        repository.setJoined(true)

        val pending = db.communityDetailDao().pending(ACCOUNT)
        assertEquals(1, pending.size)
        assertTrue(pending.single().desiredJoined)
        assertTrue(repository.flushMembershipMutations())
        assertEquals(listOf(true), remote.membershipCommands)
        assertEquals(0, db.communityDetailDao().pendingCount(ACCOUNT))
        assertEquals("subscriber", repository.awaitDetail().viewerRole)
        assertEquals(
            "subscriber",
            db.communityDrawerDao().observeMemberships(ACCOUNT).first().single().viewerRole,
        )
    }

    private fun repository(remote: FakeRemote): CommunityDetailRepository {
        var sequence = 0
        return CommunityDetailRepository(
            db = db,
            remote = remote,
            accountId = ACCOUNT,
            name = "r/androiddev",
            scheduleMembershipSync = {},
            io = Dispatchers.IO,
            nowMillis = { 1_000L + sequence },
            newId = { "mutation-${++sequence}" },
        )
    }

    private companion object { const val ACCOUNT = "account" }
}

private suspend fun CommunityDetailRepository.awaitDetail(
    predicate: (CommunityDetail) -> Boolean = { true },
): CommunityDetail = withTimeout(5_000) {
    detail.first { value -> value != null && predicate(value) }!!
}

private class FakeRemote(joined: Boolean) : CommunityDetailRemoteSource {
    private var joined = joined
    val membershipCommands = mutableListOf<Boolean>()

    override suspend fun fetch(name: String): CommunityDetail = detail(name)

    override suspend fun setJoined(name: String, joined: Boolean): CommunityDetail {
        membershipCommands += joined
        this.joined = joined
        return detail(name)
    }

    private fun detail(name: String) = CommunityDetail(
        id = "community-id",
        name = name,
        displayName = "Android Developers",
        description = "Build better Android apps.",
        accessType = "public",
        viewerRole = if (joined) "subscriber" else null,
        subscriberCount = if (joined) 101 else 100,
        avatarUrl = "https://example.test/avatar.png",
        rules = listOf(CommunityRule("rule-1", "Be constructive", order = 0)),
        updatedAt = 123L,
    )
}
