package com.hinterlay.app.render

import android.graphics.Bitmap
import java.util.stream.IntStream
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Turns a photo into a drawing: flat colour with ink outlines (Cartoon), comic shading (Comic), loud colour (Pop art) or a
 * pencil drawing (Pencil). Plain pixel work that runs on the phone in a second or two, for any subject (people, pets, things).
 */
object Toonify {

    enum class Look(val label: String, val detail: String) {
        CARTOON("Cartoon", "Flat colour with clean ink outlines"),
        COMIC("Comic", "Bold ink, with dotted shading"),
        POP("Pop art", "Loud colour and thick outlines"),
        PENCIL("Pencil", "A soft pencil drawing"),
    }

    /** The drawing for [src], at most [maxSide] pixels on the long side. [src] is not modified. */
    fun apply(src: Bitmap, look: Look, maxSide: Int = 640): Bitmap {
        val k = min(1f, maxSide.toFloat() / max(src.width, src.height))
        val bmp = if (k < 1f) Bitmap.createScaledBitmap(src, (src.width * k).toInt().coerceAtLeast(1), (src.height * k).toInt().coerceAtLeast(1), true) else src
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        if (bmp !== src) bmp.recycle()

        val out = when (look) {
            Look.PENCIL -> pencil(px, w, h)
            else -> cartoon(px, w, h, look)
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    private fun luma(c: Int) = (0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)) / 255f

    /** Smooths flat areas but keeps edges sharp, so colours group into patches like a painted cartoon. */
    private fun bilateral(src: IntArray, w: Int, h: Int, radius: Int, sigmaRange: Float): IntArray {
        val out = IntArray(w * h)
        val side = 2 * radius + 1
        val sigmaS = radius * 0.6f
        val spatial = FloatArray(side * side) { i ->
            val dx = i % side - radius; val dy = i / side - radius
            exp(-(dx * dx + dy * dy) / (2 * sigmaS * sigmaS))
        }
        val range = FloatArray(766) { d -> exp(-(d * d) / (2 * sigmaRange * sigmaRange)) }
        // Neighbour offsets for pixels away from the border, which need no clamping (most of them).
        val offsets = IntArray(side * side) { i -> (i / side - radius) * w + (i % side - radius) }
        IntStream.range(0, h).parallel().forEach { y ->
            val interiorRow = y >= radius && y < h - radius
            for (x in 0 until w) {
                val idx = y * w + x
                val c0 = src[idx]
                val r0 = (c0 shr 16) and 255; val g0 = (c0 shr 8) and 255; val b0 = c0 and 255
                var sr = 0f; var sg = 0f; var sb = 0f; var sw = 0f
                if (interiorRow && x >= radius && x < w - radius) {
                    for (k in offsets.indices) {
                        val c = src[idx + offsets[k]]
                        val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
                        val wt = spatial[k] * range[abs(r - r0) + abs(g - g0) + abs(b - b0)]
                        sr += r * wt; sg += g * wt; sb += b * wt; sw += wt
                    }
                } else {
                    for (dy in -radius..radius) {
                        val yy = (y + dy).coerceIn(0, h - 1)
                        for (dx in -radius..radius) {
                            val c = src[yy * w + (x + dx).coerceIn(0, w - 1)]
                            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
                            val wt = spatial[(dy + radius) * side + dx + radius] * range[abs(r - r0) + abs(g - g0) + abs(b - b0)]
                            sr += r * wt; sg += g * wt; sb += b * wt; sw += wt
                        }
                    }
                }
                out[idx] = (255 shl 24) or ((sr / sw).toInt() shl 16) or ((sg / sw).toInt() shl 8) or (sb / sw).toInt()
            }
        }
        return out
    }

    private fun cartoon(src: IntArray, w: Int, h: Int, look: Look): IntArray {
        val passes = 2
        var smooth = src
        repeat(passes) { smooth = bilateral(smooth, w, h, 3, 26f) }

        val levels = when (look) { Look.POP -> 4f; Look.COMIC -> 5f; else -> 6f }
        val sat = when (look) { Look.POP -> 1.6f; Look.COMIC -> 1.15f; else -> 1.3f }
        val gray = FloatArray(w * h) { luma(smooth[it]) }

        // Ink: strong changes in brightness (Sobel), widened for the bolder looks.
        val mag = FloatArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val gx = -gray[i - w - 1] - 2 * gray[i - 1] - gray[i + w - 1] + gray[i - w + 1] + 2 * gray[i + 1] + gray[i + w + 1]
            val gy = -gray[i - w - 1] - 2 * gray[i - w] - gray[i - w + 1] + gray[i + w - 1] + 2 * gray[i + w] + gray[i + w + 1]
            mag[i] = sqrt(gx * gx + gy * gy)
        }
        val t0 = if (look == Look.COMIC) 0.16f else 0.22f
        var ink = FloatArray(w * h) { smoothstep(t0, t0 + 0.2f, mag[it]) }
        val widen = when (look) { Look.POP -> 2; Look.COMIC -> 1; else -> 1 }
        repeat(widen) {
            val next = ink.copyOf()
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val i = y * w + x
                next[i] = max(ink[i], max(max(ink[i - 1], ink[i + 1]), max(ink[i - w], ink[i + w])))
            }
            ink = next
        }

