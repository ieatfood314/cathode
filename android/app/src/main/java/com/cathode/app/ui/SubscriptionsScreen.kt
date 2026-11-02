package com.cathode.app.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
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
            .background(Tube)
            .verticalScroll(rememberScrollState())
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 18.dp)) {
            ScreenTitle("Lineup", kicker = if (inputs.isEmpty()) "Channels" else "${inputs.size} channels")
            Gap(18)

            if (!ready) {
                Osd("! SET A SERVER ADDRESS IN SETUP FIRST", color = P80, size = 19.sp, glow = false)
                Gap(16)
            }

            TunePrompt(
                value = newUrl,
                onValue = { newUrl = it },
                enabled = ready && !busy,
                onAdd = { add() },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { picker.launch("*/*") }, enabled = !busy && ready) {
                    Text("Import file")
                }
                IconButton(onClick = { showHelp = true }) {
                    Icon(Icons.Outlined.Info, contentDescription = "How to import", tint = P60)
                }
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else if (message != null) {
                    Osd(message!!.uppercase(), size = 19.sp, glow = false)
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
            ChannelRow(s, name, lineup.label(name), busy) {
                runOp("remove") { removeChannel(serverUrl, s.sourceUrl); "Removed" }
            }
        }
        if (offAir.isNotEmpty()) {
            // Followed, but nothing kept on the server right now.
            SectionLabel("Off air", Modifier.padding(horizontal = 20.dp), color = P60)
            for ((s, name) in offAir.sortedBy { it.second.lowercase() }) {
                ChannelRow(s, name, null, busy) {
                    runOp("remove") { removeChannel(serverUrl, s.sourceUrl); "Removed" }
                }
            }
        }
        Gap(24)
    }
    if (showHelp) ImportHelp(onDismiss = { showHelp = false })
}

/**
 * "> channel link or @handle  [ADD]": a terminal prompt. ADD lights once
 * there's something to add; tapped while empty it puts the cursor in the
 * field instead of doing nothing.
 */
@Composable
private fun TunePrompt(value: String, onValue: (String) -> Unit, enabled: Boolean, onAdd: () -> Unit) {
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val ready = value.isNotBlank()
    Osd("TUNE IN", color = P60, size = 19.sp, glow = false)
    Gap(4)
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, if (focused) P100 else P40, RoundedCornerShape(4.dp))
            .background(Tube1)
            .clickable(enabled = enabled) { focus.requestFocus() }
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Osd(">", color = if (focused) P100 else P60, size = 26.sp, glow = focused)
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = value,
            onValueChange = onValue,
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(fontFamily = Vt, fontSize = 24.sp, color = P100),
            cursorBrush = SolidColor(P100),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Uri),
            keyboardActions = KeyboardActions(onDone = { if (ready) onAdd() }),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus)
                .onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Osd("CHANNEL LINK OR @HANDLE", color = P40, size = 22.sp, glow = false)
                    inner()
                }
            },
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .border(1.dp, if (ready) P100 else P40)
                .background(if (ready && enabled) P100 else Color.Transparent)
                .clickable(enabled = enabled) { if (ready) onAdd() else focus.requestFocus() }
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Osd("ADD", color = if (ready && enabled) OnPhosphor else P40, size = 22.sp, glow = false)
        }
    }
}

@Composable
private fun ChannelRow(s: SubInput, name: String, number: String?, busy: Boolean, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Osd(number ?: "--", color = if (number != null) P100 else P40, size = 34.sp,
            glow = number != null, modifier = Modifier.width(50.dp))
        Column(Modifier.weight(1f)) {
            Osd(
                name.uppercase(), size = 23.sp, overflow = TextOverflow.Ellipsis,
                color = if (number != null) P100 else P60, glow = false,
            )
            originLabel(s.origin)?.let { Osd(it.uppercase(), color = P40, size = 17.sp, glow = false) }
        }
        // Imported channels come and go with their file.
        if (s.origin == "manual") {
            IconButton(onClick = onRemove, enabled = !busy) {
                Icon(Icons.Default.Close, contentDescription = "Remove", tint = P60)
            }
        }
    }
    Box(Modifier.padding(start = 20.dp).fillMaxWidth().height(1.dp).background(P10))
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
