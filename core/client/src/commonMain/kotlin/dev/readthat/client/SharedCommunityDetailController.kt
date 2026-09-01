package dev.readthat.client

import dev.readthat.communitydetail.domain.CommunityDetail
import dev.readthat.data.db.AppDatabase
import dev.readthat.observability.ProductAnalytics
import dev.readthat.observability.ProductContentType
import dev.readthat.observability.ProductEvent
import dev.readthat.observability.ProductEventName
import dev.readthat.observability.ProductSurface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class SharedCommunityDetailState(
    val name: String? = null,
    val detail: CommunityDetail? = null,
    val refreshing: Boolean = false,
    val membershipChanging: Boolean = false,
    val initialCacheTier: String? = null,
    val offline: Boolean = false,
    val error: String? = null,
)

internal interface SharedCommunityDetailDataSource {
    fun observe(name: String): Flow<CommunityDetail?>
    suspend fun cached(name: String): CommunityDetail?
    suspend fun hasCachedFeed(name: String): Boolean
    suspend fun load(name: String, force: Boolean): CommunityDetail
    suspend fun setJoined(name: String, joined: Boolean, onLocalCommit: () -> Unit): CommunityDetail
}

private class OfflineFirstCommunityDetailDataSource(
    private val repository: OfflineFirstRepository,
) : SharedCommunityDetailDataSource {
    override fun observe(name: String): Flow<CommunityDetail?> = repository.observeCommunity(name)
    override suspend fun cached(name: String): CommunityDetail? = repository.cachedCommunity(name)
    override suspend fun hasCachedFeed(name: String): Boolean = repository.hasCachedCommunityFeed(name)
    override suspend fun load(name: String, force: Boolean): CommunityDetail =
        repository.community(name, force)

    override suspend fun setJoined(
        name: String,
        joined: Boolean,
        onLocalCommit: () -> Unit,
    ): CommunityDetail = repository.setCommunityJoined(name, joined, onLocalCommit)
}

/**
 * Lifecycle-neutral community-detail state machine shared by the application and focused hosts.
 * StateFlow is the hot presentation tier, Room is the durable source, and every membership tap is
 * committed optimistically to the Room outbox before platform background work is requested.
 */
