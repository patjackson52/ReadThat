package dev.readthat.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
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
import dev.readthat.client.SettingsPreference
import dev.readthat.client.SharedSettingsState
import dev.readthat.shared.AppSettings
import dev.readthat.shared.UserProfile

/** The host owns safe-area and navigation-bar insets; this screen consumes neither. */
@Composable
fun SharedSettingsScreen(
    user: UserProfile,
    state: SharedSettingsState,
    onBack: () -> Unit,
    onEditProfile: () -> Unit,
    onCreateCommunity: () -> Unit,
    onPreferenceChanged: (SettingsPreference, Boolean) -> Unit,
    onLogout: () -> Unit,
    onClearError: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var confirmLogout by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        SettingsHeader(onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsSectionTitle("ACCOUNT")
            SettingsActionRow(
                icon = Icons.Default.AccountCircle,
                title = "Account settings",
                detail = "u/${user.username}",
                onClick = onEditProfile,
            )
            SettingsActionRow(
                icon = Icons.Default.AddCircle,
                title = "Create a community",
                detail = "Start and moderate a new subreddit",
                onClick = onCreateCommunity,
            )
            HorizontalDivider()
            SettingsSectionTitle("VIEW OPTIONS")
            settingsPreferenceOrder.forEach { preference ->
                SettingsToggleRow(
                    icon = preference.icon(),
                    title = preference.label(),
                    checked = state.settings.value(preference),
                    onChecked = { enabled -> onPreferenceChanged(preference, enabled) },
                )
            }
            state.error?.let { error ->
                Text(
                    error,
                    Modifier.fillMaxWidth().clickable(onClick = onClearError)
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            HorizontalDivider()
            SettingsSectionTitle("SUPPORT")
            SettingsActionRow(
                icon = Icons.AutoMirrored.Filled.ExitToApp,
                title = "Log out",
                detail = "u/${user.username}",
                onClick = { confirmLogout = true },
            )
        }
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Log out?") },
            text = { Text("You can log back in with your username and password.") },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; onLogout() }) { Text("Log out") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SettingsHeader(onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
        }
        Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(
        text,
        Modifier.padding(start = 18.dp, top = 24.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null)
        Column(Modifier.padding(start = 18.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable { onChecked(!checked) }
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null)
        Text(title, Modifier.padding(start = 18.dp), fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

internal val settingsPreferenceOrder = listOf(
    SettingsPreference.DarkTheme,
    SettingsPreference.CompactPosts,
    SettingsPreference.AutoplayVideo,
    SettingsPreference.AutoplayOnMetered,
    SettingsPreference.ReduceDataOnMetered,
    SettingsPreference.ReduceAnimations,
    SettingsPreference.BlurMatureMedia,
)

private fun SettingsPreference.label(): String = when (this) {
    SettingsPreference.DarkTheme -> "Dark mode"
    SettingsPreference.CompactPosts -> "Compact posts"
    SettingsPreference.AutoplayVideo -> "Autoplay video"
    SettingsPreference.AutoplayOnMetered -> "Autoplay on mobile data"
    SettingsPreference.ReduceDataOnMetered -> "Reduce video data on metered networks"
    SettingsPreference.ReduceAnimations -> "Reduce animations"
    SettingsPreference.BlurMatureMedia -> "Blur mature media"
}

private fun SettingsPreference.icon(): ImageVector = when (this) {
    SettingsPreference.DarkTheme -> Icons.Default.Settings
    SettingsPreference.CompactPosts -> Icons.AutoMirrored.Filled.List
    SettingsPreference.AutoplayVideo -> Icons.Default.PlayArrow
    SettingsPreference.AutoplayOnMetered -> Icons.Default.Refresh
    SettingsPreference.ReduceDataOnMetered -> Icons.Default.Info
    SettingsPreference.ReduceAnimations -> Icons.Default.Refresh
    SettingsPreference.BlurMatureMedia -> Icons.Default.Warning
}

private fun AppSettings.value(preference: SettingsPreference): Boolean = when (preference) {
    SettingsPreference.DarkTheme -> darkTheme
    SettingsPreference.CompactPosts -> compactPosts
    SettingsPreference.AutoplayVideo -> autoplayVideo
    SettingsPreference.AutoplayOnMetered -> autoplayOnMetered
    SettingsPreference.ReduceDataOnMetered -> reduceDataOnMetered
    SettingsPreference.ReduceAnimations -> reduceAnimations
    SettingsPreference.BlurMatureMedia -> blurMatureMedia
}
