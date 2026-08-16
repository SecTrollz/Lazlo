package com.evan.lazlo.ui.theme

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The parchment reskin's stand-in for a spinner on genuinely indefinite
 * waits: a short trail of footprint marks that fade in one after another
 * — left, right, left, right — and loop, instead of a circular
 * [androidx.compose.material3.CircularProgressIndicator]. Used as
 * [com.evan.lazlo.ui.chat.ChatScreen]'s `TypingIndicator` for the chat
 * "Thinking…" state.
 *
 * Every footprint is drawn as vector [Path] shapes at draw time (see
 * [footprintPath]) — not an emoji glyph (renders differently per device
 * font/emoji set) and not a bitmap asset (an external dependency this
 * reskin is deliberately avoiding everywhere — see [CornerFlourish] and
 * [ScrollShape]'s doc comments for the same reasoning).
 *
 * Deliberately *not* used for the download-progress bars elsewhere in the
 * app (Browser's GeckoView install, AICore setup, the local-model
 * download) — those already know real byte counts and show them with
 * [androidx.compose.material3.LinearProgressIndicator], which communicates
 * "37 of 120 MB" in a way a looping indefinite animation can't. This is
 * only for waits with no known duration or size.
 */
@Composable
fun FootstepLoader(
    modifier: Modifier = Modifier,
    footprintCount: Int = 4,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    footprintSize: Dp = 8.dp,
) {
    val transition = rememberInfiniteTransition(label = "footstep-loader")

    // Each footprint's own timeline is offset by one stepDurationMs from
    // the last, so they light up in sequence rather than all at once —
    // that stagger is what reads as "walking" instead of "blinking."
    // riseMs/visibleMs/fallMs are one shared cadence every footprint
    // reuses; totalDurationMs is sized with enough slack after the last
    // footprint's fall that the loop never has to truncate an in-flight
    // fade back to 0.
    val stepDurationMs = 260
    val riseMs = 90
    val visibleMs = 260
    val fallMs = 90
    val totalDurationMs = footprintCount * stepDurationMs + riseMs + visibleMs + fallMs

    Row(
        modifier = modifier.height(footprintSize * 1.3f + 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(footprintCount) { index ->
            val startMs = index * stepDurationMs
            val alpha by transition.animateFloat(
                initialValue = 0f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = totalDurationMs
                        0f at startMs
                        1f at (startMs + riseMs)
                        1f at (startMs + riseMs + visibleMs)
                        0f at (startMs + riseMs + visibleMs + fallMs)
                    },
                ),
                label = "footprint-$index",
            )
            // Alternating left/right silhouettes, offset up/down slightly,
            // is what makes the trail read as footsteps rather than a
            // row of identical marks — done by mirroring/nudging the same
            // drawn path per index instead of authoring two separate
            // shapes.
            val isLeftFoot = index % 2 == 0
            Canvas(
                modifier = Modifier
                    .padding(horizontal = 1.dp)
                    .size(width = footprintSize, height = footprintSize * 1.3f)
                    .graphicsLayer {
                        this.alpha = alpha
                        scaleX = if (isLeftFoot) 1f else -1f
                        translationY = if (isLeftFoot) 0f else footprintSize.toPx() * 0.35f
                    },
            ) {
                drawPath(path = footprintPath(size), color = color)
            }
        }
    }
}

/**
 * One small footprint silhouette — a heel/ball oval plus three toe
 * circles fanned across the top — normalized to fractions of [size] so
 * it scales cleanly at whatever [FootstepLoader.footprintSize] is passed.
 * Left/right variants come from mirroring this single path (see
 * [FootstepLoader]'s `graphicsLayer`), not from two separate paths.
 */
private fun footprintPath(size: Size): Path {
    val w = size.width
    val h = size.height
    return Path().apply {
        addOval(Rect(offset = Offset(w * 0.15f, h * 0.42f), size = Size(w * 0.7f, h * 0.55f)))
        val toeRadius = w * 0.15f
        val toeCenters = listOf(0.26f to 0.24f, 0.5f to 0.16f, 0.74f to 0.24f)
        toeCenters.forEach { (fx, fy) ->
            addOval(Rect(center = Offset(w * fx, h * fy), radius = toeRadius))
        }
    }
}
