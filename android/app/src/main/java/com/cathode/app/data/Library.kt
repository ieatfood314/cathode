package com.cathode.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** A watch-state change the server hasn't heard about yet (offline, server down). */
@Serializable
data class PendingOp(val pos: Int? = null, val watched: Boolean? = null)

class OfflineException : IllegalStateException("not connected (or Wi-Fi only is waiting for Wi-Fi)")

/**
 * The library as this phone last saw it, kept on disk so the app opens — and
 * saved videos play, resume and get marked watched — with no network at all.
 * Progress made while offline queues up and replays on the next sync, so it's
 * never lost to bad Wi-Fi.
 *
 * Plain functions block on disk; suspend ones also touch the network. Call
 * from Dispatchers.IO (or launch in [scope]).
 */
object Library {
    /** Outlives screens and the activity: progress sent while closing still lands. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _videos = MutableStateFlow<List<Video>>(emptyList())
    val videos: StateFlow<List<Video>> = _videos

    private val lock = Any()
    private var loaded = false
    private var pending = mutableMapOf<String, PendingOp>()
    private var persistJob: Job? = null
    private val pendingSerializer = MapSerializer(String.serializer(), PendingOp.serializer())

    private fun libraryFile(c: Context) = File(c.filesDir, "library.json")
    private fun pendingFile(c: Context) = File(c.filesDir, "pending_progress.json")

    /** Read the cached library + queue from disk (once; later calls are no-ops). */
    fun load(context: Context) {
        synchronized(lock) {
            if (loaded) return
            loaded = true
            _videos.value = runCatching {
                json.decodeFromString(FeedResponse.serializer(), libraryFile(context).readText()).videos
            }.getOrDefault(emptyList())
            pending = runCatching {
                json.decodeFromString(pendingSerializer, pendingFile(context).readText()).toMutableMap()
            }.getOrDefault(mutableMapOf())
        }
    }

    fun find(id: String): Video? = _videos.value.firstOrNull { it.id == id }

    /** Mirrors the server's auto-watched rule, so offline viewing marks too. */
    fun nearEnd(v: Video, pos: Int): Boolean = v.duration > 0 && pos >= v.duration * 0.95 - 5

    /**
     * Pull the server's library, after replaying anything queued offline.
     * Failure (offline, server down) leaves the cached list as it is.
     * [savedIds]: videos with a copy on this phone stay listed even once the
     * server has pruned or deleted its own file — they still play from here.
     */
    suspend fun sync(context: Context, savedIds: Set<String>): Result<List<Video>> {
        load(context)
        val base = target(context) ?: return Result.failure(OfflineException())
        return runCatching {
            flushPending(context, base)
            // Watch time is best-effort: an older server without it still syncs.
            WatchTime.flush(context)
            val feed = fetchFeed(base)
            synchronized(lock) {
                val onServer = feed.mapTo(HashSet()) { it.id }
                val phoneOnly = _videos.value.filter { it.id !in onServer && it.id in savedIds }
                // Changes the server still hasn't taken stay visible here.
                _videos.value = (feed + phoneOnly)
                    .map { applyPending(it, pending[it.id]) }
                    .sortedByDescending { it.published }
                writeLibrary(context)
            }
            feed
        }
    }

    /**
     * Phone-only videos past the server's "Keep" window. The server prunes by
     * upload date, but phone copies outlived it, so a years-old video saved
     * once stayed in the library forever. Undated ones go by download time,
     * like on the server.
     */
    fun expired(feed: List<Video>, keepDays: Int): Set<String> {
        if (keepDays <= 0) return emptySet()
        val onServer = feed.mapTo(HashSet()) { it.id }
        val cutoff = LocalDate.now().minusDays(keepDays.toLong())
            .format(DateTimeFormatter.BASIC_ISO_DATE)
        val cutoffSec = System.currentTimeMillis() / 1000 - keepDays * 86_400L
        return _videos.value.filter { v ->
            v.id !in onServer && (v.uploadDate?.takeIf { it.length == 8 }?.let { it < cutoff }
                ?: (v.downloadedAt in 1 until cutoffSec))
        }.mapTo(HashSet()) { it.id }
    }

