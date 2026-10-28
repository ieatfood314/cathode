package com.cathode.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.cathode.app.data.AppPrefs
import com.cathode.app.data.ServerSettings
import com.cathode.app.data.VideoStore
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/** Retention steps: every day up to a month, then 60 / 90 / 180 / 360. */
val RETENTION_OPTIONS: List<Int> = listOf(0) + (1..30).toList() + listOf(60, 90, 180, 360)

fun retentionLabel(days: Int): String = when {
    days <= 0 -> "Forever"
    days == 1 -> "1 day"
    days <= 30 -> "$days days"
    days < 90 -> "2 months"
    days < 180 -> "3 months"
    days < 360 -> "6 months"
    else -> "1 year"
}

/** Real video heights (the old free slider landed on 660p, 1260p…). */
val RESOLUTION_OPTIONS = listOf(360, 480, 720, 1080, 1440, 2160)

fun resolutionLabel(h: Int): String = when (h) {
    1440 -> "1440p (2K)"
    2160 -> "2160p (4K)"
    else -> "${h}p"
}

fun intervalLabel(mins: Int): String = when {
    mins < 60 -> "$mins min"
    mins % 60 == 0 -> if (mins == 60) "1 hour" else "${mins / 60} hours"
    else -> "${mins / 60} h ${mins % 60} min"
}

private fun nearestIndex(options: List<Int>, value: Int) =
    options.indices.minByOrNull { abs(options[it] - value) } ?: 0

/** SponsorBlock categories: id + short button label. */
val SB_CATS = listOf(
    "sponsor" to "Sponsors",
    "intro" to "Intros",
    "outro" to "End cards",
    "selfpromo" to "Self-promo",
    "preview" to "Recaps",
    "interaction" to "Reminders",
    "filler" to "Filler",
    "music_offtopic" to "Off-topic music",
    "poi_highlight" to "Highlights",
)
val SB_ALL = SB_CATS.map { it.first }

private fun splitSb(s: String) = s.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

/** Toggle a category in the cut-out list (empty = cut nothing; drops it from an explicit mark list). */
fun toggleRemove(s: ServerSettings, cat: String): ServerSettings {
    val set = splitSb(s.sponsorblockRemove).toMutableSet()
    if (cat in set) set.remove(cat) else set.add(cat)
    val mark = if (s.sponsorblockMark == "all") "all"
    else (splitSb(s.sponsorblockMark) - cat).joinToString(",")
    return s.copy(sponsorblockRemove = set.joinToString(","), sponsorblockMark = mark)
}

/** Toggle a category in the chapter-mark list ("all" expands to explicit on first change). */
fun toggleMark(s: ServerSettings, cat: String): ServerSettings {
    val base = if (s.sponsorblockMark == "all") SB_ALL.toSet() else splitSb(s.sponsorblockMark)
    val set = base.toMutableSet()
    if (cat in set) set.remove(cat) else set.add(cat)
    val mark = if (set.size == SB_ALL.size && set.containsAll(SB_ALL)) "all" else set.joinToString(",")
    return s.copy(sponsorblockMark = mark)
}

/**
 * Everything configurable from the app: connection (LAN or Tailscale URL),
 * phone-side sync rules, and the full server config (polling, quality,
 * SponsorBlock, retention). Server fields load from GET /api/settings and
 * auto-save with PUT /api/settings — no SSH or .env editing needed day to day.
 */
@Composable
fun SettingsScreen(
    prefs: AppPrefs,
    server: ServerSettings?,
    serverLoading: Boolean,
    connStatus: String?,
    serverStatus: String?,
    testing: Boolean,
    savedSummary: String?,
    savedCount: Int,
    storageLabel: String,
    onPrefs: ((AppPrefs) -> AppPrefs) -> Unit,
    onServer: (ServerSettings) -> Unit,
    onTestConnection: () -> Unit,
    onClearSaved: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        ScreenTitle("Settings")

        SectionLabel("Connection")
        Panel {
            ServerUrlField(prefs.serverUrl) { url -> onPrefs { it.copy(serverUrl = url) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onTestConnection, enabled = !testing && prefs.serverUrl.isNotBlank()) {
                    Text("Test")
                }
                if (testing) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else if (connStatus != null) {
                    Hint(connStatus)
                }
            }
            RowDivider()
            SwitchRow("Wi-Fi only", "Never use mobile data", prefs.wifiOnly) { on ->
                onPrefs { it.copy(wifiOnly = on) }
            }
        }

        SectionLabel("This phone")
        Panel {
            SwitchRow("Save videos", "Keep copies for offline playback", prefs.saveOnPhone) { on ->
                onPrefs { it.copy(saveOnPhone = on) }
            }
            SwitchRow("Save new videos automatically", null, prefs.autoSaveNew, enabled = prefs.saveOnPhone) { on ->
                onPrefs { it.copy(autoSaveNew = on) }
            }
            if (prefs.saveOnPhone) {
                RowDivider()
                BackgroundRow()
            }
            RowDivider()
            AfterWatchingRow(
                phone = prefs.deleteAfterWatched,
                server = server?.deleteAfterWatched,
                onPick = { mode ->
                    onPrefs { it.copy(deleteAfterWatched = mode != AfterWatching.KEEP) }
                    if (server != null) {
                        val everywhere = mode == AfterWatching.EVERYWHERE
                        if (server.deleteAfterWatched != everywhere) {
                            onServer(server.copy(deleteAfterWatched = everywhere))
                        }
                    }
                },
            )
            RowDivider()
            StorageFolderRow(
                storageLabel = storageLabel,
                isCustom = prefs.storageUri.isNotBlank(),
                onPick = { uri -> onPrefs { it.copy(storageUri = uri) } },
                onReset = { onPrefs { it.copy(storageUri = "") } },
            )
            if (savedSummary != null) {
                RowDivider()
                StorageRow(savedSummary, savedCount, onClearSaved)
            }
        }

        Row(verticalAlignment = Alignment.Bottom) {
            SectionLabel("Server")
            Spacer(Modifier.weight(1f))
            if (serverStatus != null) Hint(serverStatus, Modifier.padding(bottom = 8.dp))
        }
        when {
            server != null -> ServerPanels(server, onServer)
            serverLoading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            else -> Hint("Connect to a server to change these.")
        }
    }
}

