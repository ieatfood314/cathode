package com.cathode.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/** Phone-copy state for one video. */
sealed interface SaveState {
    data object NotSaved : SaveState
    data class Saving(val fraction: Float) : SaveState
    data object Saved : SaveState
    data object Failed : SaveState
}

/**
 * Offline copies as plain .mp4 files in a folder you choose (shared
 * Movies/Cathode by default; SD card / USB drive via the folder picker) —
 * visible in any file manager, never app-private data.
 *
 * Source of truth is a folder scan (video ids live in filenames), so files
 * moved or renamed by hand are still recognized. All functions block on
 * network/disk: call from Dispatchers.IO.
 */
private const val PART_MIME = "application/octet-stream"

object OfflineRepo {
    private val _states = MutableStateFlow<Map<String, SaveState>>(emptyMap())
    val states: StateFlow<Map<String, SaveState>> = _states
    private val _scanned = MutableStateFlow<Set<String>>(emptySet())
    /** Ids found in the storage folder by the last scan (saved in any session). */
    val scanned: StateFlow<Set<String>> = _scanned
    private val _scannedBytes = MutableStateFlow(0L)
    // Two saves of one video (auto-save + a tap) would write the same partial.
    private val inFlight: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    private val autoSaveRunning = AtomicBoolean(false)

    fun stateOf(videoId: String): SaveState =
        _states.value[videoId] ?: if (videoId in _scanned.value) SaveState.Saved
        else SaveState.NotSaved

    /** Re-scan the folder. Returns (count, bytes). */
    fun rescan(context: Context, loc: StoreLocation): Pair<Int, Long> {
        val found = runCatching { VideoStore.listSaved(context, loc) }.getOrDefault(emptyList())
        _scanned.value = found.map { it.videoId }.toSet()
        _scannedBytes.value = found.sumOf { it.bytes }
        return found.size to found.sumOf { it.bytes }
    }

    fun savedBytes(): Long = _scannedBytes.value
    fun savedCount(): Int = _scanned.value.size

    /** Download one video to the phone. Returns false on failure or when it's already downloading. */
    fun save(context: Context, loc: StoreLocation, v: Video, streamUrl: String, wifiOnly: Boolean): Boolean {
        if (stateOf(v.id) is SaveState.Saved) return true
        if (!inFlight.add(v.id)) return false
        _states.update { it + (v.id to SaveState.Saving(0f)) }
        val ok = try {
            runCatching {
                when (loc) {
                    is StoreLocation.DefaultMovies -> saveMediaStore(context, v, streamUrl, wifiOnly)
                    is StoreLocation.CustomFolder -> saveSaf(context, loc, v, streamUrl, wifiOnly)
                }
                true
            }.getOrDefault(false)
        } finally {
            inFlight.remove(v.id)
        }
        if (ok) {
            _scanned.update { it + v.id }
            _states.update { it + (v.id to SaveState.Saved) }
            rescan(context, loc)
        } else {
            _states.update { it + (v.id to SaveState.Failed) }
        }
        return ok
    }

    private fun progress(id: String): (Long, Long) -> Unit = { done, total ->
        val f = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
        _states.update { it + (id to SaveState.Saving(f)) }
    }

    private fun saveMediaStore(context: Context, v: Video, streamUrl: String, wifiOnly: Boolean) {
        // Partial lives in cache and survives a failed attempt (Wi-Fi gone,
        // server down), so the next try resumes instead of starting over.
        // The finished file is published to Movies/Cathode as one copy.
        val dest = VideoStore.scratchPart(context, v.id)
        if (!dest.exists()) {
            downloadResumable(context, streamUrl, dest, wifiOnly, onProgress = progress(v.id))
        }
        VideoStore.publishToMediaStore(context, v, dest)
        dest.delete()
    }

    /**
     * Save new arrivals (the app does this after each successful sync). The
     * baseline is the newest server download time already handled: the first
     * run only sets it (no surprise mass download), and a failed save holds
     * the baseline just below itself so it's retried next time. One run at a
     * time — overlapping syncs would start the same downloads twice.
     */
    suspend fun autoSaveNew(
        context: Context,
        loc: StoreLocation,
        feed: List<Video>,
        baseline: Long,
        wifiOnly: Boolean,
        streamUrl: (Video) -> String,
        setBaseline: suspend (Long) -> Unit,
    ) {
        if (!autoSaveRunning.compareAndSet(false, true)) return
        try {
            val newest = feed.maxOfOrNull { it.downloadedAt } ?: return
            if (baseline <= 0L) {
                setBaseline(newest)
                return
            }
            var next = maxOf(baseline, newest)
            feed.filter { it.downloadedAt > baseline && it.watched == 0 && stateOf(it.id) !is SaveState.Saved }
                .sortedBy { it.downloadedAt }
                .forEach { v ->
                    save(context, loc, v, streamUrl(v), wifiOnly)
                    if (stateOf(v.id) is SaveState.Failed) next = minOf(next, v.downloadedAt - 1)
                }
            setBaseline(next)
        } finally {
            autoSaveRunning.set(false)
        }
    }

    private fun saveSaf(
        context: Context, loc: StoreLocation.CustomFolder, v: Video, streamUrl: String, wifiOnly: Boolean,
    ) {
        val dir = VideoStore.safDir(context, loc, v)
            ?: throw IllegalStateException("cannot open chosen folder")
        val finalName = videoFileName(v)
        if (dir.listFiles().any { it.name == finalName }) return // already there
        val partName = "$finalName.part"
        // octet-stream: with video/mp4 the provider appends ".mp4" to the
        // name, so the partial was never found again (no resume) and piled up.
        var part = dir.listFiles().firstOrNull { it.name == partName }
            ?: dir.createFile(PART_MIME, partName)
            ?: throw IllegalStateException("cannot create file in chosen folder")
        downloadResumableStream(
            context, streamUrl, wifiOnly,
            existingBytes = { part.length() },
            openSink = { VideoStore.openSafAppend(context, part.uri) },
            onProgress = progress(v.id),
            onRangeIgnored = {
                part.delete()
                part = dir.createFile(PART_MIME, partName)
                    ?: throw IllegalStateException("cannot recreate partial file")
            },
        )
        if (!part.renameTo(finalName)) {
            throw IllegalStateException("could not finalize file (rename failed)")
        }
    }

    fun remove(context: Context, loc: StoreLocation, videoId: String) {
        runCatching { VideoStore.delete(context, loc, videoId) }
        _scanned.update { it - videoId }
        _states.update { it + (videoId to SaveState.NotSaved) }
    }

    fun clearAll(context: Context, loc: StoreLocation) {
        runCatching { VideoStore.clearAll(context, loc) }
        // Drop cache-dir partials from the default backend too.
        val cache = context.externalCacheDir ?: context.cacheDir
        cache.listFiles { f -> f.name.endsWith(".bin") || f.name.endsWith(".bin.part") }
            ?.forEach { runCatching { it.delete() } }
        rescan(context, loc)
        _states.value = emptyMap()
    }
}
