package dev.readthat.ui.create

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.readthat.data.db.AppDatabase
import dev.readthat.data.db.AndroidDatabaseProvider
import dev.readthat.data.db.PendingPostEntity
import dev.readthat.data.db.PendingSubredditEntity
import dev.readthat.data.sync.PostUploadScheduler
import dev.readthat.data.sync.SubredditCreationScheduler
import dev.readthat.communities.data.optimisticMembership
import dev.readthat.communities.data.optimisticSubreddit
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CommunityCreationStatusViewModel(
    app: Application,
    private val mutationId: String,
) : AndroidViewModel(app) {
    private val dao = AndroidDatabaseProvider.get(app).subredditOutboxDao()
    val pending: StateFlow<PendingSubredditEntity?> = dao.observe(mutationId).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )

    fun retry() {
        viewModelScope.launch {
            val command = dao.get(mutationId) ?: return@launch
            dao.retryWithMembership(command, command.optimisticSubreddit(), command.optimisticMembership())
            SubredditCreationScheduler.enqueue(getApplication(), mutationId)
        }
    }
}

class PendingPostViewModel(
    app: Application,
    private val mutationId: String,
) : AndroidViewModel(app) {
    private val dao = AndroidDatabaseProvider.get(app).postOutboxDao()
    val pending: StateFlow<PendingPostEntity?> = dao.observe(mutationId).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )

    fun retry() {
        viewModelScope.launch {
            if (dao.get(mutationId) == null) return@launch
            dao.retry(mutationId)
            PostUploadScheduler.enqueue(getApplication(), mutationId)
        }
    }
}
