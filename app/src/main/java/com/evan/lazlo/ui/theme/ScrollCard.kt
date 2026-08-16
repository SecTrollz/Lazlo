package com.evan.lazlo.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Drop-in replacement for the `Card(colors = CardDefaults.cardColors(...))`
 * wrapper every dialog and major card in this app was built with —
 * [ScrollShape] instead of a plain rounded rect, a hairline aged-edge
 * border, and one restrained [CornerFlourish]. Centralizing this here
 * means one call-site change (`Card(...) { ... }` → `ScrollCard { ... }`)
 * at each of the roughly dozen places across Browser/Chat/Inspector that
 * build a `Dialog(...) { Card(...) }` pair, instead of hand-rolling the
 * shape/border/flourish boilerplate at each one — and it means a future
 * change to "what a major container looks like" only happens here.
 *
 * [content] intentionally isn't `ColumnScope`-scoped the way [Card]'s own
 * `content` parameter is: every existing call site already opens with its
 * own `Column(modifier = Modifier.padding(...)) { ... }` as the sole
 * top-level child, so nothing here needs Card's implicit column — keeping
 * this parameter receiver-free is what makes swapping `Card` for
 * `ScrollCard` at each call site a pure rename with no reshuffling.
 */
@Composable
fun ScrollCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    showFlourish: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = LazloShapes.scroll
    val colors = CardDefaults.cardColors(containerColor = containerColor)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    val elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    val flourish = @Composable {
        Box {
            content()
            if (showFlourish) {
                CornerFlourish(
                    corner = FlourishCorner.TopEnd,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 10.dp, end = 12.dp),
                )
            }
        }
    }
    // [onClick] is opt-in and null by default so every existing call site
    // (the large majority — static dialogs/panels) is unaffected; the few
    // screens that need a tappable "major container" row (e.g. a settings
    // row that opens a dialog) get the same rolled-parchment shape/border/
    // flourish instead of falling back to a plain default-shaped Card.
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier, shape = shape, colors = colors, border = border, elevation = elevation) {
            flourish()
        }
    } else {
        Card(modifier = modifier, shape = shape, colors = colors, border = border, elevation = elevation) {
            flourish()
        }
    }
}
