package dev.readthat.compose

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import dev.readthat.navigation.PlatformBackGestureBridge

@Composable
internal actual fun PlatformSystemBackHandler(
    enabled: Boolean,
    backGestures: PlatformBackGestureBridge?,
    onBack: () -> Unit,
) {
    BackHandler(enabled = enabled, onBack = onBack)
}
