package com.ruminate.app.ui

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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import com.ruminate.app.data.AppPrefs
import com.ruminate.app.data.ServerSettings

/**
 * Everything configurable from the app: connection (LAN or Tailscale URL),
 * phone-side sync rules, and the full NAS server config (polling, quality,
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
    onPrefs: (AppPrefs) -> Unit,
    onServer: (ServerSettings) -> Unit,
    onSaveServer: () -> Unit,
    onTestConnection: () -> Unit,
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
            Text("NAS server")
            OutlinedTextField(
                value = prefs.serverUrl,
                onValueChange = { onPrefs(prefs.copy(serverUrl = it)) },
                label = { Text("Server URL") },
                placeholder = { Text("https://nas.<tailnet>.ts.net:8443") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "On home Wi-Fi use http://<nas>:8095; away from home use your " +
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
                "On-open always triggers a NAS poll too. Instant clamps the server to ≥5 min. " +
                    "Saving below pushes both phone schedule and server config.",
                style = MaterialTheme.typography.bodySmall, color = Color.Gray,
            )
        }
        Spacer(Modifier.height(12.dp))

        GlassCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-download new uploads")
                    Text(
                        "NAS grabs new videos the moment they drop.",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                    )
                }
                Switch(checked = prefs.autoDownload, onCheckedChange = { onPrefs(prefs.copy(autoDownload = it)) })
            }
        }
        Spacer(Modifier.height(12.dp))

        Text("NAS server config", style = MaterialTheme.typography.titleMedium)
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
                Text("SponsorBlock")
                OutlinedTextField(
                    value = server.sponsorblockRemove,
                    onValueChange = { onServer(server.copy(sponsorblockRemove = it)) },
                    label = { Text("Remove (cut out)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = server.sponsorblockMark,
                    onValueChange = { onServer(server.copy(sponsorblockMark = it)) },
                    label = { Text("Mark (as chapters)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Cut only unambiguous ads; chapter-mark the rest so archive jobs stay fast.",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
            }
            Spacer(Modifier.height(12.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Retention")
                Text(if (server.keepDays <= 0) "Keep forever" else "Keep ${server.keepDays} days")
                Slider(
                    value = server.keepDays.toFloat(),
                    onValueChange = { onServer(server.copy(keepDays = it.toInt().coerceIn(0, 365))) },
                    valueRange = 0f..365f,
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onSaveServer, modifier = Modifier.fillMaxWidth()) {
                Text("Save NAS settings")
            }
        } else {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Enter the server URL above and test the connection to edit NAS settings here.")
            }
        }
        Spacer(Modifier.height(24.dp))
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
