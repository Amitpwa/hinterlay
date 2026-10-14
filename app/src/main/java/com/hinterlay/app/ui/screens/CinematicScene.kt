package com.hinterlay.app.ui.screens

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.hinterlay.app.editor.FontChoice
import com.hinterlay.app.render.Compositor
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * A layered dusk landscape drawn in code: sky, sun glow, stars, three mountain ridges with
 * atmospheric haze, mist, film grain and a vignette. Used by the onboarding and the home hero.
 * Everything is deterministic (seeded) so it never flickers between frames.
 */
class SceneRig(seed: Int = 11) {
    private val rnd = Random(seed)
    val far = ridge(0.62f, 0.10f, 0.55f)
    val mid = ridge(0.70f, 0.14f, 0.6f)
    val front = ridge(0.80f, 0.20f, 0.7f, peak = true)
    val stars: List<Offset> = List(70) { Offset(rnd.nextFloat(), rnd.nextFloat() * 0.5f) }
    val grain: List<Offset> = List(900) { Offset(rnd.nextFloat(), rnd.nextFloat()) }

    private fun ridge(base: Float, amp: Float, rough: Float, peak: Boolean = false): FloatArray {
        val n = 129
        val h = FloatArray(n)
        var step = n - 1
        var scale = amp
        h[0] = base + (rnd.nextFloat() - 0.5f) * amp
        h[n - 1] = base + (rnd.nextFloat() - 0.5f) * amp
        while (step > 1) {
            val half = step / 2
            var i = half
            while (i < n - 1) {
                h[i] = (h[i - half] + h[i + half]) / 2 + (rnd.nextFloat() - 0.5f) * scale
                i += step
            }
            step = half
            scale *= rough
        }
        if (peak) { // one clear dominant peak: the "subject"
            for (i in 0 until n) {
                val d = abs(i - 78) / 78f
                h[i] -= 0.28f * (1f - d.coerceAtMost(1f)) * (1f - d.coerceAtMost(1f))
            }
        }
        return h
    }
}

class SceneState(
    val beam: Float = -1f,            // 0..1 scan position, <0 = off
    val textAlpha: Float = 0f,
    val textX: Float = 0.5f,
    val text: String = "",
    val font: FontChoice = FontChoice.BEBAS,
    val bubble: Boolean = false,
    val parallax: Float = 0f,         // -1..1 tilt
    val glowSubject: Float = 0f,      // 0..1 outline glow on the front peak
    val zoom: Float = 1f,
)

fun DrawScope.cinematic(rig: SceneRig, s: SceneState) {
    // Whole scene in black and white (the layer is desaturated), to match the minimal UI.
    val mono = androidx.compose.ui.graphics.Paint().apply {
        colorFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(0f) })
    }
    drawIntoCanvas { it.saveLayer(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height), mono) }
    cinematicColor(rig, s)
    drawIntoCanvas { it.restore() }
}

