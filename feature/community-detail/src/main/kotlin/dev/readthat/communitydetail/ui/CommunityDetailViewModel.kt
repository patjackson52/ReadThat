package dev.readthat.communitydetail.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.readthat.communitydetail.data.CommunityDetailRepository
import dev.readthat.communitydetail.domain.CommunityDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CommunityDetailUiState(
    val detail: CommunityDetail? = null,
    val refreshing: Boolean = true,
    val membershipChanging: Boolean = false,
    val initialCacheTier: String? = null,
    val error: String? = null,
)

class CommunityDetailViewModel(
    private val repository: CommunityDetailRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CommunityDetailUiState())
    val state: StateFlow<CommunityDetailUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.detail.collect { detail -> mutableState.update { it.copy(detail = detail) } }
        }
        viewModelScope.launch {
            val cached = repository.cached()
            mutableState.update {
                it.copy(detail = cached ?: it.detail, initialCacheTier = if (cached == null) "network" else "l2")
            }
            refreshInternal()
        }
    }

    fun refresh() {
        viewModelScope.launch { refreshInternal() }
    }

    fun toggleMembership() {
        val current = state.value.detail ?: return
        if (!current.canChangeMembership || state.value.membershipChanging) return
        viewModelScope.launch {
            mutableState.update { it.copy(membershipChanging = true, error = null) }
            try {
                repository.setJoined(!current.isJoined)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(error = error.message ?: "Could not update membership") }
            } finally {
                mutableState.update { it.copy(membershipChanging = false) }
            }
        }
    }

    private suspend fun refreshInternal() {
        mutableState.update { it.copy(refreshing = true, error = null) }
        try {
            repository.refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.update { it.copy(error = error.message ?: "Could not refresh community") }
        } finally {
            mutableState.update { it.copy(refreshing = false) }
        }
    }
}
