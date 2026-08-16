package com.evan.lazlo.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Which corner a [CornerFlourish] is meant to sit in — controls which way the drawn curl is mirrored, not layout placement (the caller still positions it, e.g. with `Modifier.align`). */
enum class FlourishCorner { TopStart, TopEnd, BottomStart, BottomEnd }

/**
 * A single small pen-flourish curl — the "illuminated manuscript margin
 * doodle" ornament used sparingly on major cards/dialogs and the top bar
 * to sell the parchment reskin at a glance. Drawn as vector [Path] curves
 * in a [Canvas], not an imported image asset: keeps this fully original
 * (no external asset dependency, nothing to license) and lets it recolor
 * itself for free from [MaterialTheme.colorScheme] like everything else
 * in the app.
 *
 * Deliberately one curl, not a border of them: the brief is "a small
 * decorative accent," and a flourish repeated around every edge of every
 * card would compete with the actual content instead of accenting it.
 * Callers that want it in a specific corner of a container should wrap
 * their content in a `Box` and `Modifier.align` this into place (see
 * [ScrollCard] for the standard version of that).
 */
@Composable
fun CornerFlourish(
    modifier: Modifier = Modifier,
    corner: FlourishCorner = FlourishCorner.TopEnd,
    color: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
    size: Dp = 20.dp,
) {
    // The curl is authored once, for the top-end corner; the other three
    // are the same path mirrored via a layer transform rather than three
    // separate hand-drawn paths to keep in sync with each other.
    val flipX = corner == FlourishCorner.TopStart || corner == FlourishCorner.BottomStart
    val flipY = corner == FlourishCorner.BottomStart || corner == FlourishCorner.BottomEnd

    Canvas(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = if (flipX) -1f else 1f
                scaleY = if (flipY) -1f else 1f
            },
    ) {
        val strokeWidth = 1.4.dp.toPx()
        drawPath(
            path = flourishCurlPath(this.size),
            color = color,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )
        // A small accent dot at the curl's tip — the "period" a hand-drawn
        // flourish usually ends on — kept separate from the stroked path
        // since a filled dot and a stroked curl are two different draw
        // styles.
        drawCircle(
            color = color,
            radius = strokeWidth * 1.3f,
            center = Offset(this.size.width * 0.74f, this.size.height * 0.30f),
        )
    }
}

/**
 * One curling swash from near the bottom-left of the box up toward a
 * small inner spiral near the top-right — evokes a hand-drawn pen
 * flourish without spelling out any letter, symbol, or map-like shape.
 * Coordinates are fractions of [size] so the same path scales cleanly
 * whatever [CornerFlourish.size] is passed.
 */
private fun flourishCurlPath(size: Size): Path {
    val w = size.width
    val h = size.height
    return Path().apply {
        moveTo(w * 0.05f, h * 0.85f)
        cubicTo(
            w * 0.15f, h * 0.55f,
            w * 0.10f, h * 0.15f,
            w * 0.45f, h * 0.12f,
        )
        cubicTo(
            w * 0.70f, h * 0.10f,
            w * 0.72f, h * 0.30f,
            w * 0.55f, h * 0.35f,
        )
        cubicTo(
            w * 0.45f, h * 0.38f,
            w * 0.48f, h * 0.24f,
            w * 0.60f, h * 0.25f,
        )
    }
}
