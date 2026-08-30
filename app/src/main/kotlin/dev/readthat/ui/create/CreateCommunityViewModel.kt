package dev.readthat.ui.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.readthat.communities.domain.QueueCommunityCreationUseCase
import dev.readthat.observability.PerformanceMetric
import dev.readthat.observability.PerformanceSurface
import dev.readthat.observability.PerformanceTelemetry
import dev.readthat.observability.performanceTimer
import dev.readthat.shared.CreateCommunityDraft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CreateCommunityViewModel(
    private val queueCommunity: QueueCommunityCreationUseCase,
) : ViewModel() {
    private val mutableDraft = MutableStateFlow(CreateCommunityDraft())
    val draft: StateFlow<CreateCommunityDraft> = mutableDraft.asStateFlow()

    fun setName(value: String) = update {
        if (value.removePrefix("r/").length <= 21) copy(name = value, error = null) else this
    }
    fun setDisplayName(value: String) = update {
        if (value.length <= 100) copy(displayName = value, error = null) else this
    }
    fun setDescription(value: String) = update {
        if (value.length <= 1_000) copy(description = value, error = null) else this
    }
    fun setAccessType(value: String) = update { copy(accessType = value, error = null) }

    fun submit(onQueued: (mutationId: String) -> Unit) {
        val snapshot = mutableDraft.value
        if (!snapshot.canSubmit) return
        update { copy(submitting = true, error = null) }
        val timer = performanceTimer()
        viewModelScope.launch {
            runCatching {
                val mutationId = queueCommunity(snapshot)
                PerformanceTelemetry.duration(
                    PerformanceMetric.MUTATION_LOCAL_COMMIT,
                    timer,
                    surface = PerformanceSurface.CREATE_POST,
                    attributes = mapOf(
                        "mutation_type" to "subreddit_create",
                        "cache_tier" to "room",
                        "access_type" to snapshot.accessType,
                    ),
                )
                mutationId
            }.onSuccess { mutationId ->
                mutableDraft.value = CreateCommunityDraft()
                onQueued(mutationId)
            }.onFailure { error ->
                update { copy(submitting = false, error = error.message ?: "Could not save community") }
            }
        }
    }

    private inline fun update(block: CreateCommunityDraft.() -> CreateCommunityDraft) {
        mutableDraft.value = mutableDraft.value.block()
    }
}
