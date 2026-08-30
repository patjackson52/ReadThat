package dev.readthat.communities.domain

import dev.readthat.communities.data.CommunityRepository
import dev.readthat.shared.CreateCommunityDraft

class ObserveCommunityDrawerUseCase(private val repository: CommunityRepository) {
    operator fun invoke() = repository.snapshot
}

class RefreshCommunityDrawerUseCase(private val repository: CommunityRepository) {
    suspend operator fun invoke(force: Boolean = false) = repository.refresh(force)
}

class RecordCommunityVisitUseCase(private val repository: CommunityRepository) {
    suspend operator fun invoke(name: String, displayName: String? = null) = repository.recordVisit(name, displayName)
}

class RemoveCommunityVisitUseCase(private val repository: CommunityRepository) {
    suspend operator fun invoke(name: String) = repository.removeVisit(name)
}

class ClearCommunityVisitsUseCase(private val repository: CommunityRepository) {
    suspend operator fun invoke() = repository.clearVisits()
}

class QueueCommunityCreationUseCase(private val repository: CommunityRepository) {
    suspend operator fun invoke(draft: CreateCommunityDraft): String = repository.queueCommunity(draft)
}
