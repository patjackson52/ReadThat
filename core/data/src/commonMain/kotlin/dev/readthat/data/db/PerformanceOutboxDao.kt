package dev.readthat.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query

@Dao
interface PerformanceOutboxDao {
    @Insert
    suspend fun insert(event: PendingPerformanceEventEntity)

    @Query("SELECT * FROM performance_outbox ORDER BY createdAt, id LIMIT :limit")
    suspend fun oldest(limit: Int): List<PendingPerformanceEventEntity>

    @Query("DELETE FROM performance_outbox WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query(
        "DELETE FROM performance_outbox WHERE id NOT IN " +
            "(SELECT id FROM performance_outbox ORDER BY createdAt DESC, id DESC LIMIT :keep)",
    )
    suspend fun trimToNewest(keep: Int)

    @Query("SELECT COUNT(*) FROM performance_outbox")
    suspend fun count(): Int
}
