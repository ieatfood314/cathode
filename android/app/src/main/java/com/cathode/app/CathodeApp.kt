package com.cathode.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.Configuration
import com.cathode.app.worker.SyncWorker

class CathodeApp : Application(), Configuration.Provider {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("cathode_downloads", "Video downloads", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()
}
