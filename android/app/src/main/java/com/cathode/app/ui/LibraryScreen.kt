package com.cathode.app.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
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
 * On air: what to watch next, one channel at a time (swipe the tube to
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
    Column(Modifier.fillMaxSize().background(Tube)) {
        StationHeader(status, live = status == "ON AIR")
        if (notice != null && serverConfigured && !wifiBlocked) {
            Osd("! " + notice.uppercase(), Modifier.padding(horizontal = 20.dp), color = P80, size = 18.sp, glow = false, maxLines = 2)
        }
        val pull = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = onRefresh,
            state = pull,
            modifier = Modifier.fillMaxSize(),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pull, isRefreshing = loading,
                    containerColor = Tube2, color = P100,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            },
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
                            "Off air", "Nothing recorded yet. Add some channels to the lineup.",
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
                        Column(Modifier.padding(horizontal = 20.dp)) {
                            SectionLabel("Guide")
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (f in GuideFilter.entries) {
                                    BlockTab(f.label, f == filter) { filter = f }
                                }
                            }
                            Gap(14)
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
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Osd("CATHODE", size = 38.sp)
        Spacer(Modifier.weight(1f))
        if (live) Reverse("● ON AIR", size = 21.sp)
        else Reverse(status, size = 21.sp, blink = true)
        Spacer(Modifier.width(12.dp))
        Osd(now.format(DateTimeFormatter.ofPattern("HH:mm")), size = 26.sp)
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
    Column(Modifier.padding(top = 6.dp)) {
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
        // The dial: ◄ 1/3 ►, one tick per channel on air.
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp, start = 20.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Osd("◄ SWIPE TO CHANGE CHANNEL ►", color = P40, size = 17.sp, glow = false)
            Spacer(Modifier.weight(1f))
            Osd("%d/%d".format(pager.currentPage + 1, picks.size), color = P60, size = 19.sp, glow = false)
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
    val shape = RoundedCornerShape(30.dp)
    val started = isInProgress(v)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3.4f)
            .clip(shape)
            .background(Tube1)
            .border(2.dp, P20, shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
    ) {
        // The picture: the one full-color thing on this screen.
        AsyncImage(
            model = thumb,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().scanlines(0.3f).tubeGlass(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.75f),
                    0.26f to Color.Transparent,
                    0.4f to Color.Transparent,
                    0.68f to Color.Black.copy(alpha = 0.82f),
                    1f to Color.Black.copy(alpha = 0.96f),
                )
            )
        )
        if (lineup.number(v.channelTitle) != null) Row(
            Modifier.align(Alignment.TopStart).padding(18.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Osd("CH", size = 24.sp, modifier = Modifier.padding(bottom = 4.dp))
            Spacer(Modifier.width(4.dp))
            Osd(lineup.label(v.channelTitle), size = 58.sp)
        }
        Row(Modifier.align(Alignment.TopEnd).padding(20.dp)) { StateBadge(v, state) }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(18.dp)) {
            Osd(v.channelTitle.orEmpty().uppercase(), color = P60, size = 21.sp, glow = false)
            Osd(v.title.orEmpty(), size = 30.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Gap(10)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Reverse(if (started) "▶ RESUME ${formatDuration(v.positionSeconds)}" else "▶ WATCH", size = 24.sp)
                Spacer(Modifier.width(12.dp))
                Osd(
                    listOfNotNull(formatDuration(v.duration).ifEmpty { null }, uploadedLabel(v)?.uppercase())
                        .joinToString(" · "),
                    color = P60, size = 20.sp, glow = false,
                )
            }
        }
        val fraction = watchFraction(v)
        if (fraction > 0f && v.watched == 0) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(P10)) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(P100))
            }
        }
        AnimatedVisibility(snow, enter = fadeIn(tween(40)), exit = fadeOut(tween(120))) {
            Static(Modifier.fillMaxSize())
        }
    }
}

