package com.cathode.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.style.TextAlign
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

// ---------------------------------------------------------------- type

/** Phosphor text. Lit text ([glow]) bleeds a little light around it. */
@Composable
fun Osd(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = P100,
    size: TextUnit = 20.sp,
    glow: Boolean = true,
    maxLines: Int = 1,
    textAlign: TextAlign? = null,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    Text(
        text,
        modifier = modifier,
        maxLines = maxLines,
        overflow = overflow,
        textAlign = textAlign,
        style = TextStyle(
            fontFamily = Vt,
            fontSize = size,
            lineHeight = size * 1.05f,
            color = color,
            shadow = if (glow) Shadow(color.copy(alpha = 0.7f), Offset.Zero, blurRadius = 16f) else null,
            // VT323 carries tall ascent padding; centre the glyphs so
            // reverse-video blocks sit evenly around their text.
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
        ),
    )
}

/** Reverse video: a lit block with dark text. The only way anything stands out. */
@Composable
fun Reverse(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 20.sp,
    blink: Boolean = false,
    on: Boolean = true,
) {
    val lit = if (blink) rememberBlink(650) else true
    Box(
        modifier
            .alpha(if (lit) 1f else 0.35f)
            .background(if (on) P100 else Color.Transparent)
            .padding(horizontal = 6.dp),
    ) {
        Osd(text, color = if (on) OnPhosphor else P60, size = size, glow = false)
    }
}

/** A blinking block cursor. */
@Composable
fun Cursor(height: Dp = 30.dp, width: Dp = 14.dp) {
    val lit = rememberBlink(530)
    Box(Modifier.width(width).height(height).alpha(if (lit) 1f else 0f).background(P100))
}

/** On/off every [periodMs]: two state changes a second, not an animation per frame. */
@Composable
fun rememberBlink(periodMs: Long): Boolean {
    var lit by remember { mutableStateOf(true) }
    LaunchedEffect(periodMs) {
        while (true) {
            kotlinx.coroutines.delay(periodMs)
            lit = !lit
        }
    }
    return lit
}

/** Screen header: dim kicker over a big lit title and a cursor. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier, kicker: String? = null) {
    Column(modifier) {
        if (kicker != null) Osd(kicker.uppercase(), size = 20.sp, color = P60, glow = false)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Osd(text.uppercase(), size = 52.sp)
            Spacer(Modifier.width(6.dp))
            Cursor(height = 34.dp, width = 16.dp)
        }
    }
}

/** "GUIDE ────────────": a heading that rules off the rest of the line. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = P100) {
    Row(
        modifier.padding(top = 26.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Osd(text.uppercase(), color = color, size = 22.sp, glow = color == P100)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(P20))
    }
}

/** A terminal frame. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Tube1)
            .border(1.dp, P20, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        content = content,
    )
}

/** Dim secondary text. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier, color: Color = P60) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = modifier)
}

@Composable
fun Gap(h: Int = 8) = Spacer(Modifier.height(h.dp))

/** [ ON ] / [OFF] etc: a toggle drawn as a lit or unlit block. */
@Composable
fun BlockToggle(on: Boolean, modifier: Modifier = Modifier, onLabel: String = "ON", offLabel: String = "OFF") {
    Box(
        modifier
            .border(1.dp, if (on) P100 else P40, RoundedCornerShape(2.dp))
            .background(if (on) P100 else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Osd(if (on) onLabel else offLabel, color = if (on) OnPhosphor else P60, size = 20.sp, glow = false)
    }
}

/** A tab or chip that lights up (reverse video) when selected. */
@Composable
fun BlockTab(label: String, selected: Boolean, modifier: Modifier = Modifier, size: TextUnit = 20.sp, onClick: () -> Unit) {
    Box(
        modifier
            .clickable(onClick = onClick)
            .background(if (selected) P100 else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Osd(label.uppercase(), color = if (selected) OnPhosphor else P60, size = size, glow = false)
    }
}

/** An LED bar: [cells] segments, the first [fraction] of them lit. */
@Composable
fun Segments(fraction: Float, modifier: Modifier = Modifier, cells: Int = 24, lit: Color = P100, height: Dp = 10.dp) {
    val n = (fraction.coerceIn(0f, 1f) * cells).let { if (fraction > 0f) it.coerceAtLeast(1f) else it }.toInt()
    Row(modifier.height(height), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(cells) { i ->
            Box(Modifier.weight(1f).fillMaxHeight().background(if (i < n) lit else P10))
        }
    }
}

// ---------------------------------------------------------------- the tube

/** Horizontal scanlines over whatever's drawn. */
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

/** Dark corners plus a soft glare on the glass. */
fun Modifier.tubeGlass(): Modifier = drawWithContent {
    drawContent()
    drawRect(
        Brush.radialGradient(
            0.55f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.65f),
            center = center,
            radius = size.maxDimension * 0.62f,
        )
    )
    drawRect(
        Brush.linearGradient(
            0f to P100.copy(alpha = 0.06f),
            0.35f to Color.Transparent,
            start = Offset(0f, 0f),
            end = Offset(size.width * 0.6f, size.height),
        )
    )
}

/** Where the video is drawn, in window coordinates: the screen overlay leaves it alone. */
object VideoHole {
    var rect by mutableStateOf<Rect?>(null)
}

/** Report this node's bounds as the video hole while composed. */
fun Modifier.videoHole(): Modifier = onGloballyPositioned { VideoHole.rect = it.boundsInWindow() }

/**
 * The whole app behind one piece of glass: scanlines and dark corners over
 * every screen. The video's own rectangle is left clean.
 */
@Composable
fun CrtScreen(enabled: Boolean, content: @Composable () -> Unit) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    // One tiled 1×N bitmap (a dark line, then clear) instead of hundreds of
    // rects: the whole overlay is two draw calls, and it only redraws when
    // the screen under it does.
    val lines = remember(density) {
        val pitch = with(density) { 3.dp.roundToPx() }.coerceAtLeast(2)
        val dark = (pitch / 2.8f).toInt().coerceAtLeast(1)
        val px = IntArray(pitch) { if (it < dark) 0x52000000 else 0 }
        val bmp = android.graphics.Bitmap.createBitmap(px, 1, pitch, android.graphics.Bitmap.Config.ARGB_8888)
        ShaderBrush(ImageShader(bmp.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
    }
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInWindow() }
            .drawWithContent {
                drawContent()
                if (!enabled) return@drawWithContent
                val hole = VideoHole.rect?.translate(-origin)
                fun overlay() {
                    drawRect(lines)
                    drawRect(
                        Brush.radialGradient(
                            0.6f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.55f),
                            center = center,
                            radius = size.maxDimension * 0.62f,
                        )
                    )
                }
                if (hole != null) {
                    clipRect(hole.left, hole.top, hole.right, hole.bottom, ClipOp.Difference) { overlay() }
                } else overlay()
            }
    ) { content() }
}

