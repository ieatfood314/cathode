package com.ruminate.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.ruminate.app.data.AppPrefs
import com.ruminate.app.data.PrefsRepo
import com.ruminate.app.data.ServerSettings
import com.ruminate.app.data.Video
import com.ruminate.app.data.checkHealth
import com.ruminate.app.data.fetchFeed
import com.ruminate.app.data.getServerSettings
import com.ruminate.app.data.isUnmetered
import com.ruminate.app.data.putServerSettings
import com.ruminate.app.ui.LibraryScreen
import com.ruminate.app.ui.PlayerScreen
import com.ruminate.app.ui.RuminateTheme
import com.ruminate.app.ui.SettingsScreen
import com.ruminate.app.worker.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.RequestBody.Companion.toRequestBody

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefsRepo = PrefsRepo(this)

        setContent {
            RuminateTheme {
                val scope = rememberCoroutineScope()
                val prefs by prefsRepo.flow.collectAsState(initial = AppPrefs())
                var tab by remember { mutableStateOf(0) }
                var videos by remember { mutableStateOf<List<Video>>(emptyList()) }
                var loading by remember { mutableStateOf(false) }
                var open: Video? by remember { mutableStateOf(null) }
                var server by remember { mutableStateOf<ServerSettings?>(null) }
                var serverLoading by remember { mutableStateOf(false) }
                var connStatus by remember { mutableStateOf<String?>(null) }
                var testing by remember { mutableStateOf(false) }
                val wifiBlocked = prefs.wifiOnly && !isUnmetered(this)

                fun refresh() {
                    if (prefs.serverUrl.isBlank()) return
                    if (prefs.wifiOnly && !isUnmetered(this@MainActivity)) return // no cellular
                    loading = true
                    scope.launch(Dispatchers.IO) {
                        val feed = runCatching { fetchFeed(prefs.serverUrl) }.getOrDefault(emptyList())
                        withContext(Dispatchers.Main) {
                            videos = feed
                            loading = false
                        }
                    }
                }

                fun loadServer() {
                    if (prefs.serverUrl.isBlank()) return
                    if (prefs.wifiOnly && !isUnmetered(this@MainActivity)) {
                        connStatus = "Waiting for Wi-Fi (Wi-Fi only is ON)."
                        return
                    }
                    serverLoading = true
                    scope.launch(Dispatchers.IO) {
                        val s = runCatching { getServerSettings(prefs.serverUrl) }.getOrNull()
                        withContext(Dispatchers.Main) {
                            server = s
                            serverLoading = false
                            if (s == null) connStatus = "Could not reach the server."
                        }
                    }
                }

                // Auto-download the moment the app opens: trigger NAS poll via WorkManager.
                LaunchedEffect(Unit) {
                    SyncWorker.runOnOpen(this@MainActivity)
                    SyncWorker.schedulePeriodic(this@MainActivity, prefs.pollIntervalMin, prefs.pollMode)
                    refresh()
                }
                // Load full NAS config whenever the Settings tab opens.
                LaunchedEffect(tab) {
                    if (tab == 2) loadServer()
                }

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = tab == 0, onClick = { tab = 0; open = null },
                                icon = { Icon(Icons.Default.Home, null) }, label = { Text0("Library") },
                            )
                            NavigationBarItem(
                                selected = tab == 1, onClick = { tab = 1 },
                                icon = { Icon(Icons.Default.PlayArrow, null) }, label = { Text0("Now playing") },
                            )
                            NavigationBarItem(
                                selected = tab == 2, onClick = { tab = 2 },
                                icon = { Icon(Icons.Default.Settings, null) }, label = { Text0("Settings") },
                            )
                        }
                    }
                ) { pad ->
                    androidx.compose.foundation.layout.Box(Modifier.padding(pad)) {
                        when {
                            open != null && tab != 2 -> PlayerScreen(
                                video = open!!,
                                streamUrl = "${prefs.serverUrl}/api/videos/${open!!.id}/stream",
                                onWatched = {
                                    scope.launch(Dispatchers.IO) {
                                        runCatching {
                                            okhttp3.OkHttpClient().newCall(
                                                okhttp3.Request.Builder()
                                                    .url("${prefs.serverUrl}/api/videos/${open!!.id}/watched?watched=true")
                                                    .post(ByteArray(0).toRequestBody(null))
                                                    .build()
                                            ).execute().close()
                                        }
                                        refresh()
                                    }
                                },
                            )
                            tab == 0 -> LibraryScreen(
                                videos = videos,
                                thumbUrl = { v -> "${prefs.serverUrl}/api/thumbs/${v.id}" },
                                loading = loading,
                                wifiBlocked = wifiBlocked,
                                onRefresh = {
                                    SyncWorker.runOnOpen(this@MainActivity)
                                    refresh()
                                },
                                onOpen = { open = it; tab = 1 },
                                onToggleWatched = { open = it; tab = 1 },
                            )
                            tab == 2 -> SettingsScreen(
                                prefs = prefs,
                                server = server,
                                serverLoading = serverLoading,
                                connStatus = connStatus,
                                testing = testing,
                                onPrefs = { np ->
                                    scope.launch {
                                        prefsRepo.update(np)
                                        SyncWorker.schedulePeriodic(
                                            this@MainActivity, np.pollIntervalMin, np.pollMode
                                        )
                                    }
                                },
                                onServer = { server = it },
                                onSaveServer = {
                                    val s = server ?: return@SettingsScreen
                                    scope.launch(Dispatchers.IO) {
                                        val ok = runCatching {
                                            // Keep phone + NAS polling in sync.
                                            putServerSettings(
                                                prefs.serverUrl,
                                                s.copy(
                                                    pollMode = prefs.pollMode,
                                                    pollIntervalMinutes = prefs.pollIntervalMin,
                                                ),
                                            )
                                        }.isSuccess
                                        withContext(Dispatchers.Main) {
                                            connStatus = if (ok) "NAS settings saved."
                                            else "Save failed — check connection."
                                        }
                                    }
                                },
                                onTestConnection = {
                                    testing = true
                                    scope.launch(Dispatchers.IO) {
                                        val msg = try {
                                            val h = checkHealth(prefs.serverUrl)
                                            val n = runCatching { fetchFeed(prefs.serverUrl).size }.getOrDefault(-1)
                                            "Connected. " + if (n >= 0) "$n videos indexed." else h.take(120)
                                        } catch (e: Exception) {
                                            "Failed: ${e.message}"
                                        }
                                        withContext(Dispatchers.Main) {
                                            connStatus = msg
                                            testing = false
                                        }
                                        loadServer()
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun Text0(s: String) {
    androidx.compose.material3.Text(s)
}
