package dev.readthat.media.acquisition.ui

import androidx.compose.runtime.Composable
import dev.readthat.shared.LocalPostMedia
import dev.readthat.shared.PostKind

/** Native image picker constrained by the shared single-avatar policy. */
@Composable
expect fun rememberPlatformAvatarPickerLauncher(
    onPicked: (List<LocalPostMedia>) -> Unit,
    onError: (String) -> Unit,
): () -> Unit

/** Imperative launcher used by feature composers while picker mechanics stay native. */
@Composable
expect fun rememberPlatformMediaPickerLauncher(
    kind: PostKind,
    onPicked: (List<LocalPostMedia>) -> Unit,
    onError: (String) -> Unit,
): () -> Unit

/** Camera capture is optional only when a platform/device cannot provide one. */
@Composable
expect fun rememberPlatformCameraLauncher(
    onPicked: (List<LocalPostMedia>) -> Unit,
    onError: (String) -> Unit,
): (() -> Unit)?
