package dev.readthat.profile.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dev.readthat.shared.UserProfile

private val ReadThatOrange = Color(0xFFFF4500)
private val ReadThatNavy = Color(0xFF0B1416)

@Composable
fun LegacyProfileScreen(
    user: UserProfile,
    onEdit: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(
            Modifier.fillMaxWidth().background(
                Brush.verticalGradient(listOf(Color(0xFF192D35), ReadThatNavy)),
            ).padding(horizontal = 18.dp, vertical = 18.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Color.Black.copy(alpha = .3f), shape = RoundedCornerShape(24.dp)) {
                    Text("u/${user.username}", Modifier.padding(horizontal = 14.dp, vertical = 9.dp), color = Color.White)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings", tint = Color.White) }
            }
            Spacer(Modifier.height(34.dp))
            Avatar(user, 92)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    user.displayName,
                    Modifier.weight(1f).padding(top = 16.dp),
                    color = Color.White,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                OutlinedButton(onClick = onEdit, shape = RoundedCornerShape(22.dp)) {
                    Icon(Icons.Default.Edit, null, Modifier.size(17.dp), tint = Color.White)
                    Text(" Edit", color = Color.White)
                }
            }
            Text("u/${user.username}", color = Color(0xFFB9C8CD), modifier = Modifier.padding(top = 6.dp))
            if (user.bio.isNotBlank()) Text(user.bio, color = Color.White, modifier = Modifier.padding(top = 14.dp))
            Spacer(Modifier.height(30.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ProfileMetric(user.karma.toString(), "Karma")
                ProfileMetric("0", "Contributions")
                ProfileMetric(accountAge(user.createdAt), "Account age")
            }
            Spacer(Modifier.height(20.dp))
        }
        Row(Modifier.fillMaxWidth().height(58.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            ProfileTab("Posts", false)
            ProfileTab("Comments", false)
            ProfileTab("About", true)
        }
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("About", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                user.bio.ifBlank { "This profile has not added an about section yet." },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Karma", fontWeight = FontWeight.SemiBold)
                    Text(user.karma.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun LegacyPublicProfileScreen(user: UserProfile, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("u/${user.username}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(
                Modifier.fillMaxWidth().background(
                    Brush.verticalGradient(listOf(Color(0xFF192D35), ReadThatNavy)),
                ).padding(18.dp),
            ) {
                Avatar(user, 92)
                Text(
                    user.displayName,
                    Modifier.padding(top = 16.dp),
                    color = Color.White,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Black,
                )
                Text("u/${user.username}", color = Color(0xFFB9C8CD), modifier = Modifier.padding(top = 6.dp))
                if (user.bio.isNotBlank()) Text(user.bio, color = Color.White, modifier = Modifier.padding(top = 14.dp))
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ProfileMetric(user.karma.toString(), "Karma")
                    ProfileMetric(accountAge(user.createdAt), "Account age")
                }
            }
            Column(Modifier.padding(18.dp)) {
                Text("About", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    user.bio.ifBlank { "This profile has not added an about section yet." },
                    Modifier.padding(top = 10.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun LegacyEditProfileScreen(
    user: UserProfile,
    saving: Boolean,
    onBack: () -> Unit,
    onSave: (displayName: String, bio: String, selectedAvatar: Uri?, removeAvatar: Boolean) -> Unit,
) {
    var displayName by remember(user.id) { mutableStateOf(user.displayName) }
    var bio by remember(user.id) { mutableStateOf(user.bio) }
    var selectedAvatar by remember(user.id) { mutableStateOf<Uri?>(null) }
    var removeAvatar by remember(user.id) { mutableStateOf(false) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            selectedAvatar = uri
            removeAvatar = false
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Edit Profile", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { onSave(displayName, bio, selectedAvatar, removeAvatar) },
                enabled = !saving && displayName.isNotBlank() && displayName.length <= 50 && bio.length <= 500,
            ) { Text(if (saving) "Saving…" else "Save", fontWeight = FontWeight.Bold) }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier.fillMaxWidth().height(190.dp).background(
                    Brush.linearGradient(listOf(Color(0xFFE8EEF0), Color(0xFFF9FAFA))),
                    RoundedCornerShape(18.dp),
                ),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Avatar(
                    user = user,
                    size = 108,
                    imageData = selectedAvatar ?: user.avatarUrl.takeUnless { removeAvatar },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                ) { Text(if (user.avatarUrl == null && selectedAvatar == null) "Add photo" else "Change photo") }
                if (user.avatarUrl != null || selectedAvatar != null) {
                    OutlinedButton(
                        onClick = {
                            selectedAvatar = null
                            removeAvatar = true
                        },
                    ) { Text("Remove") }
                }
            }
            OutlinedTextField(
                value = displayName,
                onValueChange = { if (it.length <= 50) displayName = it },
                label = { Text("Display name") },
                supportingText = { Text("${50 - displayName.length} characters left") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
            )
            OutlinedTextField(
                value = bio,
                onValueChange = { if (it.length <= 500) bio = it },
                label = { Text("About you – optional") },
                supportingText = { Text("${500 - bio.length} characters left") },
                minLines = 6,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
            )
        }
    }
}

@Composable
private fun Avatar(user: UserProfile, size: Int, imageData: Any? = user.avatarUrl) {
    val context = LocalContext.current
    val avatarRequest = remember(user.id, user.updatedAt, imageData) {
        imageData?.let { data ->
            ImageRequest.Builder(context).data(data).apply {
                if (data is String) {
                    val stableKey = "avatar:${user.id}:${user.updatedAt}:${data.substringBefore('?').hashCode()}"
                    memoryCacheKey(stableKey)
                    diskCacheKey(stableKey)
                }
            }.build()
        }
    }
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(Color(0xFF86A8F7)),
        contentAlignment = Alignment.Center,
    ) {
        if (avatarRequest != null) {
            AsyncImage(
                model = avatarRequest,
                contentDescription = "${user.displayName}'s profile image",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                user.displayName.trim().firstOrNull()?.uppercase() ?: "U",
                color = ReadThatNavy,
                fontWeight = FontWeight.Black,
                fontSize = (size / 2.5f).sp,
            )
        }
    }
}

@Composable private fun ProfileMetric(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontWeight = FontWeight.Bold)
        Text(label, color = Color(0xFFB9C8CD), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable private fun ProfileTab(label: String, selected: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, Modifier.padding(horizontal = 18.dp, vertical = 17.dp), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        if (selected) Box(Modifier.height(3.dp).fillMaxWidth().background(ReadThatOrange))
    }
}

private fun accountAge(createdAt: Long): String {
    if (createdAt <= 0) return "New"
    val days = ((System.currentTimeMillis() - createdAt).coerceAtLeast(0) / 86_400_000L)
    return when {
        days < 1 -> "Today"
        days < 30 -> "${days}d"
        days < 365 -> "${days / 30}mo"
        else -> "${days / 365}y"
    }
}
