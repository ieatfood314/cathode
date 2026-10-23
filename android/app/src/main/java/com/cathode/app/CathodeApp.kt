package com.cathode.app

import android.app.Application
import android.app.NotificationManager
import androidx.work.Configuration

class CathodeApp : Application(), Configuration.Provider {
    override fun onCreate() {
        super.onCreate()
        // Channel of the never-used Media3 download service (removed in 0.3).
        getSystemService(NotificationManager::class.java)
            .deleteNotificationChannel("cathode_downloads")
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()
}
