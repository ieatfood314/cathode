package com.ruminate.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.ruminate.app.data.SaveState
import com.ruminate.app.data.Video
import com.ruminate.app.player.PlayerHolder

@UnstableApi
@Composable
fun PlayerScreen(
    video: Video,
    streamUrl: String,
    savedUri: android.net.Uri?,
    saveState: SaveState,
    showSave: Boolean,
    onSave: () -> Unit,
    onRemoveSaved: () -> Unit,
    onWatched: () -> Unit,
) {
    val context = LocalContext.current
    // Saved file plays straight from storage (zero network). Otherwise stream
    // through the playback cache. Keyed on both so save-while-watching
    // switches sources cleanly.
    val player = remember(streamUrl, savedUri) {
        if (savedUri != null) {
            ExoPlayer.Builder(context)
                .setMediaSourceFactory(
                    androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                        androidx.media3.datasource.DefaultDataSource.Factory(context)
                    )
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
            setMediaItem(MediaItem.fromUri(savedUri ?: android.net.Uri.parse(streamUrl)))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(streamUrl, savedUri) {
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(video.title.orEmpty(), style = MaterialTheme.typography.titleLarge)
                Text(
                    video.channelTitle.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium, color = Color.Gray,
                )
            }
            if (showSave) {
                when (saveState) {
                    is SaveState.Saved -> IconButton(onClick = onRemoveSaved) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove phone copy")
                    }
                    is SaveState.Saving -> IconButton(onClick = {}, enabled = false) {
                        Icon(Icons.Default.Download, contentDescription = "Saving…", tint = Color.Gray)
                    }
                    else -> IconButton(onClick = onSave) {
                        Icon(Icons.Default.Download, contentDescription = "Save on phone", tint = AccentTeal)
                    }
                }
            } else if (saveState is SaveState.Saved) {
                Icon(Icons.Default.Check, contentDescription = "Saved on phone", tint = AccentTeal)
            }
        }
        Spacer(Modifier.height(12.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            Text(
                if (savedUri != null) "Playing the file on your phone — no network needed"
                else "Playing from your server over Wi-Fi",
                color = AccentTeal,
            )
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