    /** Drop videos from the cached library (their phone copies are already gone). */
    fun forget(context: Context, ids: Set<String>) {
        if (ids.isEmpty()) return
        load(context)
        synchronized(lock) {
            _videos.value = _videos.value.filterNot { it.id in ids }
            writeLibrary(context)
        }
    }

    /** Where playback stopped: shown right away, sent now or queued for later. */
    suspend fun reportProgress(context: Context, id: String, pos: Int) {
        patch(context, id) { v ->
            v.copy(positionSeconds = pos, watched = if (nearEnd(v, pos)) 1 else v.watched)
        }
        val base = target(context)
        val serverWatched = base?.let { runCatching { putProgress(it, id, pos) }.getOrNull() }
        when (serverWatched) {
            null -> queue(context, id) { it.copy(pos = pos) }
            true -> patch(context, id) { it.copy(watched = 1) }
            false -> Unit
        }
    }

    /** Watched / unwatched (unwatched also rewinds to the start). */
    suspend fun markWatched(context: Context, id: String, watched: Boolean) {
        patch(context, id) {
            if (watched) it.copy(watched = 1) else it.copy(watched = 0, positionSeconds = 0)
        }
        val base = target(context)
        val sent = base != null && runCatching { setWatched(base, id, watched) }.isSuccess
        if (!sent) {
            // Unwatching resets the position too, so an older queued one is moot.
            queue(context, id) { op -> if (watched) op.copy(watched = true) else PendingOp(watched = false) }
        }
    }

    /** Server URL, or null while it can't be used (none set, offline, Wi-Fi only). */
    internal suspend fun target(context: Context): String? {
        val p = PrefsRepo(context).flow.first()
        if (p.serverUrl.isBlank() || !networkAllowed(context, p.wifiOnly)) return null
        return p.serverUrl
    }

    /** Replay queued changes. Stops at the first one the server can't take. */
    private fun flushPending(context: Context, baseUrl: String) {
        val snapshot = synchronized(lock) { pending.toMap() }
        for ((id, op) in snapshot) {
            val result = runCatching {
                // Watched first: an unwatch rewinds, then the later position applies.
                op.watched?.let { setWatched(baseUrl, id, it) }
                op.pos?.let { putProgress(baseUrl, id, it) }
            }
            val goneFromServer = (result.exceptionOrNull() as? HttpStatusException)?.code == 404
            if (result.isFailure && !goneFromServer) return
            synchronized(lock) {
                // Only drop it if nothing newer was queued meanwhile.
                if (pending[id] == op) {
                    pending.remove(id)
                    writePending(context)
                }
            }
        }
    }

    private fun applyPending(v: Video, op: PendingOp?): Video {
        if (op == null) return v
        var out = v
        op.watched?.let { w ->
            out = if (w) out.copy(watched = 1) else out.copy(watched = 0, positionSeconds = 0)
        }
        op.pos?.let { p ->
            out = out.copy(positionSeconds = p, watched = if (nearEnd(out, p)) 1 else out.watched)
        }
        return out
    }

    fun patch(context: Context, id: String, f: (Video) -> Video) {
        load(context)
        synchronized(lock) {
            _videos.value = _videos.value.map { if (it.id == id) f(it) else it }
        }
        persistSoon(context)
    }

    private fun queue(context: Context, id: String, f: (PendingOp) -> PendingOp) {
        synchronized(lock) {
            pending[id] = f(pending[id] ?: PendingOp())
            writePending(context)
        }
    }

    // Progress lands every few seconds while playing: batch the library
    // rewrite instead of doing it on every tick.
    private fun persistSoon(context: Context) {
        synchronized(lock) {
            persistJob?.cancel()
            persistJob = scope.launch {
                delay(1_500)
                synchronized(lock) { writeLibrary(context) }
            }
        }
    }

    private fun writeLibrary(context: Context) =
        writeAtomic(libraryFile(context),
            json.encodeToString(FeedResponse.serializer(), FeedResponse(_videos.value)))

    private fun writePending(context: Context) =
        writeAtomic(pendingFile(context), json.encodeToString(pendingSerializer, pending))

    internal fun writeAtomic(f: File, text: String) {
        runCatching {
            val tmp = File(f.path + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }
    }
}
