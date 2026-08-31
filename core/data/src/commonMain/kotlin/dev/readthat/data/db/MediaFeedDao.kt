package dev.readthat.data.db

import androidx.paging.PagingSource
import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert

@Dao
interface MediaFeedDao {
    @Query(
        """
        SELECT e.accountId, e.feedId, e.postId, e.position, c.payloadJson,
               s.likeCount, s.liked, s.downvoted
        FROM media_feed_entries e
        JOIN media_post_content c
          ON c.accountId = e.accountId AND c.postId = e.postId
        LEFT JOIN item_state s
          ON s.accountId = e.accountId AND s.itemId = e.postId
        WHERE e.accountId = :accountId AND e.feedId = :feedId
        ORDER BY e.position ASC
        """,
    )
    fun pagingSource(accountId: String, feedId: String): PagingSource<Int, MediaFeedRow>

    @Upsert
    suspend fun upsertContent(content: List<MediaPostContentEntity>)

    @Upsert
    suspend fun upsertEntries(entries: List<MediaFeedEntryEntity>)

    @Upsert
    suspend fun putRemoteKey(key: MediaFeedRemoteKeyEntity)

    @Query("SELECT * FROM media_feed_remote_keys WHERE accountId = :accountId AND feedId = :feedId")
    suspend fun remoteKey(accountId: String, feedId: String): MediaFeedRemoteKeyEntity?

    @Query("SELECT COUNT(*) FROM media_feed_entries WHERE accountId = :accountId AND feedId = :feedId")
    suspend fun entryCount(accountId: String, feedId: String): Int

    @Query(
        "SELECT COALESCE(MAX(position), -1) FROM media_feed_entries " +
            "WHERE accountId = :accountId AND feedId = :feedId",
    )
    suspend fun maxPosition(accountId: String, feedId: String): Long

    @Query(
        "SELECT postId FROM media_feed_entries " +
            "WHERE accountId = :accountId AND feedId = :feedId ORDER BY position ASC",
    )
    suspend fun postIds(accountId: String, feedId: String): List<String>

    @Query("DELETE FROM media_feed_entries WHERE accountId = :accountId AND feedId = :feedId")
    suspend fun clearEntries(accountId: String, feedId: String)

    @Query("DELETE FROM media_feed_remote_keys WHERE accountId = :accountId AND feedId = :feedId")
    suspend fun clearRemoteKey(accountId: String, feedId: String)

    @Query(
        """
        DELETE FROM media_feed_entries
        WHERE accountId = :accountId AND feedId <> :keepFeedId
          AND feedId NOT IN (
            SELECT feedId FROM media_feed_remote_keys
            WHERE accountId = :accountId
            ORDER BY updatedAt DESC
            LIMIT :retainedScopes
          )
        """,
    )
    suspend fun pruneOldEntries(accountId: String, keepFeedId: String, retainedScopes: Int = 8)

    @Query(
        """
        DELETE FROM media_feed_remote_keys
        WHERE accountId = :accountId AND feedId <> :keepFeedId
          AND feedId NOT IN (
            SELECT feedId FROM media_feed_remote_keys
            WHERE accountId = :accountId
            ORDER BY updatedAt DESC
            LIMIT :retainedScopes
          )
        """,
    )
    suspend fun pruneOldRemoteKeys(accountId: String, keepFeedId: String, retainedScopes: Int = 8)

    @Query(
        """
        DELETE FROM media_post_content
        WHERE accountId = :accountId
          AND postId NOT IN (
            SELECT postId FROM media_feed_entries WHERE accountId = :accountId
          )
        """,
    )
    suspend fun pruneUnreferencedContent(accountId: String)
}
