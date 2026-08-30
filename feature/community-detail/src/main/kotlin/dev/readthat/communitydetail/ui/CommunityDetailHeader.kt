package dev.readthat.communitydetail.ui

import android.os.SystemClock
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.readthat.communitydetail.domain.CommunityDetail
import dev.readthat.observability.PerformanceMetric
import dev.readthat.observability.PerformanceSurface
import dev.readthat.observability.PerformanceTelemetry
import dev.readthat.observability.ProductAnalytics
import dev.readthat.observability.ProductContentType
import dev.readthat.observability.ProductEvent
import dev.readthat.observability.ProductEventName
import dev.readthat.observability.ProductSurface
import dev.readthat.observability.performanceTimer
import kotlin.math.roundToInt

/** Scrollable community chrome inserted as the first item in the existing SDUI feed. */
@Composable
fun CommunityDetailTelemetry(
    viewModel: CommunityDetailViewModel,
    communityName: String,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ttiTimer = remember(communityName) { performanceTimer() }
    var ttiReported by remember(communityName) { mutableStateOf(false) }

    LaunchedEffect(Unit) { PerformanceTelemetry.enterSurface(PerformanceSurface.COMMUNITY) }
    LaunchedEffect(state.detail?.id, state.initialCacheTier) {
        val cacheTier = state.initialCacheTier
        if (!ttiReported && state.detail != null && cacheTier != null) {
            withFrameNanos { }
            ttiReported = true
            PerformanceTelemetry.duration(
                PerformanceMetric.COMMUNITY_TTI,
                ttiTimer,
                surface = PerformanceSurface.COMMUNITY,
                attributes = mapOf("cache_tier" to cacheTier),
            )
        }
    }
    DisposableEffect(communityName) {
        val startedAt = SystemClock.elapsedRealtime()
        ProductAnalytics.record(ProductEvent(
            name = ProductEventName.COMMUNITY_VIEW,
            surface = ProductSurface.COMMUNITY,
            contentId = communityName,
            contentType = ProductContentType.COMMUNITY,
        ))
        onDispose {
            ProductAnalytics.record(ProductEvent(
                name = ProductEventName.COMMUNITY_TIME_SPENT,
                surface = ProductSurface.COMMUNITY,
                contentId = communityName,
                contentType = ProductContentType.COMMUNITY,
                durationMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L),
            ))
        }
    }

}

/** Scrollable community chrome inserted as the first item in the existing SDUI feed. */
@Composable
fun CommunityDetailHeader(
    viewModel: CommunityDetailViewModel,
    communityName: String,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onCreatePost: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        CommunityToolbar(
            title = state.detail?.let { "r/${it.name}" } ?: "r/$communityName",
            onBack = onBack,
            onSearch = onSearch,
        )
        when (val detail = state.detail) {
            null -> CommunityHeaderLoading(
                name = communityName,
                error = state.error,
                onRetry = viewModel::refresh,
            )
            else -> CommunityIdentity(
                detail = detail,
                refreshing = state.refreshing,
                membershipChanging = state.membershipChanging,
                error = state.error,
                onToggleMembership = viewModel::toggleMembership,
                onRetry = viewModel::refresh,
                onCreatePost = onCreatePost,
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
            Icon(Icons.Default.ExpandMore, contentDescription = "Post sort")
        }
        HorizontalDivider()
    }
}

@Composable
private fun CommunityToolbar(
    title: String,
    onBack: () -> Unit,
    onSearch: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 4.dp),
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                "Back",
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }

        Crossfade(
            targetState = title,
            modifier = Modifier
                .fillMaxWidth()
                // Reserve the larger, two-action edge on both sides so the title's visual
                // center is always the screen center rather than the Row's remaining center.
                .padding(horizontal = 100.dp)
                .align(Alignment.Center),
            animationSpec = tween(durationMillis = COMMUNITY_TITLE_CROSSFADE_MILLIS),
            label = "community-title",
        ) { currentTitle ->
            Text(
                currentTitle,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onSearch) {
                Icon(Icons.Default.Search, "Search", tint = MaterialTheme.colorScheme.onPrimary)
            }
            IconButton(onClick = {}) {
                Icon(
                    Icons.Default.MoreHoriz,
                    "Community options",
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
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
    error: String?,
    onToggleMembership: () -> Unit,
    onRetry: () -> Unit,
    onCreatePost: () -> Unit,
) {
    var rulesExpanded by rememberSaveable(detail.id) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CommunityAvatar(detail)
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
        if (detail.description.isNotBlank()) {
            Text(detail.description, style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Stat(
                formatCommunityCount(detail.subscriberCount),
                if (detail.subscriberCount == 1) "member" else "members",
            )
            Stat(detail.accessType.replaceFirstChar(Char::uppercase), "community")
            if (refreshing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
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
                    Icon(
                        if (rulesExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (rulesExpanded) "Collapse rules" else "Expand rules",
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
                        if (rule.description.isNotBlank()) {
                            Text(rule.description, style = MaterialTheme.typography.bodySmall)
                        }
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
private fun CommunityAvatar(detail: CommunityDetail) {
    Box(
        Modifier
            .size(68.dp)
            .clip(CircleShape)
            .background(avatarColor(detail.name)),
        contentAlignment = Alignment.Center,
    ) {
        if (detail.avatarUrl != null) {
            AsyncImage(
                model = detail.avatarUrl,
                contentDescription = "${detail.displayName} community avatar",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(68.dp),
            )
        } else {
            Text(
                detail.displayName.take(1).uppercase(),
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatCommunityCount(value: Int): String = when {
    value >= 1_000_000 -> "${oneDecimal(value / 1_000_000f)}M"
    value >= 1_000 -> "${oneDecimal(value / 1_000f)}K"
    else -> value.toString()
}

private fun oneDecimal(value: Float): String {
    val rounded = (value * 10).roundToInt() / 10f
    return if (rounded % 1f == 0f) rounded.toInt().toString() else rounded.toString()
}

private fun avatarColor(name: String): Color {
    val colors = listOf(0xff006cbf, 0xff7b1fa2, 0xff00796b, 0xffc2410c, 0xff455a64)
    return Color(colors[(name.hashCode() and Int.MAX_VALUE) % colors.size])
}

private const val COMMUNITY_TITLE_CROSSFADE_MILLIS = 220
