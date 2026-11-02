package com.cathode.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import com.cathode.app.data.Video
import com.cathode.app.player.Playback
import kotlinx.coroutines.delay

/**
 * What's on while you browse: a strip above the dial with the picture's
 * thumbnail, play/pause and a progress line. Tap it to go back to the set.
 */
@UnstableApi
@Composable
fun NowPlayingBar(
    video: Video,
    thumb: String,
    lineup: Lineup,
    onExpand: () -> Unit,
    onClose: () -> Unit,
) {
    val player = Playback.current
    var playing by remember(player) { mutableStateOf(player?.isPlaying == true) }
    var progress by remember(player) { mutableFloatStateOf(0f) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        }
        player?.addListener(l)
        onDispose { runCatching { player?.removeListener(l) } }
    }
    LaunchedEffect(player) {
        while (player != null) {
            runCatching {
                val d = player.duration
                progress = if (d > 0) (player.currentPosition.toFloat() / d).coerceIn(0f, 1f) else 0f
            }
            delay(500)
        }
    }
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clip(shape)
            .background(Tube1)
            .border(1.dp, P40, shape)
            .clickable(onClick = onExpand)
    ) {
        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 72.dp, height = 44.dp).clip(RoundedCornerShape(4.dp)).background(Tube2)) {
                AsyncImage(
                    model = thumb, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().scanlines(0.3f),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Osd("CH ${lineup.label(video.channelTitle)} · NOW", color = P60, size = 17.sp, glow = false)
                Osd(video.title.orEmpty(), size = 21.sp, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = {
                val p = Playback.current ?: return@IconButton
                if (p.isPlaying) p.pause() else p.play()
            }) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play", tint = P100,
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Stop", tint = P60)
            }
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp).background(P10)) {
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(P100))
        }
    }
}

/** The OSD menu bar: three words, the active one lit. */
@Composable
fun Dial(tab: Int, onTab: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Tube).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(P20))
        Row(
            Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DialButton("On air", tab == 0) { onTab(0) }
            DialButton("Lineup", tab == 1) { onTab(1) }
            // Stats (tab 4) is a page inside Setup.
            DialButton("Setup", tab == 3 || tab == 4) { onTab(3) }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.DialButton(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.weight(1f).fillMaxHeight().clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Reverse(label.uppercase(), size = 23.sp, on = on)
    }
}
