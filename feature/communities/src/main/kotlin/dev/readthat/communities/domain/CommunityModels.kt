package dev.readthat.communities.domain

import kotlinx.serialization.Serializable

@Serializable
data class DrawerCommunity(
    val id: String,
    val name: String,
    val displayName: String,
    val accessType: String,
    val role: String,
)

@Serializable
data class RecentCommunity(
    val id: String,
    val name: String,
    val displayName: String,
    val visitedAt: Long,
)

data class CommunityDrawerSnapshot(
    val communities: List<DrawerCommunity> = emptyList(),
    val recentlyVisited: List<RecentCommunity> = emptyList(),
    val lastSuccessfulSyncAt: Long? = null,
)

@Serializable
data class CommunityDrawerPage(
    val communities: List<DrawerCommunity>,
    val recentlyVisited: List<RecentCommunity> = emptyList(),
    val nextCursor: String? = null,
    val validator: String,
)

sealed interface CommunityDrawerRemoteResult {
    data object NotModified : CommunityDrawerRemoteResult
    data class Page(val value: CommunityDrawerPage) : CommunityDrawerRemoteResult
}

@Serializable
data class CommunityVisitCommand(
    val id: String,
    val operation: String,
    val name: String? = null,
    val occurredAt: Long,
)

interface CommunityDrawerRemoteSource {
    suspend fun fetchDrawer(
        validator: String?,
        cursor: String?,
        limit: Int,
    ): CommunityDrawerRemoteResult

    suspend fun syncVisits(commands: List<CommunityVisitCommand>): Set<String>
}
