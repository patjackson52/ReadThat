package dev.readthat.client

import dev.readthat.shared.AuthMode
import dev.readthat.shared.SessionState
import dev.readthat.shared.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SharedAuthControllerTest {
    @Test
    fun loginIsSingleFlightAndPublishesTheSharedSession() = runTest {
        val source = FakeAuthSource()
        val authenticated = mutableListOf<UserProfile>()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val controller = SharedAuthController(source, scope, authenticated::add)
        controller.setMode(AuthMode.Login)
        controller.setUsername("reader_1")
        controller.setPassword("long-password")

        controller.submit()
        controller.submit()
        advanceUntilIdle()

        assertEquals(1, source.loginCalls)
        assertEquals(source.user, authenticated.single())
        assertIs<SessionState.SignedIn>(controller.state.value.session)
        assertFalse(controller.state.value.form.submitting)
        assertEquals("", controller.state.value.form.password)
        scope.cancel()
    }

    @Test
    fun failedRestoreLeavesSignInAvailableWithARecoverableMessage() = runTest {
        val source = FakeAuthSource(restoreFailure = IllegalStateException("Keychain unavailable"))
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val controller = SharedAuthController(source, scope)

        assertNull(controller.restoreNow())
        advanceUntilIdle()

        assertEquals(SessionState.SignedOut, controller.state.value.session)
        assertTrue(controller.state.value.backendEnabled)
        assertEquals("Keychain unavailable", controller.state.value.message)
        scope.cancel()
    }

    @Test
    fun offlineLogoutStillClearsPresentationAndInvokesCompatibilityHook() = runTest {
        val source = FakeAuthSource(logoutFailure = IllegalStateException("offline")).apply {
            session.value = SessionState.SignedIn(user)
        }
        val warnings = mutableListOf<String?>()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val controller = SharedAuthController(source, scope, onSignedOut = warnings::add)
        controller.setUsername("reader_1")

        controller.logout()
        advanceUntilIdle()

        assertEquals(SessionState.SignedOut, controller.state.value.session)
        assertEquals("", controller.state.value.form.username)
        assertEquals("Signed out locally. The server could not be reached.", warnings.single())
        scope.cancel()
    }
}

private class FakeAuthSource(
    private val restoreFailure: Throwable? = null,
    private val logoutFailure: Throwable? = null,
) : SharedAuthDataSource {
    val user = UserProfile("account-1", "reader_1", "Reader One")
    override val session = MutableStateFlow<SessionState>(SessionState.Restoring)
    override val enabled: Boolean = true
    var loginCalls = 0

    override suspend fun restore(): UserProfile? {
        restoreFailure?.let {
            session.value = SessionState.SignedOut
            throw it
        }
        session.value = SessionState.SignedOut
        return null
    }

    override suspend fun register(username: String, password: String, displayName: String): UserProfile {
        session.value = SessionState.SignedIn(user)
        return user
    }

    override suspend fun login(username: String, password: String): UserProfile {
        loginCalls += 1
        session.value = SessionState.SignedIn(user)
        return user
    }

    override suspend fun logout() {
        session.value = SessionState.SignedOut
        logoutFailure?.let { throw it }
    }
}
