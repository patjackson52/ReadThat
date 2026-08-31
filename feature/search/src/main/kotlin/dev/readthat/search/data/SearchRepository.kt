package dev.readthat.search.data

import android.util.LruCache
import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.paging.map
import androidx.room3.withWriteTransaction
import dev.readthat.search.domain.SearchDiscover
import dev.readthat.search.domain.SearchItem
import dev.readthat.search.domain.SearchPage
import dev.readthat.search.domain.SearchRequest
import dev.readthat.search.domain.SearchTypeahead
import dev.readthat.data.db.AppDatabase
import dev.readthat.data.db.SearchRecentEntity
import dev.readthat.data.db.SearchRemoteKeyEntity
import dev.readthat.data.db.SearchResultEntity
import dev.readthat.data.db.SearchSnapshotEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface SearchRemoteSource {
    suspend fun discover(): SearchDiscover
    suspend fun typeahead(query: String, limit: Int = 8): SearchTypeahead
    suspend fun search(request: SearchRequest, cursor: String?, limit: Int = 20): SearchPage
}

@OptIn(ExperimentalPagingApi::class)
class SearchRepository(
    private val db: AppDatabase,
    private val remote: SearchRemoteSource,
    private val accountId: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    internal val json: Json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
        explicitNulls = false
    },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.searchDao()
    private val memory = LruCache<String, MemoryValue>(32)

    val recent: Flow<List<String>> = dao.observeRecent(accountId).map { rows -> rows.map { it.query } }

    suspend fun record(query: String) = withContext(io) {
        val clean = query.trim().replace(Regex("\\s+"), " ").take(100)
        if (clean.isBlank()) return@withContext
        dao.putRecent(SearchRecentEntity(accountId, clean.lowercase(), clean, nowMillis()))
        dao.trimRecent(accountId, RECENT_LIMIT)
    }

    suspend fun deleteRecent(query: String) = withContext(io) {
        dao.deleteRecent(accountId, query.trim().lowercase())
    }

    suspend fun clearRecent() = withContext(io) { dao.clearRecent(accountId) }

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
        if (force) memory.remove(key)
        return cachedSnapshot(
            key = key,
            serializer = SearchPage.serializer(),
            maxAgeMillis = if (force) -1 else RESULT_FRESHNESS_MILLIS,
            fetch = { remote.search(request, null, PAGE_SIZE) },
        )
    }

    @OptIn(ExperimentalPagingApi::class)
    fun paged(request: SearchRequest): Flow<PagingData<SearchItem>> {
        require(request.type.wire != "all")
        val key = "page:${request.cacheKey}"
        return Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                initialLoadSize = PAGE_SIZE,
                prefetchDistance = PAGE_SIZE / 2,
                enablePlaceholders = false,
                maxSize = PAGE_SIZE * 8,
            ),
            remoteMediator = SearchRemoteMediator(
                db = db,
                remote = remote,
                request = request,
                accountId = accountId,
                queryKey = key,
                json = json,
                nowMillis = nowMillis,
            ),
            pagingSourceFactory = { dao.pagingSource(accountId, key) },
        ).flow.map { page ->
            page.map { row -> json.decodeFromString(SearchItem.serializer(), row.payloadJson) }
        }
    }

    suspend fun prune() = withContext(io) {
        val before = nowMillis() - DISK_RETENTION_MILLIS
        dao.pruneResults(accountId, before)
        dao.pruneSnapshots(accountId, before)
    }

    private suspend fun <T> cachedSnapshot(
        key: String,
        serializer: kotlinx.serialization.KSerializer<T>,
        maxAgeMillis: Long = RESULT_FRESHNESS_MILLIS,
        fetch: suspend () -> T,
    ): T = withContext(io) {
        val now = nowMillis()
        memory[key]?.takeIf { now - it.cachedAt <= maxAgeMillis }?.let {
            return@withContext json.decodeFromString(serializer, it.payload)
        }
        val disk = dao.snapshot(accountId, key)
        val diskValue = disk?.let { runCatching { json.decodeFromString(serializer, it.payloadJson) }.getOrNull() }
        if (disk != null && diskValue != null && now - disk.cachedAt <= maxAgeMillis) {
            memory.put(key, MemoryValue(disk.payloadJson, disk.cachedAt))
            return@withContext diskValue
        }
        try {
            val fresh = fetch()
            val payload = json.encodeToString(serializer, fresh)
            dao.putSnapshot(SearchSnapshotEntity(accountId, key, payload, now))
            memory.put(key, MemoryValue(payload, now))
            fresh
        } catch (error: Throwable) {
            diskValue ?: throw error
        }
    }

    private data class MemoryValue(val payload: String, val cachedAt: Long)

    companion object {
        const val PAGE_SIZE = 20
        const val RESULT_FRESHNESS_MILLIS = 5 * 60 * 1_000L
        const val TYPEAHEAD_FRESHNESS_MILLIS = 60 * 1_000L
        const val DISK_RETENTION_MILLIS = 7 * 24 * 60 * 60 * 1_000L
        const val RECENT_LIMIT = 10
    }
}

@OptIn(ExperimentalPagingApi::class)
private class SearchRemoteMediator(
    private val db: AppDatabase,
    private val remote: SearchRemoteSource,
    private val request: SearchRequest,
    private val accountId: String,
    private val queryKey: String,
    private val json: Json,
    private val nowMillis: () -> Long,
) : RemoteMediator<Int, SearchResultEntity>() {
    private val dao = db.searchDao()

    override suspend fun initialize(): InitializeAction {
        val key = dao.remoteKey(accountId, queryKey)
        return if (
            dao.resultCount(accountId, queryKey) > 0 && key != null &&
            nowMillis() - key.updatedAt < SearchRepository.RESULT_FRESHNESS_MILLIS
        ) InitializeAction.SKIP_INITIAL_REFRESH else InitializeAction.LAUNCH_INITIAL_REFRESH
    }

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, SearchResultEntity>,
    ): MediatorResult {
        if (loadType == LoadType.PREPEND) return MediatorResult.Success(true)
        val cursor = if (loadType == LoadType.REFRESH) null else {
            dao.remoteKey(accountId, queryKey)?.nextCursor
                ?: return MediatorResult.Success(true)
        }
        return try {
            val page = remote.search(request, cursor, state.config.pageSize)
            val now = nowMillis()
            db.withWriteTransaction {
                val start = if (loadType == LoadType.REFRESH) 0 else dao.maxSortIndex(accountId, queryKey) + 1
                val rows = page.items.mapIndexed { index, item ->
                    SearchResultEntity(
                        accountId = accountId,
                        queryKey = queryKey,
                        itemId = "${item::class.simpleName}:${item.id}",
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
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            MediatorResult.Error(error)
        }
    }
}
