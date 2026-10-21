package com.cathode.app.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cathode.app.data.PrefsRepo
import com.cathode.app.data.fetchFeed
import com.cathode.app.data.isUnmetered
import com.cathode.app.data.triggerServerPoll
import kotlinx.coroutines.flow.first

/**
 * SyncWorker: runs on app open + on a user-configurable schedule.
 * - Constraints.UNMETERED => zero cellular data, enforced by the OS.
 * - Double-checked with isUnmetered() (covers VPN/metered Wi-Fi hotspots).
 * - First triggers the NAS server poll (server downloads over its own wired
 *   link), then refreshes the local feed index.
 * - Media files themselves stream with Range resume (see Api.downloadResumable
 *   + Media3 DownloadManager with REQUIREMENT_NETWORK_UNMETERED), so bad
 *   Wi-Fi never restarts a file from zero.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val prefs = PrefsRepo(applicationContext).flow.first()
        if (prefs.serverUrl.isBlank()) return Result.success()
        if (prefs.wifiOnly && !isUnmetered(applicationContext)) {
            return Result.retry() // WorkManager reschedules when UNMETERED returns
        }
        return try {
            if (prefs.autoDownload) {
                runCatching { triggerServerPoll(prefs.serverUrl) }
            }
            runCatching { fetchFeed(prefs.serverUrl) }
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount >= 5) Result.failure() else Result.retry()
        }
    }

    companion object {
        const val ON_OPEN = "cathode-sync-onopen"
        const val PERIODIC = "cathode-sync-periodic"

        private fun wifiConstraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED) // Wi-Fi only, OS-enforced
            .setRequiresBatteryNotLow(false)
            .build()

        fun runOnOpen(context: Context) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(wifiConstraints())
                    .addTag(ON_OPEN)
                    .build()
            )
        }

        /** intervalMinutes comes straight from Settings (default 30). Min 15 (WorkManager limit). */
        fun schedulePeriodic(context: Context, intervalMinutes: Int, mode: String) {
            val wm = WorkManager.getInstance(context)
            if (mode == "on_open") {
                wm.cancelUniqueWork(PERIODIC)
                return
            }
            val mins = intervalMinutes.coerceAtLeast(15).toLong()
            val req = PeriodicWorkRequestBuilder<SyncWorker>(mins, java.util.concurrent.TimeUnit.MINUTES)
                .setConstraints(wifiConstraints())
                .addTag(PERIODIC)
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
