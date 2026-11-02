package com.cathode.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.cathode.app.data.SaveState
import com.cathode.app.data.Video
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private enum class GuideFilter(val label: String) { ALL("All"), NEW("Unwatched"), PHONE("On phone") }

/**
 * On Air: what to watch next, one channel at a time (swipe the tube to
 * change channel), over a program guide of everything, a row per channel.
 */
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
    onOpenChannels: () -> Unit = {},
    onOpen: (Video) -> Unit,
    onSetWatched: (Video, Boolean) -> Unit,
    onClearProgress: (Video) -> Unit,
    onSave: (Video) -> Unit,
    onRemoveSaved: (Video) -> Unit,
) {
    val lineup = remember(videos) { Lineup.of(videos) }
    fun stateOf(v: Video) = saveStates[v.id] ?: if (v.id in savedIds) SaveState.Saved else SaveState.NotSaved
    var menuFor by remember { mutableStateOf<Video?>(null) }
    var filter by rememberSaveable { mutableStateOf(GuideFilter.ALL) }

    val status = when {
        !serverConfigured -> "NO SERVER"
        loading -> "TUNING"
        wifiBlocked -> "WAIT WI-FI"
        notice != null -> "NO SIGNAL"
        else -> "ON AIR"
    }
    Column(Modifier.fillMaxSize().background(Glass)) {
        ColorBars(height = 3.dp)
        StationHeader(status, live = status == "ON AIR")
        if (notice != null && serverConfigured && !wifiBlocked) {
            Hint(notice, Modifier.padding(horizontal = 20.dp), color = BarYellow)
        }
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (videos.isEmpty()) {
                // Scrollable so pull-to-refresh works on an empty screen too.
                Box(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    when {
                        loading -> Unit
                        !serverConfigured -> TestCard(
                            "No server", "Tell Cathode where your server is and it'll start broadcasting.",
                            action = "Set up", onAction = onOpenSettings,
                        )
                        // Never synced, and this sync failed: nothing cached to show.
                        notice != null || wifiBlocked -> TestCard(
                            "No signal",
                            if (wifiBlocked) "Connect to Wi-Fi to tune in." else "Pull down to try again.",
                        )
                        else -> TestCard(
                            "Off the air", "Nothing recorded yet. Add some channels to the lineup.",
                            action = "Add channels", onAction = onOpenChannels,
                        )
                    }
                }
            } else {
                val picks = remember(videos) { nowShowing(videos) }
                val shown = remember(videos, filter, savedIds, saveStates) {
                    videos.filter {
                        when (filter) {
                            GuideFilter.ALL -> true
                            GuideFilter.NEW -> it.watched == 0
                            GuideFilter.PHONE -> stateOf(it) is SaveState.Saved
                        }
                    }
                }
                // Channels with the newest upload first; each row newest-first.
                val rows = remember(shown) {
                    shown.groupBy { it.channelTitle.orEmpty() }
                        .entries.sortedByDescending { (_, vs) -> vs.maxOf { sortKey(it) } }
                        .map { (ch, vs) -> ch to vs.sortedByDescending { sortKey(it) } }
                }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    item(key = "hero") {
                        NowShowing(
                            picks, lineup, thumbUrl, ::stateOf,
                            onOpen = onOpen,
                            onMenu = { menuFor = it },
                        )
                    }
                    item(key = "guide-hdr") {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SectionLabel("Guide")
                            Spacer(Modifier.weight(1f))
                            Row(Modifier.padding(top = 18.dp)) {
                                for (f in GuideFilter.entries) {
                                    FilterTab(f.label, f == filter) { filter = f }
                                }
                            }
                        }
                    }
                    if (rows.isEmpty()) {
                        item(key = "guide-empty") {
                            Hint(
                                if (filter == GuideFilter.PHONE) "Nothing saved to this phone yet."
                                else "All caught up.",
                                Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            )
                        }
                    }
                    items(rows, key = { "ch-" + it.first }) { (channel, vs) ->
                        GuideRow(
                            channel, vs, lineup, thumbUrl, ::stateOf,
                            onOpen = onOpen, onMenu = { menuFor = it },
                        )
                    }
                }
            }
        }
    }
    menuFor?.let { v ->
        VideoSheet(
            v, lineup, stateOf(v), showSaveButtons,
            onDismiss = { menuFor = null },
            onOpen = onOpen, onSetWatched = onSetWatched, onClearProgress = onClearProgress,
            onSave = onSave, onRemoveSaved = onRemoveSaved,
        )
    }
}

