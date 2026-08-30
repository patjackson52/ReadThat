package dev.readthat.communities.data

import dev.readthat.communities.domain.CommunityDrawerPage
import dev.readthat.communities.domain.CommunityDrawerRemoteResult
import dev.readthat.communities.domain.CommunityDrawerRemoteSource
import dev.readthat.communities.domain.CommunityDrawerSnapshot
import dev.readthat.communities.domain.CommunityVisitCommand
import dev.readthat.communities.domain.DrawerCommunity
import dev.readthat.communities.domain.RecentCommunity
import dev.readthat.data.db.AppDatabase
import dev.readthat.data.db.CommunityDrawerSyncEntity
import dev.readthat.data.db.CommunityMembershipEntity
import dev.readthat.data.db.CommunityVisitEntity
import dev.readthat.data.db.CommunityVisitMutationEntity
import dev.readthat.data.db.PendingSubredditEntity
import dev.readthat.data.db.SubredditEntity
import dev.readthat.shared.CreateCommunityDraft
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class CommunityRepository(
    private val db: AppDatabase,
    private val remote: CommunityDrawerRemoteSource,
    private val accountId: String,
    scope: CoroutineScope,
    private val scheduleVisitSync: () -> Unit,
    private val scheduleCommunityCreation: (String) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val dao = db.communityDrawerDao()
    private val refreshMutex = Mutex()
    private val mutationMutex = Mutex()

    /** L1 StateFlow is fed only by the account-scoped Room L2 source of truth. */
    val snapshot: StateFlow<CommunityDrawerSnapshot> = combine(
        dao.observeMemberships(accountId),
        dao.observeVisits(accountId),
        dao.observeSyncState(accountId),
    ) { memberships, visits, sync ->
        CommunityDrawerSnapshot(
            communities = memberships.map { it.toDomain() },
            recentlyVisited = visits.map { it.toDomain() },
            lastSuccessfulSyncAt = sync?.lastSuccessfulSyncAt,
        )
    }.stateIn(scope, SharingStarted.Eagerly, CommunityDrawerSnapshot())

    suspend fun refresh(force: Boolean = false) = refreshMutex.withLock {
        withContext(io) {
            val before = dao.syncState(accountId)
            if (!force && before != null && nowMillis() - before.lastSuccessfulSyncAt < FRESH_MILLIS) {
                return@withContext
            }
            val memberships = mutableListOf<DrawerCommunity>()
            var recents = emptyList<RecentCommunity>()
            var cursor: String? = null
            var responseValidator = before?.validator
            do {
                when (val result = remote.fetchDrawer(
                    validator = before?.validator.takeIf { cursor == null },
                    cursor = cursor,
                    limit = PAGE_SIZE,
                )) {
                    CommunityDrawerRemoteResult.NotModified -> {
                        dao.upsertSync(CommunityDrawerSyncEntity(accountId, before?.validator, nowMillis()))
                        return@withContext
                    }
                    is CommunityDrawerRemoteResult.Page -> {
                        val page = result.value
                        memberships += page.communities
                        if (cursor == null) recents = page.recentlyVisited
                        responseValidator = page.validator
                        cursor = page.nextCursor
                    }
                }
            } while (cursor != null)

            val syncedAt = nowMillis()
            dao.replaceRemoteSnapshot(
                accountId = accountId,
                memberships = memberships.map { it.toEntity(accountId, syncedAt) },
                visits = recents.map { it.toEntity(accountId) },
                sync = CommunityDrawerSyncEntity(accountId, responseValidator, syncedAt),
                preserveLocalVisits = dao.pendingMutationCount(accountId) > 0,
            )
        }
    }

    suspend fun recordVisit(name: String, displayName: String? = null) = mutateVisit {
        val normalized = normalize(name)
        val membership = snapshot.value.communities.firstOrNull { it.name == normalized }
        val recent = snapshot.value.recentlyVisited.firstOrNull { it.name == normalized }
        val at = nextCommandTime()
        dao.recordVisit(
            CommunityVisitEntity(
                accountId = accountId,
                id = membership?.id ?: recent?.id ?: normalized,
                name = normalized,
                displayName = displayName?.takeIf(String::isNotBlank)
                    ?: membership?.displayName ?: recent?.displayName ?: normalized,
                visitedAt = at,
            ),
            mutation("visit", normalized, at),
        )
    }

    suspend fun removeVisit(name: String) = mutateVisit {
        val normalized = normalize(name)
        val at = nextCommandTime()
        dao.removeVisit(accountId, normalized, mutation("remove", normalized, at))
    }

    suspend fun clearVisits() = mutateVisit {
        val at = nextCommandTime()
        dao.clearVisits(accountId, mutation("clear", null, at))
    }

    suspend fun flushVisitMutations(): Boolean = withContext(io) {
        val pending = dao.pendingMutations(accountId, MUTATION_BATCH_SIZE)
        if (pending.isEmpty()) return@withContext true
        val acknowledged = remote.syncVisits(pending.map { it.toCommand() })
        dao.deleteMutations(pending.map { it.mutationId }.filter(acknowledged::contains))
        dao.pendingMutationCount(accountId) == 0
    }

    suspend fun queueCommunity(draft: CreateCommunityDraft): String = withContext(io) {
        require(draft.canSubmit)
        val mutationId = newId()
        val now = nowMillis()
        val pending = PendingSubredditEntity(
            mutationId = mutationId,
            accountId = accountId,
            name = draft.normalizedName,
            displayName = draft.displayName.trim(),
            description = draft.description.trim(),
            accessType = draft.accessType,
            state = "queued",
            remoteSubredditId = null,
            lastError = null,
            createdAt = now,
        )
        db.subredditOutboxDao().enqueueWithMembership(
            pending,
            pending.optimisticSubreddit(),
            pending.optimisticMembership(),
        )
        // Room is the durable boundary. WorkManager is also resumed at sign-in/app start,
        // so a transient scheduling failure must not turn a committed local write into UI failure.
        runCatching { scheduleCommunityCreation(mutationId) }
        mutationId
    }

    private suspend fun mutateVisit(block: suspend () -> Unit) = mutationMutex.withLock {
        withContext(io) { block() }
        // The outbox survives the process and is resumed at sign-in/app start.
        runCatching(scheduleVisitSync)
    }

    private suspend fun nextCommandTime(): Long = maxOf(
        nowMillis(),
        (dao.latestMutationTime(accountId) ?: Long.MIN_VALUE) + 1,
    )

    private fun mutation(operation: String, name: String?, at: Long) = CommunityVisitMutationEntity(
        mutationId = newId(),
        accountId = accountId,
        operation = operation,
        name = name,
        occurredAt = at,
        createdAt = at,
    )

    private fun normalize(name: String): String {
        val normalized = name.trim().removePrefix("r/").lowercase()
        require(normalized.matches(Regex("^[a-z0-9_]{3,21}$")))
        return normalized
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val MUTATION_BATCH_SIZE = 50
        const val FRESH_MILLIS = 5 * 60 * 1_000L
    }
}

