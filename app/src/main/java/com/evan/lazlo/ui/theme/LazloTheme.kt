package com.evan.lazlo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import com.evan.lazlo.R

// ---------------------------------------------------------------------
// Parchment palette
// ---------------------------------------------------------------------
//
// This used to be a dark "security-tool" theme sampled from the launcher
// icon (see git history). It's now a warm parchment/aged-paper palette
// instead, inspired by hand-annotated map/scroll UIs — purely the
// typography/color/shape language of that look, built from scratch here
// as original tokens, art, and code. Nothing here references, names, or
// quotes any specific fictional property; see CornerFlourish/ScrollShape/
// FootstepLoader for the same rule applied to the decorative shapes.
//
// Every screen already reads MaterialTheme.colorScheme rather than
// hardcoding colors (confirmed by grepping the rest of the app for
// `Color(0x` — this file is the only hit), so this palette swap is the
// only file that needs to change for the new look to reach every screen.

private val Parchment = Color(0xFFF1E4C4) // page background — aged paper, not stark white
private val ParchmentSheet = Color(0xFFF8EFD8) // a lighter "sheet on the desk" surface, for cards to read as sitting on top of the background
private val ParchmentShade = Color(0xFFE4D1A4) // a deeper tan for secondary surfaces (chat bubbles, tab strips)
private val InkBrown = Color(0xFF3B2A18) // primary text — deep sepia ink, not black
private val InkBrownSoft = Color(0xFF5B4630) // secondary text
private val AgedEdge = Color(0xFFB79F6E) // outline — the "edge of the page" tone
private val AgedEdgeFaint = Color(0xFFD8C79B) // outlineVariant — a lighter hairline, used for ScrollCard's border

private val Burgundy = Color(0xFF7A2331) // primary — muted wine/oxblood, for interactive elements
private val BurgundyContainer = Color(0xFFE8CBA6)
private val BurgundyContainerInk = Color(0xFF4A1420)

private val AgedGold = Color(0xFFA8823A) // secondary — muted brass/gold
private val AgedGoldContainer = Color(0xFFEBD9A8)
private val AgedGoldContainerInk = Color(0xFF402D10)
// AgedGoldContainerInk was also being reused as the ink for AgedGold
// itself (plain `secondary`, not `secondaryContainer`) — fine against the
// *pale* container tone (9.4:1) but only ~3.7:1 against AgedGold's own
// medium-brass value, below the 4.5:1 AA floor for body text. A separate,
// darker ink for that specific pairing fixes it without touching the hue.
private val AgedGoldInk = Color(0xFF1E1303) // ~5.1:1 against AgedGold

private val Terracotta = Color(0xFFB5651D) // tertiary — warm rust accent
private val TerracottaContainer = Color(0xFFF0D3B0)
private val TerracottaContainerInk = Color(0xFF4A2A0C)
// Terracotta's own medium-rust value sits closer to black's end of the
// achievable-contrast range than white's, so the light cream this used to
// pair it with (~3.9:1, an AA fail) was the wrong direction entirely — a
// dark ink reads better here, same as AgedGoldInk above.
private val TerracottaInk = Color(0xFF0C0300) // ~4.7:1 against Terracotta

private val BrickRed = Color(0xFFA23B2E) // error — a warm-tinted red rather than a stark alert red
private val BrickRedContainer = Color(0xFFE8C4B8)
private val BrickRedContainerInk = Color(0xFF4A160B)

private val LightColors = lightColorScheme(
    primary = Burgundy,
    onPrimary = ParchmentSheet,
    primaryContainer = BurgundyContainer,
    onPrimaryContainer = BurgundyContainerInk,
    secondary = AgedGold,
    onSecondary = AgedGoldInk,
    secondaryContainer = AgedGoldContainer,
    onSecondaryContainer = AgedGoldContainerInk,
    tertiary = Terracotta,
    onTertiary = TerracottaInk,
    tertiaryContainer = TerracottaContainer,
    onTertiaryContainer = TerracottaContainerInk,
    error = BrickRed,
    onError = Color(0xFFFBEDE6),
    errorContainer = BrickRedContainer,
    onErrorContainer = BrickRedContainerInk,
    background = Parchment,
    onBackground = InkBrown,
    surface = ParchmentSheet,
    onSurface = InkBrown,
    surfaceVariant = ParchmentShade,
    onSurfaceVariant = InkBrownSoft,
    outline = AgedEdge,
    outlineVariant = AgedEdgeFaint,
)

