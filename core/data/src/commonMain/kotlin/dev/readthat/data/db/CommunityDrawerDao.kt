package dev.readthat.data.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CommunityDrawerDao {
    @Query(
        "SELECT * FROM community_memberships WHERE accountId = :accountId " +
            "ORDER BY name COLLATE NOCASE, id",
    )
    fun observeMemberships(accountId: String): Flow<List<CommunityMembershipEntity>>

    @Query(
        "SELECT * FROM community_visits WHERE accountId = :accountId " +
            "ORDER BY visitedAt DESC, name COLLATE NOCASE LIMIT :limit",
    )
    fun observeVisits(accountId: String, limit: Int = 50): Flow<List<CommunityVisitEntity>>

    @Query("SELECT * FROM community_drawer_sync WHERE accountId = :accountId")
    fun observeSyncState(accountId: String): Flow<CommunityDrawerSyncEntity?>

    @Query("SELECT * FROM community_drawer_sync WHERE accountId = :accountId")
    suspend fun syncState(accountId: String): CommunityDrawerSyncEntity?

    @Query(
        "SELECT * FROM community_visit_outbox WHERE accountId = :accountId " +
            "ORDER BY createdAt, mutationId LIMIT :limit",
    )
    suspend fun pendingMutations(accountId: String, limit: Int = 50): List<CommunityVisitMutationEntity>

    @Query("SELECT COUNT(*) FROM community_visit_outbox WHERE accountId = :accountId")
    suspend fun pendingMutationCount(accountId: String): Int

    @Query("SELECT MAX(createdAt) FROM community_visit_outbox WHERE accountId = :accountId")
    suspend fun latestMutationTime(accountId: String): Long?

    @Upsert
    suspend fun upsertMemberships(rows: List<CommunityMembershipEntity>)

    @Upsert
    suspend fun upsertVisits(rows: List<CommunityVisitEntity>)

    @Upsert
    suspend fun upsertVisit(row: CommunityVisitEntity)

    @Upsert
    suspend fun upsertMutation(row: CommunityVisitMutationEntity)

    @Upsert
    suspend fun upsertSync(row: CommunityDrawerSyncEntity)

    @Query("DELETE FROM community_memberships WHERE accountId = :accountId AND source = 'remote'")
    suspend fun deleteRemoteMemberships(accountId: String)

    @Query("DELETE FROM community_visits WHERE accountId = :accountId")
    suspend fun deleteVisits(accountId: String)

    @Query("DELETE FROM community_visits WHERE accountId = :accountId AND name = :name")
    suspend fun deleteVisit(accountId: String, name: String)

    @Query("DELETE FROM community_visit_outbox WHERE mutationId IN (:mutationIds)")
    suspend fun deleteMutations(mutationIds: List<String>)

    @Query("DELETE FROM community_visit_outbox WHERE accountId = :accountId AND name = :name")
    suspend fun deletePendingForName(accountId: String, name: String)

    @Query("DELETE FROM community_visit_outbox WHERE accountId = :accountId")
    suspend fun deletePendingForAccount(accountId: String)

    @Query(
        "DELETE FROM community_visits WHERE accountId = :accountId AND name NOT IN " +
            "(SELECT name FROM community_visits WHERE accountId = :accountId " +
            "ORDER BY visitedAt DESC, name COLLATE NOCASE LIMIT :keep)",
    )
    suspend fun trimVisits(accountId: String, keep: Int = 50)

    @Query(
        "DELETE FROM community_memberships WHERE accountId = :accountId AND source = 'optimistic' " +
            "AND name = :name",
    )
    suspend fun deleteOptimisticMembership(accountId: String, name: String)

    @Transaction
    suspend fun replaceRemoteSnapshot(
        accountId: String,
        memberships: List<CommunityMembershipEntity>,
        visits: List<CommunityVisitEntity>,
        sync: CommunityDrawerSyncEntity,
        preserveLocalVisits: Boolean,
    ) {
        deleteRemoteMemberships(accountId)
        upsertMemberships(memberships)
        if (!preserveLocalVisits) {
            deleteVisits(accountId)
            upsertVisits(visits)
        }
        upsertSync(sync)
    }

    @Transaction
    suspend fun recordVisit(
        visit: CommunityVisitEntity,
        mutation: CommunityVisitMutationEntity,
    ) {
        deletePendingForName(visit.accountId, visit.name)
        upsertVisit(visit)
        upsertMutation(mutation)
        trimVisits(visit.accountId)
    }

    @Transaction
    suspend fun removeVisit(
        accountId: String,
        name: String,
        mutation: CommunityVisitMutationEntity,
    ) {
        deletePendingForName(accountId, name)
        deleteVisit(accountId, name)
        upsertMutation(mutation)
    }

    @Transaction
    suspend fun clearVisits(
        accountId: String,
        mutation: CommunityVisitMutationEntity,
    ) {
        deletePendingForAccount(accountId)
        deleteVisits(accountId)
        upsertMutation(mutation)
    }
}