private fun sortKey(v: Video): Long = when {
    v.published > 0 -> v.published
    v.uploadDate?.length == 8 -> runCatching {
        LocalDate.parse(v.uploadDate, DateTimeFormatter.BASIC_ISO_DATE).toEpochDay() * 86_400
    }.getOrDefault(v.downloadedAt)
    else -> v.downloadedAt
}

/**
 * One pick per channel: the video you were partway through, else its newest
 * unwatched upload. Half-watched first, then newest. With everything
 * watched, the newest uploads.
 */
fun nowShowing(videos: List<Video>): List<Video> {
    val perChannel = videos.groupBy { it.channelTitle.orEmpty() }.mapNotNull { (_, vs) ->
        vs.firstOrNull(::isInProgress) ?: vs.filter { it.watched == 0 }.maxByOrNull(::sortKey)
    }
    val (started, fresh) = perChannel.partition(::isInProgress)
    val picks = started + fresh.sortedByDescending(::sortKey)
    return picks.ifEmpty { videos.sortedByDescending(::sortKey).take(5) }.take(16)
}

// ---------------------------------------------------------------- header

@Composable
private fun StationHeader(status: String, live: Boolean) {
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalTime.now()
            delay(1000L * (60 - now.second).coerceAtLeast(1))
        }
    }
    val blink = rememberInfiniteTransition(label = "blink")
    val dot by blink.animateFloat(
        1f, 0.2f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dot",
    )
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "CATHODE",
            style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 3.sp),
            color = Ink,
        )
        Spacer(Modifier.weight(1f))
        val c = if (live) Signal else BarYellow
        Box(Modifier.size(8.dp).alpha(if (live) dot else 1f).clip(CircleShape).background(c))
        Spacer(Modifier.width(6.dp))
        Osd(status, color = if (live) Ink else BarYellow, size = 19.sp, glow = false)
        Spacer(Modifier.width(12.dp))
        Osd(now.format(DateTimeFormatter.ofPattern("HH:mm")), size = 22.sp)
    }
}

@Composable
private fun FilterTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .background(if (selected) Phosphor.copy(alpha = 0.14f) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Osd(label.uppercase(), size = 17.sp, color = if (selected) Phosphor else Faint, glow = selected)
    }
}

