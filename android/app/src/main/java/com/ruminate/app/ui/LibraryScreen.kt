package com.ruminate.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ruminate.app.data.SaveState
import com.ruminate.app.data.Video

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
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(videos, key = { it.id }) { v ->
                val state = saveStates[v.id] ?: SaveState.NotSaved
                GlassCard(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(v) }
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AsyncImage(
                            model = thumbUrl(v),
                            contentDescription = null,
                            modifier = Modifier
                                .height(72.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF1A1A22)),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                v.title.orEmpty(),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                (v.channelTitle ?: "") + "  •  " + formatDuration(v.duration),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = AccentTeal)
                                Text(
                                    when {
                                        v.watched == 1 -> "Watched"
                                        state is SaveState.Saved -> "Saved on phone — tap to watch"
                                        state is SaveState.Saving -> "Auto-saving ${"%.0f".format(state.fraction * 100)}%"
                                        else -> "Tap to watch (auto-saves)"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        if (state is SaveState.Saved) {
                            Icon(Icons.Default.Check, contentDescription = "Saved",
                                tint = AccentTeal, modifier = Modifier.width(24.dp))
                        }
                    }
                }
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
