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
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
    val color = channelColor(video.channelTitle)
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clip(shape)
            .background(Glass2)
            .border(1.dp, Glass3, shape)
            .clickable(onClick = onExpand)
    ) {
        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(width = 72.dp, height = 44.dp).clip(RoundedCornerShape(10.dp)).background(Glass3)
            ) {
                AsyncImage(
                    model = thumb, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().scanlines(0.3f),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Osd("CH ${lineup.label(video.channelTitle)} · NOW", color = color, size = 15.sp, glow = false)
                Text(
                    video.title.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = Ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = {
                val p = Playback.current ?: return@IconButton
                if (p.isPlaying) p.pause() else p.play()
            }) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play", tint = Ink,
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Stop", tint = Muted)
            }
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp).background(Glass3)) {
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(color))
        }
    }
}

/** The station's dial: four buttons, the lit one glows. */
@Composable
fun Dial(tab: Int, onTab: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Glass).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
        Row(Modifier.fillMaxWidth().height(62.dp)) {
            DialButton(Icons.Outlined.LiveTv, "On air", tab == 0) { onTab(0) }
            DialButton(Icons.Outlined.ViewList, "Lineup", tab == 1) { onTab(1) }
            DialButton(Icons.Default.BarChart, "Ratings", tab == 4) { onTab(4) }
            DialButton(Icons.Default.Tune, "Setup", tab == 3) { onTab(3) }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.DialButton(
    icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit,
) {
    Column(
        Modifier.weight(1f).fillMaxHeight().clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        // A lit pilot light over the active button.
        Box(
            Modifier.size(width = 18.dp, height = 3.dp).clip(RoundedCornerShape(2.dp))
                .background(if (on) Phosphor else Color.Transparent)
        )
        Spacer(Modifier.height(6.dp))
        Icon(icon, null, tint = if (on) Phosphor else Faint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Osd(label.uppercase(), color = if (on) Phosphor else Faint, size = 15.sp, glow = on)
    }
}
