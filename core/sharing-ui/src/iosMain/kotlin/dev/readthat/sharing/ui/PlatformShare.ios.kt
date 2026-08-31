package dev.readthat.sharing.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.readthat.sharing.SharePayload
import platform.Foundation.NSNotificationCenter

@Composable
actual fun rememberPlatformShareAction(payload: SharePayload): () -> Unit = remember(payload) {
    { postShare(payload) }
}

@Composable
actual fun rememberPlatformSharePayloadAction(): (SharePayload) -> Unit = remember {
    { payload -> postShare(payload) }
}

private fun postShare(payload: SharePayload) {
    NSNotificationCenter.defaultCenter.postNotificationName(
        "ReadThatShare",
        null,
        mapOf(
            "text" to payload.text,
            "mimeType" to payload.mimeType,
            "subject" to payload.subject.orEmpty(),
        ),
    )
}
