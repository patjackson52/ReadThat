package dev.readthat.feed.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import dev.readthat.domain.AdMediaKind
import dev.readthat.domain.CellUi
import dev.readthat.observability.ProductAnalytics
import dev.readthat.observability.ProductContentType
import dev.readthat.observability.ProductEvent
import dev.readthat.observability.ProductEventName
import dev.readthat.observability.ProductSurface
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Route-level feed behavior shared by every host.
 *
 * Platform/application edges retain only navigation, native image/video rendering, share sheets,
 * and media preloading. Load-state presentation, stale/offline behavior, impression dwell,
 * bounded comment prefetch triggers, first-content reporting and reshare UI live here so they
 * cannot drift between Android and iOS.
 */
@Composable
fun SharedFeedRoute(
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
        playInline: Boolean,
        requestReshare: (postId: String) -> Unit,
    ) -> Unit,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    listHeader: (@Composable () -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    productSurface: ProductSurface = ProductSurface.FEED,
) {
    val snapshotItems = items.itemSnapshotList.items
    val adIdByGroup = remember(snapshotItems) {
        snapshotItems.filterIsInstance<CellUi.AdHeader>()
            .associate { it.key.substringBefore('/') to it.adId }
    }
    val refresh = items.loadState.refresh
    val append = items.loadState.append
    val hasCachedRows = items.itemCount > 0
    val showingOfflineRows = explicitlyOffline ||
        (hasCachedRows && (refresh is LoadState.Error || append is LoadState.Error))
    val activeAdViews = remember { mutableMapOf<String, TimeMark>() }
    var firstContentReported by remember { mutableStateOf(false) }
    var resharePostId by remember { mutableStateOf<String?>(null) }
    var reshareTarget by remember { mutableStateOf("") }
    var reshareError by remember { mutableStateOf<String?>(null) }
    var reshareSubmitting by remember { mutableStateOf(false) }

    LaunchedEffect(items.itemCount, initialCacheTier, showingOfflineRows) {
        if (!firstContentReported && items.itemCount > 0) {
            val cacheTier = initialCacheTier ?: if (showingOfflineRows) "room_offline" else null
            if (cacheTier != null) {
                withFrameNanos { }
                firstContentReported = true
                onFirstContentRendered(cacheTier)
            }
        }
    }

    fun finishAdView(adId: String) {
        val started = activeAdViews.remove(adId) ?: return
        ProductAnalytics.record(ProductEvent(
            name = ProductEventName.AD_VIEW_TIME,
            surface = productSurface,
            contentId = adId,
            contentType = ProductContentType.AD,
            durationMs = started.elapsedNow().inWholeMilliseconds.coerceAtLeast(0L),
        ))
    }
    DisposableEffect(activeAdViews) {
        onDispose { activeAdViews.keys.toList().forEach(::finishAdView) }
    }

    Column(modifier.fillMaxSize()) {
        header?.invoke()
        if (showingOfflineRows || refresh is LoadState.Loading && hasCachedRows) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (showingOfflineRows) "Offline · showing saved posts" else "Refreshing…",
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    "Refresh",
                    Modifier.clickable {
                        onUserRefresh()
                        items.refresh()
                    }.padding(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        SharedPagedFeed(
            items = items,
            videoCellKeys = remember(snapshotItems) { snapshotItems.feedVideoCellKeys() },
            promotedGroupIds = adIdByGroup.keys,
            autoplayEnabled = autoplayEnabled,
            onUserRefresh = onUserRefresh,
            onRetry = onRetry,
            onFirstVisibleItemChanged = onFirstVisibleItemChanged,
            onSettledVisibleGroups = { visibleGroups ->
                val visibleAds = visibleGroups.mapNotNullTo(mutableSetOf(), adIdByGroup::get)
                (activeAdViews.keys - visibleAds).forEach(::finishAdView)
                visibleAds.forEach { adId ->
                    if (adId !in activeAdViews) {
                        activeAdViews[adId] = TimeSource.Monotonic.markNow()
                        ProductAnalytics.record(ProductEvent(
                            name = ProductEventName.AD_IMPRESSION,
                            surface = productSurface,
                            contentId = adId,
                            contentType = ProductContentType.AD,
                        ))
                    }
                }
                val postIds = visibleGroups.filterNotTo(linkedSetOf()) { it in adIdByGroup }
                onPrefetchComments(postIds)
                postIds.forEach { postId ->
                    ProductAnalytics.record(ProductEvent(
                        name = ProductEventName.POST_IMPRESSION,
                        surface = productSurface,
                        contentId = postId,
                        contentType = ProductContentType.POST,
                    ))
                }
            },
            itemContent = { item, playInline ->
                itemContent(item, playInline) { postId ->
                    resharePostId = postId
                    reshareTarget = ""
                    reshareError = null
                    reshareSubmitting = false
                }
            },
            modifier = Modifier.weight(1f),
            listState = listState,
            listHeader = listHeader,
        )
    }

    resharePostId?.let { postId ->
        AlertDialog(
            onDismissRequest = { if (!reshareSubmitting) resharePostId = null },
            title = { Text("Reshare to a community") },
            text = {
                Column {
                    OutlinedTextField(
                        value = reshareTarget,
                        onValueChange = {
                            reshareTarget = it.take(MAX_COMMUNITY_INPUT_LENGTH)
                            reshareError = null
                        },
                        label = { Text("r/community") },
                        singleLine = true,
                        enabled = !reshareSubmitting,
                    )
                    reshareError?.let { error ->
                        Text(error, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !reshareSubmitting && normalizedCommunity(reshareTarget).length >= 3,
                    onClick = {
                        reshareSubmitting = true
                        onReshare(postId, normalizedCommunity(reshareTarget)) { error ->
                            reshareSubmitting = false
                            if (error == null) {
                                resharePostId = null
                                reshareTarget = ""
                            } else {
                                reshareError = error
                            }
                        }
                    },
                ) { Text(if (reshareSubmitting) "Resharing…" else "Reshare") }
            },
            dismissButton = {
                TextButton(
                    enabled = !reshareSubmitting,
                    onClick = { resharePostId = null },
                ) { Text("Cancel") }
            },
        )
    }
}

/** Feed and promoted video cells participate in the same one-owner autoplay selection. */
fun List<CellUi>.feedVideoCellKeys(): Set<String> = mapNotNullTo(hashSetOf()) { item ->
    when (item) {
        is CellUi.Media -> item.key.takeIf { item.video != null }
        is CellUi.AdMedia -> item.key.takeIf { item.items.any { media -> media.kind == AdMediaKind.Video } }
        else -> null
    }
}

private fun normalizedCommunity(value: String): String = value.trim().removePrefix("r/")

private const val MAX_COMMUNITY_INPUT_LENGTH = 64
