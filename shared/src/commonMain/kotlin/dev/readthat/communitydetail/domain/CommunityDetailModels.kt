package dev.readthat.communitydetail.domain

import kotlinx.serialization.Serializable

@Serializable
data class CommunityRule(
    val id: String,
    val title: String,
    val description: String = "",
    val order: Int = 0,
)

@Serializable
data class CommunityDetail(
    val id: String,
    val name: String,
    val displayName: String,
    val description: String,
    val accessType: String,
    val viewerRole: String?,
    val subscriberCount: Int,
    val avatarUrl: String? = null,
    val rules: List<CommunityRule> = emptyList(),
    val updatedAt: Long,
) {
    val isJoined: Boolean get() = viewerRole != null && viewerRole != "banned"
    val canChangeMembership: Boolean get() = viewerRole == null || viewerRole == "subscriber"
}

interface CommunityDetailRemoteSource {
    suspend fun fetch(name: String): CommunityDetail
    suspend fun setJoined(name: String, joined: Boolean): CommunityDetail
}
