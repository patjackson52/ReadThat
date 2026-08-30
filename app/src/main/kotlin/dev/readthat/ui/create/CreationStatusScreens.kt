package dev.readthat.ui.create

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.readthat.data.db.PendingPostEntity
import dev.readthat.data.db.PendingSubredditEntity
import dev.readthat.core.ui.markdown.MarkdownText

@Composable
fun CreateCommunityScreen(
    viewModel: CreateCommunityViewModel,
    onBack: () -> Unit,
    onQueued: (mutationId: String) -> Unit,
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        CreationHeader("Create a community", onBack) {
            Button(onClick = { viewModel.submit(onQueued) }, enabled = draft.canSubmit) {
                if (draft.submitting) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
                else Text("Create", fontWeight = FontWeight.Bold)
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Groups, null)
                    Column(Modifier.padding(start = 14.dp)) {
                        Text("Your community is available immediately", fontWeight = FontWeight.Bold)
                        Text(
                            "We save it offline first, then sync the same UUID when the network is ready.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            OutlinedTextField(
                value = draft.name,
                onValueChange = viewModel::setName,
                label = { Text("Community name") },
                prefix = { Text("r/") },
                supportingText = { Text("3–21 letters, numbers, or underscores. Names cannot be changed.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
            )
            OutlinedTextField(
                value = draft.displayName,
                onValueChange = viewModel::setDisplayName,
                label = { Text("Display name") },
                supportingText = { Text("${100 - draft.displayName.length} characters left") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
            )
            OutlinedTextField(
                value = draft.description,
                onValueChange = viewModel::setDescription,
                label = { Text("Description – optional") },
                supportingText = { Text("${1_000 - draft.description.length} characters left") },
                minLines = 5,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
            )
            Text("Community type", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AccessTypeChip("public", "Public", "Anyone can view, join, and post", draft.accessType, viewModel::setAccessType)
                AccessTypeChip("restricted", "Restricted", "Anyone can view; approved members can post", draft.accessType, viewModel::setAccessType)
                AccessTypeChip("private", "Private", "Only approved members can view and post", draft.accessType, viewModel::setAccessType)
            }
            draft.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun CommunityCreationStatusScreen(
    viewModel: CommunityCreationStatusViewModel,
    onBack: () -> Unit,
    onCreatePost: (subreddit: String) -> Unit,
) {
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        CreationHeader("Community", onBack)
        val command = pending
        if (command == null) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(80.dp))
                CircularProgressIndicator()
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                MutationStatusCard(communityStatus(command))
                Text("r/${command.name}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                Text(command.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (command.description.isNotBlank()) Text(command.description)
                Text(
                    command.accessType.replaceFirstChar(Char::uppercase) + " community · You are the owner",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (command.state == "failed") {
                    OutlinedButton(onClick = viewModel::retry, modifier = Modifier.fillMaxWidth()) { Text("Retry creation") }
                } else {
                    Button(onClick = { onCreatePost(command.name) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Create the first post")
                    }
                    Text(
                        "Posts created while this community is still syncing wait behind it automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun PendingPostScreen(
    viewModel: PendingPostViewModel,
    onBack: () -> Unit,
    onCommitted: (postId: String) -> Unit,
) {
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pending?.remotePostId) {
        pending?.remotePostId?.let(onCommitted)
    }
    Column(Modifier.fillMaxSize()) {
        CreationHeader("Your post", onBack)
        val post = pending
        if (post == null) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(80.dp))
                CircularProgressIndicator()
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                MutationStatusCard(postStatus(post))
                Text("r/${post.subreddit}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(post.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                post.flairText?.let { flair ->
                    Surface(
                        color = post.flairBackgroundColor.statusColor(MaterialTheme.colorScheme.surfaceVariant),
                        contentColor = post.flairTextColor.statusColor(MaterialTheme.colorScheme.onSurface),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(
                            flair,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                when (post.kind.lowercase()) {
                    "text" -> MarkdownText(post.body)
                    "link" -> Text(post.linkUrl, color = MaterialTheme.colorScheme.primary)
                    "image" -> Text("Image saved locally and ready to upload")
                    "video" -> Text("Video saved locally and ready for resumable upload")
                }
                if (post.state == "failed") {
                    OutlinedButton(onClick = viewModel::retry, modifier = Modifier.fillMaxWidth()) { Text("Retry post") }
                }
            }
        }
    }
}

private fun String?.statusColor(fallback: Color): Color = this?.let { value ->
    runCatching { Color(AndroidColor.parseColor(value)) }.getOrDefault(fallback)
} ?: fallback

@Composable
private fun CreationHeader(title: String, onBack: () -> Unit, action: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        action()
    }
}

@Composable
private fun AccessTypeChip(
    value: String,
    title: String,
    description: String,
    selected: String,
    onSelected: (String) -> Unit,
) {
    FilterChip(
        selected = selected == value,
        onClick = { onSelected(value) },
        label = {
            Column(Modifier.padding(vertical = 5.dp)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

private data class MutationStatus(val icon: ImageVector, val title: String, val detail: String, val failed: Boolean = false)

@Composable
private fun MutationStatusCard(status: MutationStatus) {
    Surface(
        color = if (status.failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(status.icon, null)
            Column(Modifier.padding(start = 14.dp)) {
                Text(status.title, fontWeight = FontWeight.Bold)
                Text(status.detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun communityStatus(command: PendingSubredditEntity): MutationStatus = when (command.state) {
    "completed" -> MutationStatus(Icons.Default.CheckCircle, "Community is live", "Synced with the server")
    "failed" -> MutationStatus(Icons.Default.CloudOff, "Could not create community", command.lastError ?: "Try again", true)
    "creating" -> MutationStatus(Icons.Default.Schedule, "Creating community", "Publishing your locally saved community")
    "retrying" -> MutationStatus(Icons.Default.CloudOff, "Saved offline", "Connection interrupted; retrying automatically")
    "waiting_account" -> MutationStatus(Icons.Default.Schedule, "Paused", "Sign back into this account to continue")
    else -> MutationStatus(Icons.Default.CloudOff, "Saved offline", "Waiting for a network connection")
}

private fun postStatus(post: PendingPostEntity): MutationStatus = when (post.state) {
    "completed" -> MutationStatus(Icons.Default.CheckCircle, "Post is live", "Opening the server-confirmed post")
    "failed" -> MutationStatus(Icons.Default.CloudOff, "Could not publish post", post.lastError ?: "Try again", true)
    "uploading" -> MutationStatus(Icons.Default.Schedule, "Uploading media", "Your draft remains safely stored on this device")
    "creating" -> MutationStatus(Icons.Default.Schedule, "Publishing post", "Finalizing the same retry-safe UUID")
    "waiting_community" -> MutationStatus(Icons.Default.Schedule, "Waiting for community", "This post will follow community creation")
    "waiting_account" -> MutationStatus(Icons.Default.Schedule, "Paused", "Sign back into this account to continue")
    "retrying" -> MutationStatus(Icons.Default.CloudOff, "Saved offline", "Connection interrupted; retrying automatically")
    else -> MutationStatus(Icons.Default.CloudOff, "Saved offline", "Waiting for a network connection")
}
