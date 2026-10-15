package com.ruminate.app.data

import android.content.Context
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import com.ruminate.app.player.PlayerHolder
import java.util.concurrent.atomic.AtomicBoolean
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
 * Offline copies on this phone, backed by the shared Media3 cache (same files
 * the player reads, so a saved video plays with zero network).
 *
 * Threading: the DownloadManager is single-threaded — every call here must
 * run on the main thread (MainActivity scope / Compose callbacks qualify).
 */
@UnstableApi
object OfflineRepo {
    private val _states = MutableStateFlow<Map<String, SaveState>>(emptyMap())
    val states: StateFlow<Map<String, SaveState>> = _states
    private val attached = AtomicBoolean(false)

    fun attach(manager: DownloadManager) {
        if (!attached.compareAndSet(false, true)) return
        manager.addListener(
            object : DownloadManager.Listener {
                override fun onDownloadChanged(
                    downloadManager: DownloadManager,
                    download: Download,
                    finalException: Exception?,
                ) {
                    _states.update { it + (download.request.id to map(download)) }
                }

                override fun onDownloadRemoved(
                    downloadManager: DownloadManager,
                    download: Download,
                ) {
                    _states.update { it + (download.request.id to SaveState.NotSaved) }
                }
            }
        )
        // Seed already-saved videos so badges show without tapping anything.
        runCatching {
            val idx = manager.downloadIndex
            val cur = idx.getDownloads()
            try {
                while (cur.moveToNext()) {
                    cur.download?.let { d ->
                        if (d.state == Download.STATE_COMPLETED) {
                            _states.update { it + (d.request.id to SaveState.Saved) }
                        }
                    }
                }
            } finally {
                cur.close()
            }
        }
    }

    private fun map(d: Download): SaveState = when (d.state) {
        Download.STATE_COMPLETED -> SaveState.Saved
        Download.STATE_FAILED -> SaveState.Failed
        Download.STATE_QUEUED, Download.STATE_DOWNLOADING,
        Download.STATE_RESTARTING -> SaveState.Saving(d.percentDownloaded / 100f)
        else -> SaveState.NotSaved
    }

    /** Live state, falling back to the on-disk index for videos never touched this session. */
    fun stateOf(manager: DownloadManager, videoId: String): SaveState {
        _states.value[videoId]?.let { return it }
        return runCatching {
            val d = manager.downloadIndex.getDownload(videoId) ?: return SaveState.NotSaved
            map(d).also { if (it == SaveState.Saved) _states.update { m -> m + (videoId to it) } }
        }.getOrDefault(SaveState.NotSaved)
    }

    /**
     * Queue a phone copy. Progressive MP4 needs no track selection, so the
     * request is built directly (no DownloadHelper round-trip over the
     * network). Must run on the main thread.
     */
    fun save(context: Context, videoId: String, streamUrl: String): Boolean {
        return try {
            val manager = PlayerHolder.getDownloadManager(context.applicationContext)
            val req = DownloadRequest.Builder(videoId, android.net.Uri.parse(streamUrl))
                .setMimeType(MimeTypes.VIDEO_MP4)
                .build()
            manager.addDownload(req)
            _states.update { it + (videoId to SaveState.Saving(0f)) }
            true
        } catch (e: Exception) {
            _states.update { it + (videoId to SaveState.Failed) }
            false
        }
    }

    fun remove(manager: DownloadManager, videoId: String) {
        runCatching { manager.removeDownload(videoId) }
    }

    fun clearAll(manager: DownloadManager) {
        runCatching {
            val cur = manager.downloadIndex.getDownloads()
            val ids = mutableListOf<String>()
            try {
                while (cur.moveToNext()) {
                    cur.download?.request?.id?.let(ids::add)
                }
            } finally {
                cur.close()
            }
            ids.forEach { runCatching { manager.removeDownload(it) } }
        }
    }

    fun savedBytes(): Long = runCatching {
        PlayerHolder.cacheIfBuilt()?.cacheSpace ?: 0
    }.getOrDefault(0)

    fun savedCount(manager: DownloadManager): Int = runCatching {
        var n = 0
        val cur = manager.downloadIndex.getDownloads()
        try {
            while (cur.moveToNext()) {
                if (cur.download?.state == Download.STATE_COMPLETED) n++
            }
        } finally {
            cur.close()
        }
        n
    }.getOrDefault(0)
}
