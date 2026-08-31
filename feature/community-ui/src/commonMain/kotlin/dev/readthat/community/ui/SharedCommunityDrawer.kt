package dev.readthat.community.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.readthat.client.SharedCommunityDrawerState
import dev.readthat.communities.domain.DrawerCommunity
import dev.readthat.communities.domain.RecentCommunity

/**
 * Canonical community drawer shared by Android and iOS. Unlike full destinations, the drawer sits
 * outside the host Scaffold, so it owns its vertical/start safe-area padding exactly once.
 */
@Composable
fun SharedCommunityDrawer(
    state: SharedCommunityDrawerState,
    onCreateCommunity: () -> Unit,
    onCommunity: (String) -> Unit,
    onSeeAll: () -> Unit,
    onShowDrawer: () -> Unit,
    onToggleCommunities: () -> Unit,
    onRetry: () -> Unit,
    onRemoveRecent: (String) -> Unit,
    onClearRecent: () -> Unit,
    onBrowse: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    ModalDrawerSheet(
        modifier = modifier.fillMaxHeight().fillMaxWidth(0.82f),
        drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
    ) {
        Box(
            Modifier.fillMaxHeight().windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Vertical + WindowInsetsSides.Start,
                ),
            ),
        ) {
            if (state.showAllRecents) {
                AllRecents(
                    recents = state.snapshot.recentlyVisited,
                    onBack = onShowDrawer,
                    onClear = onClearRecent,
                    onRemove = onRemoveRecent,
                    onCommunity = onCommunity,
                )
            } else {
                DrawerHome(
                    state = state,
                    onCreateCommunity = onCreateCommunity,
                    onSeeAll = onSeeAll,
                    onToggleCommunities = onToggleCommunities,
                    onRetry = onRetry,
                    onCommunity = onCommunity,
                    onBrowse = onBrowse,
                )
            }
        }
    }
}

@Composable
private fun DrawerHome(
    state: SharedCommunityDrawerState,
    onCreateCommunity: () -> Unit,
    onSeeAll: () -> Unit,
    onToggleCommunities: () -> Unit,
    onRetry: () -> Unit,
    onCommunity: (String) -> Unit,
    onBrowse: (() -> Unit)?,
) {
    LazyColumn(Modifier.fillMaxHeight()) {
        item(key = "create") {
            DrawerAction(Icons.Default.Add, "Start a community", onCreateCommunity)
        }
        if (state.refreshing) {
            item(key = "refreshing") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }
        item(key = "recent-divider") {
            HorizontalDivider(thickness = 6.dp, color = MaterialTheme.colorScheme.surfaceVariant)
        }
        item(key = "recent-header") {
            SectionHeader(
                "Recently Visited",
                action = if (state.snapshot.recentlyVisited.size > RECENT_PREVIEW_LIMIT) "See all" else null,
                onAction = onSeeAll,
            )
        }
        if (state.snapshot.recentlyVisited.isEmpty()) {
            item(key = "recent-empty") { EmptyLine("Communities you open will appear here") }
        } else {
            items(
                state.snapshot.recentlyVisited.take(RECENT_PREVIEW_LIMIT),
                key = { "recent:${it.name}" },
            ) { recent ->
                CommunityRow(recent.name, recent.displayName) { onCommunity(recent.name) }
            }
        }
        item(key = "community-divider") {
            HorizontalDivider(thickness = 6.dp, color = MaterialTheme.colorScheme.surfaceVariant)
        }
        item(key = "community-header") {
            SectionHeader(
                title = "Your Communities",
                trailing = if (state.communitiesExpanded) {
                    Icons.Default.KeyboardArrowUp
                } else {
                    Icons.Default.KeyboardArrowDown
                },
                onAction = onToggleCommunities,
            )
        }
        if (state.communitiesExpanded) {
            if (state.snapshot.communities.isEmpty()) {
                item(key = "community-empty") { EmptyLine("Create or join a community to see it here") }
            } else {
                items(state.snapshot.communities, key = { "community:${it.name}" }) { community ->
                    CommunityRow(
                        community.name,
                        community.displayName,
                        roleLabel(community),
                    ) { onCommunity(community.name) }
                }
            }
        }
        onBrowse?.let { browse ->
            item(key = "browse-divider") {
                HorizontalDivider(thickness = 6.dp, color = MaterialTheme.colorScheme.surfaceVariant)
            }
            item(key = "browse") {
                DrawerAction(Icons.Default.Search, "Browse communities", browse)
            }
        }
        state.error?.let { message ->
            item(key = "error") {
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onRetry).padding(18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    Text("Retry", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun AllRecents(
    recents: List<RecentCommunity>,
    onBack: () -> Unit,
    onClear: () -> Unit,
    onRemove: (String) -> Unit,
    onCommunity: (String) -> Unit,
) {
    Column(Modifier.fillMaxHeight()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Recently Visited", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Spacer(Modifier.weight(1f))
            if (recents.isNotEmpty()) {
                Text(
                    "Clear all",
                    Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClear).padding(10.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (recents.isEmpty()) {
            EmptyLine("No recently visited communities")
        } else {
            LazyColumn {
                items(recents, key = { "all-recent:${it.name}" }) { recent ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            CommunityRow(recent.name, recent.displayName) { onCommunity(recent.name) }
                        }
                        IconButton(onClick = { onRemove(recent.name) }) {
                            Icon(Icons.Default.Close, "Remove ${recent.name}")
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier.size(38.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(22.dp)) }
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SectionHeader(
    title: String,
    action: String? = null,
    trailing: ImageVector? = null,
    onAction: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(
            enabled = action != null || trailing != null,
            onClick = onAction,
        ).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
        action?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
        }
        trailing?.let {
            Icon(it, if (it == Icons.Default.KeyboardArrowUp) "Collapse" else "Expand")
        }
    }
}

@Composable
private fun CommunityRow(
    name: String,
    displayName: String,
    supporting: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(38.dp).background(Color(communityColorArgb(name)), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Black)
        }
        Column(Modifier.weight(1f)) {
            Text(
                "r/$name",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val secondary = supporting ?: displayName.takeIf { !it.equals(name, ignoreCase = true) }
            secondary?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyLine(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Filled.List, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, Modifier.padding(start = 14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun roleLabel(community: DrawerCommunity): String? = when (community.role) {
    "owner" -> "Owner"
    "moderator" -> "Moderator"
    else -> null
}

internal fun communityColorArgb(name: String): Long {
    val palette = listOf(0xFF0079D3, 0xFFFF4500, 0xFF46A508, 0xFF7E53C6, 0xFF008985)
    return palette[(name.hashCode() and Int.MAX_VALUE) % palette.size]
}

private const val RECENT_PREVIEW_LIMIT = 3
