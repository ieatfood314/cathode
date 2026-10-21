package com.cathode.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// OLED black foundation: true #000 backgrounds (pixels off), glass cards float above.
val OledBlack = Color(0xFF000000)
val OledSurface = Color(0xFF0A0A0F)
val GlassWhite10 = Color(0x1AFFFFFF)
val GlassWhite16 = Color(0x29FFFFFF)
val AccentViolet = Color(0xFF8B7CFF)
val AccentTeal = Color(0xFF5EEAD4)

private val Scheme = darkColorScheme(
    background = OledBlack,
    surface = OledSurface,
    surfaceVariant = Color(0xFF14141B),
    primary = AccentViolet,
    secondary = AccentTeal,
    onBackground = Color.White,
    onSurface = Color.White,
)

@Composable
fun CathodeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
