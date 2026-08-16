package com.evan.lazlo.ui.theme

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * A rounded-rectangle silhouette with a gentle, irregular ripple along
 * the top and bottom edges — meant to read as a loosely rolled sheet of
 * parchment rather than a plain Material rounded rect, without drawing a
 * literal scroll-with-dowels illustration (that reads as a costume prop,
 * not app chrome, and risks visual overlap with a copyrighted map prop
 * this reskin is deliberately *not* copying — see LazloTheme.kt's doc
 * comment).
 *
 * The wave is inset-only: both `top` and `bottom` dips move *inward*
 * (down from the top edge, up from the bottom edge), so the silhouette
 * is always a subset of the box Compose asked for — it can never overflow
 * into a neighboring composable the way an outward bulge could. [amplitude]
 * is also capped well below typical content padding (every dialog in this
 * app pads its content 12–20dp; the default amplitude here is a third of
 * the smallest of those), so [Card]/[Surface] clipping their content to
 * this shape trims only a couple of dp of background near the edge — the
 * "torn paper" look — never the text itself. That split (decorative outer
 * silhouette, plain readable rectangle for the actual content) is also
 * why this is a [Shape] you hand to `Card(shape = ...)` rather than
 * something that reaches in and reshapes the content column too.
 */
class ScrollShape(
    private val cornerRadius: Dp = 16.dp,
    private val amplitude: Dp = 5.dp,
    private val humpsPerEdge: Int = 3,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val corner = with(density) { cornerRadius.toPx() }.coerceAtMost(size.minDimension / 2f)
        // Never lets the ripple eat more than ~1/6th of the box's own
        // height — on a very short container (e.g. a compact chip) that
        // matters more than the fixed dp default above.
        val amp = with(density) { amplitude.toPx() }.coerceAtMost(size.height / 6f)

        val path = Path().apply {
            moveTo(0f, corner)
            quadraticBezierTo(0f, 0f, corner, 0f)
            wavyEdge(fromX = corner, toX = size.width - corner, baselineY = 0f, amplitude = amp, humps = humpsPerEdge, inward = 1f)
            quadraticBezierTo(size.width, 0f, size.width, corner)

            lineTo(size.width, size.height - corner)
            quadraticBezierTo(size.width, size.height, size.width - corner, size.height)
            wavyEdge(fromX = size.width - corner, toX = corner, baselineY = size.height, amplitude = amp, humps = humpsPerEdge, inward = -1f)
            quadraticBezierTo(0f, size.height, 0f, size.height - corner)

            close()
        }
        return Outline.Generic(path)
    }
}

/**
 * Traces a straight edge from ([fromX], [baselineY]) to ([toX], [baselineY])
 * as a series of shallow quadratic-bezier scallops instead of a straight
 * [Path.lineTo] — the "uneven edge" half of [ScrollShape]. Direction-agnostic
 * ([fromX] may be greater or less than [toX], which is what lets the same
 * helper trace the bottom edge right-to-left after tracing the top edge
 * left-to-right), and every dip moves by `inward * amplitude` off the
 * baseline so the caller controls which side of the edge is "into the
 * shape" with a single sign flip rather than a second code path.
 */
private fun Path.wavyEdge(fromX: Float, toX: Float, baselineY: Float, amplitude: Float, humps: Int, inward: Float) {
    if (humps <= 0 || amplitude <= 0f) {
        lineTo(toX, baselineY)
        return
    }
    val segments = humps * 2
    val dx = (toX - fromX) / segments
    var x = fromX
    for (i in 0 until segments) {
        val nextX = x + dx
        val midX = x + dx / 2f
        // Alternates a dip in Toward the shape's interior with a return
        // to baseline, which is what makes consecutive segments read as
        // scallops rather than one lopsided bulge.
        val dip = if (i % 2 == 0) amplitude else 0f
        quadraticBezierTo(midX, baselineY + inward * dip, nextX, baselineY)
        x = nextX
    }
}

/** Shared shape instances for the parchment reskin — one [ScrollShape] instance, reused everywhere it's needed instead of re-allocated per composable. */
object LazloShapes {
    /** The "rolled parchment" silhouette used for dialogs, the pill overlay's settings pane, and other major cards — see [ScrollShape]'s doc comment for why it's safe to clip content to. */
    val scroll: Shape = ScrollShape()
}