@Composable
private fun ServerPanels(server: ServerSettings, onServer: (ServerSettings) -> Unit) {
    Panel {
        Text("Check for new videos")
        Gap(4)
        RadioRow("When the app opens", server.pollMode == "on_open") {
            onServer(server.copy(pollMode = "on_open"))
        }
        RadioRow("On a schedule", server.pollMode == "periodic") {
            onServer(server.copy(pollMode = "periodic"))
        }
        RadioRow("Every 5 minutes", server.pollMode == "instant") {
            onServer(server.copy(pollMode = "instant"))
        }
        if (server.pollMode == "periodic") {
            // Local while dragging; one save when you let go.
            var drag by remember(server.pollIntervalMinutes) {
                mutableFloatStateOf(server.pollIntervalMinutes.toFloat())
            }
            Gap(4)
            ValueLabel("Every", intervalLabel(drag.toInt()))
            Slider(
                value = drag,
                onValueChange = { drag = ((it / 5f).roundToInt() * 5).coerceIn(5, 720).toFloat() },
                onValueChangeFinished = {
                    if (drag.toInt() != server.pollIntervalMinutes) {
                        onServer(server.copy(pollIntervalMinutes = drag.toInt()))
                    }
                },
                valueRange = 5f..720f,
            )
        }
        RowDivider()
        val resIdx = nearestIndex(RESOLUTION_OPTIONS, server.maxResolution)
        ValueLabel("Max quality", resolutionLabel(RESOLUTION_OPTIONS[resIdx]))
        StepSlider(RESOLUTION_OPTIONS, resIdx) { h ->
            if (h != server.maxResolution) onServer(server.copy(maxResolution = h))
        }
        RowDivider()
        // Snap any legacy value (e.g. from the old linear slider) to the nearest step.
        val keepIdx = nearestIndex(RETENTION_OPTIONS, server.keepDays)
        ValueLabel("Keep downloads", retentionLabel(RETENTION_OPTIONS[keepIdx]))
        StepSlider(RETENTION_OPTIONS, keepIdx) { d ->
            if (d != server.keepDays) onServer(server.copy(keepDays = d))
        }
        RowDivider()
        Text("Backfill")
        Hint("Also fetch older uploads from each channel")
        Gap()
        ChipGrid(
            cats = BACKFILL_OPTIONS.map { (d, label) -> d.toString() to label },
            selected = setOf(server.backfillDays.toString()),
            onToggle = { onServer(server.copy(backfillDays = it.toInt())) },
        )
        Gap(4)
    }

    SectionLabel("SponsorBlock")
    Panel {
        Text("Remove")
        Gap()
        ChipGrid(
            cats = SB_CATS.filter { it.first != "poi_highlight" },
            selected = splitSb(server.sponsorblockRemove),
            onToggle = { onServer(toggleRemove(server, it)) },
        )
        RowDivider()
        Text("Mark as chapters")
        Gap()
        ChipGrid(
            cats = SB_CATS,
            selected = if (server.sponsorblockMark == "all") SB_ALL.toSet()
            else splitSb(server.sponsorblockMark),
            onToggle = { onServer(toggleMark(server, it)) },
        )
        Gap(4)
    }
}

private val BACKFILL_OPTIONS = listOf(0 to "Off", 5 to "5 days", 7 to "7 days", 30 to "30 days", 90 to "90 days")

/**
 * The field owns its text while you type. Saving every keystroke started a
 * sync against each half-typed address ("h", "ht", "htt"…); now it saves
 * once you pause, and when you leave the screen.
 */
