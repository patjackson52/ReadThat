package dev.readthat.core.ui.typography

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

/**
 * Typography roles calibrated against the Reddit Android feed on the reference Pixel.
 *
 * These are intentionally separate from Material's application-wide type scale. A feed title is
 * compact editorial content, while `MaterialTheme.typography.titleLarge` is a 22sp screen/section
 * heading and makes every server-driven post substantially taller than its Reddit counterpart.
 */
object ReadThatTextStyles {
    val feedTitle = TextStyle(
        fontSize = 18.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    )

    val feedBody = TextStyle(
        fontSize = 16.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp,
    )

    val feedSupporting = TextStyle(
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
    )

    val feedMetadata = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    )

    val feedAction = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    )

    val bottomNavigationLabel = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.sp,
    )
}
