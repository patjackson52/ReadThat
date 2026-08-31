package dev.readthat.data.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SubredditDao {
    @Query("SELECT * FROM subreddits WHERE accountId = :accountId AND name = :name")
    fun observe(accountId: String, name: String): Flow<SubredditEntity?>

    @Query("SELECT * FROM subreddits WHERE accountId = :accountId AND name = :name")
    suspend fun get(accountId: String, name: String): SubredditEntity?

    @Upsert
    suspend fun upsert(subreddit: SubredditEntity)
}
