package com.cathode.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.cathode.app.data.SaveState
import com.cathode.app.data.Video

@Composable
fun LibraryScreen(
    videos: List<Video>,
    thumbUrl: (Video) -> String,
    loading: Boolean,
    wifiBlocked: Boolean,
    saveStates: Map<String, SaveState>,
    showSaveButtons: Boolean,
    onRefresh: () -> Unit,
    onOpen: (Video) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Library", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "Sync now")
            }
        }
        if (wifiBlocked) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Paused — waiting for Wi-Fi", color = AccentTeal)
                Text(
                    "Wi-Fi only is ON, so nothing uses cellular data. Reconnect to Wi-Fi and sync resumes where it stopped.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
        if (loading && videos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return
        }
        // In-progress videos first so "continue watching" sits above the
        // fold; everything else keeps the server's newest-first order.
        val (inProgress, rest) = remember(videos) { videos.partition(::isInProgress) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (inProgress.isNotEmpty()) {
                item(key = "hdr-continue") { SectionLabel("Continue watching") }
                items(inProgress, key = { it.id }) { v ->
                    VideoRow(v, thumbUrl(v), saveStates[v.id] ?: SaveState.NotSaved, onOpen)
                }
                if (rest.isNotEmpty()) item(key = "hdr-rest") { SectionLabel("Latest") }
            }
            items(rest, key = { it.id }) { v ->
                VideoRow(v, thumbUrl(v), saveStates[v.id] ?: SaveState.NotSaved, onOpen)
            }
        }
    }
}

/** Started but not finished (the server auto-marks watched past ~95%). */
private fun isInProgress(v: Video) = v.watched == 0 && v.positionSeconds > 0

private fun watchFraction(v: Video): Float = when {
    v.watched == 1 -> 1f
    v.duration <= 0 -> 0f
    else -> (v.positionSeconds.toFloat() / v.duration).coerceIn(0f, 1f)
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = Color.Gray)
}

@Composable
private fun VideoRow(v: Video, thumb: String, state: SaveState, onOpen: (Video) -> Unit) {
    val watched = v.watched == 1
    GlassCard(
        Modifier
            .fillMaxWidth()
            .clickable { onOpen(v) }
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .size(width = 128.dp, height = 72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1A1A22))
            ) {
                AsyncImage(
                    model = thumb,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (watched) {
                    // Completion marker: dimmed thumbnail + check badge.
                    Box(Modifier.fillMaxSize().background(Color(0x99000000)))
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Watched",
                        tint = AccentTeal,
                        modifier = Modifier.align(Alignment.Center).size(28.dp),
                    )
                }
                if (v.duration > 0) {
                    Text(
                        formatDuration(v.duration),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = 7.dp, end = 4.dp)
                            .background(Color(0xB3000000), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp),
                    )
                }
                val fraction = watchFraction(v)
                if (fraction > 0f) {
                    // Watch-progress bar along the thumbnail's bottom edge.
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(Color(0x66FFFFFF))
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(fraction)
                                .fillMaxHeight()
                                .background(if (watched) AccentTeal else AccentViolet)
                        )
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    v.title.orEmpty(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (watched) Color.Gray else Color.Unspecified,
                )
                Text(
                    v.channelTitle.orEmpty(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = AccentTeal)
                    Text(
                        when {
                            watched -> "Watched"
                            isInProgress(v) && v.duration > 0 ->
                                "${formatDuration((v.duration - v.positionSeconds).coerceAtLeast(1))} left"
                            state is SaveState.Saving -> "Auto-saving ${"%.0f".format(state.fraction * 100)}%"
                            state is SaveState.Saved -> "Saved on phone — tap to watch"
                            else -> "Tap to watch (auto-saves)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (state is SaveState.Saved) {
                Icon(Icons.Default.DownloadDone, contentDescription = "Saved on phone",
                    tint = AccentTeal, modifier = Modifier.width(24.dp))
            }
        }
    }
}

fun formatDuration(sec: Int): String {
    if (sec <= 0) return ""
    val m = sec / 60
    val s = sec % 60
    val h = m / 60
    return if (h > 0) "%d:%02d:%02d".format(h, m % 60, s) else "%d:%02d".format(m, s)
}

fun formatBytes(b: Long): String {
    if (b <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var v = b.toDouble()
    var u = 0
    while (v >= 1024 && u < units.size - 1) { v /= 1024; u++ }
    return "%.1f %s".format(v, units[u])
}
