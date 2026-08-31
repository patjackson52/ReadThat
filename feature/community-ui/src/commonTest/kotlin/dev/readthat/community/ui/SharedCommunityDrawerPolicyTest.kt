package dev.readthat.community.ui

import dev.readthat.communities.domain.DrawerCommunity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedCommunityDrawerPolicyTest {
    @Test
    fun roleLabelsOnlyPromoteModerationRoles() {
        assertEquals("Owner", roleLabel(community("owner")))
        assertEquals("Moderator", roleLabel(community("moderator")))
        assertNull(roleLabel(community("subscriber")))
    }

    @Test
    fun avatarColorIsStableAndOpaqueAcrossPlatforms() {
        val first = communityColorArgb("kotlin")
        assertEquals(first, communityColorArgb("kotlin"))
        assertEquals(0xFF000000, first and 0xFF000000)
    }
}

private fun community(role: String) = DrawerCommunity(
    id = "id",
    name = "kotlin",
    displayName = "Kotlin",
    accessType = "public",
    role = role,
)
