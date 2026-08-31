package dev.readthat.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import dev.readthat.navigation.PlatformBackGestureBridge
import kotlinx.coroutines.flow.collect

@Composable
internal actual fun PlatformSystemBackHandler(
    enabled: Boolean,
    backGestures: PlatformBackGestureBridge?,
    onBack: () -> Unit,
) {
    val latestEnabled = rememberUpdatedState(enabled)
    val latestOnBack = rememberUpdatedState(onBack)

    SideEffect { backGestures?.setEnabled(enabled) }
    DisposableEffect(backGestures) {
        onDispose { backGestures?.setEnabled(false) }
    }
    LaunchedEffect(backGestures) {
        backGestures?.requests?.collect {
            if (latestEnabled.value) latestOnBack.value()
        }
    }
}
