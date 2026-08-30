package dev.readthat.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SearchDao {
    @Query(
        """
        SELECT * FROM search_results
        WHERE accountId = :accountId AND queryKey = :queryKey
        ORDER BY sortIndex ASC, itemId ASC
        """,
    )
    fun pagingSource(accountId: String, queryKey: String): PagingSource<Int, SearchResultEntity>

    @Query("SELECT COUNT(*) FROM search_results WHERE accountId = :accountId AND queryKey = :queryKey")
    suspend fun resultCount(accountId: String, queryKey: String): Int

    @Query(
        """
        SELECT COALESCE(MAX(sortIndex), -1) FROM search_results
        WHERE accountId = :accountId AND queryKey = :queryKey
        """,
    )
    suspend fun maxSortIndex(accountId: String, queryKey: String): Int

    @Upsert
    suspend fun upsertResults(rows: List<SearchResultEntity>)

    @Query("DELETE FROM search_results WHERE accountId = :accountId AND queryKey = :queryKey")
    suspend fun clearResults(accountId: String, queryKey: String)

    @Upsert
    suspend fun putRemoteKey(key: SearchRemoteKeyEntity)

    @Query("SELECT * FROM search_remote_keys WHERE accountId = :accountId AND queryKey = :queryKey")
    suspend fun remoteKey(accountId: String, queryKey: String): SearchRemoteKeyEntity?

    @Query("DELETE FROM search_remote_keys WHERE accountId = :accountId AND queryKey = :queryKey")
    suspend fun clearRemoteKey(accountId: String, queryKey: String)

    @Upsert
    suspend fun putSnapshot(snapshot: SearchSnapshotEntity)

    @Query("SELECT * FROM search_snapshots WHERE accountId = :accountId AND queryKey = :queryKey")
    suspend fun snapshot(accountId: String, queryKey: String): SearchSnapshotEntity?

    @Upsert
    suspend fun putRecent(recent: SearchRecentEntity)

    @Query("SELECT * FROM search_recent WHERE accountId = :accountId ORDER BY searchedAt DESC LIMIT :limit")
    fun observeRecent(accountId: String, limit: Int = 10): Flow<List<SearchRecentEntity>>

    @Query("DELETE FROM search_recent WHERE accountId = :accountId AND normalizedQuery = :normalizedQuery")
    suspend fun deleteRecent(accountId: String, normalizedQuery: String)

    @Query("DELETE FROM search_recent WHERE accountId = :accountId")
    suspend fun clearRecent(accountId: String)

    @Query(
        """
        DELETE FROM search_recent
        WHERE accountId = :accountId AND normalizedQuery NOT IN (
            SELECT normalizedQuery FROM search_recent
            WHERE accountId = :accountId ORDER BY searchedAt DESC LIMIT :keep
        )
        """,
    )
    suspend fun trimRecent(accountId: String, keep: Int)

    @Query("DELETE FROM search_results WHERE accountId = :accountId AND cachedAt < :before")
    suspend fun pruneResults(accountId: String, before: Long)

    @Query("DELETE FROM search_snapshots WHERE accountId = :accountId AND cachedAt < :before")
    suspend fun pruneSnapshots(accountId: String, before: Long)

    @Transaction
    suspend fun replacePage(
        accountId: String,
        queryKey: String,
        rows: List<SearchResultEntity>,
        nextCursor: String?,
        updatedAt: Long,
    ) {
        clearResults(accountId, queryKey)
        clearRemoteKey(accountId, queryKey)
        upsertResults(rows)
        putRemoteKey(SearchRemoteKeyEntity(accountId, queryKey, nextCursor, updatedAt))
    }
}