// ---------------------------------------------------------------- the tube

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NowShowing(
    picks: List<Video>,
    lineup: Lineup,
    thumbUrl: (Video) -> String,
    stateOf: (Video) -> SaveState,
    onOpen: (Video) -> Unit,
    onMenu: (Video) -> Unit,
) {
    if (picks.isEmpty()) return
    val pager = rememberPagerState { picks.size }
    val haptics = LocalHapticFeedback.current
    // A burst of snow on every channel change, like flicking a dial.
    var snow by remember { mutableStateOf(false) }
    var settledOnce by remember { mutableStateOf(false) }
    LaunchedEffect(pager.settledPage) {
        if (!settledOnce) { settledOnce = true; return@LaunchedEffect }
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        snow = true
        delay(190)
        snow = false
    }
    Column(Modifier.padding(top = 8.dp)) {
        HorizontalPager(
            state = pager,
            contentPadding = PaddingValues(horizontal = 20.dp),
            pageSpacing = 12.dp,
            key = { picks[it].id },
        ) { page ->
            val v = picks[page]
            Tube(
                v, lineup, thumbUrl(v), stateOf(v),
                snow = snow && page == pager.currentPage,
                onClick = { onOpen(v) },
                onLongClick = { onMenu(v) },
            )
        }
        // Dial: one tick per channel on air, the current one lit and wide.
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            picks.forEachIndexed { i, v ->
                val on = i == pager.currentPage
                Box(
                    Modifier
                        .padding(horizontal = 2.dp)
                        .size(width = if (on) 18.dp else 6.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (on) channelColor(v.channelTitle) else Glass3)
                        .animateContentSize()
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tube(
    v: Video,
    lineup: Lineup,
    thumb: String,
    state: SaveState,
    snow: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val color = channelColor(v.channelTitle)
    val shape = RoundedCornerShape(28.dp)
    val started = isInProgress(v)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3.4f)
            .clip(shape)
            .background(Glass2)
            .border(1.dp, Glass3, shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
    ) {
        AsyncImage(
            model = thumb,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().scanlines(0.28f).tubeGlass(),
        )
        // Legibility: a dark band behind the OSD up top and the program
        // card at the bottom, whatever the thumbnail looks like.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.7f),
                    0.28f to Color.Transparent,
                    0.42f to Color.Transparent,
                    0.7f to Color.Black.copy(alpha = 0.78f),
                    1f to Color.Black.copy(alpha = 0.95f),
                )
            )
        )
        Row(
            Modifier.align(Alignment.TopStart).padding(18.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Osd("CH", size = 20.sp, modifier = Modifier.padding(bottom = 3.dp))
            Spacer(Modifier.width(4.dp))
            Osd(lineup.label(v.channelTitle), size = 44.sp)
        }
        Row(Modifier.align(Alignment.TopEnd).padding(18.dp)) {
            StateBadge(v, state)
        }
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(18.dp),
        ) {
            Osd(v.channelTitle.orEmpty().uppercase(), color = color, size = 19.sp)
            Gap(2)
            Text(
                v.title.orEmpty(),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Gap(10)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(Color.White)
                        .padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (started) "Resume ${formatDuration(v.positionSeconds)}" else "Watch",
                            color = Color.Black, style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Osd(
                    listOfNotNull(formatDuration(v.duration).ifEmpty { null }, uploadedLabel(v)?.uppercase())
                        .joinToString("  ·  "),
                    color = Color.White.copy(alpha = 0.75f), size = 18.sp, glow = false,
                )
            }
        }
        val fraction = watchFraction(v)
        if (fraction > 0f && v.watched == 0) {
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp)
                    .background(Color.White.copy(alpha = 0.18f))
            ) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(color))
            }
        }
        AnimatedVisibility(snow, enter = fadeIn(tween(40)), exit = fadeOut(tween(120))) {
            Static(Modifier.fillMaxSize())
        }
    }
}

/** NEW / ON PHONE / REC 42% / QUEUED, in OSD type. */
@Composable
private fun StateBadge(v: Video, state: SaveState, small: Boolean = false) {
    val size = if (small) 15.sp else 18.sp
    when (state) {
        is SaveState.Saving -> RecBadge("REC ${(state.fraction * 100).toInt()}%", size)
        is SaveState.Queued -> Osd("QUEUED", color = BarYellow, size = size, glow = false)
        is SaveState.Failed -> Osd("SAVE FAILED", color = Signal, size = size, glow = false)
        is SaveState.Saved -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.DownloadDone, "On phone", tint = Phosphor, modifier = Modifier.size(if (small) 13.dp else 16.dp))
            if (!small) {
                Spacer(Modifier.width(4.dp))
                Osd("ON PHONE", size = size)
            }
        }
        else -> if (!small && v.watched == 0 && v.positionSeconds == 0 && isFresh(v)) {
            Osd("NEW", color = BarYellow, size = size)
        }
    }
}

