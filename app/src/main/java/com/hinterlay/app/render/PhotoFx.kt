package com.hinterlay.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import java.util.stream.IntStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Photo tools that need neighbouring pixels or per-pixel curves (the plain colour-matrix sliders live in
 * [PhotoAdjust]). Every value is neutral at its default, so an untouched photo skips this stage entirely.
 */
data class FxParams(
    // Tune
    val ambiance: Float = 0f,     // -1..1  local contrast + colour richness
    val highlights: Float = 0f,   // -1..1  negative recovers bright areas
    val shadows: Float = 0f,      // -1..1  positive lifts dark areas
    // White balance
    val temperature: Float = 0f,  // -1 (cool) .. 1 (warm)
    val tint: Float = 0f,         // -1 (green) .. 1 (magenta)
    // Details
    val sharpen: Float = 0f,      // 0..1
    val structure: Float = 0f,    // -1..1
    // Curves: output level (0..1) at inputs 0, 0.25, 0.5, 0.75, 1. Identity by default.
    val curve: List<Float> = IdentityCurve,
    // Stylise
    val glow: Float = 0f,         // 0..1
    val hdr: Float = 0f,          // 0..1
    val grain: Float = 0f,        // 0..1
    val glitch: Float = 0f,       // 0..1  split colour channels and shifted bands
    val lensBlur: Float = 0f,     // 0..1  blurs the background (or the edges when no subject was found)
    val blend: BlendParams? = null, // a second photo mixed into this one (double exposure)
    val faceMask: com.hinterlay.app.camera.FaceMask = com.hinterlay.app.camera.FaceMask.NONE, // a mask drawn on every face found
    val frame: FrameParams? = null,
    // Local adjustments: each point changes only the area around it, fading smoothly to nothing at its edge.
    val points: List<SelectivePoint> = emptyList(),
    // Painted adjustments, in the order they were painted (the last one is the undo step).
    val strokes: List<BrushStroke> = emptyList(),
) {
    // Points that change nothing yet (just placed) and empty strokes don't count, so they don't trigger a recompute.
    val isNeutral get() = copy(points = points.filterNot { it.isNeutral }, strokes = strokes.filter { it.pts.size >= 2 }) == FxParams()

    companion object {
        val IdentityCurve = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
    }
}

enum class FrameStyle(val label: String) { THIN("Thin"), WIDE("Wide"), ROUND("Rounded"), POLAROID("Polaroid"), FILM("Film"), DOUBLE("Double") }
enum class FrameColor(val label: String, val argb: Int) { WHITE("White", 0xFFFFFFFF.toInt()), BLACK("Black", 0xFF0B0B0B.toInt()), CREAM("Cream", 0xFFF1E8D2.toInt()) }

/** A border drawn over the edges of the photo. [size] is the border width as a fraction of the shorter side. */
data class FrameParams(val style: FrameStyle = FrameStyle.WIDE, val color: FrameColor = FrameColor.WHITE, val size: Float = 0.05f)

/** How the second photo's light is mixed with the first. */
enum class BlendMode(val label: String) {
    SCREEN("Screen"), NORMAL("Mix"), MULTIPLY("Multiply"), OVERLAY("Overlay"), LIGHTEN("Lighten"), DARKEN("Darken"),
}

/** Where the second photo shows. */
enum class BlendArea(val label: String) { EVERYWHERE("Everywhere"), SUBJECT("In subject"), BACKGROUND("Background") }

/**
 * Double exposure settings. The photo itself is held by the editor; [token] changes whenever a different photo is
 * chosen, so edits know to redraw. [zoom] is 1 (just covers the frame) and up; [dx], [dy] move it, -1..1.
 */
data class BlendParams(
    val mode: BlendMode = BlendMode.SCREEN,
    val opacity: Float = 0.7f,
    val zoom: Float = 1f,
    val dx: Float = 0f,
    val dy: Float = 0f,
    val flip: Boolean = false,
    val area: BlendArea = BlendArea.EVERYWHERE,
    val token: Long = 0L,
)

enum class BrushKind(val label: String) {
    LIGHTEN("Lighten"), DARKEN("Darken"), SATURATE("Colour"), MUTE("Mute"), WARM("Warm"), COOL("Cool"), FROST("Frost"), BLUR("Blur"), REVEAL("Reveal"),
}

