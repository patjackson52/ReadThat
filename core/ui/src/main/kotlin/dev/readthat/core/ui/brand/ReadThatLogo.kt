package dev.readthat.core.ui.brand

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import dev.readthat.core.ui.R

/** The same mark exported for the launcher, iOS asset catalog, and PWA. */
@Composable
fun ReadThatLogo(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Image(
        painter = painterResource(R.drawable.readthat_logo),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Crop,
    )
}
