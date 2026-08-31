package dev.readthat.feed.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import dev.readthat.core.ui.feed.FeedAppendError
import dev.readthat.core.ui.feed.FeedErrorState
import dev.readthat.core.ui.feed.FeedSkeleton
import dev.readthat.domain.CellUi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs

/** Geometry-only input used by the shared autoplay policy and its host tests. */
data class SharedFeedVisibleItem(
    val key: String,
    val offset: Int,
    val size: Int,
)

/**
 * Canonical Paging/Compose controller for every flattened SDUI feed.
 *
 * The host owns data mutations, analytics exporters, image loading, and native playback. This
 * component owns the behavior that must not drift between Android and iOS: stale-row rendering,
 * gesture-only refresh chrome, stable keys/content types, append recovery, viewport autoplay,
 * dwell-gated visibility, and scroll-driven prefetch callbacks.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
fun SharedPagedFeed(
    items: LazyPagingItems<CellUi>,
    videoCellKeys: Set<String>,
    promotedGroupIds: Set<String>,
    autoplayEnabled: Boolean,
    onUserRefresh: () -> Unit,
    onRetry: () -> Unit,
    onFirstVisibleItemChanged: (Int) -> Unit,
    onSettledVisibleGroups: (Set<String>) -> Unit,
    itemContent: @Composable (item: CellUi, playInline: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    listHeader: (@Composable () -> Unit)? = null,
) {
    val refresh = items.loadState.refresh
    val append = items.loadState.append
    val hasCachedRows = items.itemCount > 0
    var userInitiatedRefresh by remember { mutableStateOf(false) }
    var activeVideoKey by remember { mutableStateOf<String?>(null) }
    val currentOnFirstVisibleItemChanged by rememberUpdatedState(onFirstVisibleItemChanged)
    val currentOnSettledVisibleGroups by rememberUpdatedState(onSettledVisibleGroups)

    LaunchedEffect(refresh) {
        if (refresh !is LoadState.Loading) userInitiatedRefresh = false
    }
    // Item count is a key so a newly committed Room page warms media even when the list remains
    // parked at index zero and therefore emits no scroll change.
    LaunchedEffect(listState, items.itemCount) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { currentOnFirstVisibleItemChanged(feedContentIndex(it, listHeader != null)) }
    }
    LaunchedEffect(listState, videoCellKeys, autoplayEnabled) {
        snapshotFlow {
            val layout = listState.layoutInfo
            selectSharedFeedVideo(
                visibleItems = layout.visibleItemsInfo.mapNotNull { item ->
                    (item.key as? String)?.let { key ->
                        SharedFeedVisibleItem(key, item.offset, item.size)
                    }
                },
                videoCellKeys = videoCellKeys,
                viewportStart = layout.viewportStartOffset,
                viewportEnd = layout.viewportEndOffset,
            )
        }
            .debounce(AUTOPLAY_SETTLE_MILLIS)
            .distinctUntilChanged()
            .collect { selected -> activeVideoKey = selected.takeIf { autoplayEnabled } }
    }
    LaunchedEffect(listState, promotedGroupIds) {
        snapshotFlow {
            val layout = listState.layoutInfo
            settledVisibleFeedGroups(
                visibleItems = layout.visibleItemsInfo.mapNotNull { item ->
                    (item.key as? String)?.let { key ->
                        SharedFeedVisibleItem(key, item.offset, item.size)
                    }
                },
                viewportStart = layout.viewportStartOffset,
                viewportEnd = layout.viewportEndOffset,
                promotedGroupIds = promotedGroupIds,
            )
        }
            .debounce(VISIBILITY_DWELL_MILLIS)
            .distinctUntilChanged()
            .collect { currentOnSettledVisibleGroups(it) }
    }

    when {
        refresh is LoadState.Loading && !hasCachedRows ->
            FeedSkeleton(modifier.fillMaxSize(), listHeader)

        refresh is LoadState.Error && !hasCachedRows ->
            Column(modifier.fillMaxSize()) {
                listHeader?.invoke()
                Box(Modifier.weight(1f)) {
                    FeedErrorState(refresh.error.message ?: "Could not load the feed") {
                        onRetry()
                        items.retry()
                    }
                }
            }

        else -> PullToRefreshBox(
            isRefreshing = userInitiatedRefresh && refresh is LoadState.Loading,
            onRefresh = {
                userInitiatedRefresh = true
                onUserRefresh()
                items.refresh()
            },
            modifier = modifier.fillMaxSize(),
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                listHeader?.let { header ->
                    item(key = FEED_HEADER_KEY, contentType = "feed_header") { header() }
                }
                items(
                    count = items.itemCount,
                    key = items.itemKey(CellUi::key),
                    contentType = items.itemContentType { it::class.simpleName },
                ) { index ->
                    items[index]?.let { item ->
                        itemContent(item, item.key == activeVideoKey)
                    }
                }
                when (append) {
                    is LoadState.Loading -> item(key = APPEND_SPINNER_KEY) {
                        Box(
                            Modifier.fillMaxWidth().padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }
                    }

                    is LoadState.Error -> item(key = APPEND_ERROR_KEY) {
                        FeedAppendError(append.error.message ?: "Could not load more") {
                            onRetry()
                            items.retry()
                        }
                    }

                    else -> Unit
                }
            }
        }
    }
}

/** Converts a LazyColumn index into the SDUI cell index when destination chrome leads the feed. */
fun feedContentIndex(firstVisibleItemIndex: Int, hasListHeader: Boolean): Int =
    (firstVisibleItemIndex - if (hasListHeader) 1 else 0).coerceAtLeast(0)

