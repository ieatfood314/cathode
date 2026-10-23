package com.cathode.app.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import com.cathode.app.MainActivity
import com.cathode.app.data.Library
import com.cathode.app.data.Video
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
 * requested or the user closes it. A saver keeps the resume position fresh
 * (server, or queued offline) even while the player screen is off-screen or
 * the app is backgrounded.
 */
@UnstableApi
object Playback {
    val current: ExoPlayer? get() = player
    var videoId: String? = null
        private set
    /** What's loaded, so a recreated activity (or a tap on the media
     *  notification) can get back to the player. */
    var video: Video? = null
        private set
    var session: MediaSession? = null
        private set
    private var fileSource = false
    /** Running PlaybackService, so each new session lands in Android's media
     *  player right away instead of waiting for a controller to ask for it. */
    var service: PlaybackService? = null
        set(value) {
            field = value
            session?.let { value?.addSession(it) }
        }
    private var player: ExoPlayer? = null
    private var saverJob: kotlinx.coroutines.Job? = null
    // ExoPlayer must only be touched on the main thread: an IO-thread saver
    // threw on every read, so background progress was never saved.
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun ensure(
        context: Context,
        video: Video,
        streamUrl: String,
        savedUri: Uri?,
        resumeMs: Long,
        artworkUrl: String?,
    ): ExoPlayer {
        val id = video.id
        val sameVideo = videoId == id && (savedUri != null) == fileSource
        val existing = player
        if (existing != null && sameVideo) return existing
        // Same video, new source (its phone copy just finished, or was
        // removed): carry on from where it is *now*. The position it was
        // opened at is minutes stale by then.
        val sourceSwap = existing != null && videoId == id
        val startMs = if (sourceSwap) existing?.currentPosition ?: resumeMs else resumeMs
        val keepPlaying = if (sourceSwap) existing?.playWhenReady ?: true else true
        // Session wrappers listen to their player: always retire the session
        // before the player it references.
        retireSession()
        existing?.release()
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
                        .setCache(StreamCache.get(context))
                        .setUpstreamDataSourceFactory(upstream)
                )
                ExoPlayer.Builder(context).setMediaSourceFactory(sourceFactory).build()
            }
        // Screen-off / backgrounded playback keeps the CPU (and Wi-Fi when
        // streaming) awake instead of stalling mid-video.
        fresh.setWakeMode(if (savedUri != null) C.WAKE_MODE_LOCAL else C.WAKE_MODE_NETWORK)
        fileSource = savedUri != null
        videoId = id
        this.video = video
        // Title/channel/thumbnail are what Android's media player shows.
        val metadata = MediaMetadata.Builder()
            .setTitle(video.title)
            .setArtist(video.channelTitle)
            .setArtworkUri(artworkUrl?.let(Uri::parse))
            .build()
        fresh.setMediaItem(
            MediaItem.Builder()
                .setMediaId(id)
                .setUri(savedUri ?: Uri.parse(streamUrl))
                .setMediaMetadata(metadata)
                .build()
        )
        if (startMs > 0) fresh.seekTo(startMs)
        fresh.prepare()
        fresh.playWhenReady = keepPlaying
        player = fresh
        attachSessionAndSaver(context.applicationContext, fresh, id)
        return fresh
    }

    private fun attachSessionAndSaver(app: Context, fresh: ExoPlayer, id: String) {
        // Session tap in Android's media player brings back this video's player.
        val launch = PendingIntent.getActivity(
            app, 1,
            Intent(app, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SHOW_PLAYER, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fresh.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_ENDED) return
                // Finished, wherever you were in the app: watched (which also
                // frees the server copy when that setting is on).
                val finalPos = (fresh.duration.coerceAtLeast(0) / 1000L).toInt()
                Library.scope.launch {
                    if (finalPos > 0) Library.reportProgress(app, id, finalPos)
                    Library.markWatched(app, id, true)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Paused (headphones out, notification, lock screen): save now,
                // not up to 5 s later — the app may be killed in between.
                if (isPlaying || fresh.playbackState == Player.STATE_ENDED) return
                report(app, id, fresh)
            }
        })
        session = MediaSession.Builder(app, fresh)
            .setSessionActivity(launch)
            .build()
            .also { service?.addSession(it) }
        saverJob?.cancel()
        saverJob = main.launch {
            while (isActive) {
                delay(5_000)
                if (player === fresh && fresh.isPlaying) report(app, id, fresh)
            }
        }
    }

    private fun report(app: Context, id: String, p: ExoPlayer) {
        val pos = (p.currentPosition.coerceAtLeast(0) / 1000L).toInt()
        Library.scope.launch { Library.reportProgress(app, id, pos) }
    }

    private fun retireSession() {
        session?.let { s ->
            service?.removeSession(s)
            s.release()
        }
        session = null
    }

    fun close() {
        saverJob?.cancel()
        saverJob = null
        retireSession()
        player?.release()
        player = null
        videoId = null
        video = null
    }
}
