package com.hinterlay.app.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader

/** One-tap colour looks. Used live in the camera and on photos in the editor. */
enum class Look(val label: String) {
    NORMAL("Original"), VIVID("Vivid"), WARM("Warm"), COOL("Cool"), FADE("Fade"),
    MONO("Mono"), NOIR("Noir"), SEPIA("Sepia"), DRAMA("Drama"),
    MATTE("Matte"), VINTAGE("Vintage"), CINEMA("Cinema");

    fun matrix(): ColorMatrix {
        val m = ColorMatrix()
        fun sat(s: Float) = ColorMatrix().apply { setSaturation(s) }
        fun con(c: Float, b: Float = 0f): ColorMatrix {
            val t = (-0.5f * c + 0.5f) * 255f + b
            return ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f))
        }
        fun tint(r: Float, g: Float, b: Float) = ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, r, 0f, 1f, 0f, 0f, g, 0f, 0f, 1f, 0f, b, 0f, 0f, 0f, 1f, 0f))
        when (this) {
            NORMAL -> Unit
            VIVID -> { m.postConcat(sat(1.5f)); m.postConcat(con(1.12f)) }
            WARM -> { m.postConcat(sat(1.1f)); m.postConcat(tint(22f, 6f, -22f)) }
            COOL -> { m.postConcat(sat(1.05f)); m.postConcat(tint(-18f, 2f, 24f)) }
            FADE -> { m.postConcat(sat(0.8f)); m.postConcat(con(0.82f, 26f)) }
            MONO -> m.postConcat(sat(0f))
            NOIR -> { m.postConcat(sat(0f)); m.postConcat(con(1.4f, -14f)) }
            SEPIA -> { m.postConcat(sat(0f)); m.postConcat(tint(46f, 18f, -26f)) }
            DRAMA -> { m.postConcat(sat(1.25f)); m.postConcat(con(1.3f, -20f)) }
            MATTE -> { m.postConcat(sat(0.9f)); m.postConcat(con(0.86f, 24f)) }
            VINTAGE -> { m.postConcat(sat(0.72f)); m.postConcat(con(0.92f, 8f)); m.postConcat(tint(20f, 8f, -16f)) }
            CINEMA -> { m.postConcat(sat(1.1f)); m.postConcat(con(1.18f, -12f)); m.postConcat(tint(-6f, 4f, 10f)) }
        }
        return m
    }
}

/** Look plus manual sliders. Every value is neutral at its default. */
data class PhotoAdjust(
    val look: Look = Look.NORMAL,
    val brightness: Float = 0f,   // -1..1
    val contrast: Float = 0f,     // -1..1
    val saturation: Float = 0f,   // -1..1
    val warmth: Float = 0f,       // -1..1
    val vignette: Float = 0f,     // 0..1
    val smooth: Float = 0f,       // 0..1 skin smoothing
) {
    val isNeutral get() = this == PhotoAdjust()

    fun colorMatrix(): ColorMatrix? {
        if (look == Look.NORMAL && brightness == 0f && contrast == 0f && saturation == 0f && warmth == 0f) return null
        val m = look.matrix()
        val c = 1f + contrast * 0.6f
        val t = (-0.5f * c + 0.5f) * 255f + brightness * 90f
        m.postConcat(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        m.postConcat(ColorMatrix().apply { setSaturation(1f + saturation) })
        m.postConcat(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, warmth * 30f, 0f, 1f, 0f, 0f, warmth * 6f, 0f, 0f, 1f, 0f, -warmth * 30f, 0f, 0f, 0f, 1f, 0f)))
        return m
    }

    /** A paint that applies the colour part of this look, or null when nothing changes. */
    fun paint(base: Paint): Paint? {
        val cm = colorMatrix() ?: return null
        return Paint(base).apply { colorFilter = ColorMatrixColorFilter(cm) }
    }

    fun drawVignette(canvas: Canvas, w: Int, h: Int) {
        if (vignette <= 0.01f) return
        val r = Math.hypot(w.toDouble(), h.toDouble()).toFloat() / 2f
        val p = Paint().apply {
            shader = RadialGradient(
                w / 2f, h / 2f, r,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb((190 * vignette).toInt(), 0, 0, 0)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    }
}
