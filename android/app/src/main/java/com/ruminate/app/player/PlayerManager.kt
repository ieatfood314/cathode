package com.ruminate.app.player

import android.app.Notification
import android.content.Context
import androidx.annotation.StringRes
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.scheduler.Scheduler
import com.ruminate.app.R
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
    const val REQUIREMENTS_UNMETERED_FLAGS =
        Requirements.NETWORK_UNMETERED or Requirements.DEVICE_IDLE

    @Volatile private var downloadManager: DownloadManager? = null
    @Volatile private var cache: SimpleCache? = null

    fun requirements(): Requirements = Requirements(REQUIREMENTS_UNMETERED_FLAGS)

    fun getDownloadManager(context: Context): DownloadManager {
        return downloadManager ?: synchronized(this) {
            downloadManager ?: build(context).also { downloadManager = it }
        }
    }

    private fun build(context: Context): DownloadManager {
        val dir = File(context.filesDir, "ruminate_cache")
        dir.mkdirs()
        val dbProvider = androidx.media3.database.StandaloneDatabaseProvider(context)
        val index = androidx.media3.exoplayer.offline.DefaultDownloadIndex(dbProvider)
        val c = SimpleCache(dir, NoOpCacheEvictor(), dbProvider)
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

class RuminateDownloadService :
    androidx.media3.exoplayer.offline.DownloadService(
        1,
        DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
        "ruminate_downloads",
        R.string.download_notification_channel,
        R.string.download_notification_channel_desc,
    ) {
    override fun getDownloadManager(): DownloadManager =
        PlayerHolder.getDownloadManager(this)

    override fun getScheduler(): Scheduler? =
        androidx.media3.exoplayer.scheduler.PlatformScheduler(this, 1001)

    override fun getForegroundNotification(
        downloads: List<androidx.media3.exoplayer.offline.Download>,
        @StringRes notificationContentTitle: Int,
        notMetRequirements: Int,
    ): Notification = DownloadNotificationHelper(this, "ruminate_downloads")
        .buildProgressNotification(
            this, notificationContentTitle, null, null, downloads, notMetRequirements
        )
}
