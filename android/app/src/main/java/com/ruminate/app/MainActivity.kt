package com.ruminate.app

import android.net.Uri
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
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.util.UnstableApi
import com.ruminate.app.data.AppPrefs
import com.ruminate.app.data.OfflineRepo
import com.ruminate.app.data.PrefsRepo
import com.ruminate.app.data.SaveState
import com.ruminate.app.data.ServerSettings
import com.ruminate.app.data.StoreLocation
import com.ruminate.app.data.Video
import com.ruminate.app.data.VideoStore
import com.ruminate.app.data.checkHealth
import com.ruminate.app.data.fetchFeed
import com.ruminate.app.data.getServerSettings
import com.ruminate.app.data.isUnmetered
import com.ruminate.app.data.putServerSettings
import com.ruminate.app.data.resolveStore
import com.ruminate.app.ui.LibraryScreen
import com.ruminate.app.ui.PlayerScreen
import com.ruminate.app.ui.RuminateTheme
import com.ruminate.app.ui.SettingsScreen
import com.ruminate.app.ui.SubscriptionsScreen
import com.ruminate.app.ui.formatBytes
import com.ruminate.app.worker.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

@UnstableApi
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefsRepo = PrefsRepo(this)

        setContent {
            RuminateTheme {
                val scope = rememberCoroutineScope()
                val appContext = LocalContext.current.applicationContext
                val prefs by prefsRepo.flow.collectAsState(initial = AppPrefs())
                var tab by remember { mutableStateOf(0) }
                var videos by remember { mutableStateOf<List<Video>>(emptyList()) }
                var loading by remember { mutableStateOf(false) }
                var open: Video? by remember { mutableStateOf(null) }
                var openUri by remember { mutableStateOf<Uri?>(null) }
                var server by remember { mutableStateOf<ServerSettings?>(null) }
                var serverDirty by remember { mutableStateOf(false) }
                var serverDirty by remember { mutableStateOf(false) }
                var serverLoading by remember { mutableStateOf(false) }
                var connStatus by remember { mutableStateOf<String?>(null) }
                var testing by remember { mutableStateOf(false) }
                var savedSummary by remember { mutableStateOf<String?>(null) }
                var storageLabel by remember { mutableStateOf("Movies/Ruminate") }
                val saveStates by OfflineRepo.states.collectAsState()
                val wifiBlocked = prefs.wifiOnly && !isUnmetered(this)
                val canUseNetwork = prefs.serverUrl.isNotBlank() &&
                    (!prefs.wifiOnly || isUnmetered(this@MainActivity))
                val store = remember(prefs.storageUri) {
                    resolveStore(this@MainActivity, prefs.storageUri)
                }

                fun streamUrl(v: Video) = "${prefs.serverUrl}/api/videos/${v.id}/stream"

                fun updatePhoneSummary(n: Int, b: Long) {
                    savedSummary = if (n == 0) "Nothing saved on this phone yet."
                    else "$n videos · ${formatBytes(b)} saved as files."
                    storageLabel = when (val s = store) {
                        is StoreLocation.DefaultMovies -> "Movies/Ruminate"
                        is StoreLocation.CustomFolder -> s.label
                    }
                }

                fun saveVideo(v: Video) {
                    if (!prefs.saveOnPhone || !canUseNetwork) return
                    scope.launch(Dispatchers.IO) {
                        OfflineRepo.save(appContext, store, v, streamUrl(v), prefs.wifiOnly)
                    }
                }

                fun refresh() {
                    if (!canUseNetwork) return
                    loading = true
                    scope.launch(Dispatchers.IO) {
                        val feed = runCatching { fetchFeed(prefs.serverUrl) }.getOrDefault(emptyList())
                        val (n, b) = OfflineRepo.rescan(appContext, store)
                        withContext(Dispatchers.Main) {
                            videos = feed
                            loading = false
                            updatePhoneSummary(n, b)
                            // Save new arrivals on this phone when the app opens.
                            // First run only sets the baseline (no surprise mass download);
                            // afterwards, only videos newer than the last open are saved.
                            if (prefs.saveOnPhone && prefs.autoSaveNew) {
                                val maxPub = feed.maxOfOrNull { it.published } ?: 0
                                val base = prefs.autoSaveBaseline
                                scope.launch(Dispatchers.IO) {
                                    if (base == 0L) {
                                        prefsRepo.setBaseline(maxPub)
                                    } else {
                                        feed.filter {
                                            it.watched == 0 && it.published > base &&
                                                OfflineRepo.stateOf(it.id) is SaveState.NotSaved
                                        }.forEach {
                                            OfflineRepo.save(appContext, store, it,
                                                "${prefs.serverUrl}/api/videos/${it.id}/stream",
                                                prefs.wifiOnly)
                                        }
                                        prefsRepo.setBaseline(maxPub)
                                    }
                                }
                            }
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
                            serverDirty = false
                            server = s
                            serverLoading = false
                            if (s == null) connStatus = "Could not reach the server."
                        }
                    }
                }

                fun saveServerNow(s: ServerSettings) {
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
                            else "Couldn't save — will retry on your next change."
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    // NOTE: prefs here must come from the stored flow, NOT the
                    // composable's value: on a cold start the first composition
                    // still holds blank defaults, and writing those back would
                    // wipe the saved server URL (the 0.1.3 restart bug).
                    val current = prefsRepo.flow.first()
                    if (!current.legacyCacheWiped) {
                        File(filesDir, "ruminate_cache").deleteRecursively()
                        prefsRepo.update(current.copy(legacyCacheWiped = true))
                    }
                    SyncWorker.runOnOpen(this@MainActivity)
                    SyncWorker.schedulePeriodic(
                        this@MainActivity, current.pollIntervalMin, current.pollMode)
                }
                // (Re)load the library once the server URL is known. Without
                // this the first launch shows an empty library: prefs load
                // async, so an early refresh() runs with a blank URL.
                LaunchedEffect(prefs.serverUrl, prefs.storageUri) {
                    refresh()
                    if (tab == 3) loadServer()
                }
                // Load full server config whenever the Settings tab opens.
                LaunchedEffect(tab) {
                    if (tab == 3) loadServer()
                }
                // Auto-save server settings (debounced): every change restarts
                // the wait, so dragging a slider sends one PUT at the end.
                // Loads don't mark dirty, so opening Settings never saves.
                LaunchedEffect(server) {
                    val s = server
                    if (!serverDirty || s == null) return@LaunchedEffect
                    kotlinx.coroutines.delay(900)
                    serverDirty = false
                    saveServerNow(s)
                }
                // Resolve the open video's local file (plain-file scan is IO).
                LaunchedEffect(open?.id, saveStates[open?.id]) {
                    val v = open
                    if (v == null) {
                        openUri = null
                    } else {
                        scope.launch(Dispatchers.IO) {
                            val uri = VideoStore.findUri(appContext, store, v.id)
                            withContext(Dispatchers.Main) { openUri = uri }
                        }
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
                                savedUri = openUri,
                                saveState = saveStates[open!!.id]
                                    ?: OfflineRepo.stateOf(open!!.id),
                                showSave = prefs.saveOnPhone && !wifiBlocked,
                                onSave = { saveVideo(open!!) },
                                onRemoveSaved = {
                                    val id = open!!.id
                                    scope.launch(Dispatchers.IO) {
                                        OfflineRepo.remove(appContext, store, id)
                                    }
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
                                            OfflineRepo.remove(appContext, store, v.id)
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
                                    val id = it.id
                                    scope.launch(Dispatchers.IO) {
                                        OfflineRepo.remove(appContext, store, id)
                                    }
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
                                storageLabel = storageLabel,
                                onPrefs = { np ->
                                    scope.launch {
                                        prefsRepo.update(np)
                                        SyncWorker.schedulePeriodic(
                                            this@MainActivity, np.pollIntervalMin, np.pollMode
                                        )
                                    }
                                },
                                onServer = { server = it; serverDirty = true },
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
                                onClearSaved = {
                                    scope.launch(Dispatchers.IO) {
                                        OfflineRepo.clearAll(appContext, store)
                                        val (n, b) = OfflineRepo.rescan(appContext, store)
                                        withContext(Dispatchers.Main) { updatePhoneSummary(n, b) }
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
