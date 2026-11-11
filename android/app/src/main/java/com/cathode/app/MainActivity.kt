package com.cathode.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.cathode.app.data.AppPrefs
import com.cathode.app.data.Library
import com.cathode.app.data.OfflineRepo
import com.cathode.app.data.PrefsRepo
import com.cathode.app.data.ServerSettings
import com.cathode.app.data.StoreLocation
import com.cathode.app.data.Video
import com.cathode.app.data.VideoStore
import com.cathode.app.data.WatchTime
import com.cathode.app.data.checkHealth
import com.cathode.app.data.getServerSettings
import com.cathode.app.data.putServerSettings
import com.cathode.app.data.resolveStore
import com.cathode.app.data.syncFailureMessage
import com.cathode.app.player.Playback
import com.cathode.app.player.PlaybackService
import com.cathode.app.player.StreamCache
import com.cathode.app.ui.CathodeTheme
import androidx.compose.ui.unit.sp
import com.cathode.app.ui.PowerOn
import com.cathode.app.ui.Tube
import com.cathode.app.ui.CrtScreen
import com.cathode.app.ui.Lineup
import com.cathode.app.ui.nowShowing
import com.cathode.app.ui.NowPlayingBar
import com.cathode.app.ui.Dial
import com.cathode.app.ui.LibraryScreen
import com.cathode.app.ui.PlayerScreen
import com.cathode.app.ui.SettingsScreen
import com.cathode.app.ui.StatsScreen
import com.cathode.app.ui.SubscriptionsScreen
import com.cathode.app.ui.formatBytes
import com.cathode.app.ui.rememberNetState
import com.cathode.app.worker.DownloadWorker
import com.cathode.app.worker.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
class MainActivity : ComponentActivity() {
    companion object {
        /** Set by the media notification / lock screen: open the player tab. */
        const val EXTRA_SHOW_PLAYER = "com.cathode.app.SHOW_PLAYER"
    }

