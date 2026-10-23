package com.cathode.app.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cathode.app.data.Library
import com.cathode.app.data.PrefsRepo
import com.cathode.app.data.VideoStore
import com.cathode.app.data.networkAllowed
import com.cathode.app.data.resolveStore
import com.cathode.app.data.triggerServerPoll
import kotlinx.coroutines.flow.first

/**
 * SyncWorker: runs on app open + on the polling schedule.
 * - Wi-Fi only ON => Constraints.UNMETERED: zero cellular data, enforced by
 *   the OS, double-checked with isUnmetered() (covers VPN/metered hotspots).
 * - Asks the NAS to scan (it downloads over its own wired link), then
 *   replays progress queued offline and refreshes the library this phone
 *   keeps on disk — so the app opens up to date, even with no network.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val prefs = PrefsRepo(applicationContext).flow.first()
        if (prefs.serverUrl.isBlank()) return Result.success()
        if (!networkAllowed(applicationContext, prefs.wifiOnly)) {
            return Result.retry() // WorkManager reschedules once the network allows it
        }
        runCatching { triggerServerPoll(prefs.serverUrl) }
        val saved = runCatching {
            VideoStore.listSaved(applicationContext, resolveStore(applicationContext, prefs.storageUri))
                .map { it.videoId }.toSet()
        }.getOrDefault(emptySet())
        return when {
            Library.sync(applicationContext, saved).isSuccess -> Result.success()
            runAttemptCount >= 5 -> Result.failure()
            else -> Result.retry()
        }
    }

    companion object {
        const val ON_OPEN = "cathode-sync-onopen"
        const val PERIODIC = "cathode-sync-periodic"

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
