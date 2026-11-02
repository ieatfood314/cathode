package com.cathode.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.cathode.app.R

/*
 * Cathode, phosphor edition.
 *
 * The whole app is one monochrome tube: a single green P1 phosphor at a
 * few brightness levels on black glass, one terminal typeface, scanlines
 * over everything. Nothing else gets a color. Emphasis is reverse video
 * (a lit block with dark text), never a second hue. The only full-color
 * thing on screen is the picture itself: the video and the big tube on
 * On air.
 */

// The glass.
val Tube = Color(0xFF030604)
/** Panel fill: the faint glow of unlit phosphor. */
val Tube1 = Color(0xFF07110A)
val Tube2 = Color(0xFF0B1A0F)

// One phosphor, six levels.
val P100 = Color(0xFF9DFFB4) // headings, selected, anything lit
val P80 = Color(0xFF66E585) // body text
val P60 = Color(0xFF45AD60) // secondary
val P40 = Color(0xFF2C7340) // tertiary, strong rules
val P20 = Color(0xFF184226) // rules, tracks, borders
val P10 = Color(0xFF0E2716) // the dimmest visible trace

/** Text on a lit block. */
val OnPhosphor = Color(0xFF021006)

/** One terminal face for everything. */
val Vt = FontFamily(Font(R.font.vt323))

/**
 * Thumbnails in lists are drawn by the tube: luminance only, in phosphor
 * green. The playing picture and the big tube keep their color.
 */
val PhosphorTint: ColorFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            0.10f, 0.20f, 0.04f, 0f, 0f,
            0.45f, 0.95f, 0.18f, 0f, 0f,
            0.20f, 0.42f, 0.08f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
)

// VT323 runs small (low x-height), so the scale is larger than usual.
private fun vt(size: Int, line: Int = (size * 1.08f).toInt()) =
    TextStyle(fontFamily = Vt, fontSize = size.sp, lineHeight = line.sp)

private val CathodeType = Typography(
    displayLarge = vt(96, 90),
    displayMedium = vt(64, 62),
    displaySmall = vt(48, 48),
    headlineLarge = vt(46, 46),
    headlineMedium = vt(36),
    headlineSmall = vt(30),
    titleLarge = vt(28, 28),
    titleMedium = vt(24, 25),
    titleSmall = vt(22, 23),
    bodyLarge = vt(22, 24),
    bodyMedium = vt(20, 22),
    bodySmall = vt(18, 19),
    labelLarge = vt(21),
    labelMedium = vt(19),
    labelSmall = vt(17),
)

private val Scheme = darkColorScheme(
    background = Tube,
    surface = Tube,
    surfaceVariant = Tube1,
    surfaceContainerLowest = Tube,
    surfaceContainerLow = Tube1,
    surfaceContainer = Tube1,
    surfaceContainerHigh = Tube2,
    surfaceContainerHighest = Tube2,
    surfaceTint = Tube,
    primary = P100,
    onPrimary = OnPhosphor,
    primaryContainer = P20,
    onPrimaryContainer = P100,
    secondary = P100,
    onSecondary = OnPhosphor,
    secondaryContainer = P20,
    onSecondaryContainer = P100,
    tertiary = P100,
    onTertiary = OnPhosphor,
    error = P100,
    onError = OnPhosphor,
    onBackground = P80,
    onSurface = P80,
    onSurfaceVariant = P60,
    outline = P40,
    outlineVariant = P20,
    inverseSurface = P100,
    inverseOnSurface = OnPhosphor,
    scrim = Color.Black,
)

@Composable
fun CathodeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = CathodeType, content = content)
}
