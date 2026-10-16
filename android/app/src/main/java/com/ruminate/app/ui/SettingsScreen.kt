package com.ruminate.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ruminate.app.data.AppPrefs
import com.ruminate.app.data.ServerSettings
import com.ruminate.app.data.VideoStore

/** Retention steps: every day up to a month, then 60 / 90 / 180 / 360. */
val RETENTION_OPTIONS: List<Int> = listOf(0) + (1..30).toList() + listOf(60, 90, 180, 360)

fun retentionLabel(days: Int): String = when {
    days <= 0 -> "Keep forever"
    days == 1 -> "Keep 1 day"
    days < 30 -> "Keep $days days"
    days < 60 -> "Keep about a month"
    days < 90 -> "Keep about 2 months"
    days < 180 -> "Keep about 3 months"
    days < 360 -> "Keep about 6 months"
    else -> "Keep about a year"
}

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

/** Toggle a category in the cut-out list (keeps at least one; drops it from an explicit mark list). */
fun toggleRemove(s: ServerSettings, cat: String): ServerSettings {
    val set = splitSb(s.sponsorblockRemove).toMutableSet()
    if (cat in set) {
        if (set.size > 1) set.remove(cat)
    } else {
        set.add(cat)
    }
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
 * save with PUT /api/settings — no SSH or .env editing needed day to day.
 */
@Composable
fun SettingsScreen(
    prefs: AppPrefs,
    server: ServerSettings?,
    serverLoading: Boolean,
    connStatus: String?,
    testing: Boolean,
    savedSummary: String?,
    storageLabel: String,
    onPrefs: (AppPrefs) -> Unit,
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
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))

        GlassCard(Modifier.fillMaxWidth()) {
            Text("Server")
            OutlinedTextField(
                value = prefs.serverUrl,
                onValueChange = { onPrefs(prefs.copy(serverUrl = it)) },
                label = { Text("Server URL") },
                placeholder = { Text("https://server.<tailnet>.ts.net:8443") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "On home Wi-Fi use http://<server>:8000; away from home use your " +
                    "Tailscale https URL. Either way, sync still obeys Wi-Fi only below.",
                style = MaterialTheme.typography.bodySmall, color = Color.Gray,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onTestConnection, enabled = !testing) {
                    Text("Test connection")
                }
                if (testing) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(start = 12.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
            if (connStatus != null) {
                Text(connStatus, style = MaterialTheme.typography.bodySmall, color = AccentTeal)
            }
        }
        Spacer(Modifier.height(12.dp))

        GlassCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Wi-Fi only")
                    Text(
                        "Never use cellular data. Sync + downloads wait for unmetered Wi-Fi " +
                            "(Tailscale over Wi-Fi is fine; Tailscale over cellular stays paused).",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                    )
                }
                Switch(checked = prefs.wifiOnly, onCheckedChange = { onPrefs(prefs.copy(wifiOnly = it)) })
            }
        }
        Spacer(Modifier.height(12.dp))

        GlassCard(Modifier.fillMaxWidth()) {
            Text("How it checks for new videos")
            PollModeRow("When the app opens", "on_open", prefs.pollMode) { onPrefs(prefs.copy(pollMode = it)) }
            PollModeRow("Every X minutes, in the background", "periodic", prefs.pollMode) { onPrefs(prefs.copy(pollMode = it)) }
            PollModeRow("The moment they drop", "instant", prefs.pollMode) { onPrefs(prefs.copy(pollMode = it)) }
            Text("Check every ${prefs.pollIntervalMin} min")
            Slider(
                value = prefs.pollIntervalMin.toFloat(),
                onValueChange = { onPrefs(prefs.copy(pollIntervalMin = it.toInt().coerceIn(1, 720))) },
                valueRange = 5f..720f,
            )
            Text(
                "Opening the app always triggers a check too. Background checks below " +
                    "15 min become 15 (Android limit); the server still grabs new " +
                    "uploads on its own schedule.",
                style = MaterialTheme.typography.bodySmall, color = Color.Gray,
            )
        }
        Spacer(Modifier.height(12.dp))

        GlassCard(Modifier.fillMaxWidth()) {
            Text("On this phone")
            SwitchRow("Save videos on this phone",
                "Keep offline copies so anything plays with no network at all.",
                prefs.saveOnPhone) { onPrefs(prefs.copy(saveOnPhone = it)) }
            SwitchRow("Save new videos automatically",
                "When the app opens on Wi-Fi, new arrivals are saved in the background.",
                prefs.autoSaveNew) { onPrefs(prefs.copy(autoSaveNew = it)) }
            SwitchRow("Delete phone copy after watching",
                "Frees space automatically once you mark a video watched.",
                prefs.deleteAfterWatched) { onPrefs(prefs.copy(deleteAfterWatched = it)) }
            StorageFolderRow(
                storageLabel = storageLabel,
                isCustom = prefs.storageUri.isNotBlank(),
                onPick = { uri -> onPrefs(prefs.copy(storageUri = uri)) },
                onReset = { onPrefs(prefs.copy(storageUri = "")) },
            )
            if (savedSummary != null) {
                Text(savedSummary, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                OutlinedButton(onClick = onClearSaved) { Text("Clear saved videos") }
            }
        }
        Spacer(Modifier.height(12.dp))

        Text("Server config", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (serverLoading && server == null) {
            CircularProgressIndicator()
        } else if (server != null) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Download quality")
                Text("Max ${server.maxResolution}p")
                Slider(
                    value = server.maxResolution.toFloat(),
                    onValueChange = {
                        onServer(server.copy(maxResolution = it.toInt().coerceIn(360, 2160)))
                    },
                    valueRange = 360f..2160f, steps = 5,
                )
            }
            Spacer(Modifier.height(12.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                Text("What to cut out")
                Text("These segments are removed from downloads.",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                ChipGrid(
                    cats = SB_CATS.filter { it.first != "poi_highlight" },
                    selected = splitSb(server.sponsorblockRemove),
                    onToggle = { onServer(toggleRemove(server, it)) },
                )
            }
            Spacer(Modifier.height(12.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                Text("What to chapter-mark")
                Text("Kept in the video, skippable from the player.",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                ChipGrid(
                    cats = SB_CATS,
                    selected = if (server.sponsorblockMark == "all") SB_ALL.toSet()
                    else splitSb(server.sponsorblockMark),
                    onToggle = { onServer(toggleMark(server, it)) },
                )
            }
            Spacer(Modifier.height(12.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Retention")
                Text(retentionLabel(server.keepDays))
                RetentionSlider(server.keepDays) { onServer(server.copy(keepDays = it)) }
                Text("Backfill recent videos")
                Text("Also grab the backlog from the last N days per channel — not just the newest uploads.",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                BackfillRow(server.backfillDays) { onServer(server.copy(backfillDays = it)) }
                SwitchRow("Delete server file after watching",
                    "Frees server space once you mark a video watched. Off by default.",
                    server.deleteAfterWatched) { onServer(server.copy(deleteAfterWatched = it)) }
            }
        } else {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Enter the server URL above and test the connection to edit server settings here.")
            }
        }
        Spacer(Modifier.height(24.dp))
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
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("Storage folder")
        Text(
            "Videos are saved as plain .mp4 files in $storageLabel — visible in any file manager, never app data.",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { picker.launch(null) }) { Text("Choose folder") }
            if (isCustom) {
                OutlinedButton(onClick = onReset, modifier = Modifier.padding(start = 8.dp)) {
                    Text("Use default")
                }
            }
        }
        Text(
            "Pick internal storage, an SD card, or a USB drive.",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray,
        )
    }
}

@Composable
private fun RetentionSlider(days: Int, onPick: (Int) -> Unit) {
    // Snap any legacy value (e.g. from the old linear slider) to the nearest step.
    val idx = RETENTION_OPTIONS.indices.minByOrNull { kotlin.math.abs(RETENTION_OPTIONS[it] - days) } ?: 0
    Slider(
        value = idx.toFloat(),
        onValueChange = { onPick(RETENTION_OPTIONS[it.toInt().coerceIn(RETENTION_OPTIONS.indices)]) },
        valueRange = 0f..(RETENTION_OPTIONS.size - 1).toFloat(),
        steps = RETENTION_OPTIONS.size - 2,
    )
}

@Composable
private fun RetentionSlider(days: Int, onPick: (Int) -> Unit) {
    // Snap any legacy value (e.g. from the old linear slider) to the nearest step.
    val idx = RETENTION_OPTIONS.indices.minByOrNull { kotlin.math.abs(RETENTION_OPTIONS[it] - days) } ?: 0
    Slider(
        value = idx.toFloat(),
        onValueChange = { onPick(RETENTION_OPTIONS[it.toInt().coerceIn(RETENTION_OPTIONS.indices)]) },
        valueRange = 0f..(RETENTION_OPTIONS.size - 1).toFloat(),
        steps = RETENTION_OPTIONS.size - 2,
    )
}

@Composable
private fun BackfillRow(selected: Int, onPick: (Int) -> Unit) {
    val options = listOf(0 to "Off", 5 to "5 days", 7 to "7 days", 30 to "30 days", 90 to "90 days")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in options.chunked(3)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                for ((days, label) in row) {
                    FilterChip(
                        selected = selected == days,
                        onClick = { onPick(days) },
                        label = { Text(label, maxLines = 1) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PollModeRow(label: String, value: String, selected: String, onPick: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.RadioButton(selected = selected == value, onClick = { onPick(value) })
        Text(label, modifier = Modifier.padding(start = 8.dp))
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