/**
 * One painted stroke. [pts] is x0, y0, x1, y1... as 0..1 of the photo; [radius] is a fraction of the longer side;
 * [strength] 0..1 is how strongly this stroke changes the photo.
 */
data class BrushStroke(val kind: BrushKind, val radius: Float, val strength: Float, val pts: List<Float>)

/** One selective-adjust point. [x], [y] are 0..1 of the photo; [radius] is a fraction of the photo's longer side. */
data class SelectivePoint(
    val x: Float,
    val y: Float,
    val radius: Float = 0.18f,
    val brightness: Float = 0f,   // -1..1
    val contrast: Float = 0f,     // -1..1
    val saturation: Float = 0f,   // -1..1
    val structure: Float = 0f,    // -1..1
) {
    val isNeutral get() = brightness == 0f && contrast == 0f && saturation == 0f && structure == 0f
}

object PhotoFx {

    private val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }

    /** Applies [p] to [src] (every tool except the lens blur) and returns a new bitmap of the same size. [src] is never modified. */
    fun apply(src: Bitmap, p: FxParams): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        process(px, w, h, p)
        if (p.glitch > 0f) glitch(px, w, h, p.glitch)
        applyPoints(px, w, h, p.points)
        applyStrokes(px, w, h, p.strokes)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * Applies every photo tool, then the lens blur. Returns the background photo and the subject cut-out matched to it:
     *  - tools change the whole photo, so the subject is cut from the edited photo and stays in step with the background;
     *  - the blur only touches the background, so the subject stays sharp. With a subject, the blur is built from the clean
     *    background fill (so no halo of the subject colours); without one, the edges blur and the middle stays sharp.
     * [fg] is the subject cut-out, [plate] the clean background fill (either may be null).
     */
    fun render(
        src: Bitmap, fg: Bitmap?, plate: Bitmap?, p: FxParams, second: Bitmap? = null,
        faces: List<com.hinterlay.app.camera.FacePose>? = null,
    ): Pair<Bitmap, Bitmap?> {
        val bp = p.blend?.takeIf { second != null }
        val toolsOn = !p.copy(lensBlur = 0f, blend = null, faceMask = com.hinterlay.app.camera.FaceMask.NONE, frame = null).isNeutral
        val toned = if (toolsOn) apply(src, p) else src
        // Masks go on after the tools, so they keep their colours, and before the double exposure.
        val tooled = if (p.faceMask != com.hinterlay.app.camera.FaceMask.NONE && !faces.isNullOrEmpty())
            com.hinterlay.app.camera.FaceFx.apply(toned, faces, p.faceMask).also { if (toned !== src) toned.recycle() } else toned
        // Double exposure: the second photo can land everywhere, only inside the subject, or only behind it.
        val blended = if (bp != null && second != null) blendOnto(tooled, second, bp) else null
        val hasSubject = fg != null
        val bgPhoto = when {
            blended == null -> tooled
            bp!!.area == BlendArea.SUBJECT && hasSubject -> tooled
            else -> blended
        }
        val fgPhoto = when {
            blended == null -> tooled
            bp!!.area == BlendArea.BACKGROUND && hasSubject -> tooled
            else -> blended
        }
        // The frame goes on last, over the background and over the subject, so it is never covered.
        val fgFinal = p.frame?.let { frameOn(fgPhoto, it) } ?: fgPhoto
        val sharpFg = if (fg == null) null else if (fgFinal === src) fg else maskWith(fgFinal, fg)
        fun fin(b: Bitmap): Bitmap = p.frame?.let { frameOn(b, it) } ?: b
        if (p.lensBlur <= 0.01f) return fin(bgPhoto) to sharpFg
        // The blur is built from the clean background fill, with the same tools and blend, so it matches what is sharp.
        var base = if (hasSubject && plate != null) (if (toolsOn) apply(plate, p) else plate) else bgPhoto
        if (base !== bgPhoto && blended != null && bgPhoto === blended) base = blendOnto(base, second!!, bp!!)
        val sized = if (base.width == bgPhoto.width && base.height == bgPhoto.height) base else Bitmap.createScaledBitmap(base, bgPhoto.width, bgPhoto.height, true)
        return fin(lensBlur(bgPhoto, sized, p.lensBlur, hasSubject)) to sharpFg
    }

    /** A copy of [src] with the border [f] drawn over its edges. */
    fun frameOn(src: Bitmap, f: FrameParams): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val w = out.width.toFloat()
        val h = out.height.toFloat()
        val b = f.size.coerceIn(0.01f, 0.2f) * min(w, h)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = f.color.argb }
        fun ring(l: Float, t: Float, r: Float, bt: Float, radius: Float) {
            val path = Path().apply {
                fillType = Path.FillType.EVEN_ODD
                addRect(0f, 0f, w, h, Path.Direction.CW)
                addRoundRect(RectF(l, t, w - r, h - bt), radius, radius, Path.Direction.CW)
            }
            c.drawPath(path, paint)
        }
        when (f.style) {
            FrameStyle.THIN -> ring(b * 0.45f, b * 0.45f, b * 0.45f, b * 0.45f, 0f)
            FrameStyle.WIDE -> ring(b, b, b, b, 0f)
            FrameStyle.ROUND -> ring(b, b, b, b, b * 1.7f)
            FrameStyle.POLAROID -> ring(b, b, b, b * 3.6f, 0f)
            FrameStyle.DOUBLE -> {
                ring(b, b, b, b, 0f)
                val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = max(1f, b * 0.12f)
                    color = if (f.color == FrameColor.BLACK) 0xFFF2F2F2.toInt() else 0xFF161616.toInt()
                }
                c.drawRect(b * 1.45f, b * 1.45f, w - b * 1.45f, h - b * 1.45f, line)
            }
            FrameStyle.FILM -> {
                val side = b * 1.9f
                ring(side, b * 0.5f, side, b * 0.5f, 0f)
                // Sprocket holes down both sides.
                val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (f.color == FrameColor.BLACK) 0xFFEDEDED.toInt() else 0xFF222222.toInt() }
                val hw = side * 0.42f
                val hh = side * 0.58f
                val step = hh * 1.9f
                var y = b * 0.5f + step * 0.35f
                while (y < h - hh) {
                    c.drawRoundRect(RectF(side * 0.29f, y, side * 0.29f + hw, y + hh), hw * 0.25f, hw * 0.25f, hole)
                    c.drawRoundRect(RectF(w - side * 0.29f - hw, y, w - side * 0.29f, y + hh), hw * 0.25f, hw * 0.25f, hole)
                    y += step
                }
            }
        }
        return out
    }

    /** [second] laid over [base] (cover-fitted, then zoomed and moved), mixed with [b]'s mode and opacity. */
    fun blendOnto(base: Bitmap, second: Bitmap, b: BlendParams): Bitmap {
        val w = base.width
        val h = base.height
        // Draw the second photo into a layer the size of the base: cover, then zoom, move and optional flip.
        val layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cover = max(w / second.width.toFloat(), h / second.height.toFloat()) * b.zoom.coerceAtLeast(1f)
        val m = android.graphics.Matrix()
        m.postTranslate(-second.width / 2f, -second.height / 2f)
        m.postScale(if (b.flip) -cover else cover, cover)
        // Moving is limited to the spare picture around the frame, so an edge never shows.
        val spareX = max(0f, (second.width * cover - w) / 2f)
        val spareY = max(0f, (second.height * cover - h) / 2f)
        m.postTranslate(w / 2f + b.dx.coerceIn(-1f, 1f) * spareX, h / 2f + b.dy.coerceIn(-1f, 1f) * spareY)
        Canvas(layer).drawBitmap(second, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        val a = IntArray(w * h)
        val s = IntArray(w * h)
        base.getPixels(a, 0, w, 0, 0, w, h)
        layer.getPixels(s, 0, w, 0, 0, w, h)
        layer.recycle()
        val op = b.opacity.coerceIn(0f, 1f)
        val mode = b.mode
        IntStream.range(0, h).parallel().forEach { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val ca = a[i]
                val cs = s[i]
                val ar = ((ca shr 16) and 255) / 255f; val ag = ((ca shr 8) and 255) / 255f; val ab = (ca and 255) / 255f
                val sr = ((cs shr 16) and 255) / 255f; val sg = ((cs shr 8) and 255) / 255f; val sb = (cs and 255) / 255f
                val r = mix(ar, sr, mode, op); val g = mix(ag, sg, mode, op); val bl = mix(ab, sb, mode, op)
                a[i] = (ca and (0xFF shl 24)) or ((clamp(r) * 255f + 0.5f).toInt() shl 16) or ((clamp(g) * 255f + 0.5f).toInt() shl 8) or (clamp(bl) * 255f + 0.5f).toInt()
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(a, 0, w, 0, 0, w, h)
        return out
    }

    private fun mix(a: Float, b: Float, mode: BlendMode, op: Float): Float {
        val r = when (mode) {
            BlendMode.NORMAL -> b
            BlendMode.SCREEN -> 1f - (1f - a) * (1f - b)
            BlendMode.MULTIPLY -> a * b
            BlendMode.OVERLAY -> if (a < 0.5f) 2f * a * b else 1f - 2f * (1f - a) * (1f - b)
            BlendMode.LIGHTEN -> max(a, b)
            BlendMode.DARKEN -> min(a, b)
        }
        return a + (r - a) * op
    }

    /** The background blurred by [amount] (0..1). With a subject the whole [soft] source is blurred; otherwise a radial focus. */
    private fun lensBlur(sharp: Bitmap, soft: Bitmap, amount: Float, hasSubject: Boolean): Bitmap {
        val w = sharp.width
        val h = sharp.height
        val px = IntArray(w * h)
        soft.getPixels(px, 0, w, 0, 0, w, h)
        val radius = max(2, (amount * max(w, h) * 0.03f).toInt())
        val blurred = blur(px, w, h, radius)
        if (hasSubject) {
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.setPixels(blurred, 0, w, 0, 0, w, h)
            return out
        }
        // No subject: keep the middle sharp and let the edges fall away, like a tilt-shift or a fast lens.
        val orig = IntArray(w * h)
        sharp.getPixels(orig, 0, w, 0, 0, w, h)
        val cx = w / 2f
        val cy = h * 0.46f
        val diag = kotlin.math.sqrt(cx * cx + cy * cy)
        IntStream.range(0, h).parallel().forEach { y ->
            for (x in 0 until w) {
                val d = kotlin.math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) / diag
                val k = smoothstep(0.30f, 0.95f, d)
                val a = orig[y * w + x]
                val b = blurred[y * w + x]
                val r = (((a shr 16) and 255) * (1 - k) + ((b shr 16) and 255) * k).toInt()
                val g = (((a shr 8) and 255) * (1 - k) + ((b shr 8) and 255) * k).toInt()
                val bl = ((a and 255) * (1 - k) + (b and 255) * k).toInt()
                orig[y * w + x] = (a and (0xFF shl 24)) or (r shl 16) or (g shl 8) or bl
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(orig, 0, w, 0, 0, w, h)
        return out
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** The edited photo with the subject cut-out's transparency, so the subject matches the edited background. */
    fun maskWith(edited: Bitmap, cutout: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(edited.width, edited.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(edited, 0f, 0f, null)
        c.drawBitmap(cutout, null, android.graphics.Rect(0, 0, edited.width, edited.height), maskPaint)
        return out
    }

    // ---- Pipeline -----------------------------------------------------------------------------

    private fun process(px: IntArray, w: Int, h: Int, p: FxParams) {
        // Blurs are only built when a tool needs them. Radii scale with the photo so previews match export.
        val longSide = max(w, h)
        val needMid = p.structure != 0f || p.ambiance != 0f || p.hdr > 0f
        val mid = if (needMid) blur(px, w, h, max(2, longSide / 60)) else null
        val small = if (p.sharpen > 0f) blur(px, w, h, max(1, longSide / 900)) else null
        val big = if (p.glow > 0f) blur(px, w, h, max(4, longSide / 25)) else null
        val lut = curveLut(p.curve)
        val useCurve = p.curve != FxParams.IdentityCurve

        val rGain = 1f + 0.25f * p.temperature + 0.05f * p.tint
        val gGain = 1f - 0.15f * p.tint
        val bGain = 1f - 0.25f * p.temperature + 0.05f * p.tint
        val wb = p.temperature != 0f || p.tint != 0f
        val tone = p.shadows != 0f || p.highlights != 0f

        IntStream.range(0, h).parallel().forEach { y ->
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                val c = px[i]
                var r = ((c shr 16) and 255) / 255f
                var g = ((c shr 8) and 255) / 255f
                var b = (c and 255) / 255f

                if (wb) { r *= rGain; g *= gGain; b *= bGain }

                if (tone) {
                    val l = 0.299f * r + 0.587f * g + 0.114f * b
                    val sh = p.shadows * 0.35f * (1f - l) * (1f - l)
                    val hi = p.highlights * 0.35f * l * l
                    val d = sh + hi
                    r += d; g += d; b += d
                }

                if (mid != null) {
                    val m = mid[i]
                    val mr = ((m shr 16) and 255) / 255f
                    val mg = ((m shr 8) and 255) / 255f
                    val mb = (m and 255) / 255f
                    val k = p.structure * 1.1f + p.ambiance * 0.7f + p.hdr * 1.3f
                    r += (r - mr) * k; g += (g - mg) * k; b += (b - mb) * k
                    if (p.ambiance != 0f) {
                        val l = 0.299f * r + 0.587f * g + 0.114f * b
                        val s = 1f + p.ambiance * 0.35f
                        r = l + (r - l) * s; g = l + (g - l) * s; b = l + (b - l) * s
                    }
                    if (p.hdr > 0f) {
                        // Tone compression: lift darks, pull highlights in, so detail shows at both ends.
                        val l = 0.299f * r + 0.587f * g + 0.114f * b
                        val d = p.hdr * (0.12f * (1f - l) * (1f - l) - 0.10f * l * l)
                        r += d; g += d; b += d
                    }
                }

                if (small != null) {
                    val m = small[i]
                    val k = p.sharpen * 1.6f
                    r += (r - ((m shr 16) and 255) / 255f) * k
                    g += (g - ((m shr 8) and 255) / 255f) * k
                    b += (b - (m and 255) / 255f) * k
                }

                if (big != null) {
                    val m = big[i]
                    val k = p.glow * 0.75f
                    // Screen blend of the soft, bright haze.
                    r = 1f - (1f - clamp(r)) * (1f - ((m shr 16) and 255) / 255f * k)
                    g = 1f - (1f - clamp(g)) * (1f - ((m shr 8) and 255) / 255f * k)
                    b = 1f - (1f - clamp(b)) * (1f - (m and 255) / 255f * k)
                }

                var ri = (clamp(r) * 255f + 0.5f).toInt()
                var gi = (clamp(g) * 255f + 0.5f).toInt()
                var bi = (clamp(b) * 255f + 0.5f).toInt()

                if (useCurve) { ri = lut[ri]; gi = lut[gi]; bi = lut[bi] }

                if (p.grain > 0f) {
                    val l = (ri + gi + bi) / 765f
                    // Strongest in the midtones, like film.
                    val amp = p.grain * 46f * (0.45f + 0.55f * (1f - kotlin.math.abs(l - 0.5f) * 2f))
                    val noise = (hash(x, y) - 0.5f) * amp
                    ri = clampI(ri + noise.toInt()); gi = clampI(gi + noise.toInt()); bi = clampI(bi + noise.toInt())
                }

                px[i] = (c and (0xFF shl 24)) or (ri shl 16) or (gi shl 8) or bi
            }
        }
    }

    /** Digital glitch: red and blue drift apart, and a few horizontal bands slide sideways. The pattern is fixed, so preview and export match. */
    private fun glitch(px: IntArray, w: Int, h: Int, amount: Float) {
        val src = px.clone()
        val split = (amount * 0.012f * w).toInt()
        val shift = IntArray(h)
        val rnd = java.util.Random(7)
        val bands = 4 + (amount * 14).toInt()
        repeat(bands) {
            val y0 = rnd.nextInt(h)
            val bh = max(2, (h * (0.006f + rnd.nextFloat() * 0.035f)).toInt())
            val s = ((rnd.nextFloat() - 0.5f) * 2f * amount * 0.09f * w).toInt()
            for (y in y0 until minOf(h, y0 + bh)) shift[y] = s
        }
        IntStream.range(0, h).parallel().forEach { y ->
            val row = y * w
            val sh = shift[y]
            val sp = split + if (sh != 0) kotlin.math.abs(sh) / 6 else 0
            for (x in 0 until w) {
                val xr = (x - sh - sp).coerceIn(0, w - 1)
                val xg = (x - sh).coerceIn(0, w - 1)
                val xb = (x - sh + sp).coerceIn(0, w - 1)
                val r = (src[row + xr] shr 16) and 255
                val g = (src[row + xg] shr 8) and 255
                val b = src[row + xb] and 255
                px[row + x] = (src[row + x] and (0xFF shl 24)) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    // ---- Brush strokes -------------------------------------------------------------------------

    /**
     * Each kind of brush gets one soft mask (all its strokes drawn into it, overlaps build up), then the
     * adjustment is applied through that mask. The mask is built at a quarter of the photo's size and blurred, so the
     * edge falls off smoothly (no visible rings) and the work stays small.
     */
    /** Soft mask (0..1, at [sw] x [sh]) of [list]: strokes drawn in, blurred, overlaps building up. */
    private fun paintMask(list: List<BrushStroke>, sw: Int, sh: Int, longSide: Int, sc: Float): FloatArray {
        val acc = FloatArray(sw * sh)
        for (s in list) {
            val r = max(2f, s.radius * longSide) * sc
            val one = Bitmap.createBitmap(sw, sh, Bitmap.Config.ALPHA_8)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                color = android.graphics.Color.BLACK; strokeWidth = 2f * r * 0.7f
            }
            val path = Path()
            path.moveTo(s.pts[0] * sw, s.pts[1] * sh)
            if (s.pts.size == 2) path.lineTo(s.pts[0] * sw + 0.01f, s.pts[1] * sh)
            else for (i in 2 until s.pts.size step 2) path.lineTo(s.pts[i] * sw, s.pts[i + 1] * sh)
            Canvas(one).drawPath(path, paint)
            val rb = one.rowBytes
            val bb = ByteArray(rb * sh)
            one.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(bb))
            one.recycle()
            val gray = IntArray(sw * sh) { j ->
                val a = bb[(j / sw) * rb + j % sw].toInt() and 255
                (255 shl 24) or (a shl 16) or (a shl 8) or a
            }
            val soft = blur(gray, sw, sh, max(1, (r * 0.45f).toInt()))
            val k = s.strength.coerceIn(0f, 1f)
            for (j in acc.indices) {
                val m = ((soft[j] shr 16) and 255) / 255f * k
                acc[j] = 1f - (1f - acc[j]) * (1f - m)
            }
        }
        return acc
    }

    private fun applyStrokes(px: IntArray, w: Int, h: Int, strokes: List<BrushStroke>) {
        val longSide = max(w, h)
        val revealStrokes = strokes.filter { it.kind == BrushKind.REVEAL && it.pts.size >= 2 }
        val rsc = if (longSide > 800) 0.25f else 1f
        val reveal = if (revealStrokes.isEmpty()) null else paintMask(revealStrokes, max(2, (w * rsc).toInt()), max(2, (h * rsc).toInt()), longSide, rsc)
        for (kind in BrushKind.entries) {
            if (kind == BrushKind.REVEAL) continue
            val ofKind = strokes.filter { it.kind == kind && it.pts.size >= 2 }
            if (ofKind.isEmpty()) continue
            // Frost and Blur paint toward a softened copy of the photo as it is at this point.
            val soft = when (kind) {
                BrushKind.FROST -> blur(px, w, h, max(3, longSide / 45))
                BrushKind.BLUR -> blur(px, w, h, max(2, longSide / 110))
                else -> null
            }
            val sc = if (longSide > 800) 0.25f else 1f
            val sw = max(2, (w * sc).toInt())
            val sh = max(2, (h * sc).toInt())
            val acc = paintMask(ofKind, sw, sh, longSide, sc)
            // Reveal wipes the other brush effects away again where it was painted.
            if (reveal != null) for (j in acc.indices) acc[j] *= 1f - reveal[j]
            IntStream.range(0, h).parallel().forEach { y ->
                val gy = y * sc
                val y0 = min(sh - 2, gy.toInt())
                val ty = gy - y0
                for (x in 0 until w) {
                    val gx = x * sc
                    val x0 = min(sw - 2, gx.toInt())
                    val tx = gx - x0
                    val top = acc[y0 * sw + x0] * (1f - tx) + acc[y0 * sw + x0 + 1] * tx
                    val bot = acc[(y0 + 1) * sw + x0] * (1f - tx) + acc[(y0 + 1) * sw + x0 + 1] * tx
                    val a = top * (1f - ty) + bot * ty
                    if (a <= 0.003f) continue
                    val i = y * w + x
                    val c = px[i]
                    var r = ((c shr 16) and 255) / 255f
                    var g = ((c shr 8) and 255) / 255f
                    var b = (c and 255) / 255f
                    when (kind) {
                        BrushKind.LIGHTEN -> { val k = 0.45f * a; r += (1f - r) * k; g += (1f - g) * k; b += (1f - b) * k }
                        BrushKind.DARKEN -> { val k = 1f - 0.55f * a; r *= k; g *= k; b *= k }
                        BrushKind.SATURATE, BrushKind.MUTE -> {
                            val l = 0.299f * r + 0.587f * g + 0.114f * b
                            val s = if (kind == BrushKind.SATURATE) 1f + 1.0f * a else 1f - 0.9f * a
                            r = l + (r - l) * s; g = l + (g - l) * s; b = l + (b - l) * s
                        }
                        BrushKind.WARM -> { r += 0.16f * a; b -= 0.16f * a }
                        BrushKind.COOL -> { r -= 0.16f * a; b += 0.16f * a }
                        BrushKind.REVEAL -> Unit
                        BrushKind.FROST, BrushKind.BLUR -> {
                            val m = soft!![i]
                            var fr = ((m shr 16) and 255) / 255f; var fg = ((m shr 8) and 255) / 255f; var fb = (m and 255) / 255f
                            if (kind == BrushKind.FROST) { fr = fr * 0.8f + 0.2f; fg = fg * 0.8f + 0.2f; fb = fb * 0.8f + 0.2f }
                            r += (fr - r) * a; g += (fg - g) * a; b += (fb - b) * a
                        }
                    }
                    px[i] = (c and (0xFF shl 24)) or
                        ((clamp(r) * 255f + 0.5f).toInt() shl 16) or ((clamp(g) * 255f + 0.5f).toInt() shl 8) or (clamp(b) * 255f + 0.5f).toInt()
                }
            }
        }
    }

    // ---- Selective points ----------------------------------------------------------------------

    /** Applies each point inside its own circle, with a smooth falloff. Only the pixels in the circle's box are touched. */
    private fun applyPoints(px: IntArray, w: Int, h: Int, points: List<SelectivePoint>) {
        val longSide = max(w, h)
        for (p in points) {
            if (p.isNeutral) continue
            val cx = p.x * w
            val cy = p.y * h
            val r = max(8f, p.radius * longSide)
            val x0 = max(0, (cx - r).toInt()); val x1 = min(w - 1, (cx + r).toInt())
            val y0 = max(0, (cy - r).toInt()); val y1 = min(h - 1, (cy + r).toInt())
            val bw = x1 - x0 + 1
            val bh = y1 - y0 + 1
            if (bw < 2 || bh < 2) continue
            // Structure compares each pixel with a blur of its own surroundings, built just for this box.
            val mid = if (p.structure != 0f) {
                val sub = IntArray(bw * bh)
                for (yy in 0 until bh) System.arraycopy(px, (y0 + yy) * w + x0, sub, yy * bw, bw)
                blur(sub, bw, bh, max(2, (r / 6f).toInt()))
            } else null
            IntStream.range(0, bh).parallel().forEach { yy ->
                val y = y0 + yy
                for (xx in 0 until bw) {
                    val x = x0 + xx
                    val dx = x - cx
                    val dy = y - cy
                    val d = kotlin.math.sqrt(dx * dx + dy * dy) / r
                    if (d >= 1f) continue
                    val t = 1f - d
                    val k = t * t * (3f - 2f * t) // smoothstep: full strength in the middle, nothing at the edge
                    val i = y * w + x
                    val c = px[i]
                    var rr = ((c shr 16) and 255) / 255f
                    var gg = ((c shr 8) and 255) / 255f
                    var bb = (c and 255) / 255f
                    if (mid != null) {
                        val m = mid[yy * bw + xx]
                        val s = p.structure * 1.2f * k
                        rr += (rr - ((m shr 16) and 255) / 255f) * s
                        gg += (gg - ((m shr 8) and 255) / 255f) * s
                        bb += (bb - (m and 255) / 255f) * s
                    }
                    if (p.contrast != 0f) {
                        val s = 1f + p.contrast * 0.8f * k
                        rr = (rr - 0.5f) * s + 0.5f; gg = (gg - 0.5f) * s + 0.5f; bb = (bb - 0.5f) * s + 0.5f
                    }
                    if (p.brightness != 0f) {
                        val a = p.brightness * 0.4f * k
                        rr += a; gg += a; bb += a
                    }
                    if (p.saturation != 0f) {
                        val l = 0.299f * rr + 0.587f * gg + 0.114f * bb
                        val s = 1f + p.saturation * k
                        rr = l + (rr - l) * s; gg = l + (gg - l) * s; bb = l + (bb - l) * s
                    }
                    px[i] = (c and (0xFF shl 24)) or
                        ((clamp(rr) * 255f + 0.5f).toInt() shl 16) or ((clamp(gg) * 255f + 0.5f).toInt() shl 8) or (clamp(bb) * 255f + 0.5f).toInt()
                }
            }
        }
    }

    private fun clamp(v: Float) = if (v < 0f) 0f else if (v > 1f) 1f else v
    private fun clampI(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v

    /** Cheap deterministic noise in 0..1 (no allocation, same result for preview and export at the same size). */
    private fun hash(x: Int, y: Int): Float {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65535f
    }

    // ---- Curves -------------------------------------------------------------------------------

    /** 256-entry table through the five control points, smooth and monotone so the curve never inverts tones. */
    private fun curveLut(points: List<Float>): IntArray {
        val ys = FloatArray(5) { points.getOrElse(it) { it / 4f }.coerceIn(0f, 1f) }
        // Monotone cubic (Fritsch-Carlson) over x = 0, .25, .5, .75, 1.
        val dx = 0.25f
        val d = FloatArray(4) { (ys[it + 1] - ys[it]) / dx }
        val m = FloatArray(5)
        m[0] = d[0]; m[4] = d[3]
        for (i in 1..3) m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
        for (i in 0..3) {
            if (d[i] == 0f) { m[i] = 0f; m[i + 1] = 0f } else {
                val a = m[i] / d[i]; val b = m[i + 1] / d[i]
                val s = a * a + b * b
                if (s > 9f) { val t = 3f / kotlin.math.sqrt(s); m[i] = t * a * d[i]; m[i + 1] = t * b * d[i] }
            }
        }
        return IntArray(256) { v ->
            val x = v / 255f
            val seg = min(3, (x / dx).toInt())
            val t = (x - seg * dx) / dx
            val t2 = t * t; val t3 = t2 * t
            val y = (2 * t3 - 3 * t2 + 1) * ys[seg] + (t3 - 2 * t2 + t) * dx * m[seg] +
                (-2 * t3 + 3 * t2) * ys[seg + 1] + (t3 - t2) * dx * m[seg + 1]
            (clamp(y) * 255f).roundToInt()
        }
    }

    // ---- Blur ---------------------------------------------------------------------------------

    /** Two-pass box blur (close to a Gaussian) on packed ARGB, in O(pixels) whatever the radius. */
    internal fun blur(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        var a = src
        repeat(2) {
            val tmp = IntArray(w * h)
            boxH(a, tmp, w, h, radius)
            val out = IntArray(w * h)
            boxV(tmp, out, w, h, radius)
            a = out
        }
        return a
    }

    private fun boxH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val inv = 1f / (2 * r + 1)
        IntStream.range(0, h).parallel().forEach { y ->
            val row = y * w
            var sr = 0; var sg = 0; var sb = 0
            for (k in -r..r) {
                val c = src[row + k.coerceIn(0, w - 1)]
                sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255
            }
            for (x in 0 until w) {
                dst[row + x] = (255 shl 24) or ((sr * inv).toInt() shl 16) or ((sg * inv).toInt() shl 8) or (sb * inv).toInt()
                val add = src[row + min(w - 1, x + r + 1)]
                val sub = src[row + max(0, x - r)]
                sr += ((add shr 16) and 255) - ((sub shr 16) and 255)
                sg += ((add shr 8) and 255) - ((sub shr 8) and 255)
                sb += (add and 255) - (sub and 255)
            }
        }
    }

    private fun boxV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val inv = 1f / (2 * r + 1)
        IntStream.range(0, w).parallel().forEach { x ->
            var sr = 0; var sg = 0; var sb = 0
            for (k in -r..r) {
                val c = src[k.coerceIn(0, h - 1) * w + x]
                sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255
            }
            for (y in 0 until h) {
                dst[y * w + x] = (255 shl 24) or ((sr * inv).toInt() shl 16) or ((sg * inv).toInt() shl 8) or (sb * inv).toInt()
                val add = src[min(h - 1, y + r + 1) * w + x]
                val sub = src[max(0, y - r) * w + x]
                sr += ((add shr 16) and 255) - ((sub shr 16) and 255)
                sg += ((add shr 8) and 255) - ((sub shr 8) and 255)
                sb += (add and 255) - (sub and 255)
            }
        }
    }
}