@Composable
private fun RecBadge(text: String, size: androidx.compose.ui.unit.TextUnit) {
    val blink = rememberInfiniteTransition(label = "rec")
    val a by blink.animateFloat(1f, 0f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "a")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).alpha(a).clip(CircleShape).background(Signal))
        Spacer(Modifier.width(4.dp))
        Osd(text, color = Signal, size = size)
    }
}

/** Up in the last two days. */
private fun isFresh(v: Video): Boolean {
    val k = sortKey(v)
    return k > 0 && Instant.now().epochSecond - k < 2 * 86_400
}

// ---------------------------------------------------------------- the guide

/**
 * One channel's programs. Block width follows running time, like a TV
 * guide: a 3-minute short is a sliver, a 2-hour podcast a slab.
 */
@Composable
private fun GuideRow(
    channel: String,
    videos: List<Video>,
    lineup: Lineup,
    thumbUrl: (Video) -> String,
    stateOf: (Video) -> SaveState,
    onOpen: (Video) -> Unit,
    onMenu: (Video) -> Unit,
) {
    val color = channelColor(channel)
    Column(Modifier.padding(bottom = 18.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Osd(lineup.label(channel), color = color, size = 24.sp)
            Spacer(Modifier.width(10.dp))
            Text(
                channel.ifBlank { "Unknown channel" },
                style = MaterialTheme.typography.titleSmall,
                color = Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val unseen = videos.count { it.watched == 0 }
            if (unseen > 0) Osd("$unseen NEW", color = Faint, size = 16.sp, glow = false)
        }
        Gap(8)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(videos, key = { it.id }) { v ->
                ProgramBlock(v, color, thumbUrl(v), stateOf(v), onOpen, onMenu)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProgramBlock(
    v: Video,
    color: Color,
    thumb: String,
    state: SaveState,
    onOpen: (Video) -> Unit,
    onMenu: (Video) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val watched = v.watched == 1
    val minutes = (v.duration / 60f).coerceAtLeast(1f)
    val width = (118 + minutes * 3.4f).coerceIn(140f, 300f).dp
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .width(width)
            .height(108.dp)
            .clip(shape)
            .background(Glass1)
            .border(1.dp, if (watched) Glass2 else color.copy(alpha = 0.28f), shape)
            .combinedClickable(
                onClick = { onOpen(v) },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onMenu(v)
                },
            )
            .alpha(if (watched) 0.45f else 1f)
    ) {
        AsyncImage(
            model = thumb,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(0.55f).scanlines(0.3f),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Glass.copy(alpha = 0.95f),
                    0.75f to Glass.copy(alpha = 0.55f),
                    1f to Glass.copy(alpha = 0.2f),
                )
            )
        )
        Box(Modifier.width(3.dp).fillMaxHeight().background(if (watched) Faint else color))
        Column(Modifier.fillMaxSize().padding(start = 12.dp, end = 10.dp, top = 9.dp, bottom = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Osd(
                    when {
                        watched -> "SEEN"
                        else -> uploadedLabel(v)?.uppercase() ?: ""
                    },
                    color = if (watched) Muted else color, size = 15.sp, glow = false,
                    modifier = Modifier.weight(1f),
                )
                StateBadge(v, state, small = true)
            }
            Gap(3)
            Text(
                v.title.orEmpty(),
                fontFamily = Narrow,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                lineHeight = 17.sp,
                color = Ink,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Osd(formatDuration(v.duration), color = Muted, size = 15.sp, glow = false)
        }
        val fraction = watchFraction(v)
        if (fraction > 0f && !watched) {
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp)
                    .background(Color.White.copy(alpha = 0.12f))
            ) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(color))
            }
        }
    }
}

