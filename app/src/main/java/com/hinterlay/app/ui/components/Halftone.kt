package com.hinterlay.app.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A faint halftone dot grid. Dots grow from nothing at one edge to [maxRadius] at the other, like a
 * printed gradient. Purely decorative; keep [color] very low-contrast.
 */
fun Modifier.halftone(
    color: Color,
    spacing: Dp = 14.dp,
    maxRadius: Dp = 1.8.dp,
    fromTop: Boolean = true,
): Modifier = drawBehind {
    val step = spacing.toPx()
    val rMax = maxRadius.toPx()
    var row = 0
    var y = step / 2
    while (y < size.height) {
        val t = (if (fromTop) 1f - y / size.height else y / size.height).coerceIn(0f, 1f)
        val r = rMax * t * t
        if (r > 0.25f) {
            val shift = if (row % 2 == 0) 0f else step / 2 // staggered rows, like a real halftone screen
            var x = step / 2 + shift
            while (x < size.width) {
                drawCircle(color, r, Offset(x, y))
                x += step
            }
        }
        y += step
        row++
    }
}
