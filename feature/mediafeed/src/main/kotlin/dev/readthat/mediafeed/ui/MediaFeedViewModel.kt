package dev.readthat.mediafeed.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.readthat.mediafeed.data.MediaFeedRepository

class MediaFeedViewModel(
    private val repository: MediaFeedRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val feed = repository.feed().cachedIn(viewModelScope)

    var navigationItems: List<dev.readthat.mediafeed.domain.MediaFeedItem> by
        mutableStateOf(repository.navigationItems)
        private set

    val restoredPage: Int get() = savedStateHandle[CURRENT_PAGE] ?: repository.initialPage

    fun setCurrentPage(page: Int) {
        savedStateHandle[CURRENT_PAGE] = page.coerceAtLeast(0)
    }

    fun releaseNavigationFallback() {
        if (navigationItems.isEmpty()) return
        navigationItems = emptyList()
        repository.releaseNavigationFallback()
    }

    private companion object { const val CURRENT_PAGE = "media_feed_current_page" }
}
