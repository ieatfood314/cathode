package com.cathode.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.Collections

private const val PART_MIME = "application/octet-stream"

/** Phone-copy state for one video. */
sealed interface SaveState {
    data object NotSaved : SaveState
    /** Waiting its turn in the background download queue (or for Wi-Fi). */
    data object Queued : SaveState
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
object OfflineRepo {
    private val _states = MutableStateFlow<Map<String, SaveState>>(emptyMap())
    val states: StateFlow<Map<String, SaveState>> = _states
    private val _scanned = MutableStateFlow<Set<String>>(emptySet())
    /** Ids found in the storage folder by the last scan (saved in any session). */
    val scanned: StateFlow<Set<String>> = _scanned
    private val _scannedBytes = MutableStateFlow(0L)
    // Two saves of one video (a stopped job's last read + its rerun) would write the same partial.
    private val inFlight: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    fun stateOf(videoId: String): SaveState =
        _states.value[videoId] ?: if (videoId in _scanned.value) SaveState.Saved
        else SaveState.NotSaved

    /** Re-scan the folder. Returns (count, bytes). */
    fun rescan(context: Context, loc: StoreLocation): Pair<Int, Long> {
        val found = runCatching { VideoStore.listSaved(context, loc) }.getOrDefault(emptyList())
        // Bytes first: watchers of [scanned] read the total right after.
        _scannedBytes.value = found.sumOf { it.bytes }
        _scanned.value = found.map { it.videoId }.toSet()
        return found.size to found.sumOf { it.bytes }
    }

    fun savedBytes(): Long = _scannedBytes.value
    fun savedCount(): Int = _scanned.value.size

    /** Show these as waiting for the background download queue. */
    fun markQueued(ids: Collection<String>) {
        _states.update { m ->
            m + ids.filter { stateOf(it) !is SaveState.Saved && m[it] !is SaveState.Saving }
                .associateWith { SaveState.Queued }
        }
    }

    /**
     * Download one video to the phone (blocking; DownloadWorker runs it).
     * Returns the state it ends in: Saved, Failed, or Queued when it was
     * stopped or lost Wi-Fi — its partial stays and the next run resumes it.
     */
    fun save(
        context: Context, loc: StoreLocation, v: Video, streamUrl: String, wifiOnly: Boolean,
        stop: () -> Boolean = { false },
    ): SaveState {
        if (stateOf(v.id) is SaveState.Saved) return SaveState.Saved
        if (!inFlight.add(v.id)) return stateOf(v.id)
        _states.update { it + (v.id to SaveState.Saving(0f)) }
        val result = try {
            runCatching {
                when (loc) {
                    is StoreLocation.DefaultMovies -> saveMediaStore(context, v, streamUrl, wifiOnly, stop)
                    is StoreLocation.CustomFolder -> saveSaf(context, loc, v, streamUrl, wifiOnly, stop)
                }
                SaveState.Saved
            }.getOrElse { e ->
                if (e is DownloadStopped || e is WifiBlockedException) SaveState.Queued else SaveState.Failed
            }
        } finally {
            inFlight.remove(v.id)
        }
        if (result is SaveState.Saved) {
            _scanned.update { it + v.id }
            rescan(context, loc)
        }
        _states.update { it + (v.id to result) }
        return result
    }

    // Whole percents only: a byte-level update per 64 KB chunk redrew the
    // library tens of thousands of times for one big video.
    private fun progress(id: String): (Long, Long) -> Unit {
        var last = -1
        return { done, total ->
            val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
            if (pct != last) {
                last = pct
                _states.update { it + (id to SaveState.Saving(pct / 100f)) }
            }
        }
    }

    private fun saveMediaStore(
        context: Context, v: Video, streamUrl: String, wifiOnly: Boolean, stop: () -> Boolean,
    ) {
        // Partial lives in cache and survives a failed attempt (Wi-Fi gone,
        // server down), so the next try resumes instead of starting over.
        // The finished file is published to Movies/Cathode as one copy.
        val dest = VideoStore.scratchPart(context, v.id)
        if (!dest.exists()) {
            downloadResumable(context, streamUrl, dest, wifiOnly, onProgress = progress(v.id), stop = stop)
        }
        VideoStore.publishToMediaStore(context, v, dest)
        dest.delete()
    }

    /**
     * New arrivals auto-save should fetch, oldest first. The baseline is the
     * newest server download time already handled (see DownloadWorker); only
     * unwatched videos that aren't on the phone yet count.
     */
    fun autoSavePicks(feed: List<Video>, baseline: Long): List<Video> =
        feed.filter { it.downloadedAt > baseline && it.watched == 0 && stateOf(it.id) !is SaveState.Saved }
            .sortedBy { it.downloadedAt }

    private fun saveSaf(
        context: Context, loc: StoreLocation.CustomFolder, v: Video, streamUrl: String, wifiOnly: Boolean,
        stop: () -> Boolean,
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
            stop = stop,
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
