package com.hinterlay.app.render

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.LruCache
import com.hinterlay.app.editor.TextMaterial
import java.util.stream.IntStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 3D looks for text, drawn without a 3D engine:
 *  1. the letters' shape is "inflated" into a height map (distance from the edge, rounded like a balloon or bevelled like chrome);
 *  2. surface directions are taken from that height map;
 *  3. each pixel reflects a procedural photo studio (soft boxes, bright sky, dark floor) plus a highlight and a rim, tinted by the material.
 * Steps 1 and 2 are done once per text; only step 3 runs again when the light or the phone tilt changes, so it is cheap enough to follow the phone live.
 */
object TextMaterials {

    /** Everything that only depends on the letter shapes and the material, kept so shading can be redone quickly. */
    class Field(val w: Int, val h: Int, val alpha: ByteArray, val nx: ShortArray, val ny: ShortArray, val depth: ByteArray) {
        val out = IntArray(w * h)
        var bitmap: Bitmap? = null
        var shadow: Bitmap? = null
        var shadowStrength = -1f
    }

    private class Look(
        val albedo: FloatArray, val metal: FloatArray, val reflect: Float,
        val sky: FloatArray, val ground: FloatArray, val horizon: Float,
        val spec: Float, val specPower: Float,
        val rim: FloatArray, val rimStrength: Float,
        val inflate: Float, val bevel: Boolean,
        val frost: Float = 0f, val rainbow: Float = 0f,
        /** 1 = a high-contrast studio (chrome); lower = softer reflections, which read as a smooth puffy balloon. */
        val contrast: Float = 1f,
    )

    private fun look(m: TextMaterial): Look = when (m) {
        TextMaterial.SILVER -> Look(floatArrayOf(.80f, .82f, .86f), floatArrayOf(.93f, .95f, 1f), .74f, floatArrayOf(.97f, .98f, 1f), floatArrayOf(.18f, .20f, .27f), .22f, .9f, 70f, floatArrayOf(.55f, .6f, .72f), .30f, .21f, false, contrast = .55f)
        TextMaterial.GOLD -> Look(floatArrayOf(.92f, .69f, .26f), floatArrayOf(1f, .83f, .46f), .78f, floatArrayOf(1f, .95f, .78f), floatArrayOf(.30f, .17f, .05f), .22f, .85f, 60f, floatArrayOf(.75f, .45f, .12f), .30f, .21f, false, contrast = .55f)
        TextMaterial.ROSE -> Look(floatArrayOf(.96f, .56f, .64f), floatArrayOf(1f, .74f, .80f), .72f, floatArrayOf(1f, .94f, .96f), floatArrayOf(.36f, .12f, .20f), .22f, .85f, 60f, floatArrayOf(.80f, .30f, .42f), .30f, .21f, false, contrast = .55f)
        TextMaterial.CHROME -> Look(floatArrayOf(.86f, .87f, .91f), floatArrayOf(1f, 1f, 1f), .94f, floatArrayOf(.96f, .98f, 1f), floatArrayOf(.06f, .07f, .10f), .05f, 1f, 90f, floatArrayOf(.7f, .75f, .85f), .18f, .075f, true)
        TextMaterial.ICE -> Look(floatArrayOf(.68f, .87f, 1f), floatArrayOf(.88f, .97f, 1f), .50f, floatArrayOf(.95f, 1f, 1f), floatArrayOf(.25f, .45f, .65f), .30f, 1f, 80f, floatArrayOf(.45f, .75f, 1f), .45f, .16f, false, frost = 1f)
        TextMaterial.HOLO -> Look(floatArrayOf(.85f, .86f, .92f), floatArrayOf(.95f, .96f, 1f), .62f, floatArrayOf(.96f, .97f, 1f), floatArrayOf(.12f, .13f, .20f), .20f, 1f, 80f, floatArrayOf(.7f, .7f, .9f), .20f, .18f, false, rainbow = 1f)
        TextMaterial.NONE -> look(TextMaterial.SILVER)
    }

    private val fields = object : LruCache<Any, Field>(2) {}

