package com.ruminate.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ruminate.app.data.Video

@Composable
fun PlayerScreen(video: Video, streamUrl: String, onWatched: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp)
    ) {
        Text(video.title.orEmpty(), style = MaterialTheme.typography.titleLarge)
        Text(
            video.channel_title.orEmpty(),
            style = MaterialTheme.typography.bodyMedium, color = Color.Gray,
        )
        Spacer(Modifier.height(12.dp))
        // ExoPlayer view is bound in MainActivity via AndroidView; this screen
        // shows metadata + stream URL for accessibility/debugging.
        GlassCard(Modifier.fillMaxWidth()) {
            Text("Playing from your NAS over Wi-Fi", color = AccentTeal)
            Text(streamUrl, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        Spacer(Modifier.height(12.dp))
        androidx.compose.material3.Button(onClick = onWatched) {
            Text("Mark watched")
        }
    }
}
