package dev.readthat.search.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.readthat.client.ReadThatClient
import dev.readthat.client.SharedSearchController
import dev.readthat.data.db.AppDatabase
import dev.readthat.search.domain.SearchSort
import dev.readthat.search.domain.SearchTime
import dev.readthat.search.domain.SearchType

/** Android lifecycle adapter for the canonical KMP search controller. */
class SearchViewModel(
    client: ReadThatClient,
    database: AppDatabase,
    accountId: String,
) : ViewModel() {
    private val controller = SharedSearchController(client, database, accountId, viewModelScope)

    val state = controller.state
    val pagedResults = controller.pagedResults

    fun onQueryChanged(value: String) = controller.onQueryChanged(value)
    fun submit(query: String) = controller.submit(query)
    fun clearQuery() = controller.clearQuery()
    fun selectType(type: SearchType) = controller.selectType(type)
    fun selectSort(sort: SearchSort) = controller.selectSort(sort)
    fun selectTime(time: SearchTime) = controller.selectTime(time)
    fun toggleSafe() = controller.toggleSafe()
    fun deleteRecent(query: String) = controller.deleteRecent(query)
    fun clearRecent() = controller.clearRecent()
    fun retryAll() = controller.retryAll()
}