private fun DrawScope.cinematicColor(rig: SceneRig, s: SceneState) {
    val w = size.width
    val h = size.height
    val shift = s.parallax * w * 0.05f

    // Slow push-in.
    drawContext.transform.scale(s.zoom, s.zoom, Offset(w / 2, h / 2))

    // Sky.
    drawRect(Brush.verticalGradient(0f to Color(0xFF0B1030), 0.38f to Color(0xFF3B2A6B), 0.62f to Color(0xFFB4568A), 0.80f to Color(0xFFF2A06B), 1f to Color(0xFFFFD2A0)))
    rig.stars.forEach { p ->
        val a = (0.25f + 0.6f * (1f - p.y * 2f)).coerceIn(0f, 0.8f)
        drawCircle(Color.White.copy(alpha = a), radius = 1.2f, center = Offset(p.x * w - shift * 0.2f, p.y * h))
    }
    // Sun glow low on the horizon.
    drawCircle(
        Brush.radialGradient(listOf(Color(0xCCFFE2B0), Color(0x55FF9E6B), Color.Transparent), center = Offset(w * 0.68f - shift * 0.3f, h * 0.66f), radius = w * 0.55f),
        radius = w * 0.55f, center = Offset(w * 0.68f - shift * 0.3f, h * 0.66f),
    )

    // Text sits between the far layers and the front peak.
    fun drawLayers(fromFront: Boolean) {
        if (!fromFront) {
            ridgePath(rig.far, w, h, shift * 0.4f, Color(0xFF6A4F8F), Color(0xFFB07A9E))
            hazeBand(h * 0.70f, h * 0.18f, Color(0x66FFD2A0), w)
            ridgePath(rig.mid, w, h, shift * 0.7f, Color(0xFF3A2C5E), Color(0xFF6B4A7C))
            hazeBand(h * 0.78f, h * 0.16f, Color(0x55F6B089), w)
        }
    }
    drawLayers(false)

    if (s.textAlpha > 0.01f && s.text.isNotEmpty()) {
        drawIntoCanvas { canvas ->
            val size0 = w * (if (s.text.length > 6) 0.2f else 0.3f)
            val tf: Typeface = Compositor.typeface(s.font, 900, false)
            fun paint(style: Paint.Style, color: Int, stroke: Float = 0f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.style = style; this.color = color; strokeWidth = stroke; strokeJoin = Paint.Join.ROUND
                typeface = tf; textSize = size0; textAlign = Paint.Align.CENTER
                alpha = (255 * s.textAlpha).toInt().coerceIn(0, 255)
            }
            val cx = w * s.textX - shift * 1.1f
            val cy = h * 0.52f
            val nc = canvas.nativeCanvas
            if (s.bubble) {
                nc.drawText(s.text, cx, cy, paint(Paint.Style.STROKE, 0xFF5B2A86.toInt(), size0 * 0.2f))
                nc.drawText(s.text, cx, cy, paint(Paint.Style.STROKE, 0xFFFF5FA2.toInt(), size0 * 0.1f))
                nc.drawText(s.text, cx, cy, paint(Paint.Style.FILL, 0xFFFFE3F0.toInt()))
            } else {
                // Soft glow then the letters.
                nc.drawText(s.text, cx, cy, paint(Paint.Style.STROKE, 0x66FFE2B0, size0 * 0.08f))
                nc.drawText(s.text, cx, cy, paint(Paint.Style.FILL, android.graphics.Color.WHITE))
            }
        }
    }

    // The front peak is the "subject".
    ridgePath(rig.front, w, h, shift * 1.2f, Color(0xFF1B1530), Color(0xFF0A0814))
    if (s.glowSubject > 0f) {
        val path = ridgeLine(rig.front, w, h, shift * 1.2f)
        drawPath(path, Color(0xFF7DD3FC).copy(alpha = 0.25f * s.glowSubject), style = Stroke(width = 10f))
        drawPath(path, Color(0xFFBDEBFF).copy(alpha = 0.95f * s.glowSubject), style = Stroke(width = 3f))
    }
    hazeBand(h * 0.9f, h * 0.2f, Color(0x44FFB88A), w)

    // Scan beam.
    if (s.beam in 0f..1f) {
        val y = h * s.beam
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0x667DD3FC), Color.Transparent), startY = y - 120f, endY = y + 120f))
        drawLine(Color(0xFFBDEBFF), Offset(0f, y), Offset(w, y), strokeWidth = 3f)
    }

    // Film grain and vignette.
    drawPoints(rig.grain.map { Offset(it.x * w, it.y * h) }, PointMode.Points, Color.White.copy(alpha = 0.07f), strokeWidth = 1.5f)
    drawRect(Brush.radialGradient(listOf(Color.Transparent, Color(0x66000000)), center = Offset(w / 2, h / 2), radius = w * 0.85f))
}

private fun ridgeLine(r: FloatArray, w: Float, h: Float, dx: Float): Path = Path().apply {
    val n = r.size
    // Extend past both edges so parallax shifts never reveal an empty gap.
    moveTo(-w * 0.2f + dx, h * r[0])
    for (i in 0 until n) {
        val x = w * i / (n - 1) + dx
        lineTo(x, h * r[i])
    }
    lineTo(w * 1.2f + dx, h * r[n - 1])
}

private fun DrawScope.ridgePath(r: FloatArray, w: Float, h: Float, dx: Float, top: Color, bottom: Color) {
    val p = ridgeLine(r, w, h, dx).apply {
        lineTo(w + dx + 20f, h + 20f); lineTo(dx - 20f, h + 20f); close()
    }
    drawPath(p, Brush.verticalGradient(listOf(top, bottom), startY = h * 0.5f, endY = h))
}

private fun DrawScope.hazeBand(y: Float, height: Float, color: Color, w: Float) {
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, color, Color.Transparent), startY = y - height / 2, endY = y + height / 2), topLeft = Offset(0f, y - height / 2), size = androidx.compose.ui.geometry.Size(w, height))
}

fun wave(t: Float, phase: Float = 0f): Float = sin((t + phase) * 2 * Math.PI).toFloat()
