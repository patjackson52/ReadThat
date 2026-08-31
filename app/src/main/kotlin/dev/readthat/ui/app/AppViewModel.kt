package dev.readthat.ui.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.Immutable
import dev.readthat.BuildConfig
import dev.readthat.client.AndroidReadThatClientConfiguration
import dev.readthat.client.AndroidReadThatClientRegistry
import dev.readthat.client.SharedAuthController
import dev.readthat.client.SharedAuthState
import dev.readthat.client.SharedSettingsController
import dev.readthat.data.sync.PostUploadScheduler
import dev.readthat.data.sync.SubredditCreationScheduler
import dev.readthat.data.sync.CommunityVisitSyncScheduler
import dev.readthat.data.sync.CommunityMembershipSyncScheduler
import dev.readthat.shared.AppSettings
import dev.readthat.shared.AuthMode
import dev.readthat.shared.SessionState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class AppUiState(
    val session: SessionState = SessionState.Restoring,
    val settings: AppSettings = AppSettings(),
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val sharedRuntime = AndroidReadThatClientRegistry.get(
        app,
        AndroidReadThatClientConfiguration(
            baseUrl = BuildConfig.READTHAT_API_BASE_URL,
            appVersion = BuildConfig.VERSION_NAME,
            demoUsername = BuildConfig.READTHAT_DEMO_USERNAME,
            demoPassword = BuildConfig.READTHAT_DEMO_PASSWORD,
        ),
    )
    private val authController = SharedAuthController(
        client = sharedRuntime.client,
        coroutineScope = viewModelScope,
        onAuthenticated = { user ->
            viewModelScope.launch { resumePendingMutations(user.id) }
        },
        onSignedOut = { warning ->
            warning?.let(mutableMessages::tryEmit)
        },
    )
    val session: StateFlow<SessionState> = authController.session
    val authState: StateFlow<SharedAuthState> = authController.state

    private val settingsController = SharedSettingsController(
        client = sharedRuntime.client,
        database = sharedRuntime.database,
        coroutineScope = viewModelScope,
    )
    val settings: StateFlow<AppSettings> = settingsController.state
        .map { it.settings }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = mutableMessages.asSharedFlow()

    /** One atomic stream is the UDF boundary consumed by the root UI. */
    val uiState: StateFlow<AppUiState> = combine(
        session,
        settings,
    ) { session, settings ->
        AppUiState(session, settings)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppUiState(),
    )

    init {
        viewModelScope.launch {
            val user = authController.restoreNow()
            user?.let { resumePendingMutations(it.id) }
        }
    }

    fun setAuthMode(mode: AuthMode) = authController.setMode(mode)
    fun updateUsername(value: String) = authController.setUsername(value)
    fun updateDisplayName(value: String) = authController.setDisplayName(value)
    fun updatePassword(value: String) = authController.setPassword(value)
    fun togglePasswordVisibility() = authController.togglePasswordVisibility()
    fun submitAuth() = authController.submit()
    fun clearAuthMessage() = authController.clearMessage()
    fun logout() = authController.logout()

    fun sharePayload(postId: String, title: String) =
        sharedRuntime.client.postSharePayload(postId, title)

    private suspend fun resumePendingMutations(accountId: String) {
        val context = getApplication<Application>()
        SubredditCreationScheduler.resumePending(context, accountId)
        PostUploadScheduler.resumePending(context, accountId)
        CommunityVisitSyncScheduler.resumePending(context, accountId)
        CommunityMembershipSyncScheduler.resumePending(context, accountId)
    }
}
