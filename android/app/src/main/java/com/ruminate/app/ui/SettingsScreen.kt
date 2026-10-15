package com.ruminate.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ruminate.app.data.AppPrefs

@Composable
fun SettingsScreen(
    prefs: AppPrefs,
    serverPollMode: String,
    onPrefs: (AppPrefs) -> Unit,
    onPushServerSettings: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))

        GlassCard(Modifier.fillMaxWidth()) {
            Text("NAS server")
            OutlinedTextField(
                value = prefs.serverUrl,
                onValueChange = { onPrefs(prefs.copy(serverUrl = it)) },
                label = { Text("http://nas.local:8000") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Your NAS address on Wi-Fi / Tailscale. Never exposed publicly by default.",
                style = MaterialTheme.typography.bodySmall, color = Color.Gray,
            )
        }
        Spacer(Modifier.height(12.dp))

        GlassCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Wi-Fi only")
                    Text(
                        "Never use cellular data. Sync + downloads wait for unmetered Wi-Fi.",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                    )
                }
                Switch(checked = prefs.wifiOnly, onCheckedChange = { onPrefs(prefs.copy(wifiOnly = it)) })
            }
        }
        Spacer(Modifier.height(12.dp))

        GlassCard(Modifier.fillMaxWidth()) {
            Text("How it polls for new videos")
            PollModeRow("On app open", "on_open", prefs.pollMode) { onPrefs(prefs.copy(pollMode = it)) }
            PollModeRow("Periodic (background)", "periodic", prefs.pollMode) { onPrefs(prefs.copy(pollMode = it)) }
            PollModeRow("Instant (NAS checks often)", "instant", prefs.pollMode) { onPrefs(prefs.copy(pollMode = it)) }
            Text("Check every ${prefs.pollIntervalMin} min")
            Slider(
                value = prefs.pollIntervalMin.toFloat(),
                onValueChange = { onPrefs(prefs.copy(pollIntervalMin = it.toInt().coerceIn(1, 720))) },
                valueRange = 5f..720f,
            )
            Text(
                "On-open always triggers a NAS poll too. Instant clamps the server to ≥5 min.",
                style = MaterialTheme.typography.bodySmall, color = Color.Gray,
            )
            Button(onClick = onPushServerSettings) { Text("Push polling settings to NAS") }
            Text("NAS is in '$serverPollMode' mode.", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))

        GlassCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-download new uploads")
                    Text("NAS grabs new videos the moment they drop.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Switch(checked = prefs.autoDownload, onCheckedChange = { onPrefs(prefs.copy(autoDownload = it)) })
            }
        }
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
