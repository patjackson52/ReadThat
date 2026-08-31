package dev.readthat.client

import dev.readthat.shared.LocalPostMedia
import dev.readthat.shared.UserProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SharedProfileControllerTest {
    @Test
    fun `public profile request is normalized and publishes one atomic state`() = runTest {
        val source = FakeProfileDataSource()
        val controller = SharedProfileController(source, this)

        controller.loadPublicProfile("  u/Reader  ")
        assertTrue(controller.state.value.loading)
        advanceUntilIdle()

        assertEquals("reader", source.requestedUsername)
        assertEquals("Reader", controller.state.value.publicProfile?.displayName)
        assertFalse(controller.state.value.loading)
        assertNull(controller.state.value.error)
    }

    @Test
    fun `failed save keeps the draft retryable and successful save adopts server values`() = runTest {
        val source = FakeProfileDataSource(updateError = IllegalStateException("Network unavailable"))
        val controller = SharedProfileController(source, this)
        controller.beginEditing(profile())
        controller.setDisplayName("Updated Reader")
        controller.setBio("Offline-first profile")

        controller.saveProfile()
        advanceUntilIdle()

        assertEquals("Updated Reader", controller.state.value.displayName)
        assertEquals("Offline-first profile", controller.state.value.bio)
        assertEquals("Network unavailable", controller.state.value.error)
        assertFalse(controller.state.value.saving)

        source.updateError = null
        controller.saveProfile()
        advanceUntilIdle()

        assertEquals("Updated Reader", controller.state.value.displayName)
        assertEquals("Offline-first profile", controller.state.value.bio)
        assertNull(controller.state.value.error)
        assertFalse(controller.state.value.saving)
    }

    @Test
    fun `validation is shared and prevents invalid network writes`() = runTest {
        val source = FakeProfileDataSource()
        val controller = SharedProfileController(source, this)
        controller.beginEditing(profile())
        controller.setDisplayName("")

        controller.saveProfile()
        advanceUntilIdle()

        assertEquals("Enter a display name", controller.state.value.error)
        assertEquals(0, source.updateCalls)
    }

    @Test
    fun `avatar acquisition policy is enforced before editor state owns the file`() = runTest {
        val controller = SharedProfileController(FakeProfileDataSource(), this)
        controller.beginEditing(profile())
        val oversized = LocalPostMedia(
            name = "avatar.jpg",
            mimeType = "image/jpeg",
            localPath = "/private/oversized-avatar.jpg",
            byteSize = 11L * 1_048_576L,
            width = 400,
            height = 400,
        )

        controller.setAvatar(listOf(oversized))
        advanceUntilIdle()

        assertNull(controller.state.value.avatar)
        assertEquals("Choose an image smaller than 10 MB", controller.state.value.error)
    }
}

private class FakeProfileDataSource(
    var updateError: Throwable? = null,
) : SharedProfileDataSource {
    var requestedUsername: String? = null
    var updateCalls = 0

    override suspend fun user(username: String, force: Boolean): UserProfile {
        requestedUsername = username
        return profile()
    }

    override suspend fun updateProfile(
        displayName: String,
        bio: String,
        avatar: LocalPostMedia?,
        removeAvatar: Boolean,
    ): UserProfile {
        updateCalls += 1
        updateError?.let { throw it }
        return profile().copy(displayName = displayName, bio = bio, avatarUrl = null)
    }
}

private fun profile() = UserProfile(
    id = "user-1",
    username = "reader",
    displayName = "Reader",
    bio = "Reads things",
)
