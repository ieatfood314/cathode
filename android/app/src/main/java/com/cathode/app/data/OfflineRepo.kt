package com.cathode.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

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
object OfflineRepo {
    private val _states = MutableStateFlow<Map<String, SaveState>>(emptyMap())
    val states: StateFlow<Map<String, SaveState>> = _states
    private val _scanned = MutableStateFlow<Set<String>>(emptySet())
    private val _scannedBytes = MutableStateFlow(0L)

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

    fun save(context: Context, loc: StoreLocation, v: Video, streamUrl: String, wifiOnly: Boolean): Boolean {
        if (stateOf(v.id) is SaveState.Saved) return true
        _states.update { it + (v.id to SaveState.Saving(0f)) }
        val ok = runCatching {
            when (loc) {
                is StoreLocation.DefaultMovies -> saveMediaStore(context, v, streamUrl, wifiOnly)
                is StoreLocation.CustomFolder -> saveSaf(context, loc, v, streamUrl, wifiOnly)
            }
            true
        }.getOrDefault(false)
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
        // Partial lives in cache (resume across attempts); the finished file
        // is published to Movies/Cathode as one sequential copy.
        val dest = VideoStore.scratchPart(context, v.id)
        try {
            downloadResumable(context, streamUrl, dest, wifiOnly, onProgress = progress(v.id))
            VideoStore.publishToMediaStore(context, v, dest)
        } finally {
            dest.delete()
            java.io.File(dest.path + ".part").delete()
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
        var part = dir.listFiles().firstOrNull { it.name == partName }
            ?: dir.createFile("video/mp4", partName)
            ?: throw IllegalStateException("cannot create file in chosen folder")
        try {
            downloadResumableStream(
                context, streamUrl, wifiOnly,
                existingBytes = { part.length() },
                openSink = { VideoStore.openSafAppend(context, part.uri) },
                onProgress = progress(v.id),
                onRangeIgnored = {
                    part.delete()
                    part = dir.createFile("video/mp4", partName)
                        ?: throw IllegalStateException("cannot recreate partial file")
                },
            )
        } catch (e: Exception) {
            if (e is WifiBlockedException) throw e
            throw e
        }
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
