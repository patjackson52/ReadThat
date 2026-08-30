package dev.readthat.flows.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.readthat.flows.model.Connectivity
import dev.readthat.flows.model.LoadState
import dev.readthat.flows.model.Post
import dev.readthat.flows.model.Settings
import dev.readthat.flows.model.User
import dev.readthat.flows.repo.FeedRepository
import dev.readthat.flows.repo.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DashboardUiState(
    val user: LoadState<User> = LoadState.Loading,
    val posts: List<Post> = emptyList(),
    val settings: Settings = Settings(),
    val connectivity: Connectivity = Connectivity.ONLINE,
    val canAutoplay: Boolean = true,
) {
    val isOffline: Boolean get() = connectivity == Connectivity.OFFLINE
    val userName: String? get() = user.dataOrNull?.displayName
}

/** One-shot effects. Not state — must not be replayed on rotation. */
sealed interface DashboardEvent {
    data class ShowSnackbar(val message: String) : DashboardEvent
    data object NavigateToLogin : DashboardEvent
}

/**
 * Combines several streams into ONE state object the UI collects.
 *
 * Two decisions here get asked about constantly:
 *
 * **1. Why one StateFlow instead of five.**
 * Five separate flows means five independent recompositions per change and five
 * chances for the UI to render an inconsistent mix (new user, old settings). One
 * combined state object updates atomically.
 *
 * **2. `SharingStarted.WhileSubscribed(5_000)`.**
 * - `Eagerly` — upstream runs forever, even with the app backgrounded and nobody
 *   listening. Wasteful.
 * - `Lazily` — starts on first collector but never stops. Same leak, delayed.
 * - `WhileSubscribed(5_000)` — stops 5s after the last collector leaves. The 5s
 *   grace period is what makes a configuration change (rotation) *not* tear down and
 *   re-run the upstream, because the new collector attaches well inside the window.
 */
class DashboardViewModel(
    private val userRepository: UserRepository,
    private val feedRepository: FeedRepository,
    connectivity: Flow<Connectivity>,
    userId: String = "patrick",
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val workScope: CoroutineScope = scope ?: viewModelScope

    val uiState: StateFlow<DashboardUiState> =
        combine(
            userRepository.refreshableUser(userId),
            feedRepository.feedSnapshot(connectivity),
        ) { user, snapshot ->
            DashboardUiState(
                user = user,
                posts = snapshot.posts,
                settings = snapshot.settings,
                connectivity = snapshot.connectivity,
                canAutoplay = snapshot.canAutoplay,
            )
        }.stateIn(
            scope = workScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = DashboardUiState(),
        )

    // -----------------------------------------------------------------------
    // One-shot events: Channel + receiveAsFlow
    // -----------------------------------------------------------------------

    /**
     * Why a Channel and not a StateFlow.
     *
     * A StateFlow always replays its current value to a new collector, so a
     * snackbar event would fire again on every rotation. A Channel is a *queue*:
     * each element is delivered exactly once, to exactly one collector, and nothing
     * is replayed. `receiveAsFlow` exposes it read-only.
     *
     * `BufferOverflow.DROP_OLDEST` with a small buffer means a burst of events while
     * the UI is backgrounded can't suspend the emitter or grow unbounded.
     */
    private val _events = Channel<DashboardEvent>(
        capacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: Flow<DashboardEvent> = _events.receiveAsFlow()

    /**
     * The alternative, for comparison: a SharedFlow with `replay = 0`.
     * Broadcasts to *all* current collectors (a Channel delivers to only one), but
     * an event emitted with no collector attached is simply lost.
     */
    private val _broadcasts = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val broadcasts: Flow<String> = _broadcasts

    fun refresh() {
        userRepository.refresh()
        _events.trySend(DashboardEvent.ShowSnackbar("Refreshing…"))
    }

    fun emitBroadcast(message: String) {
        workScope.launch { _broadcasts.emit(message) }
    }

    fun signOut() {
        _events.trySend(DashboardEvent.NavigateToLogin)
    }

    companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
