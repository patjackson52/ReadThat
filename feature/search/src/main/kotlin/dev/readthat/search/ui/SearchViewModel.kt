package dev.readthat.search.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dev.readthat.search.data.SearchRepository
import dev.readthat.search.domain.SearchDiscover
import dev.readthat.search.domain.SearchItem
import dev.readthat.search.domain.SearchRequest
import dev.readthat.search.domain.SearchSections
import dev.readthat.search.domain.SearchSort
import dev.readthat.search.domain.SearchTime
import dev.readthat.search.domain.SearchType
import dev.readthat.search.domain.SearchTypeahead
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val draftQuery: String = "",
    val submittedQuery: String = "",
    val type: SearchType = SearchType.All,
    val sort: SearchSort = SearchSort.Relevance,
    val time: SearchTime = SearchTime.All,
    val safe: Boolean = true,
    val recent: List<String> = emptyList(),
    val discover: SearchDiscover = SearchDiscover(),
    val typeahead: SearchTypeahead? = null,
    val allSections: SearchSections? = null,
    val loadingAll: Boolean = false,
    val error: String? = null,
) {
    val isSuggesting: Boolean get() = draftQuery.isNotBlank() && draftQuery.trim() != submittedQuery
    val hasResults: Boolean get() = submittedQuery.isNotBlank()
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SearchViewModel(private val repository: SearchRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(SearchUiState())
    val state = mutableState.asStateFlow()
    private var suggestionJob: Job? = null
    private var allSearchJob: Job? = null

    val pagedResults: Flow<PagingData<SearchItem>> = mutableState
        .map { current ->
            current.takeIf { it.submittedQuery.isNotBlank() && it.type != SearchType.All }?.toRequest()
        }
        .distinctUntilChanged()
        .flatMapLatest { request -> request?.let(repository::paged) ?: flowOf(PagingData.empty()) }
        .cachedIn(viewModelScope)

    init {
        viewModelScope.launch { repository.recent.collect { recent -> mutableState.update { it.copy(recent = recent) } } }
        viewModelScope.launch {
            runCatching { repository.discover() }
                .onSuccess { discover -> mutableState.update { it.copy(discover = discover) } }
        }
        viewModelScope.launch { repository.prune() }
    }

    fun onQueryChanged(value: String) {
        if (value.length > 100) return
        mutableState.update { it.copy(draftQuery = value, typeahead = null, error = null) }
        suggestionJob?.cancel()
        val query = value.trim()
        if (query.isBlank()) return
        suggestionJob = viewModelScope.launch {
            delay(250)
            runCatching { repository.typeahead(query) }
                .onSuccess { result ->
                    if (mutableState.value.draftQuery.trim() == query) {
                        mutableState.update { it.copy(typeahead = result) }
                    }
                }
        }
    }

    fun submit(query: String = mutableState.value.draftQuery) {
        val clean = query.trim().replace(Regex("\\s+"), " ")
        if (clean.isBlank()) return
        suggestionJob?.cancel()
        mutableState.update { current ->
            current.copy(
                draftQuery = clean,
                submittedQuery = clean,
                typeahead = null,
                allSections = current.allSections.takeIf { clean == current.submittedQuery },
                error = null,
            )
        }
        viewModelScope.launch { repository.record(clean) }
        if (mutableState.value.type == SearchType.All) loadAll()
    }

    fun selectType(type: SearchType) {
        mutableState.update { current ->
            val compatibleSort = when (type) {
                SearchType.Communities, SearchType.Profiles -> SearchSort.Relevance
                SearchType.Comments -> current.sort.takeIf { sort ->
                    sort in setOf(SearchSort.Relevance, SearchSort.Top, SearchSort.New)
                } ?: SearchSort.Relevance
                else -> current.sort
            }
            current.copy(
                type = type,
                sort = compatibleSort,
                allSections = if (type == SearchType.All && current.type != SearchType.All) null else current.allSections,
                loadingAll = if (type == SearchType.All) current.loadingAll else false,
                error = null,
            )
        }
        if (type == SearchType.All && mutableState.value.submittedQuery.isNotBlank()) {
            loadAll()
        } else {
            allSearchJob?.cancel()
        }
    }

    fun selectSort(sort: SearchSort) {
        mutableState.update { current ->
            current.copy(
                sort = sort,
                allSections = if (current.type == SearchType.All) null else current.allSections,
                error = null,
            )
        }
        if (mutableState.value.type == SearchType.All) loadAll()
    }

    fun selectTime(time: SearchTime) {
        mutableState.update { current ->
            current.copy(
                time = time,
                allSections = if (current.type == SearchType.All) null else current.allSections,
                error = null,
            )
        }
        if (mutableState.value.type == SearchType.All) loadAll()
    }

    fun toggleSafe() {
        mutableState.update { current ->
            current.copy(
                safe = !current.safe,
                allSections = if (current.type == SearchType.All) null else current.allSections,
                error = null,
            )
        }
        if (mutableState.value.type == SearchType.All) loadAll()
    }

    fun clearQuery() {
        suggestionJob?.cancel()
        allSearchJob?.cancel()
        mutableState.update {
            it.copy(
                draftQuery = "",
                submittedQuery = "",
                typeahead = null,
                allSections = null,
                loadingAll = false,
                error = null,
            )
        }
    }

    fun deleteRecent(query: String) { viewModelScope.launch { repository.deleteRecent(query) } }
    fun clearRecent() { viewModelScope.launch { repository.clearRecent() } }

    fun retryAll() = loadAll(force = true)

    private fun loadAll(force: Boolean = false) {
        val request = mutableState.value.toRequest()
        if (request.query.isBlank()) return
        allSearchJob?.cancel()
        allSearchJob = viewModelScope.launch {
            mutableState.update { it.copy(loadingAll = true, error = null) }
            try {
                val page = repository.all(request, force)
                if (mutableState.value.toRequest() == request) {
                    mutableState.update { it.copy(allSections = page.sections, loadingAll = false) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (mutableState.value.toRequest() == request) {
                    mutableState.update {
                        it.copy(loadingAll = false, error = error.message ?: "Search is unavailable")
                    }
                }
            }
        }
    }

    private fun SearchUiState.toRequest() = SearchRequest(
        query = submittedQuery,
        type = type,
        sort = sort,
        time = time,
        safe = safe,
    )
}
