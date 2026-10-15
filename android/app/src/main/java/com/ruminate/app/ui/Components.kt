package com.ruminate.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * GlassCard: frosted translucent panel over OLED black. A subtle gradient
 * border + blur behind gives the glass effect without an expensive
 * full-screen blur pass.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0x33FFFFFF), Color(0x0DFFFFFF), Color(0x1A8B7CFF))
                )
            )
            .padding(1.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(Color(0xB3121218))
            .padding(16.dp)
    ) {
        Column { content() }
    }
}
