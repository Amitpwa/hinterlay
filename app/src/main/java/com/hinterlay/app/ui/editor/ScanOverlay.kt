package com.hinterlay.app.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** A scanning beam and corner brackets over the photo while the subject is being found. */
@Composable
fun ScanOverlay(visible: Boolean, label: String, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible, modifier = modifier, enter = fadeIn(tween(250)), exit = fadeOut(tween(450))) {
        val t = rememberInfiniteTransition(label = "scan")
        val beam by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "beam")
        val pulse by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "pulse")
        val accent = Color.White
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize().padding(10.dp)) {
                val y = size.height * beam
                // Soft light behind the beam, then the beam itself.
                drawRect(
                    Brush.verticalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.28f), Color.Transparent), startY = y - 90f, endY = y + 90f),
                    size = Size(size.width, size.height),
                )
                drawLine(accent.copy(alpha = 0.95f), Offset(0f, y), Offset(size.width, y), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                // Corner brackets.
                val len = 28.dp.toPx()
                val s = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                val c = accent.copy(alpha = pulse)
                fun corner(x: Float, yy: Float, dx: Float, dy: Float) {
                    drawLine(c, Offset(x, yy), Offset(x + dx * len, yy), s.width, StrokeCap.Round)
                    drawLine(c, Offset(x, yy), Offset(x, yy + dy * len), s.width, StrokeCap.Round)
                }
                corner(0f, 0f, 1f, 1f); corner(size.width, 0f, -1f, 1f)
                corner(0f, size.height, 1f, -1f); corner(size.width, size.height, -1f, -1f)
            }
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
            )
        }
    }
}
