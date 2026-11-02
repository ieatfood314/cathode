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
fun StatsScreen(canRefresh: Boolean, notice: String?, lineup: Lineup = Lineup(emptyList()), onBack: () -> Unit = {}) {
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
            .background(Tube)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp)
    ) {
        // A page inside Setup: the kicker is the way back.
        Osd(
            "< SETUP", color = P60, size = 22.sp, glow = false,
            modifier = Modifier.clickable(onClick = onBack).padding(vertical = 6.dp),
        )
        ScreenTitle("Stats")
        (notice ?: error)?.let { Osd("! " + it.uppercase(), color = P80, size = 18.sp, glow = false, maxLines = 2) }
        Gap(18)
        Counter(stats.today)
        Gap(14)
        Panel {
            Row(Modifier.fillMaxWidth()) {
                StatCell("7 days", stats.week, Modifier.weight(1f))
                StatCell("Daily avg", stats.dailyAvg, Modifier.weight(1f))
                StatCell("All time", stats.allTime, Modifier.weight(1f))
            }
        }
        if (stats.days.isNotEmpty()) {
            SectionLabel("Last 14 days")
            DayChart(stats.days)
        }
        if (stats.channels.isNotEmpty()) {
            SectionLabel("Share · 30 days")
            Share(stats.channels, lineup)
        }
        Gap(24)
    }
}

/** Today's time as a VCR tape counter. */
@Composable
private fun Counter(seconds: Double) {
    val total = seconds.toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Tube1)
            .border(1.dp, P20, shape)
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (seconds > 0) Reverse("● TODAY", size = 21.sp) else Osd("TODAY", color = P60, size = 21.sp, glow = false)
                Spacer(Modifier.weight(1f))
                Osd("H  MM", size = 20.sp, color = P40, glow = false)
            }
            Box {
                // Unlit segments behind the lit ones, like a VFD readout.
                Osd("88:88", size = 120.sp, color = P10, glow = false)
                Osd("%d:%02d".format(h, m).padStart(5, ' '), size = 120.sp)
            }
        }
    }
}

@Composable
private fun StatCell(label: String, seconds: Double, modifier: Modifier) {
    Column(modifier) {
        Osd(formatWatch(seconds).uppercase(), size = 30.sp)
        Osd(label.uppercase(), color = P60, size = 18.sp, glow = false)
    }
}

/**
 * One LED column per day, today on the right. Tap a column to read it;
 * the selected one is fully lit, the others dim.
 */
@Composable
private fun DayChart(days: List<Pair<LocalDate, Double>>) {
    val today = days.last().first
    var selected by remember(today) { mutableStateOf(today) }
    val sel = days.firstOrNull { it.first == selected } ?: days.last()
    val max = days.maxOf { it.second }.coerceAtLeast(60.0)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Osd(dayLabel(sel.first, today).uppercase(), Modifier.weight(1f), color = P60, size = 21.sp, glow = false)
        Osd(formatWatch(sel.second).uppercase(), size = 28.sp)
    }
    Gap(10)
    val levels = 16
    Row(Modifier.fillMaxWidth().height(140.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((day, secs) in days) {
            val on = day == sel.first
            val lit = if (secs <= 0) 0 else ((secs / max) * levels).toInt().coerceIn(1, levels)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { selected = day },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (i in levels - 1 downTo 0) {
                    Box(
                        Modifier.fillMaxWidth().weight(1f).background(
                            when {
                                i >= lit -> P10
                                on -> P100
                                else -> P40
                            }
                        )
                    )
                }
            }
        }
    }
    Gap(6)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((day, _) in days) {
            Osd(
                day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                Modifier.weight(1f),
                size = 18.sp,
                color = if (day == sel.first) P100 else P40,
                glow = day == sel.first,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Audience share: an LED bar per channel. */
@Composable
private fun Share(channels: List<Pair<String, Double>>, lineup: Lineup) {
    val total = channels.sumOf { it.second }.coerceAtLeast(1.0)
    channels.forEachIndexed { i, (name, secs) ->
        if (i > 0) Gap(12)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Osd(lineup.label(name), size = 22.sp)
            Spacer(Modifier.width(10.dp))
            Osd(
                name.ifBlank { "Unknown" }.uppercase(), Modifier.weight(1f),
                color = P80, size = 21.sp, glow = false, overflow = TextOverflow.Ellipsis,
            )
            Osd(formatWatch(secs).uppercase(), color = P60, size = 19.sp, glow = false, modifier = Modifier.padding(start = 10.dp))
            Osd("%3d%%".format((secs / total * 100).toInt()), size = 21.sp, modifier = Modifier.padding(start = 10.dp))
        }
        Gap(4)
        Segments((secs / total).toFloat(), Modifier.fillMaxWidth(), cells = 30, height = 8.dp)
    }
}
