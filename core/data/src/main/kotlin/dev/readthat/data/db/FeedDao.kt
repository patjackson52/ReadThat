package dev.readthat.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
interface FeedDao {

    /**
     * ⭐ The paging read path.
     *
     * Returns a Room-generated [PagingSource], which is what makes the DB — not
     * the network — the source of truth for paging. Paging 3 asks *this* for
     * pages; [dev.readthat.data.paging.FeedRemoteMediator] only tops the DB
     * up when it runs dry.
     *
     * The `LEFT JOIN` is the SDUI merge, done in SQL: the server's opaque blob
     * on the left, the user's mutable state on the right. `LEFT` and not
     * `INNER` because a freshly-fetched group has no state row yet — an inner
     * join would make new items invisible until someone liked them.
     *
     * Room invalidates this PagingSource on any write to either table, so an
     * optimistic like re-emits the affected page automatically. That is the
     * whole reason the state is a *table* and not a field inside the blob.
     */
    @Transaction
    @Query(
        """
        SELECT g.accountId, g.feedId, g.groupId, g.sortIndex, g.payloadJson, g.payloadVersion,
               s.likeCount AS likeCount, s.liked AS liked, s.downvoted AS downvoted
        FROM feed_groups AS g
        LEFT JOIN item_state AS s
          ON s.accountId = g.accountId AND s.itemId = g.groupId
        WHERE g.accountId = :accountId AND g.feedId = :feedId
        ORDER BY g.sortIndex ASC, g.groupId ASC
        """,
    )
    fun pagingSource(
        accountId: String = CacheScope.DEFAULT_ACCOUNT_ID,
        feedId: String = CacheScope.HOME_FEED_ID,
    ): PagingSource<Int, GroupWithState>

    /**
     * One transactionally consistent ranked snapshot for the MediaFeed handoff.
     * This deliberately uses the same ORDER BY as [pagingSource].
     */
    @Transaction
    @Query(
        """
        SELECT g.accountId, g.feedId, g.groupId, g.sortIndex, g.payloadJson, g.payloadVersion,
               s.likeCount AS likeCount, s.liked AS liked, s.downvoted AS downvoted
        FROM feed_groups AS g
        LEFT JOIN item_state AS s
          ON s.accountId = g.accountId AND s.itemId = g.groupId
        WHERE g.accountId = :accountId AND g.feedId = :feedId
        ORDER BY g.sortIndex ASC, g.groupId ASC
        """,
    )
    suspend fun orderedGroups(
        accountId: String = CacheScope.DEFAULT_ACCOUNT_ID,
        feedId: String = CacheScope.HOME_FEED_ID,
    ): List<GroupWithState>

    @Upsert
    suspend fun upsertGroups(groups: List<GroupEntity>)

    @Query("DELETE FROM feed_groups WHERE accountId = :accountId AND feedId = :feedId")
    suspend fun clearGroups(
        accountId: String = CacheScope.DEFAULT_ACCOUNT_ID,
        feedId: String = CacheScope.HOME_FEED_ID,
    )

    @Query("SELECT COUNT(*) FROM feed_groups WHERE accountId = :accountId AND feedId = :feedId")
    suspend fun groupCount(
        accountId: String = CacheScope.DEFAULT_ACCOUNT_ID,
        feedId: String = CacheScope.HOME_FEED_ID,
    ): Int

    @Query(
        """
        SELECT * FROM feed_groups
        WHERE accountId = :accountId AND feedId = :feedId AND groupId IN (:groupIds)
        """,
    )
    suspend fun groupsById(
        accountId: String,
        feedId: String,
        groupIds: List<String>,
    ): List<GroupEntity>

    @Query(
        """
        SELECT COALESCE(MAX(sortIndex), -1) FROM feed_groups
        WHERE accountId = :accountId AND feedId = :feedId
        """,
    )
    suspend fun maxSortIndex(accountId: String, feedId: String): Int

    // ── mutable state — the only thing writes touch ──────────────────────────