class SharedCommunityDetailController internal constructor(
    private val source: SharedCommunityDetailDataSource,
    private val coroutineScope: CoroutineScope,
    private val accountId: () -> String?,
    private val onMembershipMutationQueued: (String) -> Unit,
    private val onCommunityLoaded: (name: String, displayName: String) -> Unit,
) {
    constructor(
        client: ReadThatClient,
        database: AppDatabase,
        coroutineScope: CoroutineScope,
        accountId: String? = null,
        onMembershipMutationQueued: (String) -> Unit = {},
        onCommunityLoaded: (name: String, displayName: String) -> Unit = { _, _ -> },
    ) : this(
        source = OfflineFirstCommunityDetailDataSource(
            OfflineFirstRepository(
                client = client,
                database = database,
                scope = coroutineScope,
                accountIdOverride = accountId,
                maintainGlobalState = false,
            ),
        ),
        coroutineScope = coroutineScope,
        accountId = { accountId },
        onMembershipMutationQueued = onMembershipMutationQueued,
        onCommunityLoaded = onCommunityLoaded,
    )

    internal constructor(
        repository: OfflineFirstRepository,
        coroutineScope: CoroutineScope,
        accountId: () -> String?,
        onMembershipMutationQueued: (String) -> Unit = {},
        onCommunityLoaded: (name: String, displayName: String) -> Unit = { _, _ -> },
    ) : this(
        source = OfflineFirstCommunityDetailDataSource(repository),
        coroutineScope = coroutineScope,
        accountId = accountId,
        onMembershipMutationQueued = onMembershipMutationQueued,
        onCommunityLoaded = onCommunityLoaded,
    )

    private val mutableState = MutableStateFlow(SharedCommunityDetailState())
    val state: StateFlow<SharedCommunityDetailState> = mutableState.asStateFlow()

    private var observationJob: Job? = null
    private var refreshJob: Job? = null
    private var membershipJob: Job? = null

    fun open(rawName: String) {
        val normalized = normalize(rawName)
        if (normalized == null) {
            close()
            mutableState.value = SharedCommunityDetailState(error = "Community not found")
            return
        }
        if (mutableState.value.name == normalized && observationJob?.isActive == true) return

        cancelJobs()
        mutableState.value = SharedCommunityDetailState(name = normalized, refreshing = true)
        ProductAnalytics.record(ProductEvent(
            name = ProductEventName.COMMUNITY_VIEW,
            surface = ProductSurface.COMMUNITY,
            contentId = normalized,
            contentType = ProductContentType.COMMUNITY,
        ))
        observationJob = coroutineScope.launch {
            source.observe(normalized).collect { detail ->
                if (mutableState.value.name == normalized && detail != null) {
                    mutableState.value = mutableState.value.copy(detail = detail)
                }
            }
        }
        refreshJob = coroutineScope.launch {
            val cached = source.cached(normalized)
            val hasCachedFeed = source.hasCachedFeed(normalized)
            if (mutableState.value.name != normalized) return@launch
            mutableState.value = mutableState.value.copy(
                detail = cached ?: mutableState.value.detail,
                initialCacheTier = if (cached != null || hasCachedFeed) "room" else null,
            )
            refreshInternal(normalized, force = cached != null)
        }
    }

    fun refresh() {
        val name = mutableState.value.name ?: return
        refreshJob?.cancel()
        refreshJob = coroutineScope.launch { refreshInternal(name, force = true) }
    }

    fun setJoined(joined: Boolean) {
        val snapshot = mutableState.value
        val name = snapshot.name ?: return
        val detail = snapshot.detail ?: return
        if (snapshot.membershipChanging || detail.isJoined == joined || !detail.canChangeMembership) return

        membershipJob = coroutineScope.launch {
            mutableState.value = mutableState.value.copy(membershipChanging = true, error = null)
            try {
                val updated = source.setJoined(name, joined) {
                    accountId()?.takeIf(String::isNotBlank)?.let { queuedAccount ->
                        runCatching { onMembershipMutationQueued(queuedAccount) }
                    }
                }
                if (mutableState.value.name == name) {
                    mutableState.value = mutableState.value.copy(
                        detail = updated,
                        membershipChanging = false,
                    )
                }
                ProductAnalytics.record(ProductEvent(
                    name = if (joined) ProductEventName.COMMUNITY_JOIN else ProductEventName.COMMUNITY_LEAVE,
                    surface = ProductSurface.COMMUNITY,
                    contentId = name,
                    contentType = ProductContentType.COMMUNITY,
                ))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (mutableState.value.name == name) {
                    mutableState.value = mutableState.value.copy(
                        membershipChanging = false,
                        error = error.message ?: "Membership change failed",
                    )
                }
            }
        }
    }

    fun clearError() {
        mutableState.value = mutableState.value.copy(error = null)
    }

    fun close() {
        cancelJobs()
        mutableState.value = SharedCommunityDetailState()
    }

    private suspend fun refreshInternal(name: String, force: Boolean) {
        if (mutableState.value.name != name) return
        mutableState.value = mutableState.value.copy(refreshing = true, offline = false, error = null)
        try {
            val detail = source.load(name, force)
            if (mutableState.value.name != name) return
            mutableState.value = mutableState.value.copy(
                detail = detail,
                refreshing = false,
                initialCacheTier = mutableState.value.initialCacheTier ?: "network",
                offline = false,
            )
            onCommunityLoaded(name, detail.displayName)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (mutableState.value.name != name) return
            val hasContent = mutableState.value.detail != null
            mutableState.value = mutableState.value.copy(
                refreshing = false,
                offline = hasContent,
                error = if (hasContent) null else error.message ?: "Unable to load community",
            )
        }
    }

    private fun cancelJobs() {
        observationJob?.cancel()
        refreshJob?.cancel()
        membershipJob?.cancel()
        observationJob = null
        refreshJob = null
        membershipJob = null
    }

    private fun normalize(value: String): String? = value
        .trim()
        .removePrefix("r/")
        .lowercase()
        .takeIf { it.matches(Regex("^[a-z0-9_]{3,21}$")) }
}
