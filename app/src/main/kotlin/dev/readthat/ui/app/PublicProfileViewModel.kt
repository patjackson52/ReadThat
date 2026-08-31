package dev.readthat.ui.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.readthat.data.backend.BackendRepository
import dev.readthat.shared.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PublicProfileUiState(
    val user: UserProfile? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

/** Mature Android repository implementation retained as a migration reference. */
@Suppress("unused")
class LegacyPublicProfileViewModel(
    repository: BackendRepository,
    username: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(PublicProfileUiState())
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { repository.getUser(username) }
                .onSuccess { mutableState.value = PublicProfileUiState(user = it, loading = false) }
                .onFailure {
                    mutableState.value = PublicProfileUiState(
                        loading = false,
                        error = it.message ?: "Could not load profile",
                    )
                }
        }
    }
}