    @Query("SELECT * FROM item_state WHERE accountId = :accountId AND itemId = :itemId")
    suspend fun stateFor(
        itemId: String,
        accountId: String = CacheScope.DEFAULT_ACCOUNT_ID,
    ): ItemStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putState(state: ItemStateEntity)

    /**
     * Seeds state from a server payload **without clobbering a local edit**.
     *
     * `INSERT OR IGNORE` is deliberate: if a row already exists it is either
     * the user's optimistic value or a confirmed one, and either way it is
     * fresher than a page fetch that may have been in flight when they tapped.
     */
    @Query(
        """
        INSERT OR IGNORE INTO item_state (accountId, itemId, likeCount, liked, downvoted)
        VALUES (:accountId, :itemId, :likeCount, :liked, :downvoted)
        """,
    )
    suspend fun seedStateIfAbsent(
        itemId: String,
        likeCount: Int,
        liked: Boolean,
        downvoted: Boolean = false,
        accountId: String = CacheScope.DEFAULT_ACCOUNT_ID,
    )

    /** Drop interaction snapshots no longer referenced by any cached feed. */
    @Query(
        """
        DELETE FROM item_state
        WHERE accountId = :accountId
          AND itemId NOT IN (SELECT groupId FROM feed_groups WHERE accountId = :accountId)
          AND itemId NOT IN (SELECT postId FROM media_feed_entries WHERE accountId = :accountId)
          AND itemId NOT IN (SELECT itemId FROM vote_outbox WHERE accountId = :accountId)
        """,
    )
    suspend fun pruneOrphanedItemState(accountId: String)

    // ── durable write outbox ────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueueVote(vote: PendingVoteEntity)

    @Query("SELECT * FROM vote_outbox WHERE accountId = :accountId ORDER BY createdAt ASC LIMIT :limit")
    suspend fun pendingVotes(
        accountId: String = CacheScope.DEFAULT_ACCOUNT_ID,
        limit: Int = 50,
    ): List<PendingVoteEntity>

    @Query("SELECT * FROM vote_outbox WHERE accountId = :accountId AND itemId = :itemId")
    suspend fun pendingVote(
        itemId: String,
        accountId: String = CacheScope.DEFAULT_ACCOUNT_ID,
    ): PendingVoteEntity?

    @Query("DELETE FROM vote_outbox WHERE accountId = :accountId AND itemId = :itemId AND mutationId = :mutationId")
    suspend fun deletePendingVote(
        itemId: String,
        mutationId: String,
        accountId: String = CacheScope.DEFAULT_ACCOUNT_ID,
    )

    /** Ignore a late response if a newer desired vote replaced this outbox row. */
    @Transaction
    suspend fun confirmVote(
        itemId: String,
        mutationId: String,
        likeCount: Int,
        value: Int,
        accountId: String = CacheScope.DEFAULT_ACCOUNT_ID,
    ) {
        if (pendingVote(itemId, accountId)?.mutationId != mutationId) return
        putState(ItemStateEntity(
            itemId = itemId,
            likeCount = likeCount,
            liked = value == 1,
            downvoted = value == -1,
            accountId = accountId,
        ))
        deletePendingVote(itemId, mutationId, accountId)
    }

    // ── cursors ─────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRemoteKey(key: RemoteKeyEntity)

    @Query("SELECT * FROM remote_keys WHERE accountId = :accountId AND feedId = :feedId")
    suspend fun remoteKey(
        feedId: String,
        accountId: String = CacheScope.DEFAULT_ACCOUNT_ID,
    ): RemoteKeyEntity?

    @Query("DELETE FROM remote_keys WHERE accountId = :accountId AND feedId = :feedId")
    suspend fun clearRemoteKeys(
        accountId: String = CacheScope.DEFAULT_ACCOUNT_ID,
        feedId: String = CacheScope.HOME_FEED_ID,
    )

    @Query("SELECT * FROM sync_metadata WHERE accountId = :accountId AND scopeKey = :scopeKey")
    suspend fun syncMetadata(accountId: String, scopeKey: String): SyncMetadataEntity?

    @Upsert
    suspend fun putSyncMetadata(metadata: SyncMetadataEntity)
}
