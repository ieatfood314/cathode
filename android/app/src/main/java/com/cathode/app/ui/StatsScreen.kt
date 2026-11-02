package com.cathode.app.ui

import androidx.compose.foundation.background
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
fun StatsScreen(canRefresh: Boolean, notice: String?) {
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
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        ScreenTitle("Stats")
        (notice ?: error)?.let { Hint(it, Modifier.padding(top = 4.dp)) }
        Gap(24)
        Hint("Today")
        Text(formatWatch(stats.today), style = MaterialTheme.typography.displayLarge)
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
            SectionLabel("Channels · 30 days")
            Panel {
                val top = stats.channels.first().second
                stats.channels.forEachIndexed { i, (name, secs) ->
                    if (i > 0) Gap(12)
                    ChannelRow(name.ifBlank { "Unknown" }, secs, top)
                }
            }
        }
        Gap(16)
    }
}

@Composable
private fun StatCell(label: String, seconds: Double, modifier: Modifier) {
    Column(modifier) {
        Text(formatWatch(seconds), style = MaterialTheme.typography.titleMedium)
        Hint(label)
    }
}

/**
 * One column per day, today on the right. Tap a column to read its value;
 * the selected one is full accent, the rest dimmed.
 */
@Composable
private fun DayChart(days: List<Pair<LocalDate, Double>>) {
    val today = days.last().first
    var selected by remember(today) { mutableStateOf(today) }
    val sel = days.firstOrNull { it.first == selected } ?: days.last()
    val max = days.maxOf { it.second }.coerceAtLeast(60.0)
    Row(Modifier.fillMaxWidth()) {
        Text(dayLabel(sel.first, today), Modifier.weight(1f), color = Muted)
        Text(formatWatch(sel.second))
    }
    Gap(12)
    Row(
        Modifier.fillMaxWidth().height(120.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
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
                if (secs > 0) {
                    Box(
                        Modifier
                            .width(12.dp)
                            // A watched day never shrinks to nothing.
                            .fillMaxHeight((secs / max).toFloat().coerceIn(0.02f, 1f))
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(if (on) Accent else Accent.copy(alpha = 0.4f))
                    )
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
    Gap(6)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((day, _) in days) {
            Text(
                day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = if (day == sel.first) Color.White else Muted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ChannelRow(name: String, seconds: Double, top: Double) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(formatWatch(seconds), color = Muted, modifier = Modifier.padding(start = 12.dp))
    }
    Gap(6)
    Box(
        Modifier
            .fillMaxWidth((seconds / top).toFloat().coerceIn(0.02f, 1f))
            .height(4.dp)
            .clip(RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
            .background(Accent)
    )
}