// "Dark mode" for a parchment app isn't the old near-black security-tool
// scheme inverted — it's the other half of the same object: an aged
// leather-bound book cover instead of the page inside it. Same ink/gold/
// burgundy identity, same relationships between tokens, just the surfaces
// gone dark and the accents brightened enough to read against them — so
// switching OS theme mode reads as "the same app, the lights are off,"
// not "a different app."
private val Leather = Color(0xFF241A10)
private val LeatherSheet = Color(0xFF2E2115)
private val LeatherShade = Color(0xFF3C2C1B)
private val ParchmentInk = Color(0xFFEDDFC0) // primary text on dark leather — parchment-cream, not white
private val ParchmentInkSoft = Color(0xFFC9B78E)
private val LeatherEdge = Color(0xFF6B5636)
private val LeatherEdgeFaint = Color(0xFF4A3B24)

private val BurgundyLight = Color(0xFFD98A96)
private val BurgundyLightContainer = Color(0xFF5A1F29)
private val BurgundyLightContainerInk = Color(0xFFF0D6DA)

private val AgedGoldLight = Color(0xFFD4AF6A)
private val AgedGoldLightContainer = Color(0xFF4A3A1E)
private val AgedGoldLightContainerInk = Color(0xFFF0DEB0)

private val TerracottaLight = Color(0xFFD98A4A)
private val TerracottaLightContainer = Color(0xFF5A3417)
private val TerracottaLightContainerInk = Color(0xFFF5D9B8)

private val BrickRedLight = Color(0xFFD97462)
private val BrickRedLightContainer = Color(0xFF5C2A20)
private val BrickRedLightContainerInk = Color(0xFFF5D9CE)

private val DarkColors = darkColorScheme(
    primary = BurgundyLight,
    onPrimary = Color(0xFF3A1218),
    primaryContainer = BurgundyLightContainer,
    onPrimaryContainer = BurgundyLightContainerInk,
    secondary = AgedGoldLight,
    onSecondary = Color(0xFF2A1B0E),
    secondaryContainer = AgedGoldLightContainer,
    onSecondaryContainer = AgedGoldLightContainerInk,
    tertiary = TerracottaLight,
    onTertiary = Color(0xFF3A1D08),
    tertiaryContainer = TerracottaLightContainer,
    onTertiaryContainer = TerracottaLightContainerInk,
    error = BrickRedLight,
    onError = Color(0xFF3A150E),
    errorContainer = BrickRedLightContainer,
    onErrorContainer = BrickRedLightContainerInk,
    background = Leather,
    onBackground = ParchmentInk,
    surface = LeatherSheet,
    onSurface = ParchmentInk,
    surfaceVariant = LeatherShade,
    onSurfaceVariant = ParchmentInkSoft,
    outline = LeatherEdge,
    outlineVariant = LeatherEdgeFaint,
)

// ---------------------------------------------------------------------
// Parchment typography
// ---------------------------------------------------------------------

// Two decorative faces, used deliberately narrowly:
//
// - Cinzel Decorative for anything short and prominent — titles, section
//   headers, the app name, button labels, empty-state headings. These
//   are places where a few ornate glyphs read as "old book" flavor
//   without costing readability, because there's never much text there.
//
// - IM Fell English for the next tier down — secondary labels and the
//   odd larger body line — where a period-appropriate serif still reads
//   fine at moderate sizes.
//
// Deliberately *not* used anywhere in the dense/small tiers (bodyMedium,
// bodySmall, labelSmall): chat messages, JSON bodies, and the many
// paragraph-length explanation strings throughout this app need to stay
// legible over decorative, so those keep Typography()'s default
// (platform system) font family untouched.
private const val HEADING_FONT_NAME = "Cinzel Decorative"
private const val ACCENT_FONT_NAME = "IM Fell English"

