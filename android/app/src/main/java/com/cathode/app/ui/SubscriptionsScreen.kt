package com.cathode.app.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.cathode.app.data.SubInput
import com.cathode.app.data.addChannel
import com.cathode.app.data.listSubscriptions
import com.cathode.app.data.removeChannel
import com.cathode.app.data.uploadSubscriptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun originLabel(origin: String) = when (origin) {
    "opml" -> "OPML import"
    "takeout" -> "Takeout import"
    "manual" -> "Added"
    else -> null
}

/**
 * The channels the server follows: add one by link, or import a
 * subscriptions export (Google Takeout CSV, OPML, or a plain .txt list).
 */
@Composable
fun SubscriptionsScreen(serverUrl: String, lineup: Lineup = Lineup(emptyList())) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var inputs by remember { mutableStateOf<List<SubInput>>(emptyList()) }
    // Channel names the server has resolved, by source URL (hand-added
    // entries carry no title of their own).
    var titles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var newUrl by remember { mutableStateOf("") }
    var showHelp by remember { mutableStateOf(false) }

    fun reload() {
        if (serverUrl.isBlank()) return
        loading = true
        scope.launch(Dispatchers.IO) {
            val resp = runCatching { listSubscriptions(serverUrl) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (resp != null) {
                    inputs = resp.inputs
                    titles = resp.indexed.mapNotNull { c -> c.title?.let { c.url to it } }.toMap()
                }
                loading = false
            }
        }
    }

    fun runOp(label: String, op: () -> String) {
        busy = true
        message = null
        scope.launch(Dispatchers.IO) {
            val msg = try {
                op()
            } catch (e: Exception) {
                e.message ?: "Failed"
            }
            withContext(Dispatchers.Main) {
                message = msg
                busy = false
            }
            reload()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null || serverUrl.isBlank()) return@rememberLauncherForActivityResult
        runOp("upload") {
            val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
            } ?: "subscriptions.opml"
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in setOf("opml", "xml", "csv", "txt")) {
                return@runOp "Use a .csv, .opml or .txt file"
            }
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@runOp "Can't read that file"
            val resp = uploadSubscriptions(serverUrl, "upload.$ext", bytes)
            if (ext == "txt") "Added ${resp.subscriptions} channels"
            else "Imported ${resp.subscriptions} channels"
        }
    }

    LaunchedEffect(serverUrl) { reload() }

    val ready = serverUrl.isNotBlank()

    fun add() {
        val u = newUrl.trim()
        if (u.isEmpty()) return
        newUrl = ""
        runOp("add") {
            val r = addChannel(serverUrl, u)
            if (r.added) "Added" else "Already added"
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Glass)
            .verticalScroll(rememberScrollState())
    ) {
        ColorBars(height = 3.dp)
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 18.dp)) {
            ScreenTitle("Lineup", kicker = if (inputs.isEmpty()) "Channels" else "${inputs.size} channels")
            Gap(18)

            if (!ready) {
                Hint("Set a server address in Setup first.", color = BarYellow)
                Gap(16)
            }

            OutlinedTextField(
                value = newUrl,
                onValueChange = { newUrl = it },
                placeholder = { Text("Channel link or @handle") },
                label = { Osd("TUNE IN", size = 16.sp, glow = false, color = Phosphor) },
                singleLine = true,
                enabled = ready,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                trailingIcon = {
                    IconButton(onClick = { add() }, enabled = !busy && ready && newUrl.isNotBlank()) {
                        Icon(Icons.Default.Add, contentDescription = "Add channel")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { picker.launch("*/*") }, enabled = !busy && ready) {
                    Text("Import file")
                }
                IconButton(onClick = { showHelp = true }) {
                    Icon(Icons.Outlined.Info, contentDescription = "How to import", tint = Muted)
                }
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else if (message != null) {
                    Osd(message!!.uppercase(), size = 17.sp, glow = false)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("On the dial")
                Spacer(Modifier.weight(1f))
                if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            if (inputs.isEmpty() && !loading) Hint("No channels yet.")
        }
        // Numbered like a cable lineup card; channels that haven't
        // broadcast anything yet sit at the end without a number.
        val all = inputs.map { it to (it.title ?: titles[it.sourceUrl] ?: it.channelId ?: it.sourceUrl) }
        val (onAir, offAir) = all.partition { lineup.number(it.second) != null }
        for ((s, name) in onAir.sortedBy { lineup.number(it.second) }) {
            ChannelRow(s, name, lineup.label(name), channelColor(name), busy) {
                runOp("remove") { removeChannel(serverUrl, s.sourceUrl); "Removed" }
            }
        }
        if (offAir.isNotEmpty()) {
            // Followed, but nothing kept on the server right now.
            SectionLabel("Off air", Modifier.padding(horizontal = 20.dp), color = Faint)
            for ((s, name) in offAir.sortedBy { it.second.lowercase() }) {
                ChannelRow(s, name, null, Faint, busy) {
                    runOp("remove") { removeChannel(serverUrl, s.sourceUrl); "Removed" }
                }
            }
        }
        Gap(24)
    }
    if (showHelp) ImportHelp(onDismiss = { showHelp = false })
}

@Composable
private fun ChannelRow(s: SubInput, name: String, number: String?, color: Color, busy: Boolean, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null) {
            Osd(number, color = color, size = 30.sp, modifier = Modifier.width(44.dp))
            Box(Modifier.size(width = 3.dp, height = 30.dp).background(color))
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (number != null) Ink else Muted,
            )
            originLabel(s.origin)?.let { Hint(it) }
        }
        // Imported channels come and go with their file.
        if (s.origin == "manual") {
            IconButton(onClick = onRemove, enabled = !busy) {
                Icon(Icons.Default.Close, contentDescription = "Remove", tint = Muted)
            }
        }
    }
    Box(Modifier.padding(start = 20.dp).fillMaxWidth().height(1.dp).background(Hairline))
}

@Composable
private fun ImportHelp(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import your subscriptions") },
        text = {
            Text(
                "1. On a computer, open takeout.google.com.\n" +
                    "2. Deselect all, then select YouTube and YouTube Music.\n" +
                    "3. Under All YouTube data included, keep only subscriptions.\n" +
                    "4. Create the export, download it and unzip. You want " +
                    "subscriptions.csv.\n" +
                    "5. Get it onto this phone and tap Import file.\n\n" +
                    "An OPML export from another app works too. A new CSV or OPML " +
                    "replaces the last one; a .txt list of channel links adds to it."
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}
