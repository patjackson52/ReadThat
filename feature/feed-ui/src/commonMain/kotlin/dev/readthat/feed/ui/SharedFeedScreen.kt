package dev.readthat.feed.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.paging.compose.LazyPagingItems
import dev.readthat.domain.CellUi
import dev.readthat.observability.ProductSurface

/** Immutable presentation lookup derived from one flattened Paging snapshot. */
data class SharedFeedPresentation(
    val cells: List<CellUi>,
    val adMediaByGroup: Map<String, CellUi.AdMedia>,
    val titleByGroup: Map<String, String>,
) {
    fun adMediaFor(item: CellUi): CellUi.AdMedia? = adMediaByGroup[item.feedGroupId()]
    fun titleFor(item: CellUi): String = titleByGroup[item.feedGroupId()] ?: DEFAULT_POST_TITLE
}

/** Pure snapshot adapter used by tests and by both application hosts. */
fun sharedFeedPresentation(cells: List<CellUi>): SharedFeedPresentation = SharedFeedPresentation(
    cells = cells,
    adMediaByGroup = cells.filterIsInstance<CellUi.AdMedia>().associateBy(CellUi::feedGroupId),
    titleByGroup = cells.filterIsInstance<CellUi.Title>().associate { it.feedGroupId() to it.text },
)

fun CellUi.feedGroupId(): String = key.substringBefore('/')

/**
 * Canonical screen-level adapter for flattened ReadThat feeds.
 *
 * Hosts retain navigation and native image/video/cache engines. Snapshot interpretation, load
 * states, offline presentation, autoplay ownership, impressions, comment prefetch and reshare
 * presentation stay shared so Home and community feeds cannot drift by platform.
 */
@Composable
fun SharedFeedScreen(
    items: LazyPagingItems<CellUi>,
    initialCacheTier: String?,
    explicitlyOffline: Boolean,
    autoplayEnabled: Boolean,
    onUserRefresh: () -> Unit,
    onRetry: () -> Unit,
    onFirstContentRendered: (cacheTier: String) -> Unit,
    onFirstVisibleItemChanged: (Int) -> Unit,
    onPrefetchComments: (Set<String>) -> Unit,
    onReshare: (postId: String, community: String, onComplete: (String?) -> Unit) -> Unit,
    itemContent: @Composable (
        item: CellUi,
        companionAdMedia: CellUi.AdMedia?,
        postTitle: String,
        playInline: Boolean,
        requestReshare: (postId: String) -> Unit,
    ) -> Unit,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    listHeader: (@Composable () -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    productSurface: ProductSurface = ProductSurface.FEED,
) {
    val cells = items.itemSnapshotList.items
    val presentation = remember(cells) { sharedFeedPresentation(cells) }
    SharedFeedRoute(
        items = items,
        initialCacheTier = initialCacheTier,
        explicitlyOffline = explicitlyOffline,
        autoplayEnabled = autoplayEnabled,
        onUserRefresh = onUserRefresh,
        onRetry = onRetry,
        onFirstContentRendered = onFirstContentRendered,
        onFirstVisibleItemChanged = onFirstVisibleItemChanged,
        onPrefetchComments = onPrefetchComments,
        onReshare = onReshare,
        itemContent = { item, playInline, requestReshare ->
            itemContent(
                item,
                presentation.adMediaFor(item),
                presentation.titleFor(item),
                playInline,
                requestReshare,
            )
        },
        modifier = modifier,
        header = header,
        listHeader = listHeader,
        listState = listState,
        productSurface = productSurface,
    )
}

private const val DEFAULT_POST_TITLE = "ReadThat post"
