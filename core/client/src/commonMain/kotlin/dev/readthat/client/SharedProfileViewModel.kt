package dev.readthat.client

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.readthat.data.db.AppDatabase
import dev.readthat.media.acquisition.MediaAcquisitionPolicies
import dev.readthat.shared.LocalPostMedia
import dev.readthat.shared.UserProfile
import dev.readthat.shared.Validators
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ProfileState(
    val publicProfile: UserProfile? = null,
    val displayName: String = "",
    val bio: String = "",
    val avatar: LocalPostMedia? = null,
    val removeAvatar: Boolean = false,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
)

internal interface SharedProfileDataSource {
    suspend fun user(username: String, force: Boolean = false): UserProfile

    suspend fun updateProfile(
        displayName: String,
        bio: String,
        avatar: LocalPostMedia?,
        removeAvatar: Boolean,
    ): UserProfile
}

private class OfflineFirstProfileDataSource(
    private val repository: OfflineFirstRepository,
) : SharedProfileDataSource {
    override suspend fun user(username: String, force: Boolean): UserProfile =
        repository.user(username, force)

    override suspend fun updateProfile(
        displayName: String,
        bio: String,
        avatar: LocalPostMedia?,
        removeAvatar: Boolean,
    ): UserProfile = repository.updateProfile(displayName, bio, avatar, removeAvatar)
}

/**
 * Lifecycle-neutral profile state machine shared by the application ViewModel and focused hosts.
 * Its StateFlow is the hot presentation cache; [OfflineFirstRepository] supplies account-scoped
 * Room snapshots and network refreshes beneath it.
 */
