package com.cathode.app.player

import android.app.Notification
import android.content.Context
import androidx.annotation.StringRes
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.scheduler.Scheduler
import com.cathode.app.R
import java.io.File
import java.util.concurrent.Executors

/**
 * Shared ExoPlayer download stack.
 * Requirements.NETWORK_UNMETERED => downloads physically cannot start on
 * cellular, even if a bug elsewhere tried. Progress is journaled in the
 * DownloadIndex, so a dropped connection resumes fragments instead of
 * restarting the file.
 */
object PlayerHolder {
    // Unmetered only — never cellular. (Do NOT add DEVICE_IDLE here: on
    // modern Android it is rarely true and would stall downloads forever.)
    const val REQUIREMENTS_UNMETERED_FLAGS = Requirements.NETWORK_UNMETERED

    @Volatile private var downloadManager: DownloadManager? = null
    @Volatile private var cache: SimpleCache? = null

    fun requirements(): Requirements = Requirements(REQUIREMENTS_UNMETERED_FLAGS)

    // Phone copies share one cache with playback and cap at 16 GB (oldest
    // re-fetched on demand). Call only from the main thread.
    const val MAX_CACHE_BYTES = 16L * 1024 * 1024 * 1024

    fun cacheIfBuilt(): SimpleCache? = cache

    fun getCache(context: Context): SimpleCache {
        getDownloadManager(context)
        return cache!!
    }

    fun getDownloadManager(context: Context): DownloadManager {
        return downloadManager ?: synchronized(this) {
            downloadManager ?: build(context).also { downloadManager = it }
        }
    }

    private fun build(context: Context): DownloadManager {
        val dir = File(context.filesDir, "cathode_cache")
        dir.mkdirs()
        val dbProvider = androidx.media3.database.StandaloneDatabaseProvider(context)
        val index = androidx.media3.exoplayer.offline.DefaultDownloadIndex(dbProvider)
        val c = SimpleCache(dir, LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES), dbProvider)
        cache = c
        val factory = androidx.media3.datasource.cache.CacheDataSource.Factory()
            .setCache(c)
            .setUpstreamDataSourceFactory(
                DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(30_000)
                    .setReadTimeoutMs(60_000)
                    .setAllowCrossProtocolRedirects(true)
            )
        val downloaderFactory = androidx.media3.exoplayer.offline.DefaultDownloaderFactory(factory,
            Executors.newFixedThreadPool(4))
        return DownloadManager(context, index, downloaderFactory).apply {
            requirements = requirements()
            maxParallelDownloads = 3
        }
    }
}

class CathodeDownloadService :
    androidx.media3.exoplayer.offline.DownloadService(
        1,
        DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
        "cathode_downloads",
        R.string.download_notification_channel,
        R.string.download_notification_channel_desc,
    ) {
    override fun getDownloadManager(): DownloadManager =
        PlayerHolder.getDownloadManager(this)

    override fun getScheduler(): Scheduler? =
        androidx.media3.exoplayer.scheduler.PlatformScheduler(this, 1001)

    override fun getForegroundNotification(
        downloads: MutableList<androidx.media3.exoplayer.offline.Download>,
        notMetRequirements: Int,
    ): Notification = DownloadNotificationHelper(this, "cathode_downloads")
        .buildProgressNotification(
            this, R.string.downloading, null, null, downloads, notMetRequirements
        )
}
