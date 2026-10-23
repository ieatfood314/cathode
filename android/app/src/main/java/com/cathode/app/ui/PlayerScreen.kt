package com.cathode.app.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.cathode.app.data.SaveState
import com.cathode.app.data.Video
import com.cathode.app.player.Playback

private const val RESUME_WINDOW = 0.95

@UnstableApi
@Composable
fun PlayerScreen(
    video: Video,
    streamUrl: String,
    artworkUrl: String?,
    savedUri: Uri?,
    saveState: SaveState,
    // Hoisted to MainActivity so it can hide the nav bar while fullscreen.
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    onClose: () -> Unit,
    onLeave: (positionSeconds: Int) -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // The player lives in Playback (outlives this screen), so browsing other
    // tabs never stops playback. Only a video/source switch or an explicit
    // close tears it down. Saved file plays straight from storage (zero
    // network); otherwise stream through the playback cache. When the local
    // file appears or goes mid-play, ensure() swaps players at the current
    // position and the AndroidView below rebinds via `update`.
    val player = remember(streamUrl, savedUri, video.id) {
        // Finished videos start over; everything else resumes exactly where
        // playback last stopped — across app restarts and other videos in
        // between.
        val nearEnd = video.duration > 0 &&
            video.positionSeconds >= video.duration * RESUME_WINDOW
        val resumeMs = if (video.watched == 1 || nearEnd) 0L
        else video.positionSeconds.coerceAtLeast(0) * 1000L
        Playback.ensure(context, video, streamUrl, savedUri, resumeMs, artworkUrl)
    }

    // Save progress on leaving (tab switch, app close). While playing,
    // Playback's own saver keeps it fresh every few seconds.
    DisposableEffect(player, video.id) {
        onDispose {
            // A released player throws on reads; -1 means it was already
            // switched/closed elsewhere and the caller already flushed the
            // real position — skip rather than overwrite progress with 0.
            val pos = runCatching { player.currentPosition.coerceAtLeast(0) }.getOrDefault(-1L)
            if (pos >= 0) onLeave((pos / 1000L).toInt())
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

    // PlayerView keeps its own idea of fullscreen (the button icon) and has
    // no setter for it. When fullscreen ends from outside its button (Back),
    // a fresh view starts in the matching "not fullscreen" state.
    var viewGeneration by remember { mutableIntStateOf(0) }
    BackHandler(enabled = fullscreen) {
        viewGeneration++
        onFullscreenChange(false)
    }

    // Fullscreen: immersive + sensor landscape while active; restore on
    // dispose. The dispose branch restores unconditionally (no fullscreen
    // check): reads inside onDispose see the CURRENT state, so a
    // fullscreen=true exit path would otherwise never restore system
    // bars/orientation.
    DisposableEffect(activity, fullscreen) {
        val window = activity?.window
        if (fullscreen && window != null) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
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
                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    WindowCompat.setDecorFitsSystemWindows(window, true)
                    setCutoutMode(window, fill = false)
                    WindowInsetsControllerCompat(window, window.decorView)
                        .show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
    }

    var controlsVisible by remember { mutableStateOf(true) }
    // One layout for every mode, so the PlayerView is never torn down by a
    // mode change (only by viewGeneration): portrait = video on top + details
    // below; landscape = video fitted to the height; fullscreen = everything.
    val showDetails = !fullscreen && !landscape
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Box(
            if (showDetails) Modifier.fillMaxWidth().aspectRatio(16f / 9f) else Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            key(viewGeneration) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = player
                            useController = true
                            setFullscreenButtonClickListener { want -> onFullscreenChange(want) }
                            // The close button rides along with the player's own
                            // controls: hidden until you tap the video.
                            setControllerVisibilityListener(
                                PlayerView.ControllerVisibilityListener { visibility ->
                                    controlsVisible = visibility == android.view.View.VISIBLE
                                }
                            )
                        }
                    },
                    // Rebind whenever `player` changes (source swap, re-entry):
                    // factory runs once, so without this the view would hold the
                    // old, released player — black screen while audio played on.
                    update = { view ->
                        if (view.player !== player) view.player = player
                        view.keepScreenOn = playing
                    },
                    onRelease = { view -> view.player = null },
                    modifier = if (landscape && !fullscreen) {
                        Modifier.fillMaxHeight().aspectRatio(16f / 9f, matchHeightConstraintsFirst = true)
                    } else {
                        Modifier.fillMaxSize()
                    },
                )
            }
            if (controlsVisible) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .windowInsetsPadding(WindowInsets.displayCutout)
                        .padding(4.dp),
                ) {
                    Icon(
                        Icons.Default.Close, contentDescription = "Close player",
                        tint = Color.White,
                    )
                }
            }
        }
        if (showDetails) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
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
                        if (savedUri != null) "Playing the file on your phone — no network needed"
                        else "Streaming from your server",
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
