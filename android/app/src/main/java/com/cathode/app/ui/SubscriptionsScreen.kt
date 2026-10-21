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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
    "opml" -> "Export file"
    "takeout" -> "Takeout"
    "manual" -> "Added by you"
    else -> "Tracked"
}

/**
 * Tells you how to point the server at your subscriptions, and lets you
 * manage them by hand: paste a channel link, or upload an export file.
 */
@Composable
fun SubscriptionsScreen(serverUrl: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var inputs by remember { mutableStateOf<List<SubInput>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var newUrl by remember { mutableStateOf("") }

    fun reload() {
        if (serverUrl.isBlank()) return
        loading = true
        scope.launch(Dispatchers.IO) {
            val list = runCatching { listSubscriptions(serverUrl).inputs }.getOrNull()
            withContext(Dispatchers.Main) {
                if (list != null) inputs = list
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
                "Failed: ${e.message}"
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
                return@runOp "Pick an .opml, .csv, or .txt export file."
            }
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@runOp "Could not read that file."
            val resp = uploadSubscriptions(serverUrl, "upload.$ext", bytes)
            "Saved ($resp)"
        }
    }

    LaunchedEffect(serverUrl) { reload() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Channels", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))

        if (serverUrl.isBlank()) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Set your server URL first", color = AccentTeal)
                Text(
                    "The buttons below wake up once the app knows where your server is — " +
                        "enter it in Settings, then come back here.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Text("How to add everything at once")
            Text(
                "1. On a computer, open Google Takeout, export YouTube subscriptions.\n" +
                    "2. You'll get a subscriptions file (.opml or .csv).\n" +
                    "3. Upload it below — the server starts tracking every channel.",
                style = MaterialTheme.typography.bodySmall, color = Color.Gray,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { picker.launch("*/*") }, enabled = !busy && serverUrl.isNotBlank()) {
                Text("Upload subscriptions file")
            }
        }
        Spacer(Modifier.height(12.dp))

        GlassCard(Modifier.fillMaxWidth()) {
            Text("Add one channel")
            OutlinedTextField(
                value = newUrl,
                onValueChange = { newUrl = it },
                label = { Text("Any link, @handle, or ID") },
                placeholder = { Text("https://www.youtube.com/@example") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val u = newUrl.trim()
                    if (u.isNotEmpty()) {
                        newUrl = ""
                        runOp("add") { addChannel(serverUrl, u); "Now tracking $u" }
                    }
                },
                enabled = !busy && serverUrl.isNotBlank(),
            ) { Text("Track channel") }
        }
        Spacer(Modifier.height(12.dp))

        if (message != null) {
            Text(message!!, style = MaterialTheme.typography.bodySmall, color = AccentTeal)
            Spacer(Modifier.height(8.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Tracked (${inputs.size})", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            if (loading) CircularProgressIndicator(strokeWidth = 2.dp)
        }
        Spacer(Modifier.height(8.dp))
        for (s in inputs) {
            GlassCard(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.title ?: s.channelId ?: s.sourceUrl,
                            style = MaterialTheme.typography.titleSmall)
                        Text(originLabel(s.origin),
                            style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    if (s.origin == "manual") {
                        IconButton(
                            onClick = { runOp("remove") { removeChannel(serverUrl, s.sourceUrl); "Stopped tracking" } },
                            enabled = !busy,
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Stop tracking")
                        }
                    }
                }
            }
        }
        if (inputs.isEmpty() && !loading) {
            Text("Nothing tracked yet — upload an export or add a channel above.",
                style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        Spacer(Modifier.height(24.dp))
    }
}
