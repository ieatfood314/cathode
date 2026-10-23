package com.cathode.app.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
fun SubscriptionsScreen(serverUrl: String) {
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
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        ScreenTitle("Channels")
        Gap(16)

        if (!ready) {
            Hint("Set a server address in Settings first.")
            Gap(16)
        }

        OutlinedTextField(
            value = newUrl,
            onValueChange = { newUrl = it },
            placeholder = { Text("Channel link or @handle") },
            singleLine = true,
            enabled = ready,
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
            if (busy) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            } else if (message != null) {
                Hint(message!!)
            }
        }
        // A new .csv or .opml replaces the previous import of that kind; a
        // .txt list adds to what's there.
        Hint("Google Takeout CSV or OPML replaces the last import. TXT adds.",
            Modifier.padding(start = 12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(if (inputs.isEmpty()) "Following" else "Following · ${inputs.size}")
            Spacer(Modifier.weight(1f))
            if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        if (inputs.isEmpty() && !loading) Hint("No channels yet.")
        for (s in inputs) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(s.title ?: titles[s.sourceUrl] ?: s.channelId ?: s.sourceUrl)
                    originLabel(s.origin)?.let { Hint(it) }
                }
                // Imported channels come and go with their file.
                if (s.origin == "manual") {
                    IconButton(
                        onClick = { runOp("remove") { removeChannel(serverUrl, s.sourceUrl); "Removed" } },
                        enabled = !busy,
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Remove", tint = Muted)
                    }
                }
            }
        }
        Gap(16)
    }
}