fun PendingSubredditEntity.optimisticSubreddit() = SubredditEntity(
    accountId = accountId,
    id = mutationId,
    name = name,
    displayName = displayName,
    description = description,
    accessType = accessType,
    viewerRole = "owner",
    subscriberCount = 1,
    updatedAt = createdAt,
)

fun PendingSubredditEntity.optimisticMembership() = CommunityMembershipEntity(
    accountId = accountId,
    id = mutationId,
    name = name,
    displayName = displayName,
    accessType = accessType,
    viewerRole = "owner",
    source = "optimistic",
    syncedAt = createdAt,
)

private fun CommunityMembershipEntity.toDomain() = DrawerCommunity(
    id = id,
    name = name,
    displayName = displayName,
    accessType = accessType,
    role = viewerRole,
)

private fun CommunityVisitEntity.toDomain() = RecentCommunity(id, name, displayName, visitedAt)

private fun DrawerCommunity.toEntity(accountId: String, syncedAt: Long) = CommunityMembershipEntity(
    accountId, id, name.lowercase(), displayName, accessType, role, "remote", syncedAt,
)

private fun RecentCommunity.toEntity(accountId: String) = CommunityVisitEntity(
    accountId, id, name.lowercase(), displayName, visitedAt,
)

private fun CommunityVisitMutationEntity.toCommand() = CommunityVisitCommand(
    mutationId, operation, name, occurredAt,
)
