package com.cathode.app.worker

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cathode.app.data.AppPrefs
import com.cathode.app.data.Library
import com.cathode.app.data.OfflineRepo
import com.cathode.app.data.PrefsRepo
import com.cathode.app.data.Video
import com.cathode.app.data.getServerSettings
import com.cathode.app.data.networkAllowed
import com.cathode.app.data.resolveStore
import com.cathode.app.data.triggerServerPoll
import com.cathode.app.player.Playback
import kotlinx.coroutines.flow.first

/**
 * SyncWorker: runs on app open + on the polling schedule.
 * - Wi-Fi only ON => Constraints.UNMETERED: zero cellular data, enforced by
 *   the OS, double-checked with isUnmetered() (covers VPN/metered hotspots).
 * - Asks the NAS to scan (it downloads over its own wired link), then
 *   replays progress queued offline and refreshes the library this phone
 *   keeps on disk — so the app opens up to date, even with no network.
 * - Then queues new arrivals for saving to the phone (DownloadWorker), so
 *   they land without the app being opened.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val prefs = PrefsRepo(applicationContext).flow.first()
        if (prefs.serverUrl.isBlank()) return Result.success()
        if (!networkAllowed(applicationContext, prefs.wifiOnly)) {
            return Result.retry() // WorkManager reschedules once the network allows it
        }
        runCatching { triggerServerPoll(prefs.serverUrl) }
        OfflineRepo.rescan(applicationContext, resolveStore(applicationContext, prefs.storageUri))
        val feed = Library.sync(applicationContext, OfflineRepo.scanned.value).getOrNull()
        if (feed != null) afterSync(applicationContext, feed, prefs)
        return when {
            feed != null -> Result.success()
            runAttemptCount >= 5 -> Result.failure()
            else -> Result.retry()
        }
    }

    companion object {
        const val ON_OPEN = "cathode-sync-onopen"
        const val PERIODIC = "cathode-sync-periodic"

        /**
         * After a successful sync (here or in the app): drop phone copies
         * past the server's Keep window, then queue new arrivals for saving.
         * Returns how many copies were dropped. Blocks on network and disk.
         */
        @OptIn(UnstableApi::class)
        fun afterSync(context: Context, feed: List<Video>, prefs: AppPrefs): Int {
            val loc = resolveStore(context, prefs.storageUri)
            val keepDays = runCatching { getServerSettings(prefs.serverUrl).keepDays }.getOrNull()
            // Never pull a copy out from under the player.
            val expired = keepDays?.let { Library.expired(feed, it) - setOfNotNull(Playback.videoId) }.orEmpty()
            expired.forEach { OfflineRepo.remove(context, loc, it) }
            if (expired.isNotEmpty()) {
                Library.forget(context, expired)
                OfflineRepo.rescan(context, loc)
            }
            if (prefs.saveOnPhone && prefs.autoSaveNew) DownloadWorker.saveNew(context, prefs.wifiOnly)
            return expired.size
        }

        private fun constraints(wifiOnly: Boolean) = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(false)
            .build()

        /** One at a time: taps while it waits for Wi-Fi don't pile up more runs. */
        fun runOnOpen(context: Context, wifiOnly: Boolean) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ON_OPEN,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(constraints(wifiOnly))
                    .build(),
            )
        }

        /** Follows the server's poll mode; Android's floor is 15 minutes. */
        fun schedulePeriodic(context: Context, intervalMinutes: Int, mode: String, wifiOnly: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (mode == "on_open") {
                wm.cancelUniqueWork(PERIODIC)
                return
            }
            val mins = (if (mode == "instant") 15 else intervalMinutes).coerceAtLeast(15).toLong()
            val req = PeriodicWorkRequestBuilder<SyncWorker>(mins, java.util.concurrent.TimeUnit.MINUTES)
                .setConstraints(constraints(wifiOnly))
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
