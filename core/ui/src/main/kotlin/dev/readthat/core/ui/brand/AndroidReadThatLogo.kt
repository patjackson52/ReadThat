package dev.readthat.core.ui.brand

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import dev.readthat.core.ui.R

/** Android resource adapter for the shared brand renderer. */
@Composable
fun ReadThatLogo(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    ReadThatLogo(
        painter = painterResource(R.drawable.readthat_logo),
        modifier = modifier,
        contentDescription = contentDescription,
    )
}
