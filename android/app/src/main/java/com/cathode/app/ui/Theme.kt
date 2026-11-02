package com.cathode.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.cathode.app.R

/*
 * Cathode, broadcast edition.
 *
 * The app is a TV station made of your subscriptions: every channel you
 * follow gets a number and a color from the test-card bars, the home screen
 * is what's on air, the library is a program guide, and the stats are the
 * ratings. Glass is near-black with a green cast (an unlit tube), the OSD
 * type glows phosphor green, and everything else stays out of its way.
 */

// Tube glass. Not pure black: an unlit CRT is a deep green-gray.
val Glass = Color(0xFF070908)
val Glass1 = Color(0xFF0E1211)
val Glass2 = Color(0xFF161B19)
val Glass3 = Color(0xFF212826)
val Hairline = Color(0xFF26302D)

// Phosphor.
val Phosphor = Color(0xFF7DFFA6)
val PhosphorDim = Color(0xFF3E8F5B)
val Ink = Color(0xFFEDF2EE)
val Muted = Color(0xFF85918C)
val Faint = Color(0xFF4C5652)

// Test-card bars, left to right (SMPTE order, minus white).
val BarYellow = Color(0xFFF2D43D)
val BarCyan = Color(0xFF3FD8E0)
val BarGreen = Color(0xFF58E07A)
val BarMagenta = Color(0xFFE05CD8)
val BarRed = Color(0xFFFF5A47)
val BarBlue = Color(0xFF5B7CFF)
val Bars = listOf(BarYellow, BarCyan, BarGreen, BarMagenta, BarRed, BarBlue)

/** REC / live / errors. */
val Signal = BarRed

// Kept for the few places that still say "the accent".
val Accent = Phosphor
val Surface1 = Glass1
val OledBlack = Glass

private val archivo = R.font.archivo

@OptIn(ExperimentalTextApi::class)
private fun archivo(weight: Int, width: Float) = Font(
    archivo,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.width(width),
    ),
)

/** Wide and heavy: the station's voice (titles, the wordmark, big numbers). */
val Display = FontFamily(archivo(800, 125f), archivo(900, 125f))

/** Body text: Archivo at normal width. */
val Body = FontFamily(
    archivo(400, 100f), archivo(500, 100f), archivo(600, 100f), archivo(700, 100f),
)

/** Slightly condensed, for dense guide blocks. */
val Narrow = FontFamily(archivo(500, 85f), archivo(700, 85f))

/** The TV's on-screen display: channel numbers, clocks, counters, status. */
val Osd = FontFamily(Font(R.font.vt323))

private val CathodeType = Typography(
    displayLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight(900), fontSize = 56.sp, letterSpacing = (-0.02).em),
    displayMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight(900), fontSize = 40.sp, letterSpacing = (-0.02).em),
    displaySmall = TextStyle(fontFamily = Display, fontWeight = FontWeight(800), fontSize = 32.sp),
    headlineLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight(900), fontSize = 30.sp, letterSpacing = (-0.01).em),
    headlineMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight(800), fontSize = 26.sp),
    headlineSmall = TextStyle(fontFamily = Display, fontWeight = FontWeight(800), fontSize = 22.sp),
    titleLarge = TextStyle(fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Body, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Body, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Body, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 11.sp),
)

private val Scheme = darkColorScheme(
    background = Glass,
    surface = Glass,
    surfaceVariant = Glass1,
    surfaceContainerLowest = Glass,
    surfaceContainerLow = Glass1,
    surfaceContainer = Glass1,
    surfaceContainerHigh = Glass2,
    surfaceContainerHighest = Glass3,
    primary = Phosphor,
    onPrimary = Color(0xFF032611),
    primaryContainer = Color(0xFF123A22),
    onPrimaryContainer = Phosphor,
    secondary = Phosphor,
    onSecondary = Color(0xFF032611),
    secondaryContainer = Color(0xFF123A22),
    onSecondaryContainer = Phosphor,
    tertiary = BarYellow,
    error = Signal,
    onBackground = Ink,
    onSurface = Ink,
    onSurfaceVariant = Muted,
    outline = Faint,
    outlineVariant = Hairline,
)

@Composable
fun CathodeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = CathodeType, content = content)
}
