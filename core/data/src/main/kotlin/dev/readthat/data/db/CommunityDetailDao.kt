package dev.readthat.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CommunityDetailDao {
    @Query("SELECT * FROM subreddits WHERE accountId = :accountId AND name = :name")
    fun observe(accountId: String, name: String): Flow<SubredditEntity?>

    @Query("SELECT * FROM subreddits WHERE accountId = :accountId AND name = :name")
    suspend fun get(accountId: String, name: String): SubredditEntity?

    @Query(
        "SELECT * FROM community_membership_outbox WHERE accountId = :accountId " +
            "AND name = :name",
    )
    suspend fun pending(accountId: String, name: String): PendingCommunityMembershipEntity?

    @Query(
        "SELECT * FROM community_membership_outbox WHERE accountId = :accountId " +
            "ORDER BY createdAt, name LIMIT :limit",
    )
    suspend fun pending(accountId: String, limit: Int = 50): List<PendingCommunityMembershipEntity>

    @Query("SELECT COUNT(*) FROM community_membership_outbox WHERE accountId = :accountId")
    suspend fun pendingCount(accountId: String): Int

    @Upsert
    suspend fun upsertDetail(row: SubredditEntity)

    @Upsert
    suspend fun upsertMembership(row: CommunityMembershipEntity)

    @Upsert
    suspend fun upsertPending(row: PendingCommunityMembershipEntity)

    @Query("DELETE FROM community_memberships WHERE accountId = :accountId AND name = :name")
    suspend fun deleteMembership(accountId: String, name: String)

    @Query(
        "DELETE FROM community_membership_outbox WHERE accountId = :accountId AND name = :name " +
            "AND mutationId = :mutationId",
    )
    suspend fun deletePending(accountId: String, name: String, mutationId: String)

    @Transaction
    suspend fun commitRemote(
        detail: SubredditEntity,
        membership: CommunityMembershipEntity?,
    ) {
        upsertDetail(detail)
        if (membership == null) deleteMembership(detail.accountId, detail.name)
        else upsertMembership(membership)
    }

    @Transaction
    suspend fun commitOptimistic(
        detail: SubredditEntity,
        membership: CommunityMembershipEntity?,
        pending: PendingCommunityMembershipEntity,
    ) {
        upsertDetail(detail)
        if (membership == null) deleteMembership(detail.accountId, detail.name)
        else upsertMembership(membership)
        upsertPending(pending)
    }

    /** Ignore a late acknowledgement after the desired state changed again. */
    @Transaction
    suspend fun confirm(
        detail: SubredditEntity,
        membership: CommunityMembershipEntity?,
        mutationId: String,
    ) {
        val current = pending(detail.accountId, detail.name)
        if (current?.mutationId != mutationId) return
        commitRemote(detail, membership)
        deletePending(detail.accountId, detail.name, mutationId)
    }
}
