package com.cathode.app.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import com.cathode.app.MainActivity
import com.cathode.app.data.PrefsRepo
import com.cathode.app.data.isUnmetered
import com.cathode.app.data.putProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * App-scoped ExoPlayer + MediaSession that outlive the playing screen, so
 * audio keeps going when you browse other tabs and shows up in Android's
 * media player (quick-settings controls / lock screen). Main thread only
 * (composition already is).
 *
 * PlayerScreen attaches/detaches its PlayerView freely; the player and its
 * session are only torn down here — when a different video/source is
 * requested or the user closes it. A background coroutine keeps the server's
 * resume position fresh even while the player screen is off-screen or the
 * app is backgrounded.
 */
@UnstableApi
object Playback {
    val current: ExoPlayer? get() = player
    var videoId: String? = null
        private set
    var session: MediaSession? = null
        private set
    private var fileSource = false
    private var player: ExoPlayer? = null
    private var prefsRepo: PrefsRepo? = null
    private var saverJob: kotlinx.coroutines.Job? = null
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun ensure(
        context: Context,
        id: String,
        streamUrl: String,
        savedUri: Uri?,
        resumeMs: Long,
    ): ExoPlayer {
        val sameVideo = videoId == id && (savedUri != null) == fileSource
        val existing = player
        if (existing != null && sameVideo) return existing
        // Session wrappers listen to their player: always retire the session
        // before the player it references.
        session?.release()
        session = null
        existing?.release()
        prefsRepo ?: PrefsRepo(context.applicationContext).also { prefsRepo = it }
        val fresh =
            if (savedUri != null) {
                ExoPlayer.Builder(context)
                    .setMediaSourceFactory(
                        DefaultMediaSourceFactory(DefaultDataSource.Factory(context))
                    )
                    .build()
            } else {
                val upstream = DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(30_000).setReadTimeoutMs(60_000)
                    .setAllowCrossProtocolRedirects(true)
                val sourceFactory = DefaultMediaSourceFactory(
                    CacheDataSource.Factory()
                        .setCache(PlayerHolder.getCache(context))
                        .setUpstreamDataSourceFactory(upstream)
                )
                ExoPlayer.Builder(context).setMediaSourceFactory(sourceFactory).build()
            }
        fileSource = savedUri != null
        videoId = id
        fresh.setMediaItem(MediaItem.fromUri(savedUri ?: Uri.parse(streamUrl)))
        if (resumeMs > 0) fresh.seekTo(resumeMs)
        fresh.prepare()
        fresh.playWhenReady = true
        player = fresh
        attachSessionAndSaver(context.applicationContext, fresh, id)
        return fresh
    }

    private fun attachSessionAndSaver(app: Context, fresh: ExoPlayer, id: String) {
        // Session tap in Android's media player relaunches this activity.
        val launch = PendingIntent.getActivity(
            app, 1,
            Intent(app, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fresh.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_ENDED) return
                val finalPos = runCatching {
                    (fresh.duration.coerceAtLeast(0) / 1000L).toInt()
                }.getOrDefault(0)
                bg.launch { saveProgress(app, id, finalPos) }
            }
        })
        session = MediaSession.Builder(app, fresh)
            .setSessionActivity(launch)
            .build()
        saverJob?.cancel()
        saverJob = bg.launch {
            while (isActive) {
                delay(5_000)
                if (runCatching { fresh.isPlaying }.getOrDefault(false)) {
                    val pos = runCatching {
                        fresh.currentPosition.coerceAtLeast(0) / 1000L
                    }.getOrDefault(-1L).toInt()
                    if (pos >= 0) saveProgress(app, id, pos)
                }
            }
        }
    }

    /** Server-side resume tracking, honoring Wi-Fi-only even in background. */
    private suspend fun saveProgress(app: Context, id: String, pos: Int) {
        val p = prefsRepo?.flow?.first() ?: return
        if (p.serverUrl.isBlank()) return
        if (p.wifiOnly && !isUnmetered(app)) return
        runCatching { putProgress(p.serverUrl, id, pos) }
    }

    fun close() {
        saverJob?.cancel()
        saverJob = null
        session?.release()
        session = null
        player?.release()
        player = null
        videoId = null
    }
}
