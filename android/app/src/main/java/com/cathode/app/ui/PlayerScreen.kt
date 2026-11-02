package com.cathode.app.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.delay

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
    lineup: Lineup = Lineup(emptyList()),
    // Same channel, not yet watched: what's on after this.
    upNext: List<Video> = emptyList(),
    // Other channels' next programs.
    alsoOnAir: List<Video> = emptyList(),
    thumbUrl: (Video) -> String = { "" },
    onOpen: (Video) -> Unit = {},
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
    // Leaving the player: the screen-wide scanlines cover everything again.
    DisposableEffect(Unit) { onDispose { VideoHole.rect = null } }
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
    // Double-tap seek feedback; fades once the follow-up tap window closes.
    var seekSide by remember { mutableIntStateOf(1) }
    var seekSeconds by remember { mutableIntStateOf(0) }
    var seekVisible by remember { mutableStateOf(false) }
    var seekTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(seekTick) {
        delay(SEEK_WINDOW_MS)
        seekVisible = false
    }
    // One layout for every mode, so the PlayerView is never torn down by a
    // mode change (only by viewGeneration): portrait = video on top + details
    // below; landscape = video fitted to the height; fullscreen = everything.
    val showDetails = !fullscreen && !landscape
    val videoModifier = if (landscape && !fullscreen) {
        Modifier.fillMaxHeight().aspectRatio(16f / 9f, matchHeightConstraintsFirst = true)
    } else {
        Modifier.fillMaxSize()
    }
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
                        TapPlayerView(ctx).apply {
                            this.player = player
                            useController = true
                            onSeek = { dir ->
                                // Same side while the arc is up adds on; a fresh
                                // or switched side starts at 10 again.
                                seekSeconds = if (seekVisible && seekSide == dir) {
                                    seekSeconds + (SEEK_STEP_MS / 1000).toInt()
                                } else (SEEK_STEP_MS / 1000).toInt()
                                seekSide = dir
                                seekVisible = true
                                seekTick++
                            }
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
                    // The screen-wide scanlines skip the picture itself.
                    modifier = videoModifier.videoHole(),
                )
            }
            SeekFeedback(seekSide, seekVisible, seekSeconds, videoModifier)
            ChannelFlash(video, lineup, videoModifier)
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
                        tint = P100,
                    )
                }
            }
        }
        if (showDetails) {
            ProgramCard(
                video, lineup, saveState, savedUri != null, upNext, alsoOnAir, thumbUrl, onOpen,
                Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

/**
 * The TV's own channel banner: on every new program, the channel number
 * and name glow in the corner for a few seconds, then fade.
 */
@Composable
private fun ChannelFlash(video: Video, lineup: Lineup, modifier: Modifier) {
    var visible by remember(video.id) { mutableStateOf(true) }
    LaunchedEffect(video.id) {
        delay(2800)
        visible = false
    }
    Box(modifier, contentAlignment = Alignment.TopEnd) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(tween(700))) {
            Column(
                Modifier
                    .windowInsetsPadding(WindowInsets.displayCutout)
                    .padding(top = 12.dp, end = 12.dp)
                    .background(Color.Black.copy(alpha = 0.78f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Osd("CH ${lineup.label(video.channelTitle)}", size = 48.sp)
                Osd(video.channelTitle.orEmpty().uppercase(), size = 20.sp)
            }
        }
    }
}

@Composable
private fun ProgramCard(
    video: Video,
    lineup: Lineup,
    saveState: SaveState,
    onPhone: Boolean,
    upNext: List<Video>,
    alsoOnAir: List<Video>,
    thumbUrl: (Video) -> String,
    onOpen: (Video) -> Unit,
    modifier: Modifier,
) {
    Column(modifier.background(Tube).verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(P40))
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
            Osd(
                "CH ${lineup.label(video.channelTitle)}  ${video.channelTitle.orEmpty().uppercase()}",
                color = P60, size = 22.sp, glow = false,
            )
            Osd(video.title.orEmpty(), size = 32.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Gap(12)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (video.duration > 0) Chip(formatDuration(video.duration))
                uploadedLabel(video)?.let { Chip(it.uppercase()) }
                when {
                    saveState is SaveState.Saving -> Reverse("● REC ${(saveState.fraction * 100).toInt()}%", size = 20.sp, blink = true)
                    saveState is SaveState.Queued -> Chip("QUEUED")
                    onPhone -> Reverse("ON PHONE", size = 20.sp)
                }
            }
        }
        if (upNext.isNotEmpty()) {
            SectionLabel("Later on CH ${lineup.label(video.channelTitle)}", Modifier.padding(horizontal = 20.dp))
            for (v in upNext) UpNextRow(v, thumbUrl(v), null) { onOpen(v) }
        }
        if (alsoOnAir.isNotEmpty()) {
            SectionLabel("Also on air", Modifier.padding(horizontal = 20.dp))
            for (v in alsoOnAir) UpNextRow(v, thumbUrl(v), lineup.label(v.channelTitle)) { onOpen(v) }
        }
        Gap(24)
    }
}

@Composable
private fun Chip(text: String) {
    Box(Modifier.border(1.dp, P40).padding(horizontal = 6.dp)) {
        Osd(text, color = P80, size = 20.sp, glow = false)
    }
}

@Composable
private fun UpNextRow(v: Video, thumb: String, channel: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 112.dp, height = 63.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Tube2)
                .border(1.dp, P20, RoundedCornerShape(4.dp))
        ) {
            AsyncImage(
                model = thumb, contentDescription = null, contentScale = ContentScale.Crop,
                colorFilter = PhosphorTint,
                modifier = Modifier.fillMaxSize().alpha(0.8f).scanlines(0.3f),
            )
            val f = watchFraction(v)
            if (f > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(f).height(3.dp).background(P100))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            if (channel != null) {
                Osd("CH $channel  ${v.channelTitle.orEmpty().uppercase()}", color = P60, size = 17.sp, glow = false)
            }
            Osd(v.title.orEmpty(), size = 21.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, glow = false)
            Osd(
                listOfNotNull(formatDuration(v.duration).ifEmpty { null }, uploadedLabel(v)?.uppercase())
                    .joinToString(" · "),
                color = P60, size = 17.sp, glow = false,
            )
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
