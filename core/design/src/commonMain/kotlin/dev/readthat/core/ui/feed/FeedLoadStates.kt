package dev.readthat.core.ui.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Layout-matched initial feed state shared by Android and iOS. */
@Composable
fun FeedSkeleton(
    modifier: Modifier = Modifier,
    listHeader: (@Composable () -> Unit)? = null,
) {
    LazyColumn(modifier) {
        listHeader?.let { header ->
            item(key = "__feed_header_skeleton__", contentType = "feed_header") { header() }
        }
        items(3, key = { "__feed_skeleton_$it" }) { index ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(if (index == 0) 0.42f else 0.34f).height(12.dp),
                ) {}
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(0.88f).height(22.dp),
                ) {}
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 7f),
                ) {}
            }
        }
    }
}

/** Inline append failure keeps already-cached feed rows usable. */
@Composable
fun FeedAppendError(message: String, onRetry: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clickable(onClick = onRetry),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Text(
            "Retry",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Full-screen error is reserved for a cold feed with no durable Room rows. */
@Composable
fun FeedErrorState(message: String, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Text(
            "Tap to retry",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .padding(top = 12.dp)
                .clickable(onClick = onRetry),
        )
    }
}
