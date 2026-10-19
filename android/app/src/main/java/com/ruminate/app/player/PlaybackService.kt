package com.ruminate.app.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.ruminate.app.R

/**
 * Hosts the Playback singleton's session so Android's media player (quick
 * settings, lock screen, Bluetooth/wear pickers) can pause/resume the app's
 * playback with the app closed or in the background. The session itself is
 * built and kept current inside Playback.ensure().
 */
class PlaybackService : MediaSessionService() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        startForegroundImmediately()
        watchdogNoSession()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        Playback.session

    /** Media3 promotes the service only once its session is playing, which
     *  arrives late (buffering, cold server) or never (failed open). Android
     *  kills the process 10s after startForegroundService() without a
     *  foreground tick, so take it right here. We post with Media3's own
     *  notification id, so its media notification later replaces this silent
     *  placeholder in place — no duplicate, no flicker. */
    private fun startForegroundImmediately() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID,
                "Playback",
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        val placeholder = NotificationCompat.Builder(
            this, DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID
        )
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setSilent(true)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this,
            DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID,
            placeholder,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    /** Service started but no session ever materialized (open failed, user
     *  backed out first): drop the placeholder and retire quietly. */
    private fun watchdogNoSession() {
        handler.postDelayed({
            if (Playback.session == null && Playback.current == null) {
                ServiceCompat.stopForeground(
                    this@PlaybackService, ServiceCompat.STOP_FOREGROUND_REMOVE
                )
                stopSelf()
            }
        }, 30_000L)
    }
}