// ---------------------------------------------------------------- menu

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoSheet(
    v: Video,
    lineup: Lineup,
    state: SaveState,
    showSave: Boolean,
    onDismiss: () -> Unit,
    onOpen: (Video) -> Unit,
    onSetWatched: (Video, Boolean) -> Unit,
    onClearProgress: (Video) -> Unit,
    onSave: (Video) -> Unit,
    onRemoveSaved: (Video) -> Unit,
) {
    val watched = v.watched == 1
    val color = channelColor(v.channelTitle)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Glass1,
        dragHandle = { ColorBars(Modifier.padding(top = 0.dp), height = 3.dp) },
    ) {
        Column(Modifier.padding(horizontal = 24.dp).padding(top = 16.dp, bottom = 24.dp)) {
            Osd("CH ${lineup.label(v.channelTitle)}  ${v.channelTitle.orEmpty().uppercase()}", color = color, size = 19.sp)
            Gap(4)
            Text(v.title.orEmpty(), style = MaterialTheme.typography.titleMedium, color = Ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Gap(16)
            fun pick(action: () -> Unit) { onDismiss(); action() }
            SheetAction(Icons.Default.PlayArrow, if (isInProgress(v)) "Resume" else "Watch") { pick { onOpen(v) } }
            if (watched) {
                SheetAction(Icons.Default.RemoveDone, "Mark unwatched") { pick { onSetWatched(v, false) } }
            } else {
                SheetAction(Icons.Default.Done, "Mark watched") { pick { onSetWatched(v, true) } }
            }
            if (!watched && v.positionSeconds > 0) {
                SheetAction(Icons.Default.Replay, "Start over") { pick { onClearProgress(v) } }
            }
            if (showSave) when (state) {
                is SaveState.Saved -> SheetAction(Icons.Default.Delete, "Remove from phone") { pick { onRemoveSaved(v) } }
                is SaveState.Saving, is SaveState.Queued -> Unit
                else -> SheetAction(Icons.Default.Download, "Save to phone") { pick { onSave(v) } }
            }
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Muted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Ink)
    }
}

// ---------------------------------------------------------------- empty

/** The empty state is a test card. */
@Composable
fun TestCard(title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        val shape = RoundedCornerShape(28.dp)
        Box(
            Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(shape).border(1.dp, Glass3, shape),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(0.68f).fillMaxWidth()) {
                    for (c in listOf(Color(0xFFC9CFCB)) + Bars) {
                        Box(Modifier.weight(1f).fillMaxHeight().background(c))
                    }
                }
                Row(Modifier.weight(0.08f).fillMaxWidth()) {
                    for (c in listOf(BarBlue, Glass, BarMagenta, Glass, BarCyan, Glass, Color(0xFFC9CFCB))) {
                        Box(Modifier.weight(1f).fillMaxHeight().background(c))
                    }
                }
                Row(Modifier.weight(0.24f).fillMaxWidth()) {
                    Box(Modifier.weight(1.2f).fillMaxHeight().background(Color(0xFF0B2A57)))
                    Box(Modifier.weight(1.2f).fillMaxHeight().background(Color.White))
                    Box(Modifier.weight(1.2f).fillMaxHeight().background(Color(0xFF2A0B57)))
                    Box(Modifier.weight(3f).fillMaxHeight().background(Glass))
                }
            }
            Box(Modifier.fillMaxSize().scanlines(0.3f).tubeGlass())
            Box(
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.85f))
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            ) {
                Osd(title.uppercase(), size = 34.sp)
            }
        }
        Gap(20)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = Muted, textAlign = TextAlign.Start)
        if (action != null) {
            Gap(16)
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(Phosphor)
                    .clickable(onClick = onAction)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(action, color = Color(0xFF032611), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

// ---------------------------------------------------------------- shared

/** Started but not finished (the server auto-marks watched past ~95%). */
fun isInProgress(v: Video) = v.watched == 0 && v.positionSeconds > 0

fun watchFraction(v: Video): Float = when {
    v.watched == 1 -> 1f
    v.duration <= 0 -> 0f
    else -> (v.positionSeconds.toFloat() / v.duration).coerceIn(0f, 1f)
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