class SharedProfileController internal constructor(
    private val source: SharedProfileDataSource,
    private val coroutineScope: CoroutineScope,
) {
    constructor(
        client: ReadThatClient,
        database: AppDatabase,
        coroutineScope: CoroutineScope,
        accountId: String? = null,
    ) : this(
        source = OfflineFirstProfileDataSource(
            OfflineFirstRepository(
                client = client,
                database = database,
                scope = coroutineScope,
                accountIdOverride = accountId,
                maintainGlobalState = false,
            ),
        ),
        coroutineScope = coroutineScope,
    )

    private val mutableState = MutableStateFlow(ProfileState())
    val state: StateFlow<ProfileState> = mutableState.asStateFlow()

    private var publicProfileJob: Job? = null
    private var saveJob: Job? = null

    fun loadPublicProfile(username: String, force: Boolean = false) {
        val normalized = username.trim().removePrefix("u/").lowercase()
        if (normalized.isBlank()) {
            mutableState.value = ProfileState(error = "Profile not found")
            return
        }
        publicProfileJob?.cancel()
        mutableState.value = ProfileState(loading = true)
        publicProfileJob = coroutineScope.launch {
            try {
                mutableState.value = ProfileState(publicProfile = source.user(normalized, force))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.value = ProfileState(error = error.message ?: "Unable to load profile")
            }
        }
    }

    fun beginEditing(user: UserProfile) {
        publicProfileJob?.cancel()
        val previous = mutableState.value.avatar
        mutableState.value = ProfileState(displayName = user.displayName, bio = user.bio)
        previous?.let(::deleteInBackground)
    }

    fun setDisplayName(value: String) {
        if (mutableState.value.saving) return
        mutableState.value = mutableState.value.copy(displayName = value.take(50), error = null)
    }

    fun setBio(value: String) {
        if (mutableState.value.saving) return
        mutableState.value = mutableState.value.copy(bio = value.take(500), error = null)
    }

    fun setAvatar(items: List<LocalPostMedia>) {
        if (mutableState.value.saving) {
            items.forEach(::deleteInBackground)
            return
        }
        if (items.isEmpty()) return
        val policy = MediaAcquisitionPolicies.avatar
        val selected = runCatching {
            require(items.size <= policy.maximumItems) { policy.tooManyItemsMessage }
            policy.validate(items.first())
        }.getOrElse { error ->
            items.forEach(::deleteInBackground)
            mutableState.value = mutableState.value.copy(
                error = error.message ?: "The selected profile photo is invalid",
            )
            return
        }
        mutableState.value.avatar?.takeIf { it.localPath != selected.localPath }?.let(::deleteInBackground)
        mutableState.value = mutableState.value.copy(
            avatar = selected,
            removeAvatar = false,
            error = null,
        )
    }

    fun removeAvatar() {
        if (mutableState.value.saving) return
        mutableState.value.avatar?.let(::deleteInBackground)
        mutableState.value = mutableState.value.copy(avatar = null, removeAvatar = true, error = null)
    }

    fun reportError(value: String) {
        if (mutableState.value.saving) return
        mutableState.value = mutableState.value.copy(error = value)
    }

    fun saveProfile(onSaved: (UserProfile) -> Unit = {}) {
        if (saveJob?.isActive == true || mutableState.value.saving) return
        val draft = mutableState.value
        val validationError = Validators.displayName(draft.displayName) ?: Validators.bio(draft.bio)
        if (validationError != null) {
            mutableState.value = draft.copy(error = validationError)
            return
        }
        mutableState.value = draft.copy(saving = true, error = null)
        saveJob = coroutineScope.launch {
            try {
                val updated = source.updateProfile(
                    displayName = draft.displayName,
                    bio = draft.bio,
                    avatar = draft.avatar,
                    removeAvatar = draft.removeAvatar,
                )
                mutableState.value = ProfileState(
                    displayName = updated.displayName,
                    bio = updated.bio,
                )
                onSaved(updated)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // The staged avatar deliberately remains available so Retry does not ask the user
                // to pick the same file again after a transient upload or PATCH failure.
                mutableState.value = mutableState.value.copy(
                    saving = false,
                    error = error.message ?: "Unable to update profile",
                )
            }
        }
    }

    fun discardEditor() {
        saveJob?.cancel()
        saveJob = null
        mutableState.value.avatar?.let(::deleteInBackground)
        mutableState.value = ProfileState()
    }

    suspend fun stagedMediaBytes(media: LocalPostMedia): ByteArray {
        require(media.byteSize in 1..Int.MAX_VALUE.toLong()) { "Staged image size is invalid" }
        return readStagedMedia(media.localPath, 0L, media.byteSize.toInt())
    }

    private fun deleteInBackground(media: LocalPostMedia) {
        coroutineScope.launch { runCatching { deleteStagedMedia(media.localPath) } }
    }
}

/** Focused lifecycle owner used by the mature Android navigation host. */
class SharedProfileViewModel(
    client: ReadThatClient,
    database: AppDatabase,
    accountId: String,
    editorUser: UserProfile? = null,
    publicUsername: String? = null,
) : ViewModel() {
    private val controller = SharedProfileController(client, database, viewModelScope, accountId)
    val state: StateFlow<ProfileState> = controller.state

    init {
        require(editorUser == null || publicUsername == null) {
            "A profile ViewModel cannot edit and display a public profile simultaneously"
        }
        editorUser?.let(controller::beginEditing)
        publicUsername?.let(controller::loadPublicProfile)
    }

    fun retryPublicProfile(username: String) = controller.loadPublicProfile(username, force = true)
    fun setDisplayName(value: String) = controller.setDisplayName(value)
    fun setBio(value: String) = controller.setBio(value)
    fun setAvatar(items: List<LocalPostMedia>) = controller.setAvatar(items)
    fun removeAvatar() = controller.removeAvatar()
    fun reportError(value: String) = controller.reportError(value)
    fun saveProfile(onSaved: (UserProfile) -> Unit = {}) = controller.saveProfile(onSaved)
    fun discardEditor() = controller.discardEditor()
    suspend fun stagedMediaBytes(media: LocalPostMedia): ByteArray = controller.stagedMediaBytes(media)

    override fun onCleared() {
        controller.discardEditor()
        super.onCleared()
    }
}
