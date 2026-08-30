package dev.readthat.flows.repo

import dev.readthat.flows.model.LoadState
import dev.readthat.flows.model.User
import dev.readthat.flows.source.FakeRemoteSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.shareIn

/**
 * Repository-level Flow patterns:
 *
 *   onStart      — emit Loading before the work begins, without a separate flag
 *   retryWhen    — bounded retry with exponential backoff, on *transient* errors only
 *   catch        — convert a terminal upstream failure into a Failure value
 *   flowOn       — move upstream work off the caller's dispatcher
 *   shareIn      — one upstream shared by N collectors (multicast)
 */
class UserRepository(
    private val remote: FakeRemoteSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * A cold, per-collector load.
     *
     * `catch` only sees exceptions from *upstream* of itself — that's why it sits
     * after `retryWhen`, so it fires only once retries are exhausted. Putting
     * `catch` above `retryWhen` would swallow the error before retry ever saw it.
     *
     * `flowOn` likewise affects only upstream operators, so `fetchUser` runs on
     * [ioDispatcher] while the collector stays wherever it was.
     */
    // Note the explicit type argument: without it Kotlin infers
    // Flow<LoadState.Success<User>> from the single emit, and the later
    // Loading/Failure emissions no longer typecheck.
    fun user(id: String): Flow<LoadState<User>> = flow<LoadState<User>> {
        emit(LoadState.Success(remote.fetchUser(id)))
    }
        .retryWhen { cause, attempt ->
            val retryable = cause is IllegalStateException && attempt < MAX_RETRIES
            if (retryable) {
                delay(BASE_BACKOFF_MS * (1 shl attempt.toInt())) // 100, 200, 400...
            }
            retryable
        }
        .catch { cause ->
            emit(LoadState.Failure(cause.message ?: "Unknown error", cause))
        }
        .onStart { emit(LoadState.Loading) }
        .flowOn(ioDispatcher)

    /**
     * A *shared* variant.
     *
     * `shareIn` turns the cold flow hot: the upstream runs once regardless of how
     * many collectors attach. `replay = 1` lets a late subscriber get the most
     * recent emission instead of waiting for the next one.
     *
     * Compare with [user]: collecting that twice performs two network calls.
     * Collecting this twice performs one.
     */
    fun sharedUser(id: String, scope: CoroutineScope): Flow<LoadState<User>> =
        user(id).shareIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            replay = 1,
        )

    /**
     * Manual refresh trigger.
     *
     * A `MutableSharedFlow<Unit>` used as a signal, piped through `flatMapLatest`
     * so a new refresh cancels an in-flight one. This is the standard
     * "pull-to-refresh re-runs the query" wiring.
     */
    private val refreshTrigger = MutableSharedFlow<Unit>(replay = 1)

    init { refreshTrigger.tryEmit(Unit) }

    fun refresh() { refreshTrigger.tryEmit(Unit) }

    @Suppress("OPT_IN_USAGE")
    fun refreshableUser(id: String): Flow<LoadState<User>> =
        refreshTrigger.flatMapLatest { user(id) }

    companion object {
        const val MAX_RETRIES = 3L
        const val BASE_BACKOFF_MS = 100L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
