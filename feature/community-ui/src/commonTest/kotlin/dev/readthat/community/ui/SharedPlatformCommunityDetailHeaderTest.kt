package dev.readthat.community.ui

import dev.readthat.client.SharedCommunityDetailState
import dev.readthat.communitydetail.domain.CommunityDetail
import dev.readthat.image.ui.PlatformImageKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SharedPlatformCommunityDetailHeaderTest {
    @Test
    fun avatarIdentityUsesCommunityVersionAndStableResourcePath() {
        val state = SharedCommunityDetailState(detail = community(updatedAt = 7L))
        val first = communityAvatarImageRequest(state, "fallback", "https://cdn.example/c/a.png?sig=one")
        val refreshed = communityAvatarImageRequest(state, "fallback", "https://cdn.example/c/a.png?sig=two")
        val updated = communityAvatarImageRequest(
            state.copy(detail = community(updatedAt = 8L)),
            "fallback",
            "https://cdn.example/c/a.png?sig=two",
        )

        assertEquals(PlatformImageKind.Avatar, first.kind)
        assertEquals(first.cacheKey, refreshed.cacheKey)
        assertNotEquals(first.cacheKey, updated.cacheKey)
    }

    @Test
    fun unloadedCommunityUsesNormalizedRouteName() {
        val request = communityAvatarImageRequest(
            SharedCommunityDetailState(),
            "r/Kotlin",
            "https://cdn.example/kotlin.png",
        )

        kotlin.test.assertTrue(request.cacheKey.startsWith("community-avatar:kotlin:0:"))
    }

    private fun community(updatedAt: Long) = CommunityDetail(
        id = "community-1",
        name = "kotlin",
        displayName = "Kotlin",
        description = "",
        accessType = "public",
        viewerRole = null,
        subscriberCount = 1,
        updatedAt = updatedAt,
    )
}