    private val showPlayerRequests = mutableIntStateOf(0)
    // Read above the theme too: fullscreen video turns the CRT overlay off.
    private val fullscreenState = mutableStateOf(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_PLAYER, false)) showPlayerRequests.intValue++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefsRepo = PrefsRepo(this)
        val startOnPlayer = intent?.getBooleanExtra(EXTRA_SHOW_PLAYER, false) == true

        setContent {
            CathodeTheme { PowerOn { CrtScreen(enabled = !fullscreenState.value) {
                val scope = rememberCoroutineScope()
                val appContext = LocalContext.current.applicationContext
                // null until DataStore has loaded: a blank URL then means
                // "not read yet", not "not configured".
                val storedPrefs by prefsRepo.flow.collectAsState(initial = null)
                val prefs = storedPrefs ?: AppPrefs()
                val videos by Library.videos.collectAsState()
                val savedIds by OfflineRepo.scanned.collectAsState()
                val saveStates by OfflineRepo.states.collectAsState()
                // A player that outlived the activity (Back while audio
                // played) is picked up again instead of "Nothing playing".
                var open: Video? by remember { mutableStateOf(Playback.video) }
                var tab by remember { mutableIntStateOf(if (startOnPlayer && open != null) 2 else 0) }
                var loading by remember { mutableStateOf(false) }
                var syncError by remember { mutableStateOf<String?>(null) }
                var openUri by remember { mutableStateOf<Uri?>(null) }
                // Hoisted from PlayerScreen: fullscreen hides the nav bar so
                // the video fills the whole screen edge-to-edge.
                var playerFullscreen by fullscreenState
                var server by remember { mutableStateOf<ServerSettings?>(null) }
                var serverDirty by remember { mutableStateOf(false) }
                var serverLoading by remember { mutableStateOf(false) }
                var connStatus by remember { mutableStateOf<String?>(null) }
                var serverStatus by remember { mutableStateOf<String?>(null) }
                var testing by remember { mutableStateOf(false) }
                var savedSummary by remember { mutableStateOf<String?>(null) }
                var savedCount by remember { mutableIntStateOf(0) }
                var storageLabel by remember { mutableStateOf("Movies/Cathode") }
                val net = rememberNetState(this@MainActivity)
                val wifiBlocked = prefs.wifiOnly && !net.unmetered
                val canUseNetwork = prefs.serverUrl.isNotBlank() &&
                    (if (prefs.wifiOnly) net.unmetered else net.online)
                val store = remember(prefs.storageUri) {
                    resolveStore(this@MainActivity, prefs.storageUri)
                }

                fun streamUrl(v: Video) = "${prefs.serverUrl}/api/videos/${v.id}/stream"

                fun updatePhoneSummary(n: Int, b: Long, cacheBytes: Long) {
                    savedCount = n
                    val saved = if (n == 0) "No videos saved"
                    else "$n ${if (n == 1) "video" else "videos"} · ${formatBytes(b)}"
                    savedSummary = "$saved · ${formatBytes(cacheBytes)} cache"
                    storageLabel = when (val s = store) {
                        is StoreLocation.DefaultMovies -> "Movies/Cathode"
                        is StoreLocation.CustomFolder -> s.label
                    }
                }

                suspend fun rescanPhone(loc: StoreLocation) {
                    val (n, b) = OfflineRepo.rescan(appContext, loc)
                    val cache = runCatching { StreamCache.get(appContext).cacheSpace }.getOrDefault(0L)
                    withContext(Dispatchers.Main) { updatePhoneSummary(n, b, cache) }
                }

                // Queued in the background (waits for Wi-Fi if needed) and
                // keeps going after the app closes.
                fun saveVideo(v: Video) {
                    if (!prefs.saveOnPhone) return
                    DownloadWorker.save(appContext, listOf(v.id), prefs.wifiOnly)
                }

                // Offline or unreachable, the library shown is the one cached
                // on disk; only a successful sync replaces it.
                fun refresh() {
                    val p = prefs
                    val loc = store
                    if (!canUseNetwork) {
                        scope.launch(Dispatchers.IO) { rescanPhone(loc) }
                        return
                    }
                    loading = true
                    scope.launch(Dispatchers.IO) {
                        rescanPhone(loc)
                        val result = Library.sync(appContext, OfflineRepo.scanned.value)
                        withContext(Dispatchers.Main) {
                            loading = false
                            syncError = if (result.isSuccess) null
                            else syncFailureMessage(result.exceptionOrNull())
                        }
                        val feed = result.getOrNull() ?: return@launch
                        if (SyncWorker.afterSync(appContext, feed, p) > 0) rescanPhone(loc)
                    }
                }

                // Leaving a video (closed, or another one opened): record where
                // it stopped. Watched phone copies are freed by the sync, a
                // day after they were marked (SyncWorker.afterSync), so an
                // accidental finish can still be undone.
                fun leave(v: Video, posSeconds: Int?) {
                    if (posSeconds == null) return
                    Library.scope.launch { Library.reportProgress(appContext, v.id, posSeconds) }
                }

                fun livePositionOf(v: Video?): Int? {
                    val p = Playback.current
                    if (v == null || p == null || Playback.videoId != v.id) return null
                    return (p.currentPosition.coerceAtLeast(0) / 1000L).toInt()
                }

                fun setWatched(v: Video, watched: Boolean) {
                    Library.scope.launch { Library.markWatched(appContext, v.id, watched) }
                }

                // Nav taps keep `open` (and playback) alive: audio continues
                // while you browse other tabs. Only the X in the player
                // closes a video.
                fun closeVideo() {
                    val v = open
                    val pos = livePositionOf(v)
                    // The screen's dispose-saver sees a released player and skips.
                    Playback.close()
                    stopService(Intent(this@MainActivity, PlaybackService::class.java))
                    // Closing from fullscreen must bring the nav bar back.
                    playerFullscreen = false
                    open = null
                    tab = 0
                    if (v != null) leave(v, pos)
                }

                fun openVideo(v: Video) {
                    // Record where the previous video stopped first:
                    // the switch below releases its player.
                    val prev = open
                    if (prev != null && prev.id != v.id) {
                        leave(prev, livePositionOf(prev))
                        // Don't let the previous video's file URI
                        // briefly feed the new one.
                        openUri = null
                    }
                    open = v
                    tab = 2
                    // Keep the media session reachable from
                    // Android's media player outside the app.
                    startForegroundService(
                        Intent(this@MainActivity, PlaybackService::class.java)
                    )
                }

                // Server polling is one setting with two halves: the server's
                // loop and this phone's background sync. The server's value wins.
                suspend fun adoptServerPolling(s: ServerSettings) {
                    prefsRepo.update {
                        it.copy(pollMode = s.pollMode, pollIntervalMin = s.pollIntervalMinutes)
                    }
                    val p = prefsRepo.flow.first()
                    SyncWorker.schedulePeriodic(appContext, p.pollIntervalMin, p.pollMode, p.wifiOnly)
                }

                fun loadServer() {
                    if (prefs.serverUrl.isBlank()) return
                    if (!canUseNetwork) {
                        connStatus = if (wifiBlocked) "Waiting for Wi-Fi" else "Offline"
                        return
                    }
                    serverLoading = true
                    val url = prefs.serverUrl
                    scope.launch(Dispatchers.IO) {
                        val s = runCatching { getServerSettings(url) }.getOrNull()
                        if (s != null) adoptServerPolling(s)
                        withContext(Dispatchers.Main) {
                            serverDirty = false
                            server = s
                            serverLoading = false
                            if (s == null) connStatus = "Can't reach server"
                        }
                    }
                }

                fun saveServerNow(s: ServerSettings) {
                    val url = prefs.serverUrl
                    scope.launch(Dispatchers.IO) {
                        val ok = runCatching { putServerSettings(url, s) }.isSuccess
                        if (ok) adoptServerPolling(s)
                        withContext(Dispatchers.Main) {
                            // A failed save is retried on the next change.
                            serverStatus = if (ok) "Saved" else "Couldn't save"
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    withContext(Dispatchers.IO) {
                        Library.load(appContext)
                        WatchTime.load(appContext)
                        StreamCache.deleteLegacy(appContext)
                    }
                    // Stored prefs, not the composable's blank first-frame defaults.
                    val current = prefsRepo.flow.first()
                    SyncWorker.runOnOpen(this@MainActivity, current.wifiOnly)
                    SyncWorker.schedulePeriodic(
                        this@MainActivity, current.pollIntervalMin, current.pollMode, current.wifiOnly)
                }
                // (Re)load the library once the server URL is known, the
                // storage folder changes, or the network comes back.
                LaunchedEffect(prefs.serverUrl, prefs.storageUri, canUseNetwork) {
                    if (storedPrefs == null) return@LaunchedEffect
                    refresh()
                    if (tab == 3) loadServer()
                }
                // Load full server config whenever the Settings tab opens.
                LaunchedEffect(tab) {
                    if (tab == 3) loadServer()
                }
                // Saves land from the background queue too: keep the storage
                // summary in step with what's on the phone.
                LaunchedEffect(savedIds) {
                    val cache = withContext(Dispatchers.IO) {
                        runCatching { StreamCache.get(appContext).cacheSpace }.getOrDefault(0L)
                    }
                    updatePhoneSummary(OfflineRepo.savedCount(), OfflineRepo.savedBytes(), cache)
                }
                LaunchedEffect(showPlayerRequests.intValue) {
                    if (showPlayerRequests.intValue == 0) return@LaunchedEffect
                    if (open == null) open = Playback.video
                    if (open != null) tab = 2
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
                // In this effect's own coroutine, so a slower lookup for the
                // previous video is cancelled instead of landing late.
                LaunchedEffect(open?.id, saveStates[open?.id], savedIds) {
                    val v = open
                    openUri = if (v == null) null
                    else withContext(Dispatchers.IO) { VideoStore.findUri(appContext, store, v.id) }
                }
                // Back walks to the library rather than out of the app; the
                // player handles it first while fullscreen.
                // Stats lives under Setup, so Back from it goes there first.
                BackHandler(enabled = tab != 0) { tab = if (tab == 4) 3 else 0 }

                val lineup = remember(videos) { Lineup.of(videos) }
                Scaffold(
                    containerColor = Tube,
                    bottomBar = {
                        // Hidden in fullscreen so the video fills the screen.
                        if (!playerFullscreen) Column {
                            val playing = open
                            if (playing != null && tab != 2) NowPlayingBar(
                                video = playing,
                                thumb = "${prefs.serverUrl}/api/thumbs/${playing.id}",
                                lineup = lineup,
                                onExpand = { tab = 2 },
                                onClose = { closeVideo() },
                            )
                            Dial(tab) { tab = it }
                        }
                    }
                ) { pad ->
                    // Fullscreen: no Scaffold insets — the player needs the
                    // whole window (already edge-to-edge via
                    // setDecorFitsSystemWindows(false) in PlayerScreen).
                    val contentPad = if (playerFullscreen) PaddingValues(0.dp) else pad
                    Box(Modifier.padding(contentPad)) {
                        val playing = open
                        when {
                            playing != null && tab == 2 -> PlayerScreen(
                                video = playing,
                                streamUrl = streamUrl(playing),
                                artworkUrl = "${prefs.serverUrl}/api/thumbs/${playing.id}",
                                savedUri = openUri,
                                saveState = saveStates[playing.id] ?: OfflineRepo.stateOf(playing.id),
                                fullscreen = playerFullscreen,
                                onFullscreenChange = { playerFullscreen = it },
                                onClose = { closeVideo() },
                                onLeave = { pos ->
                                    val id = playing.id
                                    Library.scope.launch { Library.reportProgress(appContext, id, pos) }
                                },
                                lineup = lineup,
                                upNext = videos.filter {
                                    it.channelTitle == playing.channelTitle && it.id != playing.id && it.watched == 0
                                }.take(8),
                                alsoOnAir = nowShowing(videos)
                                    .filter { it.channelTitle != playing.channelTitle }.take(6),
                                thumbUrl = { v -> "${prefs.serverUrl}/api/thumbs/${v.id}" },
                                onOpen = { v -> openVideo(v) },
                                serverUrl = prefs.serverUrl,
                                canUseNetwork = canUseNetwork,
                            )
                            tab == 0 -> LibraryScreen(
                                videos = videos,
                                thumbUrl = { v -> "${prefs.serverUrl}/api/thumbs/${v.id}" },
                                loading = loading,
                                wifiBlocked = wifiBlocked,
                                notice = when {
                                    prefs.serverUrl.isBlank() || wifiBlocked -> null
                                    !net.online -> "Offline"
                                    else -> syncError
                                },
                                serverConfigured = storedPrefs == null || prefs.serverUrl.isNotBlank(),
                                saveStates = saveStates,
                                savedIds = savedIds,
                                showSaveButtons = prefs.saveOnPhone,
                                onRefresh = {
                                    SyncWorker.runOnOpen(this@MainActivity, prefs.wifiOnly)
                                    refresh()
                                },
                                onOpenSettings = { tab = 3 },
                                onOpen = { v -> openVideo(v) },
                                onOpenChannels = { tab = 1 },
                                onSetWatched = { v, w -> setWatched(v, w) },
                                onClearProgress = { v -> setWatched(v, false) },
                                onSave = { v -> saveVideo(v) },
                                onRemoveSaved = { v ->
                                    scope.launch(Dispatchers.IO) {
                                        OfflineRepo.remove(appContext, store, v.id)
                                        rescanPhone(store)
                                    }
                                },
                            )
                            tab == 1 -> SubscriptionsScreen(serverUrl = prefs.serverUrl, lineup = lineup)
                            tab == 4 -> StatsScreen(
                                lineup = lineup,
                                onBack = { tab = 3 },
                                canRefresh = canUseNetwork,
                                notice = when {
                                    prefs.serverUrl.isBlank() -> null
                                    wifiBlocked -> "Waiting for Wi-Fi"
                                    !net.online -> "Offline"
                                    else -> null
                                },
                            )
                            tab == 3 -> SettingsScreen(
                                prefs = prefs,
                                server = server,
                                serverLoading = serverLoading,
                                connStatus = connStatus,
                                serverStatus = serverStatus,
                                testing = testing,
                                savedSummary = savedSummary,
                                savedCount = savedCount,
                                storageLabel = storageLabel,
                                onPrefs = { transform ->
                                    scope.launch {
                                        prefsRepo.update(transform)
                                        val np = prefsRepo.flow.first()
                                        SyncWorker.schedulePeriodic(
                                            this@MainActivity, np.pollIntervalMin, np.pollMode, np.wifiOnly
                                        )
                                    }
                                },
                                onServer = { server = it; serverDirty = true },
                                onTestConnection = {
                                    testing = true
                                    val url = prefs.serverUrl
                                    scope.launch(Dispatchers.IO) {
                                        val msg = try {
                                            checkHealth(url)
                                            val n = Library.sync(appContext, OfflineRepo.scanned.value)
                                                .getOrNull()?.size
                                            "Connected" + if (n != null) " · $n videos" else ""
                                        } catch (e: IllegalArgumentException) {
                                            "Invalid address"
                                        } catch (e: java.io.IOException) {
                                            "Can't connect"
                                        } catch (e: Exception) {
                                            e.message ?: "Can't connect"
                                        }
                                        withContext(Dispatchers.Main) {
                                            connStatus = msg
                                            testing = false
                                            loadServer()
                                        }
                                    }
                                },
                                onOpenStats = { tab = 4 },
                                onClearSaved = {
                                    scope.launch(Dispatchers.IO) {
                                        OfflineRepo.clearAll(appContext, store)
                                        rescanPhone(store)
                                    }
                                },
                            )
                            else -> NothingPlaying(onBrowse = { tab = 0 })
                        }
                    }
                }
            } } }
        }
    }
}

@androidx.compose.runtime.Composable
private fun NothingPlaying(onBrowse: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            com.cathode.app.ui.Reverse("NO SIGNAL", size = 40.sp)
            androidx.compose.material3.TextButton(onClick = onBrowse) { Text("Back to On Air") }
        }
    }
}
