package com.hinterlay.app.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import com.hinterlay.app.ui.components.Chip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.hinterlay.app.ui.components.PrimaryButton
import com.hinterlay.app.ui.components.SubtleButton
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private class CropRatio(val label: String, val value: Float?)

private val Ratios = listOf(
    CropRatio("Free", null), CropRatio("1:1", 1f), CropRatio("4:5", 4f / 5f),
    CropRatio("9:16", 9f / 16f), CropRatio("16:9", 16f / 9f), CropRatio("3:4", 3f / 4f),
)

/** Full-area crop editor with corner handles, move, aspect presets and Cancel / Apply. */
@Composable
fun CropOverlay(
    source: Bitmap,
    onApply: (left: Float, top: Float, right: Float, bottom: Float) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val iw = source.width.toFloat()
    val ih = source.height.toFloat()
    var l by remember { mutableStateOf(0f) }
    var t by remember { mutableStateOf(0f) }
    var r by remember { mutableStateOf(iw) }
    var b by remember { mutableStateOf(ih) }
    var ratio by remember { mutableStateOf<Float?>(null) }
    val image = remember(source) { source.asImageBitmap() }
    val density = LocalDensity.current
    val hit = with(density) { 44.dp.toPx() }
    val minSide = min(iw, ih) * 0.08f

    fun fitRatio(rv: Float) {
        // Largest rectangle of this ratio, centred in the photo.
        var w = iw
        var h = w / rv
        if (h > ih) { h = ih; w = h * rv }
        l = (iw - w) / 2f; r = l + w; t = (ih - h) / 2f; b = t + h
    }

    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(12.dp)) {
            val wPx = with(density) { maxWidth.toPx() }
            val hPx = with(density) { maxHeight.toPx() }
            val s = min(wPx / iw, hPx / ih)
            val ox = (wPx - iw * s) / 2f
            val oy = (hPx - ih * s) / 2f
            fun toImg(p: Offset) = Offset(((p.x - ox) / s).coerceIn(0f, iw), ((p.y - oy) / s).coerceIn(0f, ih))

            var mode by remember { mutableStateOf(-1) } // 0..3 = corners TL TR BR BL, 4 = move, -1 none
            var last by remember { mutableStateOf(Offset.Zero) }

            Box(
                Modifier.fillMaxSize().pointerInput(source, s) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            val corners = listOf(Offset(l, t), Offset(r, t), Offset(r, b), Offset(l, b)).map { Offset(ox + it.x * s, oy + it.y * s) }
                            val near = corners.indices.minByOrNull { hypot(corners[it].x - pos.x, corners[it].y - pos.y) }!!
                            mode = when {
                                hypot(corners[near].x - pos.x, corners[near].y - pos.y) <= hit -> near
                                pos.x in corners[0].x..corners[2].x && pos.y in corners[0].y..corners[2].y -> 4
                                else -> -1
                            }
                            last = pos
                        },
                        onDrag = { change, drag ->
                            change.consume()
                            if (mode == 4) {
                                val dx = drag.x / s
                                val dy = drag.y / s
                                val w = r - l
                                val h = b - t
                                l = (l + dx).coerceIn(0f, iw - w); r = l + w
                                t = (t + dy).coerceIn(0f, ih - h); b = t + h
                            } else if (mode in 0..3) {
                                val p = toImg(change.position)
                                // The corner opposite to the one being dragged stays fixed.
                                val fx = if (mode == 0 || mode == 3) r else l
                                val fy = if (mode == 0 || mode == 1) b else t
                                val sx = if (p.x >= fx) 1f else -1f
                                val sy = if (p.y >= fy) 1f else -1f
                                var w = max(abs(p.x - fx), minSide)
                                var h = max(abs(p.y - fy), minSide)
                                val rv = ratio
                                if (rv != null) { if (w / h > rv) w = h * rv else h = w / rv }
                                val maxW = if (sx > 0) iw - fx else fx
                                val maxH = if (sy > 0) ih - fy else fy
                                val k = min(1f, min(maxW / w, maxH / h))
                                w *= k; h *= k
                                val nx = fx + sx * w
                                val ny = fy + sy * h
                                l = min(fx, nx); r = max(fx, nx); t = min(fy, ny); b = max(fy, ny)
                            }
                        },
                        onDragEnd = { mode = -1 },
                        onDragCancel = { mode = -1 },
                    )
                },
            ) {
                androidx.compose.foundation.Image(
                    image, null, modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                )
                Canvas(Modifier.fillMaxSize()) {
                    val rl = ox + l * s; val rt = oy + t * s; val rr = ox + r * s; val rb = oy + b * s
                    val dim = Color.Black.copy(alpha = 0.6f)
                    // Dim everything outside the crop.
                    drawRect(dim, Offset(ox, oy), Size(iw * s, rt - oy))
                    drawRect(dim, Offset(ox, rb), Size(iw * s, oy + ih * s - rb))
                    drawRect(dim, Offset(ox, rt), Size(rl - ox, rb - rt))
                    drawRect(dim, Offset(rr, rt), Size(ox + iw * s - rr, rb - rt))
                    // Thirds grid, frame, handles.
                    val grid = Color.White.copy(alpha = 0.35f)
                    for (i in 1..2) {
                        drawLine(grid, Offset(rl + (rr - rl) * i / 3f, rt), Offset(rl + (rr - rl) * i / 3f, rb), 1f)
                        drawLine(grid, Offset(rl, rt + (rb - rt) * i / 3f), Offset(rr, rt + (rb - rt) * i / 3f), 1f)
                    }
                    drawRect(Color.White, Offset(rl, rt), Size(rr - rl, rb - rt), style = Stroke(1.5.dp.toPx()))
                    val len = 22.dp.toPx()
                    val sw = 4.dp.toPx()
                    fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                        drawLine(Color.White, Offset(x, y), Offset(x + dx * len, y), sw, StrokeCap.Round)
                        drawLine(Color.White, Offset(x, y), Offset(x, y + dy * len), sw, StrokeCap.Round)
                    }
                    corner(rl, rt, 1f, 1f); corner(rr, rt, -1f, 1f); corner(rl, rb, 1f, -1f); corner(rr, rb, -1f, -1f)
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
                items(Ratios) { c ->
                    Chip(
                        selected = ratio == c.value,
                        onClick = { ratio = c.value; c.value?.let { fitRatio(it) } },
                        label = { Text(c.label) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                SubtleButton("Cancel", onClick = onCancel)
                PrimaryButton("Apply crop", onClick = { onApply(l / iw, t / ih, r / iw, b / ih) })
            }
        }
    }
}
