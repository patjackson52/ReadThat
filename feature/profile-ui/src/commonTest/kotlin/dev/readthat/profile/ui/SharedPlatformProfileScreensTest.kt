package dev.readthat.profile.ui

import dev.readthat.image.ui.PlatformImageKind
import dev.readthat.shared.UserProfile
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedPlatformProfileScreensTest {
    @Test
    fun avatarRequestIgnoresExpiringQueryButChangesWithResourcePath() {
        val user = testUser()
        val first = profileAvatarImageRequest(user, "https://cdn.example/avatar/a.jpg?token=one")
        val refreshed = profileAvatarImageRequest(user, "https://cdn.example/avatar/a.jpg?token=two")
        val replaced = profileAvatarImageRequest(user, "https://cdn.example/avatar/b.jpg?token=two")

        assertEquals(PlatformImageKind.Avatar, first.kind)
        assertEquals(first.cacheKey, refreshed.cacheKey)
        kotlin.test.assertNotEquals(first.cacheKey, replaced.cacheKey)
    }

    @Test
    fun emptyDisplayNameHasSafeFallbackInitial() {
        assertEquals("U", profileInitial("  "))
        assertEquals("R", profileInitial(" readthat"))
    }

    private fun testUser() = UserProfile(
        id = "user-42",
        username = "reader",
        displayName = "Reader",
        updatedAt = 99L,
    )
}