/** NEW / ON PHONE / REC 42% / QUEUED. */
@Composable
private fun StateBadge(v: Video, state: SaveState, small: Boolean = false) {
    val size = if (small) 17.sp else 21.sp
    when (state) {
        is SaveState.Saving -> Reverse("● REC ${(state.fraction * 100).toInt()}%", size = size, blink = true)
        is SaveState.Queued -> Osd("QUEUED", color = P60, size = size, glow = false)
        is SaveState.Failed -> Reverse("SAVE FAILED", size = size, blink = true)
        is SaveState.Saved -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.DownloadDone, "On phone", tint = P100, modifier = Modifier.size(if (small) 14.dp else 18.dp))
            if (!small) {
                Spacer(Modifier.width(4.dp))
                Osd("ON PHONE", size = size)
            }
        }
        else -> if (v.watched == 0 && v.positionSeconds == 0 && isFresh(v)) Reverse("NEW", size = size)
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
    Column(Modifier.padding(bottom = 18.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Osd(lineup.label(channel), size = 28.sp)
            Spacer(Modifier.width(10.dp))
            Osd(
                channel.ifBlank { "Unknown channel" }.uppercase(),
                color = P80, size = 22.sp, glow = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val unseen = videos.count { it.watched == 0 }
            if (unseen > 0) Osd("$unseen NEW", color = P60, size = 18.sp, glow = false)
        }
        Gap(6)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(videos, key = { it.id }) { v ->
                ProgramBlock(v, thumbUrl(v), stateOf(v), onOpen, onMenu)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProgramBlock(
    v: Video,
    thumb: String,
    state: SaveState,
    onOpen: (Video) -> Unit,
    onMenu: (Video) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val watched = v.watched == 1
    val minutes = (v.duration / 60f).coerceAtLeast(1f)
    val width = (118 + minutes * 3.4f).coerceIn(150f, 300f).dp
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .width(width)
            .height(112.dp)
            .clip(shape)
            .background(Tube1)
            .border(1.dp, if (watched) P10 else P40, shape)
            .combinedClickable(
                onClick = { onOpen(v) },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onMenu(v)
                },
            )
            .alpha(if (watched) 0.45f else 1f)
    ) {
        // The listing's thumbnail is drawn by the tube: phosphor only.
        AsyncImage(
            model = thumb,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = PhosphorTint,
            modifier = Modifier.fillMaxSize().alpha(0.4f).scanlines(0.35f),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Tube.copy(alpha = 0.95f),
                    0.7f to Tube.copy(alpha = 0.6f),
                    1f to Tube.copy(alpha = 0.25f),
                )
            )
        )
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Osd(
                    if (watched) "SEEN" else uploadedLabel(v)?.uppercase().orEmpty(),
                    color = P60, size = 17.sp, glow = false,
                    modifier = Modifier.weight(1f),
                )
                StateBadge(v, state, small = true)
            }
            Osd(
                v.title.orEmpty(),
                color = P100, size = 21.sp, glow = !watched, maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Osd(formatDuration(v.duration), color = P60, size = 17.sp, glow = false)
        }
        val fraction = watchFraction(v)
        if (fraction > 0f && !watched) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(P10)) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(P100))
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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Tube1,
        shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
        dragHandle = { Box(Modifier.padding(top = 10.dp).size(width = 40.dp, height = 3.dp).background(P40)) },
    ) {
        Column(Modifier.padding(horizontal = 24.dp).padding(top = 14.dp, bottom = 24.dp)) {
            Osd("CH ${lineup.label(v.channelTitle)}  ${v.channelTitle.orEmpty().uppercase()}", color = P60, size = 20.sp, glow = false)
            Osd(v.title.orEmpty(), size = 26.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Gap(14)
            fun pick(action: () -> Unit) { onDismiss(); action() }
            SheetAction(if (isInProgress(v)) "Resume" else "Watch") { pick { onOpen(v) } }
            if (watched) SheetAction("Mark unwatched") { pick { onSetWatched(v, false) } }
            else SheetAction("Mark watched") { pick { onSetWatched(v, true) } }
            if (!watched && v.positionSeconds > 0) SheetAction("Start over") { pick { onClearProgress(v) } }
            if (showSave) when (state) {
                is SaveState.Saved -> SheetAction("Remove from phone") { pick { onRemoveSaved(v) } }
                is SaveState.Saving, is SaveState.Queued -> Unit
                else -> SheetAction("Save to phone") { pick { onSave(v) } }
            }
        }
    }
}

/** A menu line: "> MARK WATCHED". */
@Composable
private fun SheetAction(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Osd(">", color = P60, size = 24.sp, glow = false)
        Spacer(Modifier.width(12.dp))
        Osd(label.uppercase(), color = P100, size = 24.sp)
    }
}

// ---------------------------------------------------------------- empty

/** The empty state is the station's test pattern. */
@Composable
fun TestCard(title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Monoscope(title, Modifier.fillMaxWidth().aspectRatio(4f / 3f))
        Gap(20)
        Osd(body, color = P80, size = 22.sp, glow = false, maxLines = 4)
        if (action != null) {
            Gap(16)
            BlockTab("▶ $action", selected = true, size = 24.sp, onClick = onAction)
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
