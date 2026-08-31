package dev.readthat.feed.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.readthat.communitydetail.domain.CommunityDetail
import kotlin.math.roundToInt

data class SharedCommunityHeaderState(
    val detail: CommunityDetail? = null,
    val refreshing: Boolean = false,
    val membershipChanging: Boolean = false,
    val offline: Boolean = false,
    val error: String? = null,
)

/** Shared overflow policy; native hosts never decide which community actions exist. */
enum class CommunityOverflowAction {
    Refresh,
    CreatePost,
    ToggleMembership,
}

fun communityOverflowActions(canChangeMembership: Boolean): List<CommunityOverflowAction> = buildList {
    add(CommunityOverflowAction.Refresh)
    add(CommunityOverflowAction.CreatePost)
    if (canChangeMembership) add(CommunityOverflowAction.ToggleMembership)
}

typealias CommunityAvatarRenderer = @Composable (
    url: String,
    contentDescription: String,
    modifier: Modifier,
) -> Unit

/** Canonical community chrome shared by the mature Android and Compose Multiplatform feeds. */
@Composable
fun SharedCommunityHeader(
    state: SharedCommunityHeaderState,
    communityName: String,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onOptions: () -> Unit,
    onToggleMembership: () -> Unit,
    onRetry: () -> Unit,
    onCreatePost: () -> Unit,
    avatarRenderer: CommunityAvatarRenderer,
    modifier: Modifier = Modifier,
) {
    val normalizedName = communityName.trim().removePrefix("r/")
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        CommunityToolbar(
            title = state.detail?.let { "r/${it.name}" } ?: "r/$normalizedName",
            onBack = onBack,
            onSearch = onSearch,
            onOptions = onOptions,
            isJoined = state.detail?.isJoined == true,
            canChangeMembership = state.detail?.canChangeMembership == true,
            membershipChanging = state.membershipChanging,
            onRefresh = onRetry,
            onCreatePost = onCreatePost,
            onToggleMembership = onToggleMembership,
        )
        when (val detail = state.detail) {
            null -> CommunityHeaderLoading(normalizedName, state.error, onRetry)
            else -> CommunityIdentity(
                detail = detail,
                refreshing = state.refreshing,
                membershipChanging = state.membershipChanging,
                offline = state.offline,
                error = state.error,
                onToggleMembership = onToggleMembership,
                onRetry = onRetry,
                onCreatePost = onCreatePost,
                avatarRenderer = avatarRenderer,
            )
        }
        HorizontalDivider(thickness = 6.dp, color = MaterialTheme.colorScheme.surfaceVariant)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Best posts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Ranked for this community", style = MaterialTheme.typography.bodySmall)
            }
            Text("⌄", style = MaterialTheme.typography.titleLarge)
        }
        HorizontalDivider()
    }
}

