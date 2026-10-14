package com.hinterlay.app.ui.components

import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer

/**
 * Wraps [content] and, when [curling] turns true, peels it away like a page lifting off the
 * stack (a vertical-axis paper roll that rolls up from the right and fades), then calls
 * [onCurled] so the caller can remove the item for good. Quiet, black-and-white, no colour.
 *
 * The live content is recorded into a graphics layer every frame, so while [curling] is false the
 * card behaves normally; the curl animates a one-time snapshot of that layer.
 */
@Composable
fun PageCurlBox(
    curling: Boolean,
    onCurled: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val layer = rememberGraphicsLayer()
    var snapshot by remember { mutableStateOf<Bitmap?>(null) }
    val progress = remember { Animatable(0f) }

    LaunchedEffect(curling) {
        if (!curling) return@LaunchedEffect
        // Snapshot the card exactly as it looks right now, then roll it away.
        val bmp = runCatching { layer.toImageBitmap().asAndroidBitmap() }.getOrNull()
        if (bmp == null) { onCurled(); return@LaunchedEffect }
        snapshot = bmp
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 460, easing = FastOutSlowInEasing))
        onCurled()
    }

    androidx.compose.foundation.layout.Box(
        modifier.drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            val p = progress.value
            val bmp = snapshot
            if (p <= 0f || bmp == null) {
                drawLayer(layer)
            } else {
                drawIntoCanvas { canvas -> drawPageCurl(canvas.nativeCanvas, bmp, p) }
            }
        },
    ) { content() }
}

/**
 * Draws [bmp] as a sheet of paper rolling up around a vertical axis. The fold sweeps from the
 * right edge off the left; everything past the fold wraps onto a cylinder whose radius grows so
 * the roll never exceeds a single turn (which would self-overlap and look wrong). Vertex colours
 * shade the curve where the device honours them; a soft shadow sits on the flat page at the fold.
 */
private fun drawPageCurl(canvas: android.graphics.Canvas, bmp: Bitmap, p: Float) {
    val w = bmp.width.toFloat()
    val h = bmp.height.toFloat()
    if (w <= 0f || h <= 0f) return

    val cols = 24
    val rows = 1
    val count = (cols + 1) * (rows + 1)
    val verts = FloatArray(count * 2)
    val colors = IntArray(count)

    // Fold position: starts at the right edge, overshoots the left so the sheet fully leaves.
    val foldX = w * (1f - p * 1.15f)
    val rolledLen = (w - foldX).coerceAtLeast(1f)
    val twoPi = (2.0 * Math.PI).toFloat()
    val radius = maxOf(w * 0.11f, rolledLen / twoPi)

    var i = 0
    for (r in 0..rows) {
        val y = h * r / rows
        for (c in 0..cols) {
            val x = w * c / cols
            if (x <= foldX) {
                verts[i * 2] = x
                verts[i * 2 + 1] = y
                colors[i] = shade(1f)
            } else {
                val angle = ((x - foldX) / radius).toDouble()
                verts[i * 2] = foldX + radius * Math.sin(angle).toFloat()
                verts[i * 2 + 1] = y
                // Bright facing the viewer (angle 0), dim around the back of the roll.
                val s = 0.4f + 0.6f * ((Math.cos(angle).toFloat() + 1f) / 2f)
                colors[i] = shade(s)
            }
            i++
        }
    }

    val alpha = (255f * (if (p < 0.72f) 1f else (1f - (p - 0.72f) / 0.28f))).toInt().coerceIn(0, 255)
    val paint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
        this.alpha = alpha
    }
    canvas.drawBitmapMesh(bmp, cols, rows, verts, 0, colors, 0, paint)

    // Soft shadow the lifting page casts on the flat part, just left of the fold.
    if (foldX > 0f && foldX < w) {
        val shadowW = (radius * 0.9f).coerceAtLeast(2f)
        val x0 = (foldX - shadowW).coerceAtLeast(0f)
        val darkest = (70f * alpha / 255f).toInt().coerceIn(0, 255)
        val shadow = Paint().apply {
            isAntiAlias = true
            shader = LinearGradient(
                x0, 0f, foldX, 0f,
                0x00000000, (darkest shl 24),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(x0, 0f, foldX, h, shadow)
    }
}

private fun shade(s: Float): Int {
    val v = (s * 255f).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (v shl 16) or (v shl 8) or v
}
