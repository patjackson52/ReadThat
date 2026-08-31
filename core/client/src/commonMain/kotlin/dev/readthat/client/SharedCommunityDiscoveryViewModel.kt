package dev.readthat.client

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.readthat.data.db.AppDatabase
import dev.readthat.search.domain.SearchDiscover
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SharedCommunityDiscoveryState(
    val discover: SearchDiscover = SearchDiscover(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val initialCacheTier: String? = null,
    val offline: Boolean = false,
    val error: String? = null,
)

internal interface SharedCommunityDiscoveryDataSource {
    suspend fun cached(): SearchDiscover?
    suspend fun refresh(): SearchDiscover
}

private class OfflineFirstCommunityDiscoveryDataSource(
    private val repository: OfflineFirstRepository,
) : SharedCommunityDiscoveryDataSource {
    override suspend fun cached(): SearchDiscover? = repository.cachedDiscover()
    override suspend fun refresh(): SearchDiscover = repository.refreshDiscover()
}

/** Shared L1 StateFlow over the account-scoped Room discovery document and network refinement. */
class SharedCommunityDiscoveryController internal constructor(
    private val source: SharedCommunityDiscoveryDataSource,
    private val coroutineScope: CoroutineScope,
) {
    constructor(
        client: ReadThatClient,
        database: AppDatabase,
        coroutineScope: CoroutineScope,
        accountId: String? = null,
    ) : this(
        source = OfflineFirstCommunityDiscoveryDataSource(
            OfflineFirstRepository(
                client = client,
                database = database,
                scope = coroutineScope,
                accountIdOverride = accountId,
                maintainGlobalState = false,
            ),
        ),
        coroutineScope = coroutineScope,
    )

    internal constructor(
        repository: OfflineFirstRepository,
        coroutineScope: CoroutineScope,
    ) : this(OfflineFirstCommunityDiscoveryDataSource(repository), coroutineScope)

    private val mutableState = MutableStateFlow(SharedCommunityDiscoveryState())
    val state: StateFlow<SharedCommunityDiscoveryState> = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var attempted = false

    fun onOpened() {
        if (!attempted) load()
    }

    fun load() {
        loadInternal(force = false)
    }

    fun refresh() {
        loadInternal(force = true)
    }

    fun clearError() {
        mutableState.value = mutableState.value.copy(error = null)
    }

    private fun loadInternal(force: Boolean) {
        loadJob?.cancel()
        attempted = true
        loadJob = coroutineScope.launch {
            val previous = mutableState.value
            if (!force) {
                val cached = source.cached()
                if (cached != null) {
                    mutableState.value = previous.copy(
                        discover = cached,
                        loading = false,
                        refreshing = true,
                        initialCacheTier = "room",
                        offline = false,
                        error = null,
                    )
                } else {
                    mutableState.value = previous.copy(
                        loading = true,
                        refreshing = false,
                        offline = false,
                        error = null,
                    )
                }
            } else {
                mutableState.value = previous.copy(
                    loading = previous.initialCacheTier == null,
                    refreshing = previous.initialCacheTier != null,
                    offline = false,
                    error = null,
                )
            }

            try {
                val fresh = source.refresh()
                mutableState.value = mutableState.value.copy(
                    discover = fresh,
                    loading = false,
                    refreshing = false,
                    initialCacheTier = mutableState.value.initialCacheTier ?: "network",
                    offline = false,
                    error = null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val hasCachedContent = mutableState.value.initialCacheTier != null
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    refreshing = false,
                    offline = hasCachedContent,
                    error = if (hasCachedContent) null else {
                        error.message ?: "Communities are unavailable"
                    },
                )
            }
        }
    }
}

class SharedCommunityDiscoveryViewModel(
    client: ReadThatClient,
    database: AppDatabase,
    accountId: String,
) : ViewModel() {
    private val controller = SharedCommunityDiscoveryController(
        client = client,
        database = database,
        coroutineScope = viewModelScope,
        accountId = accountId,
    )

    val state: StateFlow<SharedCommunityDiscoveryState> = controller.state

    init {
        controller.load()
    }

    fun refresh() = controller.refresh()
    fun clearError() = controller.clearError()
}
