package com.cathode.app.worker

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cathode.app.R
import com.cathode.app.data.Library
import com.cathode.app.data.OfflineRepo
import com.cathode.app.data.PrefsRepo
import com.cathode.app.data.SaveState
import com.cathode.app.data.Video
import com.cathode.app.data.networkAllowed
import com.cathode.app.data.resolveStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * DownloadWorker: saves videos to the phone, and keeps going after the app
 * is closed. Every request joins one queue and runs in turn: either videos
 * you tapped Save on, or the new arrivals auto-save picks after a sync.
 *
 * It runs as a foreground service (progress notification, no time limit)
 * when Android allows it: always while the app is open, and from the
 * background once battery optimization is off for the app (Settings →
 * Background downloads). Otherwise it's a plain job the system stops after
 * about 10 minutes; stopped work is rescheduled and the partial file
 * resumes, so a big video still lands, just in pieces.
 */
class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val repo = PrefsRepo(context)
        val prefs = repo.flow.first()
        if (prefs.serverUrl.isBlank() || !prefs.saveOnPhone) return Result.success()
        val loc = resolveStore(context, prefs.storageUri)
        withContext(Dispatchers.IO) {
            Library.load(context)
            OfflineRepo.rescan(context, loc)
        }
        val feed = Library.videos.value
        val tapped = inputData.getStringArray(KEY_IDS).orEmpty().mapNotNull { Library.find(it) }

        // Auto-save's baseline is the newest server download time already
        // handled. The very first run only sets it (no surprise mass
        // download); anything not saved by the end of a run holds it just
        // below itself, so the next sync tries that video again.
        val auto = inputData.getBoolean(KEY_AUTO, false) && prefs.autoSaveNew
        val newest = feed.maxOfOrNull { it.downloadedAt } ?: 0L
        val baseline = prefs.autoSaveBaseline
        if (auto && baseline <= 0L && newest > 0L) repo.setBaseline(newest)
        val picks = if (auto && baseline > 0L) OfflineRepo.autoSavePicks(feed, baseline) else emptyList()

        val queue = (tapped + picks).distinctBy { it.id }
            .filter { OfflineRepo.stateOf(it.id) !is SaveState.Saved }
        if (queue.isNotEmpty()) {
            OfflineRepo.markQueued(queue.map { it.id })
            if (!networkAllowed(context, prefs.wifiOnly)) return Result.retry()
            val foreground = foregroundAllowed(context) &&
                runCatching { setForeground(info(queue.first(), 1, queue.size, 0f)) }.isSuccess
            coroutineScope {
                var current = queue.first() to 1
                val ticker = if (foreground) launch {
                    var shown = ""
                    while (isActive) {
                        delay(1_000)
                        val (v, n) = current
                        val f = (OfflineRepo.stateOf(v.id) as? SaveState.Saving)?.fraction ?: 0f
                        val key = "${v.id} ${(f * 100).toInt()}"
                        if (key != shown) {
                            shown = key
                            runCatching { setForeground(info(v, n, queue.size, f)) }
                        }
                    }
                } else null
                withContext(Dispatchers.IO) {
                    for ((i, v) in queue.withIndex()) {
                        if (isStopped || !networkAllowed(context, prefs.wifiOnly)) break
                        current = v to i + 1
                        OfflineRepo.save(
                            context, loc, v, "${prefs.serverUrl}/api/videos/${v.id}/stream",
                            prefs.wifiOnly, stop = { isStopped },
                        )
                    }
                }
                ticker?.cancel()
            }
        }

        if (auto && baseline > 0L) {
            val held = picks.filter { OfflineRepo.stateOf(it.id) !is SaveState.Saved }
                .minOfOrNull { it.downloadedAt - 1 }
            repo.setBaseline(minOf(maxOf(baseline, newest), held ?: Long.MAX_VALUE))
        }
        // Wi-Fi went away mid-queue: run again once it's back. A failed save
        // doesn't retry here, or one broken video would block the queue.
        val waiting = queue.any { OfflineRepo.stateOf(it.id) is SaveState.Queued }
        return if (waiting && !networkAllowed(context, prefs.wifiOnly)) Result.retry() else Result.success()
    }

    private fun info(v: Video, n: Int, total: Int, fraction: Float): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
        )
        // Tapping it brings the app up as it was (its launcher intent).
        val open = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
            ?.let { PendingIntent.getActivity(applicationContext, 0, it, PendingIntent.FLAG_IMMUTABLE) }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (total > 1) "Saving $n of $total" else "Saving")
            .setContentText(v.title.orEmpty())
            .setProgress(100, (fraction * 100).toInt(), fraction <= 0f)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val QUEUE = "cathode-downloads"
        private const val CHANNEL = "cathode_saves"
        private const val NOTIFICATION_ID = 2
        private const val KEY_IDS = "ids"
        private const val KEY_AUTO = "auto"

        /**
         * Whether Android lets this job become a foreground service now: the
         * app is on screen or already in one (playing), or battery
         * optimization is off. Asking when it's refused leaves WorkManager
         * half-promoted (a job the system no longer stops or reschedules),
         * so don't ask then.
         */
        fun foregroundAllowed(context: Context): Boolean {
            val power = context.getSystemService(PowerManager::class.java)
            if (power.isIgnoringBatteryOptimizations(context.packageName)) return true
            val me = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(me)
            return me.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
        }

        /** Save these videos to the phone. */
        fun save(context: Context, ids: List<String>, wifiOnly: Boolean) {
            OfflineRepo.markQueued(ids)
            enqueue(context, workDataOf(KEY_IDS to ids.toTypedArray()), wifiOnly)
        }

        /** Save whatever arrived since the last run (after each sync). */
        fun saveNew(context: Context, wifiOnly: Boolean) =
            enqueue(context, workDataOf(KEY_AUTO to true), wifiOnly)

        // Appended, never dropped: a tap while a long download runs still
        // gets its turn. Workers only ever end in success or retry, so the
        // queue never fails as a whole.
        private fun enqueue(context: Context, data: androidx.work.Data, wifiOnly: Boolean) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                QUEUE,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<DownloadWorker>()
                    .setInputData(data)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                            .build()
                    )
                    .build(),
            )
        }
    }
}
