package dev.readthat.ui.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

internal const val POST_DETAIL_POSITION_MILLIS = 360

/**
 * Moves the destination's real content from the tapped feed post's vertical position.
 *
 * This deliberately is not a shared-element transition: the feed and detail retain independent
 * composition, measurement, and rendering. Only the detail content layer is translated while the
 * detail toolbar remains fixed. On Back, Navigation's exit progress drives the same layer toward
 * the retained feed position so the two directions use one spatial model.
 */
@Composable
internal fun PostDetailPositionTransition(
    enabled: Boolean,
    sourceY: Float?,
    destinationY: Float,
    popProgress: Float = 0f,
    content: @Composable (detailContentModifier: Modifier) -> Unit,
) {
    val canAnimate = enabled && sourceY != null
    val entryProgress = remember(canAnimate, sourceY, destinationY) {
        Animatable(if (canAnimate) 0f else 1f)
    }

    LaunchedEffect(canAnimate, sourceY, destinationY) {
        if (!canAnimate) {
            entryProgress.snapTo(1f)
            return@LaunchedEffect
        }

        entryProgress.snapTo(0f)
        // Present the source-aligned frame before advancing the position animation.
        withFrameNanos { }
        entryProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = POST_DETAIL_POSITION_MILLIS,
                easing = FastOutSlowInEasing,
            ),
        )
    }

    val progress = detailContentTransitionProgress(entryProgress.value, popProgress)
    val translationY = detailContentTranslationY(sourceY, destinationY, progress)
    content(
        Modifier.graphicsLayer {
            this.translationY = translationY
        },
    )
}

/** Entry advances 0→1; a Navigation pop takes precedence and reverses that progress. */
internal fun detailContentTransitionProgress(entryProgress: Float, popProgress: Float): Float =
    if (popProgress > 0f) {
        1f - popProgress.coerceIn(0f, 1f)
    } else {
        entryProgress.coerceIn(0f, 1f)
    }

/** Translation needed to place destination content at [sourceY], decaying to its final position. */
internal fun detailContentTranslationY(
    sourceY: Float?,
    destinationY: Float,
    progress: Float,
): Float = sourceY?.let {
    (it - destinationY) * (1f - progress.coerceIn(0f, 1f))
} ?: 0f
