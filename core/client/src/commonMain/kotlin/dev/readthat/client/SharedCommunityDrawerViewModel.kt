package dev.readthat.client

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.readthat.communities.domain.CommunityDrawerSnapshot
import dev.readthat.data.db.AppDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SharedCommunityDrawerState(
    val snapshot: CommunityDrawerSnapshot = CommunityDrawerSnapshot(),
    val showAllRecents: Boolean = false,
    val communitiesExpanded: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
)

internal interface SharedCommunityDrawerDataSource {
    val snapshot: StateFlow<CommunityDrawerSnapshot>
    suspend fun refresh(force: Boolean)
    suspend fun recordVisit(name: String, displayName: String? = null)
    suspend fun removeVisit(name: String)
    suspend fun clearVisits()
}

private class OfflineFirstCommunityDrawerDataSource(
    private val repository: OfflineFirstRepository,
) : SharedCommunityDrawerDataSource {
    override val snapshot: StateFlow<CommunityDrawerSnapshot> = repository.communityDrawer
    override suspend fun refresh(force: Boolean) = repository.refreshCommunityDrawer(force)
    override suspend fun recordVisit(name: String, displayName: String?) =
        repository.recordCommunityVisit(name, displayName)
    override suspend fun removeVisit(name: String) = repository.removeCommunityVisit(name)
    override suspend fun clearVisits() = repository.clearCommunityVisits()
}

/** Shared drawer state and Room-first recent-history commands for both application hosts. */
class SharedCommunityDrawerController internal constructor(
    private val source: SharedCommunityDrawerDataSource,
    private val coroutineScope: CoroutineScope,
    private val mutationAccountId: () -> String? = { null },
    private val onVisitMutationQueued: (String) -> Unit = {},
) {
    constructor(
        client: ReadThatClient,
        database: AppDatabase,
        coroutineScope: CoroutineScope,
        accountId: String,
        onVisitMutationQueued: (String) -> Unit = {},
    ) : this(
        OfflineFirstCommunityDrawerDataSource(
            OfflineFirstRepository(
                client = client,
                database = database,
                scope = coroutineScope,
                accountIdOverride = accountId,
                maintainGlobalState = false,
            ),
        ),
        coroutineScope,
        { accountId },
        onVisitMutationQueued,
    )

    internal constructor(
        repository: OfflineFirstRepository,
        coroutineScope: CoroutineScope,
        accountId: () -> String?,
        onVisitMutationQueued: (String) -> Unit = {},
    ) : this(
        OfflineFirstCommunityDrawerDataSource(repository),
        coroutineScope,
        accountId,
        onVisitMutationQueued,
    )

    private val mutableState = MutableStateFlow(SharedCommunityDrawerState(source.snapshot.value))
    val state: StateFlow<SharedCommunityDrawerState> = mutableState.asStateFlow()
    private var refreshJob: Job? = null
    private val visitMutationMutex = Mutex()

    init {
        coroutineScope.launch {
            source.snapshot.collect { snapshot ->
                mutableState.update { it.copy(snapshot = snapshot) }
            }
        }
    }

    fun onOpened() = refresh(force = false)
    fun retry() = refresh(force = true)

    fun showAllRecents() = mutableState.update { it.copy(showAllRecents = true) }
    fun showDrawer() = mutableState.update { it.copy(showAllRecents = false) }
    fun toggleCommunities() = mutableState.update {
        it.copy(communitiesExpanded = !it.communitiesExpanded)
    }

    fun record(name: String, displayName: String? = null) = mutateVisit {
        source.recordVisit(name, displayName)
    }

    fun removeRecent(name: String) = mutateVisit { source.removeVisit(name) }

    fun clearRecent() = mutateVisit { source.clearVisits() }

    private fun refresh(force: Boolean) {
        if (refreshJob?.isActive == true) return
        refreshJob = coroutineScope.launch {
            mutableState.update { it.copy(refreshing = true, error = null) }
            try {
                source.refresh(force)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(error = error.message ?: "Could not refresh communities")
                }
            } finally {
                mutableState.update { it.copy(refreshing = false) }
            }
        }
    }

    private fun mutateVisit(block: suspend () -> Unit) {
        coroutineScope.launch {
            try {
                visitMutationMutex.withLock { block() }
                mutationAccountId()?.let(onVisitMutationQueued)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(error = error.message ?: "Could not update recent communities")
                }
            }
        }
    }
}

/** Focused lifecycle owner used by the mature Android navigation host. */
class SharedCommunityDrawerViewModel(
    client: ReadThatClient,
    database: AppDatabase,
    accountId: String,
    onVisitMutationQueued: (String) -> Unit = {},
) : ViewModel() {
    private val controller = SharedCommunityDrawerController(
        client,
        database,
        viewModelScope,
        accountId,
        onVisitMutationQueued,
    )
    val state: StateFlow<SharedCommunityDrawerState> = controller.state

    fun onOpened() = controller.onOpened()
    fun retry() = controller.retry()
    fun showAllRecents() = controller.showAllRecents()
    fun showDrawer() = controller.showDrawer()
    fun toggleCommunities() = controller.toggleCommunities()
    fun record(name: String, displayName: String? = null) = controller.record(name, displayName)
    fun removeRecent(name: String) = controller.removeRecent(name)
    fun clearRecent() = controller.clearRecent()
}
