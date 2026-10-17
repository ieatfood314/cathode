package com.ruminate.app.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.ruminate.app.data.SaveState
import com.ruminate.app.data.Video
import com.ruminate.app.player.PlayerHolder
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val RESUME_WINDOW = 0.95

@UnstableApi
@Composable
fun PlayerScreen(
    video: Video,
    streamUrl: String,
    savedUri: Uri?,
    saveState: SaveState,
    showSave: Boolean,
    onProgress: (positionSeconds: Int) -> Boolean,
    onWatched: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var fullscreen by remember { mutableStateOf(false) }

    // Saved file plays straight from storage (zero network). Otherwise stream
    // through the playback cache. Keyed on both so save-while-watching
    // switches sources cleanly.
    val player = remember(streamUrl, savedUri, video.id) {
        // Finished videos start over; everything else resumes exactly where
        // playback last stopped — across app restarts and other videos in
        // between.
        val nearEnd = video.duration > 0 &&
            video.positionSeconds >= video.duration * RESUME_WINDOW
        val resumeMs = if (video.watched == 1 || nearEnd) 0L
        else video.positionSeconds.coerceAtLeast(0) * 1000L
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
        }.apply {
            setMediaItem(MediaItem.fromUri(savedUri ?: Uri.parse(streamUrl)))
            if (resumeMs > 0) seekTo(resumeMs)
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(streamUrl, savedUri) {
        onDispose { player.release() }
    }

    // Save progress on leaving (tab switch, back, app close): dispose runs
    // before the release above, so the player is still readable.
    DisposableEffect(player, video.id) {
        onDispose {
            onProgress((player.currentPosition.coerceAtLeast(0) / 1000L).toInt())
        }
    }
    // ...and every 5s while playing.
    LaunchedEffect(player, video.id) {
        var lastSaved = -1
        while (isActive) {
            delay(5_000)
            if (player.isPlaying) {
                val p = (player.currentPosition.coerceAtLeast(0) / 1000L).toInt()
                if (p != lastSaved) {
                    lastSaved = p
                    onProgress(p)
                }
            }
        }
    }
    // Auto-mark watched when the video finishes — no button to press.
    DisposableEffect(player, video.id) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) onWatched()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Fullscreen: immersive + sensor landscape while active; hook up
    // PlayerView's native fullscreen button and restore on dispose.
    DisposableEffect(activity, fullscreen) {
        val window = activity?.window
        if (fullscreen && window != null) {
            activity!!.requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat
                    .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        onDispose {
            if (fullscreen && window != null) {
                activity!!.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                WindowCompat.setDecorFitsSystemWindows(window, true)
                WindowInsetsControllerCompat(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    setFullscreenButtonClickListener { want -> fullscreen = want }
                }
            },
            modifier = if (fullscreen) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .align(Alignment.TopCenter)
            },
        )
        if (!fullscreen) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(video.title.orEmpty(), style = MaterialTheme.typography.titleLarge)
                        Text(
                            video.channelTitle.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium, color = Color.Gray,
                        )
                    }
                    if (saveState is SaveState.Saved) {
                        Icon(
                            Icons.Default.Check, contentDescription = "Saved on phone",
                            tint = AccentTeal, modifier = Modifier.size(24.dp),
                        )
                    } else if (saveState is SaveState.Saving) {
                        Text("Saving…", color = AccentTeal)
                    }
                }
                Spacer(Modifier.height(12.dp))
                GlassCard(Modifier.fillMaxWidth()) {
                    Text(
                        when {
                            saveState is SaveState.Saved ->
                                "Playing the file on your phone — no network needed"
                            else -> "Playing from your server over Wi-Fi"
                        },
                        color = AccentTeal,
                    )
                    Text(
                        "Resumes where you stopped — switching videos or restarting " +
                            "the app never loses your place. Tap the fullscreen button " +
                            "in the player for landscape.",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
