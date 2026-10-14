package com.hinterlay.app.render

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A soft "clean background" for the area behind the subject.
 *
 * It is built at low resolution: pixels that belong to the subject are discarded and filled by
 * diffusing the colours of the surrounding background inward (a smooth, heavily blurred smear).
 * Used to (a) remove parts of the subject for real when the user erases them and (b) replace the
 * subject in the back layer of the tilt wallpaper so the subject does not appear twice.
 */
object SubjectPlate {
    private const val MAX_SIDE = 192
    private const val ITERATIONS = 260

    fun build(source: Bitmap, foreground: Bitmap): Bitmap {
        val k = MAX_SIDE.toFloat() / max(source.width, source.height)
        val w = max(2, (source.width * k).roundToInt())
        val h = max(2, (source.height * k).roundToInt())
        val src = Bitmap.createScaledBitmap(source, w, h, true)
        val fg = Bitmap.createScaledBitmap(foreground, w, h, true)

        val n = w * h
        val px = IntArray(n).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        val fp = IntArray(n).also { fg.getPixels(it, 0, w, 0, 0, w, h) }
        if (src !== source) src.recycle()
        if (fg !== foreground) fg.recycle()

        // Unknown = subject pixels, grown a little so no subject edge colour leaks into the fill.
        var unknown = BooleanArray(n) { Color.alpha(fp[it]) > 24 }
        repeat(2) {
            val grown = unknown.copyOf()
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                if (unknown[i]) continue
                if ((x > 0 && unknown[i - 1]) || (x < w - 1 && unknown[i + 1]) ||
                    (y > 0 && unknown[i - w]) || (y < h - 1 && unknown[i + w])
                ) grown[i] = true
            }
            unknown = grown
        }

        val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n)
        var sr = 0f; var sg = 0f; var sb = 0f; var known = 0
        for (i in 0 until n) {
            r[i] = Color.red(px[i]).toFloat(); g[i] = Color.green(px[i]).toFloat(); b[i] = Color.blue(px[i]).toFloat()
            if (!unknown[i]) { sr += r[i]; sg += g[i]; sb += b[i]; known++ }
        }
        if (known == 0) { sr = 128f; sg = 128f; sb = 128f; known = 1 }
        val mr = sr / known; val mg = sg / known; val mb = sb / known
        for (i in 0 until n) if (unknown[i]) { r[i] = mr; g[i] = mg; b[i] = mb }

        // Heat diffusion: unknown pixels relax towards the average of their neighbours.
        repeat(ITERATIONS) {
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                if (!unknown[i]) continue
                var cnt = 0; var ar = 0f; var ag = 0f; var ab = 0f
                if (x > 0) { ar += r[i - 1]; ag += g[i - 1]; ab += b[i - 1]; cnt++ }
                if (x < w - 1) { ar += r[i + 1]; ag += g[i + 1]; ab += b[i + 1]; cnt++ }
                if (y > 0) { ar += r[i - w]; ag += g[i - w]; ab += b[i - w]; cnt++ }
                if (y < h - 1) { ar += r[i + w]; ag += g[i + w]; ab += b[i + w]; cnt++ }
                r[i] = ar / cnt; g[i] = ag / cnt; b[i] = ab / cnt
            }
        }

        val out = IntArray(n) { Color.rgb(r[it].roundToInt().coerceIn(0, 255), g[it].roundToInt().coerceIn(0, 255), b[it].roundToInt().coerceIn(0, 255)) }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }
}
