package dev.readthat.sharing.ui

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.readthat.sharing.SharePayload

@Composable
actual fun rememberPlatformShareAction(payload: SharePayload): () -> Unit {
    val context = LocalContext.current
    return remember(context, payload) {
        { context.startActivity(payload.toChooserIntent()) }
    }
}

@Composable
actual fun rememberPlatformSharePayloadAction(): (SharePayload) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { payload -> context.startActivity(payload.toChooserIntent()) }
    }
}

private fun SharePayload.toChooserIntent(): Intent {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_TEXT, text)
        subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
    }
    return Intent.createChooser(sendIntent, subject ?: "Share")
}