        val dot = 6
        val out = IntArray(w * h)
        IntStream.range(0, h).parallel().forEach { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val c = smooth[i]
                var r = ((c shr 16) and 255) / 255f; var g = ((c shr 8) and 255) / 255f; var b = (c and 255) / 255f
                val l = gray[i]
                // Flat tonal bands that keep the hue.
                // Lift the darks a little first, so skin and shadows stay warm instead of turning muddy.
                val lift = Math.pow(l.toDouble(), if (look == Look.POP) 0.7 else 0.82).toFloat()
                val lq = (Math.floor((lift * levels + 0.5f).toDouble()).toFloat() / levels).coerceIn(if (look == Look.POP) 0.25f else 0.18f, 1f)
                val s = (lq / max(l, 0.02f)).coerceIn(0.4f, 2.4f)
                r *= s; g *= s; b *= s
                val gl = 0.299f * r + 0.587f * g + 0.114f * b
                r = gl + (r - gl) * sat; g = gl + (g - gl) * sat; b = gl + (b - gl) * sat
                if (look == Look.COMIC && lq < 0.62f) {
                    // Halftone dots in the shadows.
                    val cx = (x % dot) - dot / 2f; val cy = (y % dot) - dot / 2f
                    if (hypot(cx, cy) < (0.62f - lq) * dot * 1.5f) { r *= 0.55f; g *= 0.55f; b *= 0.55f }
                }
                val keep = 1f - ink[i] * 0.92f
                r *= keep; g *= keep; b *= keep
                out[i] = (255 shl 24) or (c8(r) shl 16) or (c8(g) shl 8) or c8(b)
            }
        }
        return out
    }

    /** Colour-dodge of the photo against a blurred negative of itself: the classic pencil-drawing effect. */
    private fun pencil(src: IntArray, w: Int, h: Int): IntArray {
        val gray = IntArray(w * h) { val v = (luma(src[it]) * 255f).toInt(); (255 shl 24) or (v shl 16) or (v shl 8) or v }
        val inverted = IntArray(w * h) { val v = 255 - (gray[it] and 255); (255 shl 24) or (v shl 16) or (v shl 8) or v }
        val blurred = PhotoFx.blur(inverted, w, h, max(4, max(w, h) / 70))
        val smooth = bilateral(src, w, h, 3, 30f)
        val out = IntArray(w * h)
        IntStream.range(0, h).parallel().forEach { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val base = (gray[i] and 255) / 255f
                val top = (blurred[i] and 255) / 255f
                var d = if (top >= 0.999f) 1f else min(1f, base / (1f - top))
                d = Math.pow(d.toDouble(), 1.15).toFloat()
                // Paper tone, with a faint wash of the real colours.
                val c = smooth[i]
                val wr = ((c shr 16) and 255) / 255f; val wg = ((c shr 8) and 255) / 255f; val wb = (c and 255) / 255f
                val wash = 0.18f
                val r = (d * (0.98f - wash) + wr * wash * d).coerceIn(0f, 1f)
                val g = (d * (0.96f - wash) + wg * wash * d).coerceIn(0f, 1f)
                val b = (d * (0.92f - wash) + wb * wash * d).coerceIn(0f, 1f)
                out[i] = (255 shl 24) or (c8(r) shl 16) or (c8(g) shl 8) or c8(b)
            }
        }
        return out
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun c8(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
}
