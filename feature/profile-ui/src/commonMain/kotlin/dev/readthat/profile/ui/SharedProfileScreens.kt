package dev.readthat.profile.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.readthat.shared.UserProfile
import kotlin.time.Clock

private val ReadThatOrange = Color(0xFFFF4500)
private val ReadThatNavy = Color(0xFF0B1416)

typealias ProfileAvatarRenderer = @Composable (
    user: UserProfile,
    sizeDp: Int,
    modifier: Modifier,
) -> Unit

typealias ProfileAvatarPicker = @Composable (
    enabled: Boolean,
    hasAvatar: Boolean,
) -> Unit

@Immutable
data class ProfileEditorUiState(
    val displayName: String,
    val bio: String,
    val saving: Boolean = false,
    val hasAvatar: Boolean = false,
    val error: String? = null,
) {
    val canSave: Boolean
        get() = !saving && displayName.isNotBlank() && displayName.length <= DISPLAY_NAME_LIMIT &&
            bio.length <= BIO_LIMIT
}

/** Shared own-profile presentation. The application shell owns all safe-area consumption. */
@Composable
fun SharedOwnProfileScreen(
    user: UserProfile,
    onEdit: () -> Unit,
    onSettings: () -> Unit,
    avatarRenderer: ProfileAvatarRenderer,
    nowEpochMillis: Long = Clock.System.now().toEpochMilliseconds(),
    modifier: Modifier = Modifier,
) {
    val accountAge = remember(user.createdAt, nowEpochMillis) {
        accountAgeLabel(user.createdAt, nowEpochMillis)
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ProfileHero {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Color.Black.copy(alpha = .3f), shape = RoundedCornerShape(24.dp)) {
                    Text(
                        "u/${user.username}",
                        Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        color = Color.White,
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onSettings) { Text("Settings", color = Color.White) }
            }
            Spacer(Modifier.height(28.dp))
            avatarRenderer(user, 92, Modifier)
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
                    Text("Edit", color = Color.White)
                }
            }
            ProfileIdentityAndMetrics(user, accountAge, includeContributions = true)
        }
        Row(Modifier.fillMaxWidth().height(58.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            ProfileTab("Posts", false)
            ProfileTab("Comments", false)
            ProfileTab("About", true)
        }
        ProfileAbout(user, includeKarmaCard = true)
    }
}

@Composable
fun SharedPublicProfileScreen(
    user: UserProfile?,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    avatarRenderer: ProfileAvatarRenderer,
    onRetry: (() -> Unit)? = null,
    nowEpochMillis: Long = Clock.System.now().toEpochMilliseconds(),
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        ProfileHeader(user?.let { "u/${it.username}" } ?: "Profile", onBack)
        HorizontalDivider()
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            user == null -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(error ?: "Profile unavailable")
                if (onRetry != null) {
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
            }
            else -> {
                val accountAge = accountAgeLabel(user.createdAt, nowEpochMillis)
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ProfileHero {
                        avatarRenderer(user, 92, Modifier)
                        Text(
                            user.displayName,
                            Modifier.padding(top = 16.dp),
                            color = Color.White,
                            fontSize = 27.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        ProfileIdentityAndMetrics(user, accountAge, includeContributions = false)
                    }
                    ProfileAbout(user, includeKarmaCard = false)
                }
            }
        }
    }
}

/** Shared editor form; media selection and preview decoding remain host-injected. */
@Composable
fun SharedEditProfileScreen(
    user: UserProfile,
    state: ProfileEditorUiState,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onDisplayNameChanged: (String) -> Unit,
    onBioChanged: (String) -> Unit,
    onRemoveAvatar: () -> Unit,
    avatarRenderer: ProfileAvatarRenderer,
    avatarPicker: ProfileAvatarPicker,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, enabled = !state.saving) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            Text("Edit Profile", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onSave, enabled = state.canSave) {
                Text(if (state.saving) "Saving…" else "Save", fontWeight = FontWeight.Bold)
            }
        }
        HorizontalDivider()
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
                avatarRenderer(user, 108, Modifier)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                avatarPicker(!state.saving, state.hasAvatar)
                if (state.hasAvatar) {
                    OutlinedButton(onClick = onRemoveAvatar, enabled = !state.saving) { Text("Remove") }
                }
            }
            OutlinedTextField(
                value = state.displayName,
                onValueChange = { value -> if (value.length <= DISPLAY_NAME_LIMIT) onDisplayNameChanged(value) },
                label = { Text("Display name") },
                supportingText = { Text("${DISPLAY_NAME_LIMIT - state.displayName.length} characters left") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.saving,
                shape = RoundedCornerShape(18.dp),
            )
            OutlinedTextField(
                value = state.bio,
                onValueChange = { value -> if (value.length <= BIO_LIMIT) onBioChanged(value) },
                label = { Text("About you – optional") },
                supportingText = { Text("${BIO_LIMIT - state.bio.length} characters left") },
                minLines = 6,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.saving,
                shape = RoundedCornerShape(18.dp),
            )
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun ProfileHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ProfileHero(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(
            Brush.verticalGradient(listOf(Color(0xFF192D35), ReadThatNavy)),
        ).padding(horizontal = 18.dp, vertical = 18.dp),
    ) {
        content()
    }
}

@Composable
private fun ProfileIdentityAndMetrics(
    user: UserProfile,
    accountAge: String,
    includeContributions: Boolean,
) {
    Text("u/${user.username}", color = Color(0xFFB9C8CD), modifier = Modifier.padding(top = 6.dp))
    if (user.bio.isNotBlank()) Text(user.bio, color = Color.White, modifier = Modifier.padding(top = 14.dp))
    Spacer(Modifier.height(if (includeContributions) 28.dp else 24.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (includeContributions) Arrangement.SpaceBetween else Arrangement.SpaceEvenly,
    ) {
        ProfileMetric(user.karma.toString(), "Karma")
        if (includeContributions) ProfileMetric("0", "Contributions")
        ProfileMetric(accountAge, "Account age")
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun ProfileAbout(user: UserProfile, includeKarmaCard: Boolean) {
    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("About", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            user.bio.ifBlank { "This profile has not added an about section yet." },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (includeKarmaCard) {
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
private fun ProfileMetric(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontWeight = FontWeight.Bold)
        Text(label, color = Color(0xFFB9C8CD), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ProfileTab(label: String, selected: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            Modifier.padding(horizontal = 18.dp, vertical = 17.dp),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
        if (selected) Box(Modifier.height(3.dp).fillMaxWidth().background(ReadThatOrange))
    }
}

internal fun accountAgeLabel(createdAt: Long, nowMillis: Long): String {
    if (createdAt <= 0L || nowMillis < createdAt) return "New"
    val days = (nowMillis - createdAt) / 86_400_000L
    return when {
        days < 1L -> "Today"
        days < 30L -> "${days}d"
        days < 365L -> "${days / 30L}mo"
        else -> "${days / 365L}y"
    }
}

private const val DISPLAY_NAME_LIMIT = 50
private const val BIO_LIMIT = 500
