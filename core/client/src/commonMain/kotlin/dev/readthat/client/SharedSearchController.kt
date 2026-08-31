package dev.readthat.client

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.paging.cachedIn
import androidx.paging.map
import androidx.room3.withWriteTransaction
import dev.readthat.data.db.AppDatabase
import dev.readthat.data.db.SearchRecentEntity
import dev.readthat.data.db.SearchRemoteKeyEntity
import dev.readthat.data.db.SearchResultEntity
import dev.readthat.data.db.SearchSnapshotEntity
import dev.readthat.search.domain.SearchDiscover
import dev.readthat.search.domain.SearchItem
import dev.readthat.search.domain.SearchPage
import dev.readthat.search.domain.SearchRequest
import dev.readthat.search.domain.SearchSections
import dev.readthat.search.domain.SearchSort
import dev.readthat.search.domain.SearchTime
import dev.readthat.search.domain.SearchType
import dev.readthat.search.domain.SearchTypeahead
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class SharedSearchUiState(
    val draftQuery: String = "",
    val submittedQuery: String = "",
    val type: SearchType = SearchType.All,
    val sort: SearchSort = SearchSort.Relevance,
    val time: SearchTime = SearchTime.All,
    val safe: Boolean = true,
    val recent: List<String> = emptyList(),
    val discover: SearchDiscover = SearchDiscover(),
    val typeahead: SearchTypeahead? = null,
    val allSections: SearchSections? = null,
    val loadingAll: Boolean = false,
    val error: String? = null,
) {
    val isSuggesting: Boolean
        get() = draftQuery.isNotBlank() && draftQuery.trim() != submittedQuery
    val hasResults: Boolean get() = submittedQuery.isNotBlank()
}

interface SharedSearchTransport {
    suspend fun discover(): SearchDiscover
    suspend fun typeahead(query: String, limit: Int = 8): SearchTypeahead
    suspend fun search(request: SearchRequest, cursor: String?, limit: Int = 20): SearchPage
}

private class ClientSearchTransport(client: ReadThatClient) : SharedSearchTransport {
    private val api = ReadThatApi(client)
    override suspend fun discover(): SearchDiscover = api.discover()
    override suspend fun typeahead(query: String, limit: Int): SearchTypeahead = api.typeahead(query, limit)
    override suspend fun search(request: SearchRequest, cursor: String?, limit: Int): SearchPage =
        api.search(request, cursor, limit)
}

