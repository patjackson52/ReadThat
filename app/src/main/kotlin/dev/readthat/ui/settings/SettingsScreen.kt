package dev.readthat.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DataSaverOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.MotionPhotosOff
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.readthat.shared.AppSettings
import dev.readthat.shared.UserProfile

@Composable
fun SettingsScreen(
    user: UserProfile,
    settings: AppSettings,
    onBack: () -> Unit,
    onEditProfile: () -> Unit,
    onCreateCommunity: () -> Unit,
    onSettings: ((AppSettings) -> AppSettings) -> Unit,
    onLogout: () -> Unit,
) {
    var confirmLogout by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionTitle("ACCOUNT")
            SettingsRow(Icons.Default.AccountCircle, "Account settings", "u/${user.username}", onEditProfile)
            SettingsRow(Icons.Default.Groups, "Create a community", "Start and moderate a new subreddit", onCreateCommunity)
            HorizontalDivider()
            SectionTitle("VIEW OPTIONS")
            ToggleRow(Icons.Default.DarkMode, "Dark mode", settings.darkTheme) { onSettings { it.copy(darkTheme = !it.darkTheme) } }
            ToggleRow(Icons.Default.ViewAgenda, "Compact posts", settings.compactPosts) { onSettings { it.copy(compactPosts = !it.compactPosts) } }
            ToggleRow(Icons.Default.PlayCircle, "Autoplay video", settings.autoplayVideo) { onSettings { it.copy(autoplayVideo = !it.autoplayVideo) } }
            ToggleRow(Icons.Default.DataSaverOn, "Autoplay on mobile data", settings.autoplayOnMetered) {
                onSettings { it.copy(autoplayOnMetered = !it.autoplayOnMetered) }
            }
            ToggleRow(Icons.Default.DataSaverOn, "Reduce video data on metered networks", settings.reduceDataOnMetered) {
                onSettings { it.copy(reduceDataOnMetered = !it.reduceDataOnMetered) }
            }
            ToggleRow(Icons.Default.MotionPhotosOff, "Reduce animations", settings.reduceAnimations) { onSettings { it.copy(reduceAnimations = !it.reduceAnimations) } }
            ToggleRow(Icons.Default.Image, "Blur mature media", settings.blurMatureMedia) { onSettings { it.copy(blurMatureMedia = !it.blurMatureMedia) } }
            HorizontalDivider()
            SectionTitle("SUPPORT")
            SettingsRow(Icons.AutoMirrored.Filled.Logout, "Log out", "u/${user.username}") { confirmLogout = true }
        }
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Log out?") },
            text = { Text("You can log back in with your username and password.") },
            confirmButton = { TextButton(onClick = { confirmLogout = false; onLogout() }) { Text("Log out") } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
        )
    }
}

@Composable private fun SectionTitle(text: String) {
    Text(
        text,
        Modifier.padding(start = 18.dp, top = 24.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null)
        Column(Modifier.padding(start = 18.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ToggleRow(icon: ImageVector, title: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null)
        Text(title, Modifier.padding(start = 18.dp), fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}
