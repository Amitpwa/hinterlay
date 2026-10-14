package com.hinterlay.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * What a screen shows when it has nothing yet: a small line drawing of the app's idea (a photo frame, text tucked behind a
 * round subject) that floats gently, a title, a sentence, and one clear next step.
 */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    actionIcon: ImageVector? = null,
    onAction: (() -> Unit)? = null,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val t = rememberInfiniteTransition(label = "empty")
    val float by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "float")
    Column(
        modifier.fillMaxSize().padding(horizontal = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Canvas(Modifier.size(168.dp, 148.dp)) {
            val w = size.width
            val h = size.height
            val line = Stroke(width = 1.5.dp.toPx())
            val faint = ink.copy(alpha = 0.22f)
            val mid = ink.copy(alpha = 0.55f)
            val dy = (float - 0.5f) * 8.dp.toPx()
            // Two frames behind, tilted, like a stack of photos.
            rotate(-9f, Offset(w / 2, h / 2)) {
                drawRoundRect(faint, Offset(w * 0.20f, h * 0.10f + dy * 0.4f), Size(w * 0.58f, h * 0.76f), CornerRadius(14.dp.toPx()), style = line)
            }
            rotate(7f, Offset(w / 2, h / 2)) {
                drawRoundRect(faint, Offset(w * 0.22f, h * 0.12f - dy * 0.4f), Size(w * 0.58f, h * 0.76f), CornerRadius(14.dp.toPx()), style = line)
            }
            // The front frame floats a little.
            val fx = w * 0.20f
            val fy = h * 0.08f + dy
            val fw = w * 0.60f
            val fh = h * 0.80f
            drawRoundRect(mid, Offset(fx, fy), Size(fw, fh), CornerRadius(16.dp.toPx()), style = line)
            // Text tucked behind: a bar passing behind the round subject.
            val cy = fy + fh * 0.62f
            drawRoundRect(ink.copy(alpha = 0.85f), Offset(fx + fw * 0.12f, fy + fh * 0.27f), Size(fw * 0.76f, 7.dp.toPx()), CornerRadius(4.dp.toPx()))
            drawRoundRect(ink.copy(alpha = 0.45f), Offset(fx + fw * 0.12f, fy + fh * 0.27f + 14.dp.toPx()), Size(fw * 0.5f, 5.dp.toPx()), CornerRadius(3.dp.toPx()))
            // The subject: a head and shoulders.
            drawCircle(ink, radius = fw * 0.14f, center = Offset(fx + fw * 0.5f, cy - fh * 0.06f), style = line)
            drawArc(
                ink, startAngle = 180f, sweepAngle = 180f, useCenter = false,
                topLeft = Offset(fx + fw * 0.22f, cy + fh * 0.07f), size = Size(fw * 0.56f, fh * 0.34f), style = line,
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            PrimaryButton(actionLabel, onClick = onAction, icon = actionIcon)
        }
    }
}
