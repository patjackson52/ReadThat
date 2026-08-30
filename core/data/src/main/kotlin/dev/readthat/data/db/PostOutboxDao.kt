package dev.readthat.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PostOutboxDao {
    @Query("SELECT * FROM post_outbox WHERE mutationId = :mutationId")
    fun observe(mutationId: String): Flow<PendingPostEntity?>

    @Query("SELECT * FROM post_outbox WHERE mutationId = :mutationId")
    suspend fun get(mutationId: String): PendingPostEntity?

    @Query(
        "SELECT * FROM post_outbox WHERE accountId = :accountId " +
            "AND remotePostId IS NULL AND state != 'failed' ORDER BY createdAt",
    )
    suspend fun resumable(accountId: String): List<PendingPostEntity>

    @Query(
        "SELECT * FROM post_outbox WHERE accountId = :accountId AND subreddit = :subreddit " +
            "AND remotePostId IS NULL AND state != 'failed' ORDER BY createdAt",
    )
    suspend fun resumableForSubreddit(accountId: String, subreddit: String): List<PendingPostEntity>

    @Upsert
    suspend fun upsert(post: PendingPostEntity)

    @Query("UPDATE post_outbox SET state = :state, mediaId = :mediaId, lastError = :lastError WHERE mutationId = :mutationId")
    suspend fun updateProgress(mutationId: String, state: String, mediaId: String?, lastError: String?)

    @Query(
        "UPDATE post_outbox SET state = :state, mediaId = :mediaId, " +
            "mediaItemsJson = :mediaItemsJson, lastError = :lastError WHERE mutationId = :mutationId",
    )
    suspend fun updateMediaProgress(
        mutationId: String,
        state: String,
        mediaId: String?,
        mediaItemsJson: String,
        lastError: String?,
    )

    @Query("UPDATE post_outbox SET state = 'completed', remotePostId = :postId, lastError = NULL WHERE mutationId = :mutationId")
    suspend fun complete(mutationId: String, postId: String)

    @Query("UPDATE post_outbox SET state = 'failed', lastError = :message WHERE mutationId = :mutationId")
    suspend fun fail(mutationId: String, message: String)

    @Query("UPDATE post_outbox SET state = 'queued', lastError = NULL WHERE mutationId = :mutationId")
    suspend fun retry(mutationId: String)
}
