package com.hinterlay.app.render

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A softened copy of the skin areas, used by the "Smooth" control. The result is a bitmap whose colour
 * is a blurred version of the photo and whose alpha is "how skin-like (and how much of the subject)
 * this pixel is". Drawn over the photo at the user's strength, it smooths skin while eyes, hair,
 * clothes and the background stay sharp.
 */
object SkinSmooth {
    private const val MAX_SIDE = 960

    fun build(source: Bitmap, foreground: Bitmap?): Bitmap {
        val k = MAX_SIDE.toFloat() / max(source.width, source.height)
        val w = max(2, (source.width * minOf(1f, k)).roundToInt())
        val h = max(2, (source.height * minOf(1f, k)).roundToInt())
        val base = Bitmap.createScaledBitmap(source, w, h, true)

        // Blur by shrinking and enlarging with filtering (two rounds for a soft, even result).
        fun blur(bmp: Bitmap, f: Int): Bitmap {
            val small = Bitmap.createScaledBitmap(bmp, max(2, w / f), max(2, h / f), true)
            return Bitmap.createScaledBitmap(small, w, h, true).also { small.recycle() }
        }
        val b1 = blur(base, 5)
        val blurred = blur(b1, 2)
        b1.recycle()

        val px = IntArray(w * h).also { base.getPixels(it, 0, w, 0, 0, w, h) }
        val bl = IntArray(w * h).also { blurred.getPixels(it, 0, w, 0, 0, w, h) }
        val subj: IntArray? = foreground?.let {
            val f = Bitmap.createScaledBitmap(it, w, h, true)
            IntArray(w * h).also { arr -> f.getPixels(arr, 0, w, 0, 0, w, h) }.also { f.recycle() }
        }

        val alpha = IntArray(w * h)
        for (i in 0 until w * h) {
            val c = px[i]
            val r = Color.red(c); val g = Color.green(c); val bb = Color.blue(c)
            // YCbCr skin range (works across skin tones; excludes most blue/green clothing and sky).
            val cb = 128 - 0.168736f * r - 0.331264f * g + 0.5f * bb
            val cr = 128 + 0.5f * r - 0.418688f * g - 0.081312f * bb
            val inCb = softRange(cb, 77f, 127f, 8f)
            val inCr = softRange(cr, 133f, 173f, 8f)
            val bright = ((0.299f * r + 0.587f * g + 0.114f * bb - 35f) / 40f).coerceIn(0f, 1f) // skip very dark pixels (eyes, hair)
            var a = inCb * inCr * bright
            if (subj != null) a *= Color.alpha(subj[i]) / 255f
            alpha[i] = (a * 255f).roundToInt().coerceIn(0, 255)
        }

        // Feather the mask so the smoothing fades in and out instead of showing hard patches.
        val maskBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        maskBmp.setPixels(IntArray(w * h) { Color.argb(alpha[it], 0, 0, 0) }, 0, w, 0, 0, w, h)
        val soft = blur(maskBmp, 4)
        maskBmp.recycle()
        val sp = IntArray(w * h).also { soft.getPixels(it, 0, w, 0, 0, w, h) }
        soft.recycle()

        val out = IntArray(w * h) { i ->
            val c = bl[i]
            Color.argb(Color.alpha(sp[i]), Color.red(c), Color.green(c), Color.blue(c))
        }
        base.recycle(); blurred.recycle()
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** 1 inside [lo, hi], fading to 0 over [edge] outside it. */
    private fun softRange(v: Float, lo: Float, hi: Float, edge: Float): Float = when {
        v in lo..hi -> 1f
        v < lo -> ((v - (lo - edge)) / edge).coerceIn(0f, 1f)
        else -> (((hi + edge) - v) / edge).coerceIn(0f, 1f)
    }

}
