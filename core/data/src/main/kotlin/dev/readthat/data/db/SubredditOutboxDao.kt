package dev.readthat.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SubredditOutboxDao {
    @Query("SELECT * FROM subreddit_outbox WHERE mutationId = :mutationId")
    fun observe(mutationId: String): Flow<PendingSubredditEntity?>

    @Query("SELECT * FROM subreddit_outbox WHERE mutationId = :mutationId")
    suspend fun get(mutationId: String): PendingSubredditEntity?

    @Query("SELECT * FROM subreddit_outbox WHERE accountId = :accountId AND name = :name LIMIT 1")
    suspend fun getByName(accountId: String, name: String): PendingSubredditEntity?

    @Query(
        "SELECT * FROM subreddit_outbox WHERE accountId = :accountId " +
            "AND remoteSubredditId IS NULL AND state != 'failed' ORDER BY createdAt",
    )
    suspend fun resumable(accountId: String): List<PendingSubredditEntity>

    @Upsert
    suspend fun upsertPending(pending: PendingSubredditEntity)

    @Upsert
    suspend fun upsertSubreddit(subreddit: SubredditEntity)

    @Upsert
    suspend fun upsertDrawerMembership(membership: CommunityMembershipEntity)

    @Query(
        "UPDATE subreddit_outbox SET state = :state, lastError = :lastError " +
            "WHERE mutationId = :mutationId AND remoteSubredditId IS NULL",
    )
    suspend fun updateProgress(mutationId: String, state: String, lastError: String?)

    @Query(
        "UPDATE subreddit_outbox SET state = 'completed', remoteSubredditId = :remoteId, lastError = NULL " +
            "WHERE mutationId = :mutationId",
    )
    suspend fun markCompleted(mutationId: String, remoteId: String)

    @Query(
        "UPDATE subreddit_outbox SET state = 'failed', lastError = :message " +
            "WHERE mutationId = :mutationId",
    )
    suspend fun markFailed(mutationId: String, message: String)

    @Query(
        "DELETE FROM subreddits WHERE accountId = :accountId AND name = :name AND id = :optimisticId",
    )
    suspend fun removeOptimistic(accountId: String, name: String, optimisticId: String)

    @Query(
        "DELETE FROM community_memberships WHERE accountId = :accountId AND name = :name " +
            "AND source = 'optimistic'",
    )
    suspend fun removeOptimisticDrawerMembership(accountId: String, name: String)

    @Transaction
    suspend fun enqueue(pending: PendingSubredditEntity, optimistic: SubredditEntity) {
        upsertPending(pending)
        upsertSubreddit(optimistic)
    }

    @Transaction
    suspend fun enqueueWithMembership(
        pending: PendingSubredditEntity,
        optimistic: SubredditEntity,
        membership: CommunityMembershipEntity,
    ) {
        enqueue(pending, optimistic)
        upsertDrawerMembership(membership)
    }

    @Transaction
    suspend fun complete(mutationId: String, remote: SubredditEntity) {
        upsertSubreddit(remote)
        markCompleted(mutationId, remote.id)
    }

    @Transaction
    suspend fun completeWithMembership(
        mutationId: String,
        remote: SubredditEntity,
        membership: CommunityMembershipEntity,
    ) {
        complete(mutationId, remote)
        upsertDrawerMembership(membership)
    }

    @Transaction
    suspend fun fail(pending: PendingSubredditEntity, message: String) {
        markFailed(pending.mutationId, message)
        removeOptimistic(pending.accountId, pending.name, pending.mutationId)
        removeOptimisticDrawerMembership(pending.accountId, pending.name)
    }

    @Transaction
    suspend fun retry(pending: PendingSubredditEntity, optimistic: SubredditEntity) {
        upsertSubreddit(optimistic)
        updateProgress(pending.mutationId, "queued", null)
    }

    @Transaction
    suspend fun retryWithMembership(
        pending: PendingSubredditEntity,
        optimistic: SubredditEntity,
        membership: CommunityMembershipEntity,
    ) {
        retry(pending, optimistic)
        upsertDrawerMembership(membership)
    }
}
