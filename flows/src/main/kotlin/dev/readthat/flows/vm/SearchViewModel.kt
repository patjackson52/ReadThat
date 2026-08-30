package dev.readthat.flows.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.readthat.flows.model.LoadState
import dev.readthat.flows.model.Post
import dev.readthat.flows.source.FakeRemoteSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * The type-ahead search pipeline — the single most-asked Flow question in Android
 * interviews, because every operator in it is load-bearing.
 *
 *   query
 *     .debounce(300)             don't fire on every keystroke
 *     .distinctUntilChanged()    "and" -> "and" (cursor move) must not re-query
 *     .flatMapLatest { search }  a new query CANCELS the in-flight one
 *     .catch { }                 a failed search must not kill the pipeline
 *     .stateIn(...)              cache the latest result for the UI
 *
 * `flatMapLatest` is the important one. With `flatMapConcat` you'd queue every
 * query and show stale results in order; with `flatMapMerge` you'd race them and
 * the slowest response would win. `flatMapLatest` cancels the previous request the
 * moment a newer query arrives — which is exactly what a search box needs.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val remote: FakeRemoteSource,
    scope: CoroutineScope? = null,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
) : ViewModel() {

    private val workScope: CoroutineScope = scope ?: viewModelScope

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun onQueryChanged(value: String) { _query.value = value }

    val results: StateFlow<LoadState<List<Post>>> =
        _query
            .debounce(debounceMs)
            .distinctUntilChanged()
            .flatMapLatest { q ->
                if (q.isBlank()) {
                    flowOf(LoadState.Success(emptyList()))
                } else {
                    searchFlow(q)
                }
            }
            .stateIn(
                scope = workScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = LoadState.Success(emptyList()),
            )

    private fun searchFlow(q: String): Flow<LoadState<List<Post>>> = flow {
        emit(remote.search(q))
    }
        .map<List<Post>, LoadState<List<Post>>> { LoadState.Success(it) }
        .onStart { emit(LoadState.Loading) }
        // catch is placed INSIDE the flatMapLatest branch on purpose: a failure
        // terminates only this query's inner flow, leaving the outer pipeline alive
        // to serve the next keystroke. Catch on the outer flow and one bad search
        // would permanently kill search for the rest of the session.
        .catch { e -> emit(LoadState.Failure(e.message ?: "Search failed", e)) }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 300L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
