package dev.readthat.creation.ui

import androidx.compose.ui.graphics.Color
import dev.readthat.communities.domain.CommunityDrawerSnapshot
import dev.readthat.communities.domain.DrawerCommunity
import dev.readthat.communities.domain.RecentCommunity
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedCreationPolicyTest {
    @Test
    fun `community picker puts recent entries first without duplicates`() {
        val snapshot = CommunityDrawerSnapshot(
            recentlyVisited = listOf(RecentCommunity("recent-kotlin", "kotlin", "Kotlin", 5L)),
            communities = listOf(
                DrawerCommunity("android-id", "android", "Android", "public", "member"),
                DrawerCommunity("kotlin-id", "KOTLIN", "Kotlin", "restricted", "moderator"),
            ),
        )

        val choices = snapshot.creationChoices()

        assertEquals(listOf("kotlin", "android"), choices.map { it.name.lowercase() })
        assertEquals("restricted", choices.first().accessType)
        assertEquals("moderator", choices.first().role)
    }

    @Test
    fun `composer colors accept RGB and ARGB and reject malformed values`() {
        val fallback = Color.Magenta
        assertEquals(Color(0xFF112233), colorOr("#112233", fallback))
        assertEquals(Color(0xAA112233), colorOr("AA112233", fallback))
        assertEquals(fallback, colorOr("not-a-color", fallback))
        assertEquals(fallback, colorOr("123", fallback))
    }
}
