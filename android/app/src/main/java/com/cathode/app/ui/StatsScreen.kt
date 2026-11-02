package com.cathode.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cathode.app.data.WatchTime
import com.cathode.app.data.syncFailureMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** "1h 24m", "24m"; under a minute is "0m". */
fun formatWatch(seconds: Double): String {
    val m = (seconds / 60).toLong()
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}

private fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> day.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
}

/**
 * Time spent watching: today up top, then the week, a two-week chart and
 * the channels it went to. Refreshes from the server when opened; offline
 * it shows the last numbers plus whatever was watched since.
 */
@Composable
fun StatsScreen(canRefresh: Boolean, notice: String?, lineup: Lineup = Lineup(emptyList())) {
    val context = LocalContext.current.applicationContext
    val stats by WatchTime.stats.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(canRefresh) {
        withContext(Dispatchers.IO) { WatchTime.load(context) }
        if (!canRefresh) return@LaunchedEffect
        val r = withContext(Dispatchers.IO) { WatchTime.refresh(context) }
        error = r.exceptionOrNull()?.let { syncFailureMessage(it) }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Glass)
            .verticalScroll(rememberScrollState())
    ) {
        ColorBars(height = 3.dp)
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 18.dp)) {
            ScreenTitle("Ratings", kicker = "Your viewing")
            (notice ?: error)?.let { Hint(it, Modifier.padding(top = 4.dp), color = BarYellow) }
            Gap(20)
            Counter(stats.today)
            Gap(16)
            Panel {
                Row(Modifier.fillMaxWidth()) {
                    StatCell("7 days", stats.week, Modifier.weight(1f))
                    StatCell("Daily avg", stats.dailyAvg, Modifier.weight(1f))
                    StatCell("All time", stats.allTime, Modifier.weight(1f))
                }
            }
            if (stats.days.isNotEmpty()) {
                SectionLabel("Last 14 days")
                Panel { DayChart(stats.days) }
            }
            if (stats.channels.isNotEmpty()) {
                SectionLabel("Share · 30 days")
                Panel { Share(stats.channels, lineup) }
            }
            Gap(24)
        }
    }
}

/** Today's time as a VCR tape counter. */
@Composable
private fun Counter(seconds: Double) {
    val total = seconds.toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0xFF050706))
            .border(1.dp, Glass3, shape)
            .scanlines(0.35f)
            .padding(horizontal = 22.dp, vertical = 18.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(if (seconds > 0) Signal else Faint))
                Spacer(Modifier.width(8.dp))
                Osd("TODAY", size = 20.sp, color = if (seconds > 0) Ink else Muted, glow = false)
                Spacer(Modifier.weight(1f))
                Osd("H  MM", size = 18.sp, color = PhosphorDim, glow = false)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                // Ghost segments behind the lit ones, like an LCD/VFD readout.
                Box {
                    Osd("88:88", size = 104.sp, color = Phosphor.copy(alpha = 0.07f), glow = false)
                    Osd("%d:%02d".format(h, m).padStart(5, ' '), size = 104.sp)
                }
            }
        }
    }
}

@Composable
private fun StatCell(label: String, seconds: Double, modifier: Modifier) {
    Column(modifier) {
        Osd(formatWatch(seconds), size = 30.sp, color = Ink, glow = false)
        Hint(label)
    }
}

/**
 * One column per day, today on the right. Tap a column to read its value;
 * the selected one glows, the rest are dim phosphor.
 */
@Composable
private fun DayChart(days: List<Pair<LocalDate, Double>>) {
    val today = days.last().first
    var selected by remember(today) { mutableStateOf(today) }
    val sel = days.firstOrNull { it.first == selected } ?: days.last()
    val max = days.maxOf { it.second }.coerceAtLeast(60.0)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(dayLabel(sel.first, today), Modifier.weight(1f), color = Muted, style = MaterialTheme.typography.bodyMedium)
        Osd(formatWatch(sel.second), size = 24.sp)
    }
    Gap(12)
    Row(
        Modifier.fillMaxWidth().height(132.dp).scanlines(0.4f, 2.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for ((day, secs) in days) {
            val on = day == sel.first
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { selected = day },
                contentAlignment = Alignment.BottomCenter,
            ) {
                // Empty days still show a dim floor tick.
                Box(Modifier.fillMaxWidth().height(2.dp).background(Glass3))
                if (secs > 0) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            // A watched day never shrinks to nothing.
                            .fillMaxHeight((secs / max).toFloat().coerceIn(0.03f, 1f))
                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                            .background(
                                if (on) Brush.verticalGradient(listOf(Color(0xFFD9FFE5), Phosphor))
                                else Brush.verticalGradient(listOf(PhosphorDim, PhosphorDim.copy(alpha = 0.55f)))
                            )
                    )
                }
            }
        }
    }
    Gap(6)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for ((day, _) in days) {
            Osd(
                day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                Modifier.weight(1f),
                size = 16.sp,
                color = if (day == sel.first) Phosphor else Faint,
                glow = false,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Audience share: one bar split by channel, then the breakdown. */
@Composable
private fun Share(channels: List<Pair<String, Double>>, lineup: Lineup) {
    val total = channels.sumOf { it.second }.coerceAtLeast(1.0)
    Row(
        Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(5.dp)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((name, secs) in channels) {
            Box(
                Modifier.weight((secs / total).toFloat().coerceAtLeast(0.01f)).fillMaxHeight()
                    .background(channelColor(name))
            )
        }
    }
    Gap(14)
    channels.forEachIndexed { i, (name, secs) ->
        if (i > 0) Gap(10)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val color = channelColor(name)
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
            Spacer(Modifier.width(10.dp))
            Osd(lineup.label(name), color = color, size = 19.sp, glow = false)
            Spacer(Modifier.width(8.dp))
            Text(
                name.ifBlank { "Unknown" }, Modifier.weight(1f), maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = Ink,
            )
            Osd(formatWatch(secs), color = Muted, size = 18.sp, glow = false, modifier = Modifier.padding(start = 10.dp))
            Osd(
                "%3d%%".format((secs / total * 100).toInt()), color = Ink, size = 18.sp, glow = false,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}
