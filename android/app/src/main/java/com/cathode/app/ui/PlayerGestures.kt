package com.cathode.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.ui.PlayerView

const val SEEK_STEP_MS = 10_000L

// After a double-tap seek, single taps on either side keep seeking for this
// long (YouTube's "tap again to add 10 more seconds").
const val SEEK_WINDOW_MS = 700L

/**
 * PlayerView with YouTube's double-tap zones: left third seeks back, right
 * third seeks forward, middle plays/pauses.
 *
 * Taps on the controller's buttons are untouched. A tap on the video itself
 * (PlayerView.performClick, which normally toggles the controls right away)
 * waits one double-tap timeout first; a second tap in that window becomes a
 * zone action instead, so the controls don't flash on and off.
 */
@UnstableApi
@SuppressLint("ViewConstructor")
class TapPlayerView(context: Context) : PlayerView(context) {
    /** -1 back, +1 forward, after the player has already seeked. */
    var onSeek: (direction: Int) -> Unit = {}

    private val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
    private var pendingClick: Runnable? = null
    private var seekUntil = 0L
    // The rest of a touch we've taken for ourselves never reaches the children.
    private var swallowing = false

    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            swallowing = false
            val zone = zoneOf(ev.x)
            val now = SystemClock.uptimeMillis()
            if (player != null && zone != 0 && now < seekUntil) {
                seek(zone)
                swallowing = true
            } else if (player != null && pendingClick != null) {
                removeCallbacks(pendingClick)
                pendingClick = null
                if (zone == 0) togglePlay() else seek(zone)
                swallowing = true
            } else {
                seekUntil = 0L
            }
        }
        if (swallowing) {
            if (ev.actionMasked == MotionEvent.ACTION_UP ||
                ev.actionMasked == MotionEvent.ACTION_CANCEL
            ) swallowing = false
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun performClick(): Boolean {
        pendingClick?.let(::removeCallbacks)
        val click = Runnable {
            pendingClick = null
            toggleControls()
        }
        pendingClick = click
        postDelayed(click, doubleTapTimeout)
        return true
    }

    private fun toggleControls() = super.performClick()

    override fun onDetachedFromWindow() {
        pendingClick?.let(::removeCallbacks)
        pendingClick = null
        super.onDetachedFromWindow()
    }

    private fun zoneOf(x: Float): Int = when {
        width <= 0 -> 0
        x < width * 0.35f -> -1
        x > width * 0.65f -> 1
        else -> 0
    }

    private fun seek(direction: Int) {
        val p = player ?: return
        val duration = p.duration
        var target = p.currentPosition + direction * SEEK_STEP_MS
        if (duration != C.TIME_UNSET) target = target.coerceAtMost(duration)
        p.seekTo(target.coerceAtLeast(0L))
        seekUntil = SystemClock.uptimeMillis() + SEEK_WINDOW_MS
        hideController()
        onSeek(direction)
    }

    private fun togglePlay() {
        // Same rules as the controller's own button (Play after the end
        // restarts, Play while buffering still counts as playing).
        val p = player ?: return
        if (Util.shouldShowPlayButton(p)) Util.handlePlayButtonAction(p)
        else Util.handlePauseButtonAction(p)
    }
}

/** YouTube-style side arc: three chasing arrows and "10 seconds". */
@Composable
fun SeekFeedback(side: Int, visible: Boolean, seconds: Int, modifier: Modifier = Modifier) {
    Box(modifier) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(100)),
            exit = fadeOut(tween(250)),
            modifier = Modifier
                .align(if (side < 0) Alignment.CenterStart else Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(0.4f),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .drawBehind {
                        // An oval centred past the screen edge, so only its
                        // curved inner side shows.
                        val left = if (side < 0) -size.width else 0f
                        drawOval(
                            Color.White.copy(alpha = 0.14f),
                            topLeft = Offset(left, -size.height * 0.2f),
                            size = Size(size.width * 2f, size.height * 1.4f),
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    SeekArrows(side)
                    Text(
                        "$seconds seconds",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun SeekArrows(side: Int) {
    val phase by rememberInfiniteTransition(label = "seek").animateFloat(
        initialValue = 0f, targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(750, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    Row {
        repeat(3) { i ->
            // Light runs outward: left to right going forward, right to left back.
            val step = if (side < 0) 2 - i else i
            val lit = phase.toInt() == step
            Icon(
                Icons.Default.PlayArrow, contentDescription = null,
                tint = Color.White.copy(alpha = if (lit) 1f else 0.35f),
                modifier = Modifier
                    .size(20.dp)
                    .rotate(if (side < 0) 180f else 0f),
            )
        }
    }
}
