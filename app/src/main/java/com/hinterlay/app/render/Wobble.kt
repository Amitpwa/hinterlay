package com.hinterlay.app.render

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.hinterlay.app.editor.TextLayer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The "3D photo" (wobble): the picture is split into layers that move by different amounts, so the subject seems to
 * float in front of the background.
 *   back  = the photo with the subject area replaced by the clean background fill, so nothing tears when layers slide;
 *   mid   = text that sits behind the subject (it floats between background and subject);
 *   front = the subject cut-out plus any text in front of it.
 * Without a detected subject there is a single layer and the motion is a gentle zoom.
 */
object Wobble {

    class Planes(val back: Bitmap, val mid: Bitmap?, val front: Bitmap?) {
        val width get() = back.width
        val height get() = back.height
        val hasDepth get() = front != null
    }

    enum class Style(val label: String) { WOBBLE("Wobble"), ORBIT("Orbit"), PUSH("Push") }

    private val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dstInPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }

    /**
     * Builds the layers at a working size (longer side at most [maxSide]). [photo] and [subject] are the already edited
     * photo and subject cut-out (photo tools applied), [plate] the clean background fill (built here when null).
     */
    fun build(
        photo: Bitmap, subject: Bitmap?, layers: List<TextLayer>, edit: Compositor.SubjectEdit?,
        adjust: PhotoAdjust, plate: Bitmap?, maxSide: Int,
    ): Planes {
        val k = min(1f, maxSide.toFloat() / max(photo.width, photo.height))
        val w = max(2, (photo.width * k).roundToInt())
        val h = max(2, (photo.height * k).roundToInt())
        fun scaled(b: Bitmap) = if (b.width == w && b.height == h) b else Bitmap.createScaledBitmap(b, w, h, true)
        val bg = scaled(photo)
        val colour = adjust.paint(photoPaint) ?: photoPaint

        if (subject == null) {
            // One layer: everything flattened. The motion is a slow zoom, as there is nothing to separate.
            val flat = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(flat)
            c.drawBitmap(bg, 0f, 0f, colour)
            adjust.drawVignette(c, w, h)
            layers.forEach { Compositor.drawLayer(c, it, w, h) }
            return Planes(flat, null, null)
        }

        // Subject with any erased parts removed, at the working size.
        val cutout = scaled(Compositor.bakeSubject(subject, edit))

        // Back: the photo, with the subject's area replaced by the soft clean fill (grown a little so no sharp edge peeks out).
        val back = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val bc = Canvas(back)
        bc.drawBitmap(bg, 0f, 0f, colour)
        val fill = plate?.let { scaled(it) } ?: scaled(SubjectPlate.build(photo, cutout))
        val grow = Paint().apply { maskFilter = BlurMaskFilter(max(2f, w * 0.014f), BlurMaskFilter.Blur.SOLID) }
        val region = cutout.extractAlpha(grow, IntArray(2))
        val saved = bc.saveLayer(null, null)
        bc.drawBitmap(fill, 0f, 0f, colour)
        bc.drawBitmap(region, 0f, 0f, dstInPaint)
        bc.restoreToCount(saved)
        region.recycle()
        adjust.drawVignette(bc, w, h)

        val behind = layers.filter { it.behindSubject && it.visible }
        val mid = if (behind.isEmpty()) null else Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { m ->
            val mc = Canvas(m)
            behind.forEach { Compositor.drawLayer(mc, it, w, h) }
        }

        val front = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val fc = Canvas(front)
        fc.drawBitmap(cutout, 0f, 0f, colour)
        layers.filter { !it.behindSubject && it.visible }.forEach { Compositor.drawLayer(fc, it, w, h) }
        return Planes(back, mid, front)
    }

    /** Ease in, ease out: starts slowly, is quickest in the middle, settles gently (smoothstep). */
    private fun ease(p: Float) = p * p * (3f - 2f * p)

    /**
     * A swing from one side to the other and back, in -1..1, with ease in and ease out at each end so the motion never
     * jerks. [t] loops every 1.0; t = 0 is the middle, moving.
     */
    private fun swing(t: Float): Float {
        val u = ((t % 1f) + 1f) % 1f
        val p = if (u < 0.5f) u * 2f else (1f - u) * 2f
        return ease(p) * 2f - 1f
    }

    /** Where the camera is for time [t] (0..1, loops): x, y in -1..1 and a push amount z in -1..1. Every move is eased. */
    fun motion(style: Style, t: Float): FloatArray = when (style) {
        Style.WOBBLE -> floatArrayOf(swing(t + 0.25f), 0.35f * swing(2f * t + 0.25f), 0f)
        Style.ORBIT -> floatArrayOf(swing(t + 0.25f), 0.6f * swing(t), 0f)
        Style.PUSH -> floatArrayOf(0.25f * swing(t), 0f, swing(t + 0.25f))
    }

    private val m = Matrix()

    /**
     * Draws one frame into a [outW] x [outH] canvas (same shape as the photo). [mx], [my] is the camera position and [mz] the
     * push, each about -1..1; [strength] 0..1 scales how far the layers travel. The back layer travels the most, the
     * subject the least and in the opposite direction, which is what makes it read as depth. Layers are drawn a little
     * larger than the frame so their edges never show.
     */
    fun drawFrame(c: Canvas, p: Planes, outW: Int, outH: Int, mx: Float, my: Float, mz: Float, strength: Float) {
        val travel = strength.coerceIn(0f, 1f) * outW * 0.035f
        val single = !p.hasDepth
        // Only the back layer has to cover the whole frame, so only it is enlarged (by what it can travel); text and the
        // subject keep their size, which keeps text near the edges of the photo from being cropped.
        fun layer(b: Bitmap?, depth: Float, zoom: Float, covers: Boolean = false) {
            if (b == null) return
            val extra = if (covers) 2f * kotlin.math.abs(depth) * travel / outW + 0.02f + kotlin.math.abs(zoom) else 0f
            val s = outW.toFloat() / b.width * (1f + extra) * (1f + zoom * mz)
            m.reset()
            m.postTranslate(-b.width / 2f, -b.height / 2f)
            m.postScale(s, s)
            m.postTranslate(outW / 2f + mx * travel * depth, outH / 2f + my * travel * depth * 0.7f)
            c.drawBitmap(b, m, photoPaint)
        }
        c.drawColor(android.graphics.Color.BLACK)
        if (single) {
            layer(p.back, 0f, 0.06f, covers = true)
        } else {
            layer(p.back, -1.0f, 0.03f, covers = true)
            layer(p.mid, -0.45f, 0.05f)
            layer(p.front, 0.35f, 0.07f)
        }
    }
}
