package dev.readthat.data.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CachedDocumentDao {
    @Query("SELECT * FROM cached_documents WHERE accountId = :accountId AND cacheKey = :cacheKey")
    fun observe(accountId: String, cacheKey: String): Flow<CachedDocumentEntity?>

    @Query("SELECT * FROM cached_documents WHERE accountId = :accountId AND cacheKey = :cacheKey")
    suspend fun get(accountId: String, cacheKey: String): CachedDocumentEntity?

    @Upsert
    suspend fun upsert(document: CachedDocumentEntity)

    @Query("DELETE FROM cached_documents WHERE accountId = :accountId AND cacheKey = :cacheKey")
    suspend fun delete(accountId: String, cacheKey: String)

    @Query("DELETE FROM cached_documents WHERE accountId = :accountId AND updatedAt < :before")
    suspend fun prune(accountId: String, before: Long)

    @Query(
        """
        DELETE FROM cached_documents
        WHERE accountId = :accountId AND cacheKey NOT IN (
            SELECT cacheKey FROM cached_documents
            WHERE accountId = :accountId
            ORDER BY updatedAt DESC, cacheKey ASC
            LIMIT :keep
        )
        """,
    )
    suspend fun pruneToLimit(accountId: String, keep: Int)
}