/** Select the most-visible eligible video, using viewport-center distance as a tie-breaker. */
fun selectSharedFeedVideo(
    visibleItems: List<SharedFeedVisibleItem>,
    videoCellKeys: Set<String>,
    viewportStart: Int,
    viewportEnd: Int,
): String? {
    if (viewportEnd <= viewportStart || videoCellKeys.isEmpty()) return null
    val viewportCenter = (viewportStart + viewportEnd) / 2
    return visibleItems.asSequence()
        .filter { it.key in videoCellKeys && it.size > 0 }
        .mapNotNull { item ->
            val visible = visiblePixels(item, viewportStart, viewportEnd)
            val maximumVisible = minOf(item.size, viewportEnd - viewportStart)
            if (maximumVisible <= 0 || visible * 2 < maximumVisible) null
            else Triple(
                item.key,
                visible,
                abs(item.offset + item.size / 2 - viewportCenter),
            )
        }
        .maxWithOrNull(compareBy<Triple<String, Int, Int>> { it.second }.thenBy { -it.third })
        ?.first
}

/** Groups eligible for impressions/comment prefetch after the caller applies the dwell gate. */
fun settledVisibleFeedGroups(
    visibleItems: List<SharedFeedVisibleItem>,
    viewportStart: Int,
    viewportEnd: Int,
    promotedGroupIds: Set<String>,
): Set<String> {
    if (viewportEnd <= viewportStart) return emptySet()
    val viewportSize = viewportEnd - viewportStart
    return visibleItems.mapNotNullTo(linkedSetOf()) { item ->
        val groupId = item.key.substringBefore('/')
        val cellId = item.key.substringAfter('/', "")
        if (
            item.key.startsWith("__") ||
            groupId.isBlank() ||
            item.size <= 0 ||
            cellId == "actions" ||
            (groupId in promotedGroupIds && cellId != "media")
        ) {
            return@mapNotNullTo null
        }
        val visible = visiblePixels(item, viewportStart, viewportEnd)
        groupId.takeIf { visible * 2 >= minOf(item.size, viewportSize) }
    }
}

private fun visiblePixels(item: SharedFeedVisibleItem, viewportStart: Int, viewportEnd: Int): Int =
    (minOf(item.offset + item.size, viewportEnd) - maxOf(item.offset, viewportStart)).coerceAtLeast(0)

private const val AUTOPLAY_SETTLE_MILLIS = 150L
private const val VISIBILITY_DWELL_MILLIS = 600L
private const val FEED_HEADER_KEY = "__feed_header__"
private const val APPEND_SPINNER_KEY = "__append_spinner__"
private const val APPEND_ERROR_KEY = "__append_error__"
