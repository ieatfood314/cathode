package com.cathode.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// OLED black (pixels off), one accent, gray for everything secondary.
val OledBlack = Color(0xFF000000)
val Surface1 = Color(0xFF111114)
val Muted = Color(0xFF8E8E96)
val Accent = Color(0xFF8B7CFF)

private val Scheme = darkColorScheme(
    background = OledBlack,
    surface = OledBlack,
    surfaceVariant = Surface1,
    surfaceContainer = Surface1,
    surfaceContainerHigh = Color(0xFF1A1A1F),
    primary = Accent,
    secondary = Accent,
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = Muted,
    outlineVariant = Color(0xFF222228),
)

@Composable
fun CathodeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