@Composable
private fun CommunityToolbar(
    title: String,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onOptions: () -> Unit,
    isJoined: Boolean,
    canChangeMembership: Boolean,
    membershipChanging: Boolean,
    onRefresh: () -> Unit,
    onCreatePost: () -> Unit,
    onToggleMembership: () -> Unit,
) {
    var optionsExpanded by remember(title) { mutableStateOf(false) }
    fun runOption(action: () -> Unit) {
        optionsExpanded = false
        action()
    }
    Box(
        Modifier.fillMaxWidth().height(56.dp)
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 4.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onPrimary)
        }
        Crossfade(
            targetState = title,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 100.dp).align(Alignment.Center),
            animationSpec = tween(COMMUNITY_TITLE_CROSSFADE_MILLIS),
            label = "community-title",
        ) { currentTitle ->
            Text(
                currentTitle,
                Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onSearch) {
                Icon(Icons.Default.Search, "Search", tint = MaterialTheme.colorScheme.onPrimary)
            }
            Box {
                IconButton(onClick = {
                    optionsExpanded = true
                    onOptions()
                }) {
                    Icon(Icons.Default.MoreVert, "Community options", tint = MaterialTheme.colorScheme.onPrimary)
                }
                DropdownMenu(
                    expanded = optionsExpanded,
                    onDismissRequest = { optionsExpanded = false },
                ) {
                    communityOverflowActions(canChangeMembership).forEach { action ->
                        val label = when (action) {
                            CommunityOverflowAction.Refresh -> "Refresh community"
                            CommunityOverflowAction.CreatePost -> "Create a post"
                            CommunityOverflowAction.ToggleMembership ->
                                if (isJoined) "Leave community" else "Join community"
                        }
                        DropdownMenuItem(
                            text = { Text(label) },
                            enabled = action != CommunityOverflowAction.ToggleMembership ||
                                !membershipChanging,
                            onClick = {
                                runOption {
                                    when (action) {
                                        CommunityOverflowAction.Refresh -> onRefresh()
                                        CommunityOverflowAction.CreatePost -> onCreatePost()
                                        CommunityOverflowAction.ToggleMembership -> onToggleMembership()
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CommunityHeaderLoading(name: String, error: String?, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { Text(name.take(1).uppercase(), style = MaterialTheme.typography.headlineMedium) }
            if (error == null) CircularProgressIndicator(Modifier.size(24.dp))
            else Column {
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun CommunityIdentity(
    detail: CommunityDetail,
    refreshing: Boolean,
    membershipChanging: Boolean,
    offline: Boolean,
    error: String?,
    onToggleMembership: () -> Unit,
    onRetry: () -> Unit,
    onCreatePost: () -> Unit,
    avatarRenderer: CommunityAvatarRenderer,
) {
    var rulesExpanded by remember(detail.id) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CommunityAvatar(detail, avatarRenderer)
            Column(Modifier.weight(1f)) {
                Text(detail.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("r/${detail.name}", style = MaterialTheme.typography.bodyMedium)
            }
            val buttonContent: @Composable () -> Unit = {
                if (membershipChanging) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text(if (detail.isJoined) "Joined" else "Join")
            }
            if (detail.isJoined) {
                OutlinedButton(
                    onClick = onToggleMembership,
                    enabled = detail.canChangeMembership && !membershipChanging,
                    content = { buttonContent() },
                )
            } else {
                Button(
                    onClick = onToggleMembership,
                    enabled = detail.canChangeMembership && !membershipChanging,
                    content = { buttonContent() },
                )
            }
        }
        if (detail.description.isNotBlank()) Text(detail.description, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Stat(formatCommunityCount(detail.subscriberCount), if (detail.subscriberCount == 1) "member" else "members")
            Stat(detail.accessType.replaceFirstChar(Char::uppercase), "community")
            if (refreshing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        if (offline) Text("Offline · showing saved community", color = MaterialTheme.colorScheme.tertiary)
        error?.let {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(10.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(it, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().clickable { rulesExpanded = !rulesExpanded },
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Community rules", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    Text(
                        if (rulesExpanded) "⌃" else "⌄",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                val first = detail.rules.minByOrNull { it.order }
                if (!rulesExpanded) {
                    Text(
                        first?.let { "1. ${it.title}" } ?: "Follow community guidelines",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else if (detail.rules.isEmpty()) {
                    Text("No additional rules have been published.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    detail.rules.sortedBy { it.order }.forEachIndexed { index, rule ->
                        Spacer(Modifier.height(8.dp))
                        Text("${index + 1}. ${rule.title}", fontWeight = FontWeight.SemiBold)
                        if (rule.description.isNotBlank()) Text(rule.description, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        OutlinedButton(onClick = onCreatePost, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Create a post")
        }
    }
}

@Composable
private fun CommunityAvatar(detail: CommunityDetail, avatarRenderer: CommunityAvatarRenderer) {
    Box(
        Modifier.size(68.dp).clip(CircleShape).background(communityAvatarColor(detail.name)),
        contentAlignment = Alignment.Center,
    ) {
        detail.avatarUrl?.takeIf(String::isNotBlank)?.let { url ->
            avatarRenderer(url, "${detail.displayName} community avatar", Modifier.size(68.dp))
        } ?: Text(
            detail.displayName.take(1).uppercase(),
            color = Color.White,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

fun formatCommunityCount(value: Int): String = when {
    value >= 1_000_000 -> "${oneDecimal(value / 1_000_000f)}M"
    value >= 1_000 -> "${oneDecimal(value / 1_000f)}K"
    else -> value.toString()
}

private fun oneDecimal(value: Float): String {
    val rounded = (value * 10).roundToInt() / 10f
    return if (rounded % 1f == 0f) rounded.toInt().toString() else rounded.toString()
}

fun communityAvatarColor(name: String): Color {
    val colors = listOf(0xff006cbf, 0xff7b1fa2, 0xff00796b, 0xffc2410c, 0xff455a64)
    return Color(colors[(name.hashCode() and Int.MAX_VALUE) % colors.size])
}

private const val COMMUNITY_TITLE_CROSSFADE_MILLIS = 220
