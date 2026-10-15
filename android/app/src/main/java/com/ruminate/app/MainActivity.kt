package com.ruminate.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subscriptions
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
import androidx.media3.common.util.UnstableApi
import com.ruminate.app.data.AppPrefs
import com.ruminate.app.data.OfflineRepo
import com.ruminate.app.data.PrefsRepo
import com.ruminate.app.data.SaveState
import com.ruminate.app.data.ServerSettings
import com.ruminate.app.data.Video
import com.ruminate.app.data.checkHealth
import com.ruminate.app.data.fetchFeed
import com.ruminate.app.data.getServerSettings
import com.ruminate.app.data.isUnmetered
import com.ruminate.app.data.putServerSettings
import com.ruminate.app.player.PlayerHolder
import com.ruminate.app.ui.LibraryScreen
import com.ruminate.app.ui.PlayerScreen
import com.ruminate.app.ui.RuminateTheme
import com.ruminate.app.ui.SettingsScreen
import com.ruminate.app.ui.SubscriptionsScreen
import com.ruminate.app.ui.formatBytes
import com.ruminate.app.worker.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.RequestBody.Companion.toRequestBody

@UnstableApi
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
                var savedSummary by remember { mutableStateOf<String?>(null) }
                val saveStates by OfflineRepo.states.collectAsState()
                val wifiBlocked = prefs.wifiOnly && !isUnmetered(this)
                val canUseNetwork = prefs.serverUrl.isNotBlank() &&
                    (!prefs.wifiOnly || isUnmetered(this@MainActivity))

                fun streamUrl(v: Video) = "${prefs.serverUrl}/api/videos/${v.id}/stream"

                fun saveVideo(v: Video) {
                    if (!prefs.saveOnPhone || !canUseNetwork) return
                    scope.launch {
                        OfflineRepo.save(this@MainActivity, v.id, streamUrl(v))
                    }
                }

                fun refresh() {
                    if (!canUseNetwork) return
                    loading = true
                    scope.launch(Dispatchers.IO) {
                        val feed = runCatching { fetchFeed(prefs.serverUrl) }.getOrDefault(emptyList())
                        withContext(Dispatchers.Main) {
                            videos = feed
                            loading = false
                            // Save new arrivals on this phone when the app opens.
                            // First run only sets the baseline (no surprise mass download);
                            // afterwards, only videos newer than the last open are saved.
                            if (prefs.saveOnPhone && prefs.autoSaveNew) {
                                val maxPub = feed.maxOfOrNull { it.published } ?: 0
                                val base = prefs.autoSaveBaseline
                                scope.launch {
                                    if (base == 0L) {
                                        prefsRepo.setBaseline(maxPub)
                                    } else {
                                        feed.filter {
                                            it.watched == 0 && it.published > base &&
                                                OfflineRepo.stateOf(
                                                    PlayerHolder.getDownloadManager(this@MainActivity),
                                                    it.id,
                                                ) is SaveState.NotSaved
                                        }.forEach { saveVideo(it) }
                                        prefsRepo.setBaseline(maxPub)
                                    }
                                }
                            }
                        }
                    }
                }

                fun refreshSavedSummary() {
                    scope.launch(Dispatchers.Main) {
                        runCatching {
                            val m = PlayerHolder.getDownloadManager(this@MainActivity)
                            val n = OfflineRepo.savedCount(m)
                            val b = OfflineRepo.savedBytes()
                            savedSummary = if (n == 0) "Nothing saved on this phone yet."
                            else "$n videos · ${formatBytes(b)} on this phone (max 16 GB)."
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

                // DownloadManager + listener live on the main thread; attach once.
                LaunchedEffect(Unit) {
                    OfflineRepo.attach(PlayerHolder.getDownloadManager(this@MainActivity))
                    SyncWorker.runOnOpen(this@MainActivity)
                    SyncWorker.schedulePeriodic(this@MainActivity, prefs.pollIntervalMin, prefs.pollMode)
                }
                // (Re)load the library once the server URL is known. Without
                // this the first launch shows an empty library: prefs load
                // async, so an early refresh() runs with a blank URL.
                LaunchedEffect(prefs.serverUrl) {
                    refresh()
                    if (tab == 3) {
                        loadServer()
                        refreshSavedSummary()
                    }
                }
                // Load full server config whenever the Settings tab opens.
                LaunchedEffect(tab) {
                    if (tab == 3) {
                        loadServer()
                        refreshSavedSummary()
                    }
                }

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = tab == 0, onClick = { tab = 0; open = null },
                                icon = { Icon(Icons.Default.Home, null) }, label = { Text0("Library") },
                            )
                            NavigationBarItem(
                                selected = tab == 1, onClick = { tab = 1; open = null },
                                icon = { Icon(Icons.Default.Subscriptions, null) }, label = { Text0("Channels") },
                            )
                            NavigationBarItem(
                                selected = tab == 2, onClick = { tab = 2 },
                                icon = { Icon(Icons.Default.PlayArrow, null) }, label = { Text0("Playing") },
                            )
                            NavigationBarItem(
                                selected = tab == 3, onClick = { tab = 3 },
                                icon = { Icon(Icons.Default.Settings, null) }, label = { Text0("Settings") },
                            )
                        }
                    }
                ) { pad ->
                    androidx.compose.foundation.layout.Box(Modifier.padding(pad)) {
                        when {
                            open != null && tab != 3 -> PlayerScreen(
                                video = open!!,
                                streamUrl = streamUrl(open!!),
                                saveState = saveStates[open!!.id]
                                    ?: runCatching {
                                        OfflineRepo.stateOf(
                                            PlayerHolder.getDownloadManager(this@MainActivity),
                                            open!!.id,
                                        )
                                    }.getOrDefault(SaveState.NotSaved),
                                showSave = prefs.saveOnPhone && !wifiBlocked,
                                onSave = { saveVideo(open!!) },
                                onRemoveSaved = {
                                    OfflineRepo.remove(
                                        PlayerHolder.getDownloadManager(this@MainActivity), open!!.id
                                    )
                                },
                                onWatched = {
                                    val v = open!!
                                    scope.launch(Dispatchers.IO) {
                                        runCatching {
                                            okhttp3.OkHttpClient().newCall(
                                                okhttp3.Request.Builder()
                                                    .url("${prefs.serverUrl}/api/videos/${v.id}/watched?watched=true")
                                                    .post(ByteArray(0).toRequestBody(null))
                                                    .build()
                                            ).execute().close()
                                        }
                                        if (prefs.deleteAfterWatched) {
                                            withContext(Dispatchers.Main) {
                                                OfflineRepo.remove(
                                                    PlayerHolder.getDownloadManager(this@MainActivity), v.id
                                                )
                                            }
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
                                saveStates = saveStates,
                                showSaveButtons = prefs.saveOnPhone,
                                onRefresh = {
                                    SyncWorker.runOnOpen(this@MainActivity)
                                    refresh()
                                },
                                onOpen = { open = it; tab = 2 },
                                onToggleWatched = { open = it; tab = 2 },
                                onSave = { saveVideo(it) },
                                onRemoveSaved = {
                                    OfflineRepo.remove(
                                        PlayerHolder.getDownloadManager(this@MainActivity), it.id
                                    )
                                },
                                onSaveAll = { videos.forEach { saveVideo(it) } },
                            )
                            tab == 1 -> SubscriptionsScreen(serverUrl = prefs.serverUrl)
                            tab == 3 -> SettingsScreen(
                                prefs = prefs,
                                server = server,
                                serverLoading = serverLoading,
                                connStatus = connStatus,
                                testing = testing,
                                savedSummary = savedSummary,
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
                                            // Keep phone + server polling in sync.
                                            putServerSettings(
                                                prefs.serverUrl,
                                                s.copy(
                                                    pollMode = prefs.pollMode,
                                                    pollIntervalMinutes = prefs.pollIntervalMin,
                                                ),
                                            )
                                        }.isSuccess
                                        withContext(Dispatchers.Main) {
                                            connStatus = if (ok) "Server settings saved."
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
                                        refreshSavedSummary()
                                    }
                                },
                                onClearSaved = {
                                    scope.launch(Dispatchers.Main) {
                                        OfflineRepo.clearAll(
                                            PlayerHolder.getDownloadManager(this@MainActivity)
                                        )
                                        refreshSavedSummary()
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
