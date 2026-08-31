package dev.readthat.core.ui.brand

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** Platform resource adapters provide the painter; sizing, crop and semantics stay shared. */
@Composable
fun ReadThatLogo(
    painter: Painter,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Image(
        painter = painter,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Crop,
    )
}

/**
 * Code-native brand mark for KMP hosts.
 *
 * Keeping the compact launch/header mark in vector drawing code avoids transitive Compose
 * resource packaging differences between an Android application and an embedded KMP library.
 */
@Composable
fun ReadThatLogo(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val semanticModifier = if (contentDescription == null) modifier else {
        modifier.semantics { this.contentDescription = contentDescription }
    }
    Canvas(semanticModifier) {
        val unit = minOf(size.width, size.height)
        val left = (size.width - unit) / 2f
        val top = (size.height - unit) / 2f
        fun point(x: Float, y: Float) = Offset(left + unit * x, top + unit * y)

        drawRoundRect(
            color = Color(0xFFF04424),
            topLeft = Offset(left, top),
            size = androidx.compose.ui.geometry.Size(unit, unit),
            cornerRadius = CornerRadius(unit * .08f),
        )

        val paper = Color(0xFFFFFBF5)
        val leftPage = Path().apply {
            moveTo(point(.22f, .24f).x, point(.22f, .24f).y)
            cubicTo(
                point(.34f, .23f).x, point(.34f, .23f).y,
                point(.43f, .27f).x, point(.43f, .27f).y,
                point(.49f, .35f).x, point(.49f, .35f).y,
            )
            lineTo(point(.49f, .65f).x, point(.49f, .65f).y)
            cubicTo(
                point(.45f, .67f).x, point(.45f, .67f).y,
                point(.43f, .72f).x, point(.43f, .72f).y,
                point(.41f, .76f).x, point(.41f, .76f).y,
            )
            cubicTo(
                point(.39f, .79f).x, point(.39f, .79f).y,
                point(.37f, .77f).x, point(.37f, .77f).y,
                point(.39f, .73f).x, point(.39f, .73f).y,
            )
            cubicTo(
                point(.43f, .65f).x, point(.43f, .65f).y,
                point(.39f, .63f).x, point(.39f, .63f).y,
                point(.24f, .61f).x, point(.24f, .61f).y,
            )
            cubicTo(
                point(.20f, .61f).x, point(.20f, .61f).y,
                point(.19f, .59f).x, point(.19f, .59f).y,
                point(.19f, .55f).x, point(.19f, .55f).y,
            )
            lineTo(point(.19f, .28f).x, point(.19f, .28f).y)
            cubicTo(
                point(.19f, .25f).x, point(.19f, .25f).y,
                point(.20f, .24f).x, point(.20f, .24f).y,
                point(.22f, .24f).x, point(.22f, .24f).y,
            )
            close()
        }
        val rightPage = Path().apply {
            moveTo(point(.51f, .35f).x, point(.51f, .35f).y)
            cubicTo(
                point(.57f, .27f).x, point(.57f, .27f).y,
                point(.66f, .23f).x, point(.66f, .23f).y,
                point(.78f, .24f).x, point(.78f, .24f).y,
            )
            cubicTo(
                point(.80f, .24f).x, point(.80f, .24f).y,
                point(.81f, .25f).x, point(.81f, .25f).y,
                point(.81f, .28f).x, point(.81f, .28f).y,
            )
            lineTo(point(.81f, .55f).x, point(.81f, .55f).y)
            cubicTo(
                point(.81f, .59f).x, point(.81f, .59f).y,
                point(.80f, .61f).x, point(.80f, .61f).y,
                point(.76f, .61f).x, point(.76f, .61f).y,
            )
            cubicTo(
                point(.63f, .62f).x, point(.63f, .62f).y,
                point(.56f, .64f).x, point(.56f, .64f).y,
                point(.51f, .67f).x, point(.51f, .67f).y,
            )
            close()
        }
        drawPath(leftPage, paper)
        drawPath(rightPage, paper)
        drawLine(
            color = Color(0xFF0B2235),
            start = point(.50f, .36f),
            end = point(.50f, .64f),
            strokeWidth = unit * .014f,
            cap = StrokeCap.Round,
        )
    }
}
