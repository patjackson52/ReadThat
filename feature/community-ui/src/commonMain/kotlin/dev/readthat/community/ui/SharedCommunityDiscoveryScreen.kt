package dev.readthat.community.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.readthat.client.SharedCommunityDiscoveryState
import dev.readthat.search.domain.DiscoverCommunity
import dev.readthat.search.domain.TrendingSearch

/** Canonical discovery destination. The containing host owns safe-area padding exactly once. */
@Composable
fun SharedCommunityDiscoveryScreen(
    state: SharedCommunityDiscoveryState,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onCommunity: (String) -> Unit,
    onTrendingPost: (String) -> Unit,
    onCreateCommunity: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val error = state.error
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = 8.dp,
            bottom = 28.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
                Text(
                    "Communities",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRefresh, enabled = !state.refreshing && !state.loading) {
                    Icon(Icons.Default.Refresh, "Refresh communities")
                }
                IconButton(onClick = onSearch) {
                    Icon(Icons.Default.Search, "Search communities")
                }
            }
        }

        if (state.refreshing) {
            item(key = "refreshing") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }

        item(key = "intro") {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Find your next corner of ReadThat",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                    )
                    Text(
                        "Browse active communities, open their feeds, and keep recent details available offline.",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onSearch) {
                            Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                            Text("Search", Modifier.padding(start = 8.dp))
                        }
                        OutlinedButton(onClick = onCreateCommunity) {
                            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                            Text("Start one", Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }

        if (state.offline) {
            item(key = "offline") {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(
                        "Offline · showing saved discovery results",
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        if (state.loading && state.initialCacheTier == null) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(vertical = 36.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        } else if (error != null && state.initialCacheTier == null) {
            item(key = "error") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(
                        Modifier.fillMaxWidth().padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                        OutlinedButton(onClick = onRefresh) { Text("Retry") }
                    }
                }
            }
        } else {
            item(key = "community-title") {
                SectionTitle("Popular communities", "Open a community to see its latest posts")
            }
            if (state.discover.communities.isEmpty()) {
                item(key = "community-empty") {
                    EmptyDiscoveryLine("No community recommendations yet. Search to find one by name.")
                }
            } else {
                items(
                    state.discover.communities,
                    key = { "community:${it.id}" },
                ) { community ->
                    DiscoveryCommunityRow(community) { onCommunity(community.name) }
                }
            }

            if (state.discover.trending.isNotEmpty()) {
                item(key = "trending-title") {
                    SectionTitle("Trending now", "Conversations gaining momentum")
                }
                items(
                    state.discover.trending,
                    key = { "trending:${it.id}" },
                ) { trending ->
                    TrendingRow(trending) { onTrendingPost(trending.id) }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DiscoveryCommunityRow(community: DiscoverCommunity, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(48.dp).background(Color(communityColorArgb(community.name)), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                community.displayName.firstOrNull()?.uppercase() ?: "#",
                color = Color.White,
                fontWeight = FontWeight.Black,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                "r/${community.name}",
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${community.displayName} · ${compactMemberCount(community.subscriberCount)} members",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun TrendingRow(trending: TrendingSearch, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            trending.query,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "r/${trending.subreddit} · ${compactMemberCount(trending.score)} votes · " +
                "${compactMemberCount(trending.commentCount)} comments",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider()
}

@Composable
private fun EmptyDiscoveryLine(message: String) {
    Text(
        message,
        Modifier.fillMaxWidth().padding(vertical = 20.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

internal fun compactMemberCount(value: Int): String = when {
    value >= 1_000_000 -> compact(value, 1_000_000, "M")
    value >= 1_000 -> compact(value, 1_000, "K")
    else -> value.coerceAtLeast(0).toString()
}

private fun compact(value: Int, divisor: Int, suffix: String): String {
    val whole = value / divisor
    val decimal = (value % divisor) / (divisor / 10)
    return if (decimal == 0 || whole >= 100) "$whole$suffix" else "$whole.$decimal$suffix"
}
