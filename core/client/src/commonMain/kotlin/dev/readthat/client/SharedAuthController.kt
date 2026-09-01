package dev.readthat.client

import dev.readthat.shared.AuthAction
import dev.readthat.shared.AuthForm
import dev.readthat.shared.AuthMode
import dev.readthat.shared.SessionState
import dev.readthat.shared.UserProfile
import dev.readthat.shared.reduceAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SharedAuthState(
    val session: SessionState = SessionState.Restoring,
    val form: AuthForm = AuthForm(),
    val backendEnabled: Boolean = false,
    val message: String? = null,
)

internal interface SharedAuthDataSource {
    val session: StateFlow<SessionState>
    val enabled: Boolean
    suspend fun restore(): UserProfile?
    suspend fun register(username: String, password: String, displayName: String): UserProfile
    suspend fun login(username: String, password: String): UserProfile
    suspend fun logout()
}

private class ReadThatAuthDataSource(
    private val client: ReadThatClient,
) : SharedAuthDataSource {
    override val session: StateFlow<SessionState> = client.session
    override val enabled: Boolean get() = client.enabled
    override suspend fun restore(): UserProfile? = client.restoreSession()
    override suspend fun register(
        username: String,
        password: String,
        displayName: String,
    ): UserProfile = client.register(username, password, displayName)
    override suspend fun login(username: String, password: String): UserProfile =
        client.login(username, password)
    override suspend fun logout() = client.logout()
}

/**
 * Canonical authentication state machine for every host.
 *
 * Tokens remain inside the platform secure store owned by [ReadThatClient]; this controller owns
 * only form state, single-flight operations, session restoration, and user-visible recovery.
 */
class SharedAuthController internal constructor(
    private val source: SharedAuthDataSource,
    private val coroutineScope: CoroutineScope,
    private val onAuthenticated: (UserProfile) -> Unit = {},
    private val onSignedOut: (warning: String?) -> Unit = {},
) {
    constructor(
        client: ReadThatClient,
        coroutineScope: CoroutineScope,
        onAuthenticated: (UserProfile) -> Unit = {},
        onSignedOut: (warning: String?) -> Unit = {},
    ) : this(ReadThatAuthDataSource(client), coroutineScope, onAuthenticated, onSignedOut)

    private val mutableForm = MutableStateFlow(AuthForm())
    val form: StateFlow<AuthForm> = mutableForm.asStateFlow()
    val session: StateFlow<SessionState> = source.session
    private val mutableMessage = MutableStateFlow<String?>(null)
    val state: StateFlow<SharedAuthState> = combine(
        source.session,
        mutableForm,
        mutableMessage,
    ) { session, form, message ->
        SharedAuthState(session, form, source.enabled, message)
    }.stateIn(
        coroutineScope,
        SharingStarted.Eagerly,
        SharedAuthState(source.session.value, backendEnabled = source.enabled),
    )

    private var restoreJob: Job? = null
    private var authJob: Job? = null

    fun restore() {
        if (restoreJob?.isActive == true) return
        restoreJob = coroutineScope.launch { restoreNow() }
    }

    suspend fun restoreNow(): UserProfile? = try {
        source.restore()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        mutableMessage.value = error.message?.takeIf(String::isNotBlank)
            ?: "Could not restore your session. You can sign in again."
        null
    }

    fun setMode(mode: AuthMode) = dispatch(AuthAction.SetMode(mode))
    fun setUsername(value: String) = dispatch(AuthAction.SetUsername(value))
    fun setDisplayName(value: String) = dispatch(AuthAction.SetDisplayName(value))
    fun setPassword(value: String) = dispatch(AuthAction.SetPassword(value))
    fun togglePasswordVisibility() = dispatch(AuthAction.TogglePasswordVisibility)

    fun submit() {
        val form = mutableForm.value
        if (!form.canSubmit || !source.enabled || authJob?.isActive == true) return
        dispatch(AuthAction.Submit)
        authJob = coroutineScope.launch {
            try {
                val user = when (form.mode) {
                    AuthMode.Register -> source.register(form.username, form.password, form.displayName)
                    AuthMode.Login -> source.login(form.username, form.password)
                }
                mutableForm.value = AuthForm(mode = form.mode)
                mutableMessage.value = null
                onAuthenticated(user)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                dispatch(AuthAction.Failed(error.message ?: "Unable to sign in"))
            }
        }
    }

    fun logout() {
        authJob?.cancel()
        authJob = coroutineScope.launch {
            var warning: String? = null
            try {
                source.logout()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // ReadThatClient clears tokens, bookmark, Room principal, and session state in a
                // finally block. A network failure therefore changes only the acknowledgement.
                warning = "Signed out locally. The server could not be reached."
            } finally {
                mutableForm.value = AuthForm()
                mutableMessage.value = warning
                onSignedOut(warning)
            }
        }
    }

    fun clearMessage() {
        mutableMessage.value = null
    }

    private fun dispatch(action: AuthAction) {
        mutableForm.value = reduceAuth(mutableForm.value, action)
    }
}