    /** The shaped field for [key] (any value that changes whenever the letters or the material change), built by [maskProvider] on first use. */
    fun field(key: Any, material: TextMaterial, sizePx: Float, maskProvider: () -> Triple<ByteArray, Int, Int>): Field {
        synchronized(fields) { fields.get(key) }?.let { return it }
        val (alpha, w, h) = maskProvider()
        val f = buildField(alpha, w, h, sizePx, look(material))
        synchronized(fields) { fields.put(key, f) }
        return f
    }

    // ---- Step 1 and 2: height map and surface directions ------------------------------------------

    private fun buildField(alpha: ByteArray, w: Int, h: Int, sizePx: Float, look: Look): Field {
        val n = w * h
        val inf = 1e9f
        val d = FloatArray(n) { if ((alpha[it].toInt() and 255) >= 128) inf else 0f }
        // Distance to the nearest outside pixel (two chamfer sweeps).
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (d[i] == 0f) continue
            var v = d[i]
            if (x > 0) v = min(v, d[i - 1] + 1f)
            if (y > 0) {
                v = min(v, d[i - w] + 1f)
                if (x > 0) v = min(v, d[i - w - 1] + 1.414f)
                if (x < w - 1) v = min(v, d[i - w + 1] + 1.414f)
            }
            d[i] = v
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val i = y * w + x
            if (d[i] == 0f) continue
            var v = d[i]
            if (x < w - 1) v = min(v, d[i + 1] + 1f)
            if (y < h - 1) {
                v = min(v, d[i + w] + 1f)
                if (x < w - 1) v = min(v, d[i + w + 1] + 1.414f)
                if (x > 0) v = min(v, d[i + w - 1] + 1.414f)
            }
            d[i] = v
        }
        val radius = (sizePx * look.inflate).coerceIn(3f, 90f)
        val height = FloatArray(n)
        val depth = ByteArray(n)
        for (i in 0 until n) {
            val t = min(d[i] / radius, 1f)
            depth[i] = (t * 255f).toInt().toByte()
            height[i] = (if (look.bevel) {
                // Chrome: a steep bevel then a flat top.
                val s = t * t * (3f - 2f * t)
                s.pow(0.75f) * 0.55f
            } else {
                // Balloon: a circular cross-section, so the letter is round like a tube.
                sqrt(1f - (1f - t) * (1f - t))
            }) * radius
        }
        // Chrome keeps crisp bevel edges; balloons are softened a lot so corners and joins are rounded and puffy.
        val smooth = blurF(height, w, h, max(1, (radius * (if (look.bevel) 0.18f else 0.30f)).toInt()))
        val nx = ShortArray(n)
        val ny = ShortArray(n)
        IntStream.range(0, h).parallel().forEach { y ->
            val ym = max(0, y - 1) * w
            val yp = min(h - 1, y + 1) * w
            for (x in 0 until w) {
                val xm = max(0, x - 1)
                val xp = min(w - 1, x + 1)
                val gx = (smooth[y * w + xp] - smooth[y * w + xm]) * 0.5f
                val gy = (smooth[yp + x] - smooth[ym + x]) * 0.5f
                val inv = 1f / sqrt(gx * gx + gy * gy + 1f)
                nx[y * w + x] = (-gx * inv * 32767f).toInt().toShort()
                ny[y * w + x] = (-gy * inv * 32767f).toInt().toShort()
            }
        }
        return Field(w, h, alpha, nx, ny, depth)
    }

    private fun blurF(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        var a = src
        repeat(2) {
            val tmp = FloatArray(w * h)
            val inv = 1f / (2 * radius + 1)
            for (y in 0 until h) {
                var s = 0f
                for (k in -radius..radius) s += a[y * w + k.coerceIn(0, w - 1)]
                for (x in 0 until w) {
                    tmp[y * w + x] = s * inv
                    s += a[y * w + min(w - 1, x + radius + 1)] - a[y * w + max(0, x - radius)]
                }
            }
            val out = FloatArray(w * h)
            for (x in 0 until w) {
                var s = 0f
                for (k in -radius..radius) s += tmp[k.coerceIn(0, h - 1) * w + x]
                for (y in 0 until h) {
                    out[y * w + x] = s * inv
                    s += tmp[min(h - 1, y + radius + 1) * w + x] - tmp[max(0, y - radius) * w + x]
                }
            }
            a = out
        }
        return a
    }

    // ---- Step 3: shading ----------------------------------------------------------------------------

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** A soft-edged rectangle in reflection space: 1 inside, fading to 0 over [soft]. */
    private fun box(x: Float, y: Float, cx: Float, cy: Float, hw: Float, hh: Float, soft: Float): Float =
        (1f - smooth(hw, hw + soft, abs(x - cx))) * (1f - smooth(hh, hh + soft, abs(y - cy)))

    /**
     * Shades [f] with [material]. [lightX] / [lightY] (about -1..1) turn the studio, so reflections slide across the
     * letters as the light slider or the phone tilt changes. Returns a bitmap owned by the field (reused between calls).
     */
    fun shade(f: Field, material: TextMaterial, lightX: Float, lightY: Float, opacity: Float): Bitmap {
        val lk = look(material)
        val w = f.w
        val h = f.h
        // Light direction, moved a little by the light controls.
        var lx = -0.35f + lightX * 0.5f
        var ly = 0.55f + lightY * 0.4f
        var lz = 0.76f
        val ll = 1f / sqrt(lx * lx + ly * ly + lz * lz)
        lx *= ll; ly *= ll; lz *= ll
        var hx = lx; var hy = ly; var hz = lz + 1f // half vector with the viewer straight on
        val hl = 1f / sqrt(hx * hx + hy * hy + hz * hz)
        hx *= hl; hy *= hl; hz *= hl
        val op = opacity.coerceIn(0f, 1f)
        val tiltX = lightX * 0.65f
        val tiltY = lightY * 0.65f

        IntStream.range(0, h).parallel().forEach { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val a8 = f.alpha[i].toInt() and 255
                if (a8 == 0) { f.out[i] = 0; continue }
                val nxv = f.nx[i] / 32767f
                val nyv = f.ny[i] / 32767f
                val nzv = sqrt(max(0f, 1f - nxv * nxv - nyv * nyv))
                val t = (f.depth[i].toInt() and 255) / 255f

                // Reflection of a viewer looking straight at the letter, with the studio turned by the tilt.
                val rx = 2f * nzv * nxv + tiltX
                val ry = 2f * nzv * nyv + tiltY
                val v = ry * 0.5f + 0.5f
                // Studio: bright sky above, dark floor below, a horizon between, and two soft boxes.
                val sky = smooth(0.50f - lk.horizon, 0.50f + lk.horizon * 0.6f, v)
                var er = lk.ground[0] + (lk.sky[0] - lk.ground[0]) * sky
                var eg = lk.ground[1] + (lk.sky[1] - lk.ground[1]) * sky
                var eb = lk.ground[2] + (lk.sky[2] - lk.ground[2]) * sky
                // Softer studios (balloons) pull the bright and dark parts towards the middle so the shape reads smoothly.
                val bx = (box(rx, ry, -0.42f, 0.52f, 0.30f, 0.14f, 0.16f) + 0.8f * box(rx, ry, 0.60f, 0.05f, 0.08f, 0.42f, 0.12f) + 0.45f * box(rx, ry, 0.0f, -0.72f, 0.55f, 0.06f, 0.12f)) * lk.contrast
                val mr = (lk.sky[0] + lk.ground[0]) * 0.5f; val mg = (lk.sky[1] + lk.ground[1]) * 0.5f; val mb = (lk.sky[2] + lk.ground[2]) * 0.5f
                er = mr + (er - mr) * lk.contrast + bx
                eg = mg + (eg - mg) * lk.contrast + bx
                eb = mb + (eb - mb) * lk.contrast + bx

                val ndl = max(0f, nxv * lx + nyv * ly + nzv * lz)
                val ndh = max(0f, nxv * hx + nyv * hy + nzv * hz)
                val spec = ndh.pow(lk.specPower) * lk.spec
                val fres = (1f - nzv).let { it * it }

                val refl = lk.reflect
                var r = lk.albedo[0] * (0.30f + 0.55f * ndl) * (1f - refl) + er * lk.metal[0] * refl
                var g = lk.albedo[1] * (0.30f + 0.55f * ndl) * (1f - refl) + eg * lk.metal[1] * refl
                var b = lk.albedo[2] * (0.30f + 0.55f * ndl) * (1f - refl) + eb * lk.metal[2] * refl

                if (lk.rainbow > 0f) {
                    // Holo: the hue follows the surface direction and the light, like a foil that changes with the angle.
                    val hue = nxv * 1.3f + nyv * 0.9f + lightX * 0.7f + t * 0.35f
                    val k = lk.rainbow * 0.55f * (0.35f + 0.65f * (1f - nzv).coerceAtLeast(0.15f))
                    val pr = 0.5f + 0.5f * cos(2f * PI.toFloat() * hue)
                    val pg = 0.5f + 0.5f * cos(2f * PI.toFloat() * (hue + 0.333f))
                    val pb = 0.5f + 0.5f * cos(2f * PI.toFloat() * (hue + 0.667f))
                    r += (pr - r) * k; g += (pg - g) * k; b += (pb - b) * k
                }
                if (lk.frost > 0f) {
                    // Ice: a glow from inside (stronger in the middle of thick parts) and a fine frost speckle.
                    val glow = smooth(0.2f, 1f, t) * 0.18f
                    val speckle = (noise(x, y) - 0.5f) * 0.10f * lk.frost
                    r += glow * 0.7f + speckle; g += glow * 0.9f + speckle; b += glow + speckle
                }

                // Edge: a coloured rim, darker where the surface turns away.
                r = r * (1f - fres * 0.25f) + lk.rim[0] * fres * lk.rimStrength
                g = g * (1f - fres * 0.25f) + lk.rim[1] * fres * lk.rimStrength
                b = b * (1f - fres * 0.25f) + lk.rim[2] * fres * lk.rimStrength
                r += spec; g += spec; b += spec

                val ri = (r.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                val gi = (g.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                val bi = (b.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                val ai = (a8 * op + 0.5f).toInt()
                f.out[i] = (ai shl 24) or (ri shl 16) or (gi shl 8) or bi
            }
        }
        val bmp = f.bitmap?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { f.bitmap = it }
        bmp.setPixels(f.out, 0, w, 0, 0, w, h)
        return bmp
    }

    /** A soft shadow under the letters, as an alpha mask the size of the field. Cached on the field. */
    fun shadowMask(f: Field, radius: Float): Bitmap {
        f.shadow?.let { if (f.shadowStrength == radius) return it }
        val src = Bitmap.createBitmap(f.w, f.h, Bitmap.Config.ALPHA_8)
        val px = ByteArray(src.rowBytes * f.h)
        for (y in 0 until f.h) System.arraycopy(f.alpha, y * f.w, px, y * src.rowBytes, f.w)
        src.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(px))
        val soft = src.extractAlpha(Paint().apply { maskFilter = BlurMaskFilter(max(1f, radius), BlurMaskFilter.Blur.NORMAL) }, IntArray(2))
        src.recycle()
        f.shadow = soft
        f.shadowStrength = radius
        return soft
    }

    private fun noise(x: Int, y: Int): Float {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65535f
    }

    // ---- Small previews for the style strip ---------------------------------------------------------

    private val thumbs = HashMap<TextMaterial, Bitmap>()

    /** A small "Ab" drawn in [material], cached for the whole run. */
    fun thumbnail(material: TextMaterial, typeface: Typeface): Bitmap = synchronized(thumbs) {
        thumbs.getOrPut(material) {
            val w = 150
            val h = 110
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface; textSize = 86f; color = Color.WHITE; textAlign = Paint.Align.CENTER }
            val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(mask).drawText("Ab", w / 2f, h * 0.76f, paint)
            val px = IntArray(w * h)
            mask.getPixels(px, 0, w, 0, 0, w, h)
            mask.recycle()
            val alpha = ByteArray(w * h) { (px[it] ushr 24).toByte() }
            val f = buildField(alpha, w, h, 86f, look(material))
            shade(f, material, 0f, 0f, 1f)
        }
    }
}
