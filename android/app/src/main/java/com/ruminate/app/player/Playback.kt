package com.ruminate.app.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * App-scoped ExoPlayer that outlives the playing screen, so audio keeps going
 * when you browse other tabs. Main thread only (composition already is).
 *
 * PlayerScreen attaches/detaches its PlayerView freely; the player itself is
 * only torn down here — when a different video/source is requested or the
 * user closes it.
 */
@UnstableApi
object Playback {
    val current: ExoPlayer? get() = player
    var videoId: String? = null
        private set
    private var fileSource = false
    private var player: ExoPlayer? = null

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
        return fresh
    }

    fun close() {
        player?.release()
        player = null
        videoId = null
    }
}
