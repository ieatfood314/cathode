package com.ruminate.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.ruminate.app.data.Video

@Composable
fun PlayerScreen(video: Video, streamUrl: String, onWatched: () -> Unit) {
    val context = LocalContext.current
    // Player is scoped to this stream URL: switching videos builds a new one,
    // leaving the screen releases it. The server's Range support means seeks
    // and retries resume mid-file instead of restarting.
    val player = remember(streamUrl) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(streamUrl))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(streamUrl) {
        onDispose { player.release() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp)
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black),
        )
        Spacer(Modifier.height(12.dp))
        Text(video.title.orEmpty(), style = MaterialTheme.typography.titleLarge)
        Text(
            video.channelTitle.orEmpty(),
            style = MaterialTheme.typography.bodyMedium, color = Color.Gray,
        )
        Spacer(Modifier.height(12.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            Text("Streaming from your NAS over Wi-Fi", color = AccentTeal)
            Text(
                "Seeks and reconnects resume where they stopped — a dropped " +
                    "connection never restarts the video.",
                style = MaterialTheme.typography.bodySmall, color = Color.Gray,
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onWatched) {
            Text("Mark watched")
        }
    }
}
