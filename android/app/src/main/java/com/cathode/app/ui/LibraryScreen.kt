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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
    onSetWatched: (Video, Boolean) -> Unit,
    onClearProgress: (Video) -> Unit,
    onSave: (Video) -> Unit,
    onRemoveSaved: (Video) -> Unit,
) {
    @Composable
    fun Item(v: Video) = VideoRow(
        v, thumbUrl(v), saveStates[v.id] ?: SaveState.NotSaved, showSaveButtons,
        onOpen, onSetWatched, onClearProgress, onSave, onRemoveSaved,
    )
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
                    Item(v)
                }
                if (rest.isNotEmpty()) item(key = "hdr-rest") { SectionLabel("Latest") }
            }
            items(rest, key = { it.id }) { v ->
                Item(v)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoRow(
    v: Video,
    thumb: String,
    state: SaveState,
    showSave: Boolean,
    onOpen: (Video) -> Unit,
    onSetWatched: (Video, Boolean) -> Unit,
    onClearProgress: (Video) -> Unit,
    onSave: (Video) -> Unit,
    onRemoveSaved: (Video) -> Unit,
) {
    val watched = v.watched == 1
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    Box {
        GlassCard(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .combinedClickable(
                    onClick = { onOpen(v) },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    },
                )
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Thumbnail(v, thumb)
                Column(Modifier.weight(1f)) {
                    Text(
                        v.title.orEmpty(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (watched) Color.Gray else Color.Unspecified,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state is SaveState.Saved) {
                            Icon(
                                Icons.Default.DownloadDone, contentDescription = "Saved on phone",
                                tint = AccentTeal, modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            v.channelTitle.orEmpty(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                        )
                    }
                }
                PlayButton(state) { onOpen(v) }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            fun pick(action: () -> Unit) { menu = false; action() }
            if (watched) {
                DropdownMenuItem(
                    text = { Text("Mark as unwatched") },
                    leadingIcon = { Icon(Icons.Default.RemoveDone, null) },
                    onClick = { pick { onSetWatched(v, false) } },
                )
            } else {
                DropdownMenuItem(
                    text = { Text("Mark as watched") },
                    leadingIcon = { Icon(Icons.Default.Done, null) },
                    onClick = { pick { onSetWatched(v, true) } },
                )
            }
            if (!watched && v.positionSeconds > 0) {
                DropdownMenuItem(
                    text = { Text("Start from the beginning") },
                    leadingIcon = { Icon(Icons.Default.Replay, null) },
                    onClick = { pick { onClearProgress(v) } },
                )
            }
            if (showSave) when (state) {
                is SaveState.Saved -> DropdownMenuItem(
                    text = { Text("Remove from phone") },
                    leadingIcon = { Icon(Icons.Default.Delete, null) },
                    onClick = { pick { onRemoveSaved(v) } },
                )
                is SaveState.Saving -> Unit
                else -> DropdownMenuItem(
                    text = { Text("Save on phone") },
                    leadingIcon = { Icon(Icons.Default.Download, null) },
                    onClick = { pick { onSave(v) } },
                )
            }
        }
    }
}

/** Big play button; while the phone copy downloads, a ring shows how far along it is. */
@Composable
private fun PlayButton(state: SaveState, onClick: () -> Unit) {
    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        if (state is SaveState.Saving) {
            CircularProgressIndicator(
                progress = { state.fraction },
                modifier = Modifier.fillMaxSize(),
                color = AccentTeal,
                strokeWidth = 3.dp,
                trackColor = Color(0x33FFFFFF),
            )
        }
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(AccentViolet)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.PlayArrow, contentDescription = "Play",
                tint = Color.White, modifier = Modifier.size(32.dp),
            )
        }
    }
}

@Composable
private fun Thumbnail(v: Video, thumb: String) {
    val watched = v.watched == 1
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
