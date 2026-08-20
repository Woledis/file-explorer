package com.filebridge.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Blue600,
    onPrimary = Color.White,
    secondary = Teal600,
    onSecondary = Color.White,
    background = BackgroundLight,
    onBackground = Ink,
    surface = SurfaceLight,
    onSurface = Ink,
    outlineVariant = BorderLight,
    error = DangerRed,
)

private val DarkColors = darkColorScheme(
    primary = Blue500,
    onPrimary = Color.White,
    secondary = Teal600,
    onSecondary = Color.White,
    background = Color(0xFF0E1524),
    onBackground = Color(0xFFE7EDF7),
    surface = Color(0xFF151E30),
    onSurface = Color(0xFFE7EDF7),
    outlineVariant = Color(0xFF263349),
    error = Color(0xFFF87171),
)

@Composable
fun FileBridgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}