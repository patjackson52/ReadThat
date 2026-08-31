package dev.readthat.sharing.ui

import androidx.compose.runtime.Composable
import dev.readthat.sharing.SharePayload

/** Presents one stable shared payload through the target platform's system share surface. */
@Composable
expect fun rememberPlatformShareAction(payload: SharePayload): () -> Unit

/** Captures platform presentation state once while allowing shared callers to choose content. */
@Composable
expect fun rememberPlatformSharePayloadAction(): (SharePayload) -> Unit
