package dev.readthat.communities.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.readthat.communities.domain.ClearCommunityVisitsUseCase
import dev.readthat.communities.domain.CommunityDrawerSnapshot
import dev.readthat.communities.domain.ObserveCommunityDrawerUseCase
import dev.readthat.communities.domain.RecordCommunityVisitUseCase
import dev.readthat.communities.domain.RefreshCommunityDrawerUseCase
import dev.readthat.communities.domain.RemoveCommunityVisitUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CommunityDrawerUiState(
    val snapshot: CommunityDrawerSnapshot = CommunityDrawerSnapshot(),
    val showAllRecents: Boolean = false,
    val communitiesExpanded: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
)

class CommunityDrawerViewModel(
    observe: ObserveCommunityDrawerUseCase,
    private val refresh: RefreshCommunityDrawerUseCase,
    private val recordVisit: RecordCommunityVisitUseCase,
    private val removeVisit: RemoveCommunityVisitUseCase,
    private val clearVisits: ClearCommunityVisitsUseCase,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CommunityDrawerUiState())
    val state: StateFlow<CommunityDrawerUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            observe().collect { snapshot -> mutableState.update { it.copy(snapshot = snapshot) } }
        }
    }

    fun onOpened() {
        viewModelScope.launch {
            mutableState.update { it.copy(refreshing = true, error = null) }
            try {
                refresh()
                mutableState.update { it.copy(refreshing = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(refreshing = false, error = error.message ?: "Could not refresh communities")
                }
            }
        }
    }

    fun showAllRecents() = mutableState.update { it.copy(showAllRecents = true) }
    fun showDrawer() = mutableState.update { it.copy(showAllRecents = false) }
    fun toggleCommunities() = mutableState.update { it.copy(communitiesExpanded = !it.communitiesExpanded) }

    fun record(name: String, displayName: String? = null) {
        viewModelScope.launch { recordVisit(name, displayName) }
    }

    fun removeRecent(name: String) {
        viewModelScope.launch { removeVisit(name) }
    }

    fun clearRecent() {
        viewModelScope.launch { clearVisits() }
    }

    fun retry() = onOpened()
}
