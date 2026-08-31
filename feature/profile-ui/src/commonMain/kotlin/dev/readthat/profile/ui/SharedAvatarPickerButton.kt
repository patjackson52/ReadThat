package dev.readthat.profile.ui

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.readthat.media.acquisition.ui.rememberPlatformAvatarPickerLauncher
import dev.readthat.shared.LocalPostMedia

/** Shared profile affordance over the platform picker launcher and avatar acquisition policy. */
@Composable
fun SharedAvatarPickerButton(
    enabled: Boolean,
    hasAvatar: Boolean,
    onPicked: (List<LocalPostMedia>) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val launch = rememberPlatformAvatarPickerLauncher(onPicked, onError)
    OutlinedButton(onClick = launch, enabled = enabled, modifier = modifier) {
        Text(if (hasAvatar) "Change photo" else "Add photo")
    }
}
