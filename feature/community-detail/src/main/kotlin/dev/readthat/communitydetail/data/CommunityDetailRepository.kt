package dev.readthat.communitydetail.data

import androidx.room.withTransaction
import dev.readthat.communitydetail.domain.CommunityDetail
import dev.readthat.communitydetail.domain.CommunityDetailRemoteSource
import dev.readthat.communitydetail.domain.CommunityRule
import dev.readthat.observability.PerformanceMetric
import dev.readthat.observability.PerformanceOutcome
import dev.readthat.observability.PerformanceSurface
import dev.readthat.observability.PerformanceTelemetry
import dev.readthat.observability.ProductAnalytics
import dev.readthat.observability.ProductContentType
import dev.readthat.observability.ProductEvent
import dev.readthat.observability.ProductEventName
import dev.readthat.observability.ProductSurface
import dev.readthat.observability.performanceTimer
import dev.readthat.data.db.AppDatabase
import dev.readthat.data.db.CommunityMembershipEntity
import dev.readthat.data.db.PendingCommunityMembershipEntity
import dev.readthat.data.db.SubredditEntity
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Room is the only UI source; remote responses and optimistic membership both write through it. */
class CommunityDetailRepository(
    private val db: AppDatabase,
    private val remote: CommunityDetailRemoteSource,
    private val accountId: String,
    name: String,
    private val scheduleMembershipSync: () -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    val name: String = normalize(name)
    private val dao = db.communityDetailDao()
    private val refreshMutex = Mutex()
    private val membershipMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    val detail: Flow<CommunityDetail?> = dao.observe(accountId, this.name).map { it?.toDomain() }

    suspend fun cached(): CommunityDetail? = withContext(io) { dao.get(accountId, name)?.toDomain() }

    suspend fun refresh() = refreshMutex.withLock {
        val timer = performanceTimer()
        try {
            val fetched = remote.fetch(name)
            withContext(io) {
                val pending = dao.pending(accountId, name)
                val local = dao.get(accountId, name)
                // A fetch launched before a tap may return afterwards. Server-owned metadata
                // still refreshes, but the pending desired membership remains authoritative.
                val merged = if (pending != null && local != null) {
                    fetched.copy(
                        viewerRole = local.viewerRole,
                        subscriberCount = local.subscriberCount,
                    )
                } else fetched
                dao.commitRemote(
                    detail = merged.toEntity(),
                    membership = merged.toMembership(if (pending == null) "remote" else "optimistic"),
                )
            }
            PerformanceTelemetry.duration(
                PerformanceMetric.COMMUNITY_INITIAL_FETCH,
                timer,
                surface = PerformanceSurface.COMMUNITY,
                attributes = mapOf("phase" to "detail"),
            )
        } catch (error: Throwable) {
            PerformanceTelemetry.duration(
                PerformanceMetric.COMMUNITY_INITIAL_FETCH,
                timer,
                surface = PerformanceSurface.COMMUNITY,
                outcome = PerformanceOutcome.FAILURE,
                attributes = mapOf("phase" to "detail"),
            )
            throw error
        }
    }

    /** Local state and the coalesced command commit atomically before WorkManager is scheduled. */
    suspend fun setJoined(joined: Boolean) = membershipMutex.withLock {
        val timer = performanceTimer()
        withContext(io) {
            val current = dao.get(accountId, name)?.toDomain()
                ?: throw IllegalStateException("Community details are not available")
            if (current.isJoined == joined) return@withContext
            require(current.canChangeMembership) { "Community owners and moderators cannot leave here" }
            val at = nowMillis()
            val optimistic = current.copy(
                viewerRole = if (joined) "subscriber" else null,
                subscriberCount = (current.subscriberCount + if (joined) 1 else -1).coerceAtLeast(0),
            )
            dao.commitOptimistic(
                detail = optimistic.toEntity(),
                membership = optimistic.toMembership("optimistic"),
                pending = PendingCommunityMembershipEntity(
                    accountId = accountId,
                    name = name,
                    mutationId = newId(),
                    desiredJoined = joined,
                    createdAt = at,
                ),
            )
        }
        PerformanceTelemetry.duration(
            PerformanceMetric.MUTATION_LOCAL_COMMIT,
            timer,
            surface = PerformanceSurface.COMMUNITY,
            outcome = PerformanceOutcome.QUEUED,
            attributes = mapOf("mutation_type" to if (joined) "community_join" else "community_leave"),
        )
        ProductAnalytics.record(ProductEvent(
            name = if (joined) ProductEventName.COMMUNITY_JOIN else ProductEventName.COMMUNITY_LEAVE,
            surface = ProductSurface.COMMUNITY,
            contentId = name,
            contentType = ProductContentType.COMMUNITY,
        ))
        // Room is the durability boundary; process-start recovery handles a scheduling failure.
        runCatching(scheduleMembershipSync)
    }

    suspend fun flushMembershipMutations(): Boolean = withContext(io) {
        val pending = dao.pending(accountId, MEMBERSHIP_BATCH_SIZE)
        for (command in pending) {
            val timer = performanceTimer()
            val authoritative = remote.setJoined(command.name, command.desiredJoined)
            dao.confirm(
                detail = authoritative.toEntity(),
                membership = authoritative.toMembership("remote"),
                mutationId = command.mutationId,
            )
            PerformanceTelemetry.duration(
                PerformanceMetric.MUTATION_SERVER_ACK,
                timer,
                surface = PerformanceSurface.BACKGROUND,
                attributes = mapOf(
                    "mutation_type" to if (command.desiredJoined) "community_join" else "community_leave",
                ),
            )
        }
        dao.pendingCount(accountId) == 0
    }

    private fun SubredditEntity.toDomain() = CommunityDetail(
        id = id,
        name = name,
        displayName = displayName,
        description = description,
        accessType = accessType,
        viewerRole = viewerRole,
        subscriberCount = subscriberCount,
        avatarUrl = avatarUrl,
        rules = runCatching { json.decodeFromString<List<CommunityRule>>(rulesJson) }.getOrDefault(emptyList()),
        updatedAt = updatedAt,
    )

    private fun CommunityDetail.toEntity() = SubredditEntity(
        accountId = accountId,
        id = id,
        name = name,
        displayName = displayName,
        description = description,
        accessType = accessType,
        viewerRole = viewerRole,
        subscriberCount = subscriberCount,
        updatedAt = updatedAt,
        avatarUrl = avatarUrl,
        rulesJson = json.encodeToString(rules.sortedBy(CommunityRule::order)),
    )

    private fun CommunityDetail.toMembership(source: String): CommunityMembershipEntity? {
        val role = viewerRole?.takeUnless { it == "banned" } ?: return null
        return CommunityMembershipEntity(
            accountId = accountId,
            id = id,
            name = name,
            displayName = displayName,
            accessType = accessType,
            viewerRole = role,
            source = source,
            syncedAt = nowMillis(),
        )
    }

    private fun normalize(value: String): String {
        val normalized = value.trim().removePrefix("r/").lowercase()
        require(normalized.matches(Regex("^[a-z0-9_]{3,21}$")))
        return normalized
    }

    private companion object { const val MEMBERSHIP_BATCH_SIZE = 25 }
}

class FakeCommunityDetailRemoteSource : CommunityDetailRemoteSource {
    private var joined = true

    override suspend fun fetch(name: String): CommunityDetail = sample(name)

    override suspend fun setJoined(name: String, joined: Boolean): CommunityDetail {
        this.joined = joined
        return sample(name)
    }

    private fun sample(name: String) = CommunityDetail(
        id = "community:$name",
        name = name,
        displayName = name.replaceFirstChar(Char::uppercase),
        description = "A community for thoughtful discussion and useful posts.",
        accessType = "public",
        viewerRole = if (joined) "subscriber" else null,
        subscriberCount = if (joined) 12_481 else 12_480,
        rules = listOf(CommunityRule("respect", "Be respectful", "Discuss ideas, not people.")),
        updatedAt = 0L,
    )
}