/**
 * Builds the parchment type scale from Material3's default [Typography]
 * by swapping in the two Google Fonts above on specific styles only.
 *
 * Everything font-related — the [GoogleFont.Provider] pointing at the
 * standard Play Services "downloadable fonts" provider (`certificates`
 * points at the fixed, publicly-documented cert set every app using this
 * API ships; see res/values/font_certs.xml's doc comment), the
 * [GoogleFont]/[FontFamily] construction, and the [Typography.copy] that
 * uses them — is built *inside* this one `runCatching`, deliberately not
 * as top-level `val`s outside it: a top-level `val`'s initializer runs in
 * this file's static initializer the first time anything in it is
 * touched, which is *before* any try/catch inside a function gets a
 * chance to run, so if construction ever throws synchronously it would
 * take the whole class down instead of being caught. Keeping the
 * construction inside the lambda passed to `runCatching` is what actually
 * makes the fallback below real.
 *
 * The asynchronous half of "downloadable fonts can fail offline" doesn't
 * need this guard — Compose already falls back to the platform default
 * per-glyph while a font is resolving or if it never resolves. What this
 * guards against is the rarer synchronous case: some unusual build with
 * no Play services at all failing right at setup. Either way, the
 * instruction from the top of this file stands: a font problem degrades
 * to stock Material typography, it never blanks or crashes the UI.
 */
private fun buildLazloTypography(): Typography {
    val base = Typography()
    return runCatching {
        val provider = GoogleFont.Provider(
            providerAuthority = "com.google.android.gms.fonts",
            providerPackage = "com.google.android.gms",
            certificates = R.array.com_google_android_gms_fonts_certs,
        )
        val headingFamily = FontFamily(Font(googleFont = GoogleFont(HEADING_FONT_NAME), fontProvider = provider))
        val accentFamily = FontFamily(Font(googleFont = GoogleFont(ACCENT_FONT_NAME), fontProvider = provider))
        base.copy(
            displayLarge = base.displayLarge.copy(fontFamily = headingFamily),
            displayMedium = base.displayMedium.copy(fontFamily = headingFamily),
            displaySmall = base.displaySmall.copy(fontFamily = headingFamily),
            headlineLarge = base.headlineLarge.copy(fontFamily = headingFamily),
            headlineMedium = base.headlineMedium.copy(fontFamily = headingFamily),
            headlineSmall = base.headlineSmall.copy(fontFamily = headingFamily),
            titleLarge = base.titleLarge.copy(fontFamily = headingFamily),
            titleMedium = base.titleMedium.copy(fontFamily = headingFamily),
            titleSmall = base.titleSmall.copy(fontFamily = headingFamily),
            labelLarge = base.labelLarge.copy(fontFamily = headingFamily),
            bodyLarge = base.bodyLarge.copy(fontFamily = accentFamily),
            labelMedium = base.labelMedium.copy(fontFamily = accentFamily),
            // bodyMedium, bodySmall, labelSmall: left as base's defaults —
            // see the doc comment above.
        )
    }.getOrDefault(base)
}

// Built once per process rather than once per LazloTheme() recomposition
// — constructing FontFamily/Provider objects on every recomposition would
// be wasted work, and `by lazy` means the first real use is still inside
// composition (safe: any throw is already caught inside the builder).
private val LazloTypography: Typography by lazy { buildLazloTypography() }

/** Central Material3 theme wrapper so every screen shares the same parchment palette and type scale. */
@Composable
fun LazloTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (useDarkTheme) DarkColors else LightColors,
        typography = LazloTypography,
        content = content,
    )
}