/**
 * Canonical cross-platform search state machine.
 *
 * Non-`All` result sets are Room-backed Paging streams. Discover, typeahead and `All` snapshots
 * use a bounded in-memory L1 over an account-scoped Room L2 so both hosts retain useful content
 * through transient network failures and process restarts.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SharedSearchController(
    database: AppDatabase,
    accountId: String,
    private val coroutineScope: CoroutineScope,
    transport: SharedSearchTransport,
) {
    constructor(
        client: ReadThatClient,
        database: AppDatabase,
        accountId: String,
        coroutineScope: CoroutineScope,
    ) : this(database, accountId, coroutineScope, ClientSearchTransport(client))

    private val repository = SharedSearchRepository(database, transport, accountId)
    private val mutableState = MutableStateFlow(SharedSearchUiState())
    val state: StateFlow<SharedSearchUiState> = mutableState.asStateFlow()
    private var suggestionJob: Job? = null
    private var allSearchJob: Job? = null

    val pagedResults: Flow<PagingData<SearchItem>> = mutableState
        .map { current ->
            current.takeIf { it.submittedQuery.isNotBlank() && it.type != SearchType.All }
                ?.toRequest()
        }
        .distinctUntilChanged()
        .flatMapLatest { request -> request?.let(repository::paged) ?: flowOf(PagingData.empty()) }
        .cachedIn(coroutineScope)

    init {
        coroutineScope.launch {
            repository.recent.collect { recent -> mutableState.update { it.copy(recent = recent) } }
        }
        coroutineScope.launch {
            try {
                mutableState.update { it.copy(discover = repository.discover()) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Cached recents and a functional query field remain available offline.
            }
        }
        coroutineScope.launch {
            try {
                repository.prune()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Retention cleanup is opportunistic and must not cancel the host scope.
            }
        }
    }

    fun onQueryChanged(value: String) {
        if (value.length > MAX_QUERY_LENGTH) return
        mutableState.update { it.copy(draftQuery = value, typeahead = null, error = null) }
        suggestionJob?.cancel()
        val query = value.trim()
        if (query.isBlank()) return
        suggestionJob = coroutineScope.launch {
            delay(TYPEAHEAD_DEBOUNCE_MILLIS)
            try {
                val result = repository.typeahead(query)
                if (mutableState.value.draftQuery.trim() == query) {
                    mutableState.update { it.copy(typeahead = result) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Suggestions are speculative; never replace useful cached UI with an error.
            }
        }
    }

    fun submit(query: String = mutableState.value.draftQuery) {
        val clean = query.trim().replace(Regex("\\s+"), " ").take(MAX_QUERY_LENGTH)
        if (clean.isBlank()) return
        suggestionJob?.cancel()
        mutableState.update { current ->
            current.copy(
                draftQuery = clean,
                submittedQuery = clean,
                typeahead = null,
                allSections = current.allSections.takeIf { clean == current.submittedQuery },
                error = null,
            )
        }
        coroutineScope.launch {
            try {
                repository.record(clean)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Search itself remains useful if updating local history fails.
            }
        }
        if (mutableState.value.type == SearchType.All) loadAll()
    }

    fun selectType(type: SearchType) {
        mutableState.update { current ->
            val compatibleSort = when (type) {
                SearchType.Communities, SearchType.Profiles -> SearchSort.Relevance
                SearchType.Comments -> current.sort.takeIf {
                    it in setOf(SearchSort.Relevance, SearchSort.Top, SearchSort.New)
                } ?: SearchSort.Relevance
                else -> current.sort
            }
            current.copy(
                type = type,
                sort = compatibleSort,
                allSections = if (type == SearchType.All && current.type != SearchType.All) {
                    null
                } else current.allSections,
                loadingAll = if (type == SearchType.All) current.loadingAll else false,
                error = null,
            )
        }
        if (type == SearchType.All && mutableState.value.submittedQuery.isNotBlank()) loadAll()
        else allSearchJob?.cancel()
    }

    fun selectSort(sort: SearchSort) = updateFilter { copy(sort = sort) }

    fun selectTime(time: SearchTime) = updateFilter { copy(time = time) }

    fun toggleSafe() = updateFilter { copy(safe = !safe) }

    fun clearQuery() {
        suggestionJob?.cancel()
        allSearchJob?.cancel()
        mutableState.update {
            it.copy(
                draftQuery = "",
                submittedQuery = "",
                typeahead = null,
                allSections = null,
                loadingAll = false,
                error = null,
            )
        }
    }

    fun deleteRecent(query: String) {
        coroutineScope.launch {
            try {
                repository.deleteRecent(query)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // A history mutation should never tear down the shared UI scope.
            }
        }
    }

    fun clearRecent() {
        coroutineScope.launch {
            try {
                repository.clearRecent()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // A history mutation should never tear down the shared UI scope.
            }
        }
    }

    fun retryAll() = loadAll(force = true)

    private fun updateFilter(transform: SharedSearchUiState.() -> SharedSearchUiState) {
        mutableState.update { current ->
            current.transform().copy(
                allSections = if (current.type == SearchType.All) null else current.allSections,
                error = null,
            )
        }
        if (mutableState.value.type == SearchType.All && mutableState.value.submittedQuery.isNotBlank()) {
            loadAll()
        }
    }

    private fun loadAll(force: Boolean = false) {
        val request = mutableState.value.toRequest()
        if (request.query.isBlank()) return
        allSearchJob?.cancel()
        allSearchJob = coroutineScope.launch {
            mutableState.update { it.copy(loadingAll = true, error = null) }
            try {
                val page = repository.all(request, force)
                if (mutableState.value.toRequest() == request) {
                    mutableState.update { it.copy(allSections = page.sections, loadingAll = false) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (mutableState.value.toRequest() == request) {
                    mutableState.update {
                        it.copy(loadingAll = false, error = error.message ?: "Search is unavailable")
                    }
                }
            }
        }
    }

    private fun SharedSearchUiState.toRequest() = SearchRequest(
        query = submittedQuery,
        type = type,
        sort = sort,
        time = time,
        safe = safe,
    )

    private companion object {
        const val MAX_QUERY_LENGTH = 100
        const val TYPEAHEAD_DEBOUNCE_MILLIS = 250L
    }
}

@OptIn(ExperimentalPagingApi::class)
internal class SharedSearchRepository(
    private val database: AppDatabase,
    private val remote: SharedSearchTransport,
    private val accountId: String,
    internal val json: Json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
        explicitNulls = false
    },
    private val nowMillis: () -> Long = ::platformEpochMillis,
) {
    private val dao = database.searchDao()
    private val memoryMutex = Mutex()
    private val memory = LinkedHashMap<String, MemoryValue>()

    val recent: Flow<List<String>> = dao.observeRecent(accountId).map { rows -> rows.map { it.query } }

    suspend fun record(query: String) {
        val clean = query.trim().replace(Regex("\\s+"), " ").take(100)
        if (clean.isBlank()) return
        dao.putRecent(SearchRecentEntity(accountId, clean.lowercase(), clean, nowMillis()))
        dao.trimRecent(accountId, RECENT_LIMIT)
    }

    suspend fun deleteRecent(query: String) {
        dao.deleteRecent(accountId, query.trim().lowercase())
    }

    suspend fun clearRecent() = dao.clearRecent(accountId)

    suspend fun discover(): SearchDiscover = cachedSnapshot(
        key = "discover",
        serializer = SearchDiscover.serializer(),
        fetch = remote::discover,
    )

    suspend fun typeahead(query: String): SearchTypeahead = cachedSnapshot(
        key = "typeahead:${query.trim().lowercase()}",
        serializer = SearchTypeahead.serializer(),
        maxAgeMillis = TYPEAHEAD_FRESHNESS_MILLIS,
        fetch = { remote.typeahead(query) },
    )

    suspend fun all(request: SearchRequest, force: Boolean = false): SearchPage {
        val key = "all:${request.cacheKey}"
        if (force) removeMemory(key)
        return cachedSnapshot(
            key = key,
            serializer = SearchPage.serializer(),
            maxAgeMillis = if (force) -1 else RESULT_FRESHNESS_MILLIS,
            fetch = { remote.search(request, null, PAGE_SIZE) },
        )
    }

    fun paged(request: SearchRequest): Flow<PagingData<SearchItem>> {
        require(request.type != SearchType.All)
        val queryKey = "page:${request.cacheKey}"
        return Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                initialLoadSize = PAGE_SIZE,
                prefetchDistance = PAGE_SIZE / 2,
                enablePlaceholders = false,
                maxSize = PAGE_SIZE * 8,
            ),
            remoteMediator = SharedSearchRemoteMediator(
                database, remote, request, accountId, queryKey, json, nowMillis,
            ),
            pagingSourceFactory = { dao.pagingSource(accountId, queryKey) },
        ).flow.map { page ->
            page.map { row -> json.decodeFromString(SearchItem.serializer(), row.payloadJson) }
        }
    }

    suspend fun prune() {
        val before = nowMillis() - DISK_RETENTION_MILLIS
        dao.pruneResults(accountId, before)
        dao.pruneSnapshots(accountId, before)
    }

    private suspend fun <T> cachedSnapshot(
        key: String,
        serializer: KSerializer<T>,
        maxAgeMillis: Long = RESULT_FRESHNESS_MILLIS,
        fetch: suspend () -> T,
    ): T {
        val now = nowMillis()
        memoryValue(key)?.takeIf { now - it.cachedAt <= maxAgeMillis }?.let {
            return json.decodeFromString(serializer, it.payload)
        }
        val disk = dao.snapshot(accountId, key)
        val diskValue = disk?.let {
            runCatching { json.decodeFromString(serializer, it.payloadJson) }.getOrNull()
        }
        if (disk != null && diskValue != null && now - disk.cachedAt <= maxAgeMillis) {
            putMemory(key, MemoryValue(disk.payloadJson, disk.cachedAt))
            return diskValue
        }
        return try {
            val fresh = fetch()
            val payload = json.encodeToString(serializer, fresh)
            dao.putSnapshot(SearchSnapshotEntity(accountId, key, payload, now))
            putMemory(key, MemoryValue(payload, now))
            fresh
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            diskValue ?: throw error
        }
    }

    private suspend fun memoryValue(key: String): MemoryValue? = memoryMutex.withLock {
        memory.remove(key)?.also { memory[key] = it }
    }

    private suspend fun putMemory(key: String, value: MemoryValue) = memoryMutex.withLock {
        memory.remove(key)
        memory[key] = value
        while (memory.size > MEMORY_ENTRY_LIMIT) memory.remove(memory.keys.first())
    }

    private suspend fun removeMemory(key: String) = memoryMutex.withLock { memory.remove(key) }

    private data class MemoryValue(val payload: String, val cachedAt: Long)

    companion object {
        const val PAGE_SIZE = 20
        const val RESULT_FRESHNESS_MILLIS = 5 * 60 * 1_000L
        const val TYPEAHEAD_FRESHNESS_MILLIS = 60 * 1_000L
        const val DISK_RETENTION_MILLIS = 7 * 24 * 60 * 60 * 1_000L
        const val RECENT_LIMIT = 10
        const val MEMORY_ENTRY_LIMIT = 32
    }
}

@OptIn(ExperimentalPagingApi::class)
private class SharedSearchRemoteMediator(
    private val database: AppDatabase,
    private val remote: SharedSearchTransport,
    private val request: SearchRequest,
    private val accountId: String,
    private val queryKey: String,
    private val json: Json,
    private val nowMillis: () -> Long,
) : RemoteMediator<Int, SearchResultEntity>() {
    private val dao = database.searchDao()

    override suspend fun initialize(): InitializeAction {
        val key = dao.remoteKey(accountId, queryKey)
        return if (
            dao.resultCount(accountId, queryKey) > 0 && key != null &&
            nowMillis() - key.updatedAt < SharedSearchRepository.RESULT_FRESHNESS_MILLIS
        ) InitializeAction.SKIP_INITIAL_REFRESH else InitializeAction.LAUNCH_INITIAL_REFRESH
    }

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, SearchResultEntity>,
    ): MediatorResult {
        if (loadType == LoadType.PREPEND) return MediatorResult.Success(true)
        val cursor = if (loadType == LoadType.REFRESH) null else {
            dao.remoteKey(accountId, queryKey)?.nextCursor ?: return MediatorResult.Success(true)
        }
        return try {
            val page = remote.search(request, cursor, state.config.pageSize)
            val now = nowMillis()
            database.withWriteTransaction {
                val start = if (loadType == LoadType.REFRESH) 0 else {
                    dao.maxSortIndex(accountId, queryKey) + 1
                }
                val rows = page.items.mapIndexed { index, item ->
                    SearchResultEntity(
                        accountId = accountId,
                        queryKey = queryKey,
                        itemId = searchItemStorageId(item),
                        sortIndex = start + index,
                        payloadJson = json.encodeToString(SearchItem.serializer(), item),
                        cachedAt = now,
                    )
                }
                if (loadType == LoadType.REFRESH) {
                    dao.replacePage(accountId, queryKey, rows, page.nextCursor, now)
                } else {
                    dao.upsertResults(rows)
                    dao.putRemoteKey(SearchRemoteKeyEntity(accountId, queryKey, page.nextCursor, now))
                }
            }
            MediatorResult.Success(page.nextCursor == null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            MediatorResult.Error(error)
        }
    }
}

internal fun searchItemStorageId(item: SearchItem): String = when (item) {
    is dev.readthat.search.domain.SearchPost -> "post:${item.id}"
    is dev.readthat.search.domain.SearchComment -> "comment:${item.id}"
    is dev.readthat.search.domain.SearchCommunity -> "community:${item.id}"
    is dev.readthat.search.domain.SearchProfile -> "profile:${item.id}"
}