/** A low-res frame of phosphor snow; drawn scaled up with chunky pixels. */
private fun noiseFrame(w: Int, h: Int, seed: Int): ImageBitmap {
    val r = Random(seed)
    val px = IntArray(w * h) {
        val g = r.nextFloat()
        if (g < 0.45f) 0xFF030604.toInt()
        else {
            val a = ((g - 0.45f) / 0.55f)
            val gr = (0x9D * a).toInt(); val gg = (0xFF * a).toInt(); val gb = (0xB4 * a).toInt()
            (0xFF shl 24) or (gr shl 16) or (gg shl 8) or gb
        }
    }
    return android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * Live analog snow. Four pre-rendered frames cycled every frame: one
 * bitmap draw, however big the screen.
 */
@Composable
fun Static(modifier: Modifier = Modifier) {
    val frames = remember { List(4) { noiseFrame(140, 120, it) } }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameMillis { frame = it } }
    Canvas(modifier) {
        val img = frames[((frame / 16) % frames.size).toInt()]
        drawImage(
            img,
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            filterQuality = FilterQuality.None,
        )
        val barY = (frame / 3 % size.height.toLong().coerceAtLeast(1)).toFloat()
        drawRect(P100.copy(alpha = 0.1f), topLeft = Offset(0f, barY), size = Size(size.width, size.height / 6))
    }
}

/**
 * The monoscope: the test pattern a station puts up when there's nothing
 * to show. Grid, circles, a centre cross, all in phosphor.
 */
@Composable
fun Monoscope(label: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(30.dp)
    Box(
        modifier.clip(shape).background(Tube1).border(2.dp, P20, shape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().tubeGlass()) {
            val cell = size.width / 12f
            var x = cell
            while (x < size.width) { drawLine(P20, Offset(x, 0f), Offset(x, size.height), 1.5f); x += cell }
            var y = (size.height % cell) / 2f
            while (y < size.height) { drawLine(P20, Offset(0f, y), Offset(size.width, y), 1.5f); y += cell }
            val r = size.minDimension * 0.42f
            drawCircle(P40, r, center, style = Stroke(3f))
            drawCircle(P40, r * 0.62f, center, style = Stroke(2f))
            drawLine(P40, Offset(center.x - r, center.y), Offset(center.x + r, center.y), 2f)
            drawLine(P40, Offset(center.x, center.y - r), Offset(center.x, center.y + r), 2f)
            // Resolution wedges.
            for (i in 0 until 9) {
                val w = 2f + i * 1.6f
                val xx = center.x - r * 0.9f + i * (r * 0.2f)
                drawLine(P60, Offset(xx, center.y + r * 0.7f), Offset(xx, center.y + r * 0.82f), w * 0.6f)
            }
        }
        Box(Modifier.fillMaxSize().scanlines(0.3f))
        Reverse(label.uppercase(), size = 40.sp)
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
                    scaleY = (o * o).coerceAtLeast(0.004f)
                    scaleX = (0.6f + o * 0.4f).coerceAtMost(1f)
                    alpha = (o * 3f).coerceAtMost(1f)
                }
        ) {
            content()
            if (flash.value > 0f) {
                Box(Modifier.fillMaxSize().background(P100.copy(alpha = flash.value * 0.5f)))
            }
        }
    }
}
