package com.cathode.app.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.view.WindowManager
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.cathode.app.data.SaveState
import com.cathode.app.data.Video
import com.cathode.app.player.Playback
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val RESUME_WINDOW = 0.95

@UnstableApi
@Composable
fun PlayerScreen(
    video: Video,
    streamUrl: String,
    artworkUrl: String?,
    savedUri: Uri?,
    saveState: SaveState,
    showSave: Boolean,
    // Hoisted to MainActivity so it can hide the nav bar while fullscreen.
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    onClose: () -> Unit,
    onProgress: (positionSeconds: Int) -> Boolean,
    onWatched: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity

    // The player lives in Playback (outlives this screen), so browsing other
    // tabs never stops playback. Only a video/source switch or an explicit
    // close tears it down. Saved file plays straight from storage (zero
    // network); otherwise stream through the playback cache. Note: when the
    // local file finishes resolving a beat after first composition
    // (savedUri null -> uri), ensure() swaps players here, and the AndroidView
    // below rebinds via `update` — the old detached-player black screen.
    val player = remember(streamUrl, savedUri, video.id) {
        // Finished videos start over; everything else resumes exactly where
        // playback last stopped — across app restarts and other videos in
        // between.
        val nearEnd = video.duration > 0 &&
            video.positionSeconds >= video.duration * RESUME_WINDOW
        val resumeMs = if (video.watched == 1 || nearEnd) 0L
        else video.positionSeconds.coerceAtLeast(0) * 1000L
        Playback.ensure(
            context, video.id, streamUrl, savedUri, resumeMs,
            title = video.title, channel = video.channelTitle, artworkUrl = artworkUrl,
        )
    }

    // Save progress on leaving (tab switch, app close): the player outlives
    // the screen but may already have been switched/released during the same
    // recomposition, so guard the read. Order: this disposal runs before the
    // text above can matter — flush whatever we can read.
    DisposableEffect(player, video.id) {
        onDispose {
            // A released player throws on reads; -1 means it was already
            // switched/closed elsewhere and the caller already flushed the
            // real position — skip rather than overwrite progress with 0.
            val pos = runCatching { player.currentPosition.coerceAtLeast(0) }.getOrDefault(-1L)
            if (pos >= 0) onProgress((pos / 1000L).toInt())
        }
    }
    // ...and every 5s while playing.
    LaunchedEffect(player, video.id) {
        var lastSaved = -1
        while (isActive) {
            delay(5_000)
            val playing = runCatching { player.isPlaying }.getOrDefault(false)
            if (playing) {
                val p = runCatching {
                    player.currentPosition.coerceAtLeast(0) / 1000L
                }.getOrDefault(0L).toInt()
                if (p != lastSaved) {
                    lastSaved = p
                    onProgress(p)
                }
            }
        }
    }
    // Keep the display awake while a video is actually playing (Android's
    // dim/sleep timeout otherwise blanks it mid-video); paused or ended lets
    // the screen time out normally again.
    var playing by remember(player) { mutableStateOf(player.isPlaying) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }
        }
        player.addListener(listener)
        onDispose { runCatching { player.removeListener(listener) } }
    }

    // Auto-mark watched when the video finishes — no button to press.
    DisposableEffect(player, video.id) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) onWatched()
            }
        }
        player.addListener(listener)
        onDispose { runCatching { player.removeListener(listener) } }
    }

    // Fullscreen: immersive + sensor landscape while active; hook up
    // PlayerView's native fullscreen button and restore on dispose.
    // The dispose branch restores unconditionally (no fullscreen check):
    // reads inside onDispose see the CURRENT state, so a fullscreen=true
    // exit path would otherwise never restore system bars/orientation.
    DisposableEffect(activity, fullscreen) {
        val window = activity?.window
        if (fullscreen && window != null) {
            activity?.requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            WindowCompat.setDecorFitsSystemWindows(window, false)
            // Draw under the camera cutout too; otherwise Android keeps that
            // edge black in landscape.
            setCutoutMode(window, fill = true)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat
                    .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        onDispose {
            if (window != null) {
                runCatching {
                    activity?.requestedOrientation =
                        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    WindowCompat.setDecorFitsSystemWindows(window, true)
                    setCutoutMode(window, fill = false)
                    WindowInsetsControllerCompat(window, window.decorView)
                        .show(WindowInsetsCompat.Type.systemBars())
                }
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
                    setFullscreenButtonClickListener { want -> onFullscreenChange(want) }
                }
            },
            // Rebind whenever `player` changes (source swap, re-entry):
            // factory runs once, so without this the view would hold the old,
            // released player — black screen while audio played on.
            // Fullscreen zooms to fill the display (crops the few % a 16:9
            // video doesn't cover on a tall phone) instead of black bars.
            update = { view ->
                if (view.player !== player) view.player = player
                view.keepScreenOn = playing
                view.resizeMode = if (fullscreen) AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                else AspectRatioFrameLayout.RESIZE_MODE_FIT
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
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(4.dp),
        ) {
            Icon(
                Icons.Default.Close, contentDescription = "Close player",
                tint = Color.White,
            )
        }
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
                            "in the player for landscape; audio keeps going if you " +
                            "browse other tabs.",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

private fun setCutoutMode(window: android.view.Window, fill: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
    window.attributes = window.attributes.apply {
        layoutInDisplayCutoutMode = when {
            !fill -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }
}
