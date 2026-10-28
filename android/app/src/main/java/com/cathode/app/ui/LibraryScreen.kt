package com.cathode.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.cathode.app.data.SaveState
import com.cathode.app.data.Video
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    videos: List<Video>,
    thumbUrl: (Video) -> String,
    loading: Boolean,
    wifiBlocked: Boolean,
    // Offline / unreachable server: the list below is the last synced one.
    notice: String?,
    serverConfigured: Boolean,
    saveStates: Map<String, SaveState>,
    // Videos found in the storage folder (saved in this or any earlier session).
    savedIds: Set<String>,
    showSaveButtons: Boolean,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpen: (Video) -> Unit,
    onSetWatched: (Video, Boolean) -> Unit,
    onClearProgress: (Video) -> Unit,
    onSave: (Video) -> Unit,
    onRemoveSaved: (Video) -> Unit,
) {
    @Composable
    fun Item(v: Video) = VideoRow(
        v, thumbUrl(v),
        saveStates[v.id] ?: if (v.id in savedIds) SaveState.Saved else SaveState.NotSaved,
        showSaveButtons,
        onOpen, onSetWatched, onClearProgress, onSave, onRemoveSaved,
    )
    val banner = when {
        !serverConfigured -> null
        wifiBlocked -> "Waiting for Wi-Fi"
        else -> notice
    }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        ScreenTitle("Library", Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
        if (banner != null) Hint(banner, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (videos.isEmpty()) {
                // Scrollable so pull-to-refresh works on an empty screen too.
                Box(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        loading -> Unit
                        !serverConfigured -> EmptyState(
                            "No server", "Add your server's address to get started.",
                            action = "Open settings", onAction = onOpenSettings,
                        )
                        // Never synced, and this sync failed: nothing cached to show.
                        notice != null || wifiBlocked -> EmptyState(
                            "Not synced yet",
                            if (wifiBlocked) "Connect to Wi-Fi to sync." else "Pull down to retry.",
                        )
                        else -> EmptyState("No videos", "Add channels in the Channels tab.")
                    }
                }
            } else {
                // In-progress videos first so "continue watching" sits above
                // the fold; everything else keeps the server's newest-first order.
                val (inProgress, rest) = remember(videos) { videos.partition(::isInProgress) }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (inProgress.isNotEmpty()) {
                        item(key = "hdr-continue") { SectionLabel("Continue watching") }
                        items(inProgress, key = { it.id }) { v -> Item(v) }
                        if (rest.isNotEmpty()) item(key = "hdr-rest") { SectionLabel("Latest") }
                    } else {
                        item(key = "top") { Gap(12) }
                    }
                    items(rest, key = { it.id }) { v -> Item(v) }
                }
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
private fun EmptyState(title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Gap(4)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Muted, textAlign = TextAlign.Center)
        if (action != null) {
            Gap(16)
            OutlinedButton(onClick = onAction) { Text(action) }
        }
    }
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
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(
                    onClick = { onOpen(v) },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    },
                )
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumbnail(v, thumb)
            Column(Modifier.weight(1f)) {
                Text(
                    v.title.orEmpty(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (watched) Muted else Color.Unspecified,
                )
                Gap(2)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state is SaveState.Saved) {
                        Icon(
                            Icons.Default.DownloadDone, contentDescription = "On phone",
                            tint = Muted, modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        when (state) {
                            is SaveState.Saving -> "Saving ${(state.fraction * 100).toInt()}%"
                            is SaveState.Queued -> "Queued"
                            is SaveState.Failed -> "Save failed"
                            else -> v.channelTitle.orEmpty()
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (state) {
                            is SaveState.Saving -> Accent
                            is SaveState.Failed -> MaterialTheme.colorScheme.error
                            else -> Muted
                        },
                        // A long channel name shortens, the date stays whole.
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    val uploaded = uploadedLabel(v)
                    if (uploaded != null) {
                        Text(
                            " · $uploaded", maxLines = 1,
                            style = MaterialTheme.typography.bodySmall, color = Muted,
                        )
                    }
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            fun pick(action: () -> Unit) { menu = false; action() }
            if (watched) {
                DropdownMenuItem(
                    text = { Text("Mark unwatched") },
                    leadingIcon = { Icon(Icons.Default.RemoveDone, null) },
                    onClick = { pick { onSetWatched(v, false) } },
                )
            } else {
                DropdownMenuItem(
                    text = { Text("Mark watched") },
                    leadingIcon = { Icon(Icons.Default.Done, null) },
                    onClick = { pick { onSetWatched(v, true) } },
                )
            }
            if (!watched && v.positionSeconds > 0) {
                DropdownMenuItem(
                    text = { Text("Start over") },
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
                is SaveState.Saving, is SaveState.Queued -> Unit
                else -> DropdownMenuItem(
                    text = { Text("Save to phone") },
                    leadingIcon = { Icon(Icons.Default.Download, null) },
                    onClick = { pick { onSave(v) } },
                )
            }
        }
    }
}

@Composable
private fun Thumbnail(v: Video, thumb: String) {
    val watched = v.watched == 1
    Box(
        Modifier
            .size(width = 128.dp, height = 72.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Surface1)
    ) {
        AsyncImage(
            model = thumb,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (watched) {
            // Completion marker: dimmed thumbnail + check.
            Box(Modifier.fillMaxSize().background(Color(0x99000000)))
            Icon(
                Icons.Default.Check,
                contentDescription = "Watched",
                tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(24.dp),
            )
        }
        if (v.duration > 0 && !watched) {
            Text(
                formatDuration(v.duration),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 6.dp, end = 4.dp)
                    .background(Color(0xB3000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp),
            )
        }
        val fraction = watchFraction(v)
        if (fraction > 0f && !watched) {
            // Watch-progress bar along the thumbnail's bottom edge.
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color(0x66FFFFFF))
            ) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Accent))
            }
        }
    }
}

/**
 * When it went up: "42 min ago", "5 hours ago", "Yesterday", "3 days ago",
 * then the date. Uses the exact upload time when the server has it, else
 * the upload day.
 */
fun uploadedLabel(v: Video, now: ZonedDateTime = ZonedDateTime.now()): String? {
    if (v.published > 0) {
        val mins = (now.toEpochSecond() - v.published) / 60
        when {
            mins < 1 -> return "Just now"
            mins < 60 -> return "$mins min ago"
            mins < 24 * 60 -> return (mins / 60).let { if (it == 1L) "1 hour ago" else "$it hours ago" }
        }
    }
    val day = if (v.published > 0) Instant.ofEpochSecond(v.published).atZone(now.zone).toLocalDate()
    else v.uploadDate?.takeIf { it.length == 8 }
        ?.let { runCatching { LocalDate.parse(it, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull() }
        ?: return null
    val today = now.toLocalDate()
    val days = ChronoUnit.DAYS.between(day, today)
    return when {
        days <= 0 -> "Today"
        days == 1L -> "Yesterday"
        days < 7 -> "$days days ago"
        day.year == today.year -> day.format(DateTimeFormatter.ofPattern("MMM d"))
        else -> day.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
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
