package dev.readthat.compose

import androidx.compose.runtime.Composable
import dev.readthat.navigation.AppDestination
import dev.readthat.navigation.PlatformBackGestureBridge

/** Narrow host capability: Android owns system Back; iOS supplies native edge-pan requests. */
@Composable
internal expect fun PlatformSystemBackHandler(
    enabled: Boolean,
    backGestures: PlatformBackGestureBridge?,
    onBack: () -> Unit,
)

/** Home exits through the OS. Every other shared destination first returns through KMP history. */
internal fun AppDestination.handlesPlatformSystemBack(): Boolean = this != AppDestination.Feed
