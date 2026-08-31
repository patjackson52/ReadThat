package dev.readthat.client

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.readthat.data.db.AppDatabase
import dev.readthat.shared.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SettingsPreference {
    DarkTheme,
    CompactPosts,
    AutoplayVideo,
    AutoplayOnMetered,
    ReduceDataOnMetered,
    ReduceAnimations,
    BlurMatureMedia,
}

data class SharedSettingsState(
    val settings: AppSettings = AppSettings(),
    val saving: Boolean = false,
    val error: String? = null,
)

internal interface SharedSettingsDataSource {
    val settings: StateFlow<AppSettings>
    suspend fun replaceSettings(settings: AppSettings)
}

private class OfflineFirstSettingsDataSource(
    private val repository: OfflineFirstRepository,
) : SharedSettingsDataSource {
    override val settings: StateFlow<AppSettings> = repository.settings

    override suspend fun replaceSettings(settings: AppSettings) {
        repository.updateSettings { settings }
    }
}

/**
 * Platform-neutral settings state machine backed by the shared Room settings row.
 *
 * UI changes are applied optimistically and a conflated single-writer queue persists complete
 * snapshots. This matters for switches: two fast taps on different rows cannot race through two
 * coroutines and accidentally restore an older value. Room remains the durable source of truth,
 * and an unsuccessful write rolls the UI back to its last committed snapshot.
 */
class SharedSettingsController internal constructor(
    private val source: SharedSettingsDataSource,
    coroutineScope: CoroutineScope,
) {
    constructor(
        client: ReadThatClient,
        database: AppDatabase,
        coroutineScope: CoroutineScope,
        accountId: String? = null,
    ) : this(
        OfflineFirstSettingsDataSource(
            OfflineFirstRepository(
                client = client,
                database = database,
                scope = coroutineScope,
                accountIdOverride = accountId,
                maintainGlobalState = false,
            ),
        ),
        coroutineScope,
    )

    internal constructor(
        repository: OfflineFirstRepository,
        coroutineScope: CoroutineScope,
    ) : this(OfflineFirstSettingsDataSource(repository), coroutineScope)

    private val mutableState = MutableStateFlow(SharedSettingsState(source.settings.value))
    val state: StateFlow<SharedSettingsState> = mutableState.asStateFlow()

    private val writes = Channel<AppSettings>(Channel.CONFLATED)
    private var pendingSettings: AppSettings? = null

    init {
        coroutineScope.launch {
            source.settings.collect { persisted ->
                val pending = pendingSettings
                when {
                    pending == null -> mutableState.value = SharedSettingsState(persisted)
                    pending == persisted -> {
                        pendingSettings = null
                        mutableState.value = SharedSettingsState(persisted)
                    }
                    // Ignore an older Room emission while a newer optimistic snapshot is queued.
                    else -> Unit
                }
            }
        }
        coroutineScope.launch {
            for (desired in writes) {
                try {
                    source.replaceSettings(desired)
                    if (pendingSettings == desired && source.settings.value == desired) {
                        pendingSettings = null
                        mutableState.value = SharedSettingsState(desired)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    if (pendingSettings == desired) {
                        pendingSettings = null
                        mutableState.value = SharedSettingsState(
                            settings = source.settings.value,
                            error = error.message ?: "Unable to save settings",
                        )
                    }
                }
            }
        }
    }

    fun setPreference(preference: SettingsPreference, enabled: Boolean) {
        val next = mutableState.value.settings.withPreference(preference, enabled)
        if (next == mutableState.value.settings && mutableState.value.error == null) return
        pendingSettings = next
        mutableState.value = SharedSettingsState(settings = next, saving = true)
        writes.trySend(next)
    }

    fun clearError() {
        if (mutableState.value.error != null) {
            mutableState.value = mutableState.value.copy(error = null)
        }
    }
}

/** Focused lifecycle owner used by the mature Android navigation host. */
class SharedSettingsViewModel(
    client: ReadThatClient,
    database: AppDatabase,
    accountId: String,
) : ViewModel() {
    private val controller = SharedSettingsController(client, database, viewModelScope, accountId)
    val state: StateFlow<SharedSettingsState> = controller.state

    fun setPreference(preference: SettingsPreference, enabled: Boolean) =
        controller.setPreference(preference, enabled)

    fun clearError() = controller.clearError()
}

internal fun AppSettings.withPreference(
    preference: SettingsPreference,
    enabled: Boolean,
): AppSettings = when (preference) {
    SettingsPreference.DarkTheme -> copy(darkTheme = enabled)
    SettingsPreference.CompactPosts -> copy(compactPosts = enabled)
    SettingsPreference.AutoplayVideo -> copy(autoplayVideo = enabled)
    SettingsPreference.AutoplayOnMetered -> copy(autoplayOnMetered = enabled)
    SettingsPreference.ReduceDataOnMetered -> copy(reduceDataOnMetered = enabled)
    SettingsPreference.ReduceAnimations -> copy(reduceAnimations = enabled)
    SettingsPreference.BlurMatureMedia -> copy(blurMatureMedia = enabled)
}
