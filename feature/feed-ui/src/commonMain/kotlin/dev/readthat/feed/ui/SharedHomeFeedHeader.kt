package dev.readthat.feed.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.readthat.core.ui.brand.ReadThatLogo

/** The account identity required by feed chrome, independent of either application host. */
data class SharedFeedAccount(
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val updatedAt: Long = 0,
)

typealias SharedFeedAvatarRenderer = @Composable (
    url: String,
    cacheKey: String,
    modifier: Modifier,
) -> Unit

/** Canonical home-feed chrome shared by the mature Android shell and Compose Multiplatform. */
@Composable
fun SharedHomeFeedHeader(
    account: SharedFeedAccount?,
    onOpenNavigation: () -> Unit,
    onSearch: () -> Unit,
    onAccountClick: () -> Unit,
    avatarRenderer: SharedFeedAvatarRenderer,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconButton(onClick = onOpenNavigation, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Menu, "Open community menu", Modifier.size(28.dp))
        }
        ReadThatLogo(
            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)),
            contentDescription = "ReadThat",
        )
        Row(
            Modifier.weight(1f).height(46.dp)
                .clickable(
                    role = Role.Button,
                    onClickLabel = "Search ReadThat",
                    onClick = onSearch,
                )
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(24.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp))
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("  Find anything", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SharedAccountButton(account, onAccountClick, avatarRenderer)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SharedAccountButton(
    account: SharedFeedAccount?,
    onClick: () -> Unit,
    avatarRenderer: SharedFeedAvatarRenderer,
) {
    val username = account?.username.orEmpty().trim().removePrefix("u/")
    val fallback = account?.displayName?.trim()?.firstOrNull()?.uppercase()
        ?: username.firstOrNull()?.uppercase()
        ?: "U"
    val profileDescription = username.takeIf(String::isNotBlank)?.let { "u/$it profile" }

    Box(
        Modifier.size(40.dp).clip(CircleShape)
            .background(Color(0xFF86A8F7))
            .clickable(
                enabled = account != null,
                role = Role.Button,
                onClickLabel = profileDescription,
                onClick = onClick,
            )
            .semantics {
                role = Role.Button
                profileDescription?.let { description ->
                    contentDescription = description
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            fallback,
            color = Color(0xFF0B1416),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Black,
        )
        account?.avatarUrl?.takeIf(String::isNotBlank)?.let { avatarUrl ->
            avatarRenderer(
                avatarUrl,
                "feed-account-avatar:${account.username}:${account.updatedAt}",
                Modifier.fillMaxSize(),
            )
        }
    }
}
