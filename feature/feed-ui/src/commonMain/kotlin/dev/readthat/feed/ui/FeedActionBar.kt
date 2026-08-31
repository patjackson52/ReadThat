package dev.readthat.feed.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Share
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.readthat.core.ui.typography.ReadThatTextStyles
import dev.readthat.domain.CellUi

data class FeedActionIcons(
    val upvote: ImageVector? = Icons.Outlined.ArrowUpward,
    val downvote: ImageVector? = Icons.Outlined.ArrowDownward,
    val comments: ImageVector? = Icons.Outlined.ChatBubbleOutline,
    val reshare: ImageVector? = Icons.Outlined.Repeat,
    val share: ImageVector? = Icons.Outlined.Share,
)

@Composable
fun FeedActionBarCell(
    item: CellUi.ActionBar,
    onVote: (String, Int) -> Unit,
    onOpenComments: (String) -> Unit,
    onReshare: (String) -> Unit,
    onShare: (String) -> Unit,
    modifier: Modifier = Modifier,
    icons: FeedActionIcons = FeedActionIcons(),
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = Color.Transparent,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FeedActionGlyph(
                    icon = icons.upvote,
                    fallback = "▲",
                    accessibilityLabel = "Upvote",
                    tint = if (item.viewerVote == 1) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    onClick = { onVote(item.itemId, toggledVote(item.viewerVote, 1)) },
                )
                Text(item.scoreLabel, fontWeight = FontWeight.Bold, style = ReadThatTextStyles.feedAction)
                FeedActionGlyph(
                    icon = icons.downvote,
                    fallback = "▼",
                    accessibilityLabel = "Downvote",
                    tint = if (item.viewerVote == -1) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    onClick = { onVote(item.itemId, toggledVote(item.viewerVote, -1)) },
                )
            }
        }
        FeedActionPill(
            icon = icons.comments,
            fallback = "○",
            label = item.commentLabel,
            accessibilityLabel = "Comments, ${item.commentLabel}",
        ) { onOpenComments(item.itemId) }
        FeedActionPill(
            icon = icons.reshare,
            fallback = "↻",
            accessibilityLabel = "Reshare",
        ) { onReshare(item.itemId) }
        Spacer(Modifier.weight(1f))
        FeedActionPill(
            icon = icons.share,
            fallback = "↗",
            accessibilityLabel = "Share",
        ) { onShare(item.itemId) }
    }
}

internal fun toggledVote(currentVote: Int, requestedVote: Int): Int {
    require(currentVote in -1..1)
    require(requestedVote == -1 || requestedVote == 1)
    return if (currentVote == requestedVote) 0 else requestedVote
}

@Composable
private fun FeedActionGlyph(
    icon: ImageVector?,
    fallback: String,
    accessibilityLabel: String,
    tint: Color,
    onClick: () -> Unit,
) {
    val modifier = Modifier
        .clickable(onClick = onClick)
        .semantics { contentDescription = accessibilityLabel }
        .padding(8.dp)
        .size(19.dp)
    if (icon != null) {
        Icon(icon, null, modifier, tint = tint)
    } else {
        Text(fallback, modifier, color = tint, style = ReadThatTextStyles.feedAction)
    }
}

@Composable
private fun FeedActionPill(
    icon: ImageVector?,
    fallback: String,
    label: String = "",
    accessibilityLabel: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = Color.Transparent,
        modifier = Modifier
            .clickable(onClick = onClick)
            .semantics { contentDescription = accessibilityLabel },
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(icon, null, Modifier.size(18.dp))
            else Text(fallback, style = ReadThatTextStyles.feedAction, fontWeight = FontWeight.Bold)
            if (label.isNotBlank()) {
                Text(" $label", style = ReadThatTextStyles.feedAction, fontWeight = FontWeight.Bold)
            }
        }
    }
}
