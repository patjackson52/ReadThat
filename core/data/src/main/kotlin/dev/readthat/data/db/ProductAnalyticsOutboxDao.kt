package dev.readthat.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ProductAnalyticsOutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: PendingProductAnalyticsEventEntity): Long

    @Query("SELECT * FROM product_analytics_outbox ORDER BY createdAt, id LIMIT 1")
    suspend fun oldest(): PendingProductAnalyticsEventEntity?

    @Query(
        "SELECT * FROM product_analytics_outbox " +
            "WHERE sessionId = :sessionId AND installationId = :installationId " +
            "AND ((accountId IS NULL AND :accountId IS NULL) OR accountId = :accountId) " +
            "ORDER BY createdAt, id LIMIT :limit",
    )
    suspend fun oldestForScope(
        sessionId: String,
        installationId: String,
        accountId: String?,
        limit: Int,
    ): List<PendingProductAnalyticsEventEntity>

    @Query("DELETE FROM product_analytics_outbox WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query(
        "DELETE FROM product_analytics_outbox WHERE id NOT IN " +
            "(SELECT id FROM product_analytics_outbox ORDER BY createdAt DESC, id DESC LIMIT :keep)",
    )
    suspend fun trimToNewest(keep: Int)

    @Query("SELECT COUNT(*) FROM product_analytics_outbox")
    suspend fun count(): Int
}