@Composable
private fun ServerUrlField(stored: String, onCommit: (String) -> Unit) {
    var urlText by remember { mutableStateOf(stored) }
    var urlEdited by remember { mutableStateOf(false) }
    val commit by rememberUpdatedState(onCommit)
    LaunchedEffect(stored) { if (!urlEdited) urlText = stored }
    LaunchedEffect(urlText) {
        if (!urlEdited) return@LaunchedEffect
        delay(800)
        commit(urlText.trim())
    }
    DisposableEffect(Unit) {
        onDispose { if (urlEdited) commit(urlText.trim()) }
    }
    OutlinedTextField(
        value = urlText,
        onValueChange = {
            urlText = it
            urlEdited = true
        },
        label = { Text("Server address") },
        placeholder = { Text("http://192.168.1.10:8000") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

enum class AfterWatching(val label: String) {
    KEEP("Keep"), PHONE("Delete from phone"), EVERYWHERE("Delete from phone and server")
}

/**
 * One choice for what happens to a watched video. It used to be two
 * switches on two cards (phone, server), and it wasn't obvious that the
 * phone one is what frees phone storage. The phone copy goes when the
 * player closes, never mid-play.
 */
@Composable
private fun AfterWatchingRow(phone: Boolean, server: Boolean?, onPick: (AfterWatching) -> Unit) {
    val current = when {
        server == true -> AfterWatching.EVERYWHERE
        phone -> AfterWatching.PHONE
        else -> AfterWatching.KEEP
    }
    Text("After watching")
    Gap(4)
    for (mode in AfterWatching.entries) {
        // The server half can only change while the server is reachable.
        val enabled = server != null || mode != AfterWatching.EVERYWHERE
        RadioRow(mode.label, mode == current, enabled) { onPick(mode) }
    }
}

/**
 * Saves run in the background either way, but while Android's battery
 * optimization covers the app they're cut off after ~10 minutes and wait
 * for the next slot. Allowing it lets a save run to the end with a
 * progress notification (so notifications are asked for too).
 */
@Composable
private fun BackgroundRow() {
    val context = LocalContext.current
    val power = remember { context.getSystemService(PowerManager::class.java) }
    var allowed by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        allowed = power.isIgnoringBatteryOptimizations(context.packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Background downloads")
            Hint(if (allowed) "On" else "Limited by battery settings")
        }
        if (!allowed) {
            TextButton(onClick = {
                runCatching {
                    ask.launch(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}"),
                        )
                    )
                }.onFailure {
                    // No direct prompt on this phone: open the app list instead.
                    ask.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }) { Text("Allow") }
        }
    }
}

@Composable
private fun StorageFolderRow(
    storageLabel: String,
    isCustom: Boolean,
    onPick: (String) -> Unit,
    onReset: () -> Unit,
) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            VideoStore.takeTreePermission(context, uri)
            onPick(uri.toString())
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Folder")
            Hint(storageLabel)
        }
        if (isCustom) TextButton(onClick = onReset) { Text("Reset") }
        TextButton(onClick = { picker.launch(null) }) { Text("Change") }
    }
}

@Composable
private fun StorageRow(summary: String, savedCount: Int, onClear: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Storage")
            Hint(summary)
        }
        if (savedCount > 0) TextButton(onClick = { confirm = true }) { Text("Clear") }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete saved videos?") },
            text = { Text("$savedCount videos will be removed from this phone. They stay on the server.") },
            confirmButton = {
                TextButton(onClick = { confirm = false; onClear() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun RowDivider() = HorizontalDivider(
    Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant,
)

/** Label on the left, current value on the right (above a slider). */
@Composable
private fun ValueLabel(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f))
        Text(value, color = Muted)
    }
}

@Composable
private fun StepSlider(options: List<Int>, index: Int, onPick: (Int) -> Unit) {
    Slider(
        value = index.toFloat(),
        onValueChange = { onPick(options[it.roundToInt().coerceIn(options.indices)]) },
        valueRange = 0f..(options.size - 1).toFloat(),
        steps = options.size - 2,
    )
}

@Composable
private fun SwitchRow(
    label: String,
    desc: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = if (enabled) Color.Unspecified else Muted)
            if (desc != null) Hint(desc)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, enabled: Boolean = true, onPick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onPick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled, modifier = Modifier.padding(8.dp))
        Text(label, Modifier.padding(start = 8.dp), color = if (enabled) Color.Unspecified else Muted)
    }
}

@Composable
private fun ChipGrid(
    cats: List<Pair<String, String>>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    // Plain rows (not lazy): this screen already scrolls, and nested lazy
    // grids fight the outer scroll + mis-measure their height.
    Column {
        for (row in cats.chunked(3)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                for ((id, label) in row) {
                    FilterChip(
                        selected = id in selected,
                        onClick = { onToggle(id) },
                        label = { Text(label, maxLines = 1) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
