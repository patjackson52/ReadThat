package dev.readthat.feed.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.readthat.core.ui.markdown.MarkdownText
import dev.readthat.core.ui.typography.ReadThatTextStyles
import dev.readthat.domain.CellUi

@Composable
fun FeedMetadataCell(
    item: CellUi.Metadata,
    onOpenCommunity: (String) -> Unit,
    modifier: Modifier = Modifier,
    trailingContent: @Composable () -> Unit = {},
) {
    val communityName = item.subreddit.trim().removePrefix("r/")
    Row(
        modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .clickable(
                    enabled = communityName.isNotBlank(),
                    onClickLabel = "Open r/$communityName",
                ) { onOpenCommunity(communityName) },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(28.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    communityName.take(1).uppercase(),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = ReadThatTextStyles.feedMetadata,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                item.line,
                style = ReadThatTextStyles.feedMetadata,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailingContent()
        if (item.pinned) {
            Text("PINNED", style = ReadThatTextStyles.feedMetadata, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun FeedTitleCell(item: CellUi.Title, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(item.text, style = ReadThatTextStyles.feedTitle, fontWeight = FontWeight.Bold)
        item.flair?.let { flair ->
            Surface(
                color = parseFeedColor(flair.backgroundColor, MaterialTheme.colorScheme.surfaceVariant),
                contentColor = parseFeedColor(flair.textColor, MaterialTheme.colorScheme.onSurface),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                Text(
                    flair.text,
                    style = ReadThatTextStyles.feedMetadata,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
fun FeedTextCell(item: CellUi.Text, modifier: Modifier = Modifier) {
    MarkdownText(
        markdown = item.body,
        style = ReadThatTextStyles.feedBody,
        maxLines = item.maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
fun FeedLinkCell(item: CellUi.Link, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(item.domain, style = ReadThatTextStyles.feedSupporting)
            Text(
                item.url,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = ReadThatTextStyles.feedMetadata,
            )
        }
    }
}

@Composable
fun FeedAnnouncementCell(item: CellUi.Announcement, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(item.text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

internal fun parseFeedColor(value: String, fallback: Color): Color {
    val hex = value.trim().removePrefix("#")
    val parsed = hex.toLongOrNull(16) ?: return fallback
    return when (hex.length) {
        6 -> Color(0xFF000000L or parsed)
        8 -> Color(parsed)
        else -> fallback
    }
}
