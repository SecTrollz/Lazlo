package com.evan.lazlo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Sampled from the Lazlo emblem (see res/values/colors.xml for the same
// values used by the launcher icon / splash background), kept here too
// since Compose theming doesn't read XML color resources by default.
private val LazloInk = Color(0xFF0B1B3A)
private val LazloRed = Color(0xFFE23B3B)
private val LazloOrange = Color(0xFFF2802E)
private val LazloBlue = Color(0xFF1D63D8)
private val LazloSky = Color(0xFF3FA4E0)

private val LightColors = lightColorScheme(
    primary = LazloBlue,
    onPrimary = Color.White,
    secondary = LazloSky,
    onSecondary = Color.White,
    tertiary = LazloOrange,
    error = LazloRed,
    background = Color(0xFFF7F8FC),
    surface = Color.White,
    onBackground = LazloInk,
    onSurface = LazloInk,
)

private val DarkColors = darkColorScheme(
    primary = LazloSky,
    onPrimary = LazloInk,
    secondary = LazloBlue,
    onSecondary = Color.White,
    tertiary = LazloOrange,
    error = Color(0xFFFF8A80),
    background = Color(0xFF0B1220),
    surface = Color(0xFF121A2B),
    onBackground = Color(0xFFE7EAF2),
    onSurface = Color(0xFFE7EAF2),
)

/** Central Material3 theme wrapper so every screen shares the same brand palette. */
@Composable
fun LazloTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (useDarkTheme) DarkColors else LightColors,
        content = content,
    )
}
