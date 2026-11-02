package com.cathode.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cathode.app.data.Video
import kotlin.random.Random

// ---------------------------------------------------------------- channels

/**
 * The station's lineup: every channel in the library gets a number, in
 * alphabetical order, so CH 04 is the same channel on every screen.
 */
class Lineup(names: Collection<String>) {
    private val sorted = names.filter { it.isNotBlank() }.distinct()
        .sortedBy { it.lowercase() }
    private val index = sorted.withIndex().associate { (i, n) -> n to i + 1 }
    val size get() = sorted.size
    fun number(name: String?): Int? = name?.let { index[it] }
    fun label(name: String?): String = number(name)?.let { "%02d".format(it) } ?: "--"

    companion object {
        fun of(videos: List<Video>) = Lineup(videos.mapNotNull { it.channelTitle })
    }
}

/** A channel's color from the test-card bars; stable per name. */
fun channelColor(name: String?): Color {
    if (name.isNullOrBlank()) return Muted
    val h = name.fold(7) { acc, c -> acc * 31 + c.code }
    return Bars[Math.floorMod(h, Bars.size)]
}

// ---------------------------------------------------------------- type

/** Glowing on-screen-display text (the TV's own font). */
@Composable
fun Osd(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Phosphor,
    size: TextUnit = 18.sp,
    glow: Boolean = true,
    maxLines: Int = 1,
    textAlign: androidx.compose.ui.text.style.TextAlign? = null,
) {
    Text(
        text,
        modifier = modifier,
        maxLines = maxLines,
        textAlign = textAlign,
        overflow = TextOverflow.Clip,
        style = TextStyle(
            fontFamily = Osd,
            fontSize = size,
            lineHeight = size,
            color = color,
            shadow = if (glow) Shadow(color.copy(alpha = 0.75f), Offset.Zero, blurRadius = 14f) else null,
        ),
    )
}

/** Screen header: OSD kicker over a wide, heavy title. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier, kicker: String? = null) {
    Column(modifier) {
        if (kicker != null) Osd(kicker.uppercase(), size = 17.sp, color = PhosphorDim, glow = false)
        Text(text, style = MaterialTheme.typography.headlineLarge, color = Ink)
    }
}

/** OSD caps heading above a group, with a short color tick. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = Phosphor) {
    Row(
        modifier.padding(top = 28.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 10.dp, height = 3.dp).background(color))
        Spacer(Modifier.width(8.dp))
        Osd(text.uppercase(), color = color, size = 19.sp, glow = false)
    }
}

/** Glass panel with a hairline frame. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Glass1)
            .border(1.dp, Hairline, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        content = content,
    )
}

/** One line of gray secondary text. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier, color: Color = Muted) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = modifier)
}

@Composable
fun Gap(h: Int = 8) = Spacer(Modifier.height(h.dp))

// ---------------------------------------------------------------- the tube

/** The test-card stripe. */
@Composable
fun ColorBars(modifier: Modifier = Modifier, height: Dp = 4.dp) {
    Row(modifier.fillMaxWidth().height(height)) {
        for (c in listOf(Color(0xFFC9CFCB)) + Bars) {
            Box(Modifier.weight(1f).fillMaxSize().background(c))
        }
    }
}

/** Horizontal scanlines over whatever's drawn (images, the hero tube). */
fun Modifier.scanlines(alpha: Float = 0.22f, pitch: Dp = 3.dp): Modifier = drawWithContent {
    drawContent()
    val step = pitch.toPx()
    val line = (step / 2.6f).coerceAtLeast(1f)
    var y = 0f
    val c = Color.Black.copy(alpha = alpha)
    while (y < size.height) {
        drawRect(c, topLeft = Offset(0f, y), size = Size(size.width, line))
        y += step
    }
}

/** The tube's dark corners plus a soft glare on the glass. */
fun Modifier.tubeGlass(): Modifier = drawWithContent {
    drawContent()
    drawRect(
        Brush.radialGradient(
            0.55f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.6f),
            center = center,
            radius = size.maxDimension * 0.62f,
        )
    )
    drawRect(
        Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.07f),
            0.35f to Color.Transparent,
            start = Offset(0f, 0f),
            end = Offset(size.width * 0.6f, size.height),
        )
    )
}

/** Live analog snow. Drawn while composed; cheap enough for a flash. */
@Composable
fun Static(modifier: Modifier = Modifier, density: Float = 1f) {
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameMillis { frame = it } }
    Canvas(modifier) {
        val r = Random(frame)
        val cell = 3.dp.toPx()
        val cols = (size.width / cell).toInt() + 1
        val rows = (size.height / cell).toInt() + 1
        drawRect(Color(0xFF101312))
        val n = (cols * rows * 0.35f * density).toInt()
        repeat(n) {
            val g = r.nextFloat()
            drawRect(
                Color(g, g, g, 0.9f),
                topLeft = Offset(r.nextInt(cols) * cell, r.nextInt(rows) * cell),
                size = Size(cell, cell),
            )
        }
        // A rolling bar, like a badly tuned set.
        val barY = (frame / 4 % size.height.toLong().coerceAtLeast(1)).toFloat()
        drawRect(Color.White.copy(alpha = 0.06f), topLeft = Offset(0f, barY), size = Size(size.width, size.height / 6))
    }
}

/**
 * The CRT power-on: a bright line that opens into the picture. Runs once
 * per [key] (the app's first frame).
 */
@Composable
fun PowerOn(key: Any = Unit, content: @Composable () -> Unit) {
    val open = remember { Animatable(0f) }
    val flash = remember { Animatable(1f) }
    LaunchedEffect(key) {
        open.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
        flash.animateTo(0f, tween(260, easing = LinearEasing))
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val o = open.value
                    // Line first (thin, full width), then the height opens.
                    scaleY = (o * o).coerceAtLeast(0.004f)
                    scaleX = (0.6f + o * 0.4f).coerceAtMost(1f)
                    alpha = (o * 3f).coerceAtMost(1f)
                }
        ) {
            content()
            if (flash.value > 0f) {
                Box(Modifier.fillMaxSize().background(Color(0xFFDFFFEA).copy(alpha = flash.value * 0.55f)))
            }
        }
    }
}
