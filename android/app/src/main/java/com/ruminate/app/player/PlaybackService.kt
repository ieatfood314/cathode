package com.ruminate.app.player

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Hosts the Playback singleton's session so Android's media player (quick
 * settings, lock screen, Bluetooth/wear pickers) can pause/resume the app's
 * playback with the app closed or in the background. The session itself is
 * built and kept current inside Playback.ensure(); this service only serves
 * it and gains foreground protection from MediaSessionService's built-in
 * media notification.
 */
class PlaybackService : MediaSessionService() {
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        Playback.session
}
