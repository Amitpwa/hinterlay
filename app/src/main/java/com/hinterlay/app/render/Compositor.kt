package com.hinterlay.app.render

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.hinterlay.app.editor.ExportFormat
import com.hinterlay.app.editor.FontChoice
import com.hinterlay.app.editor.TextAlignChoice
import com.hinterlay.app.editor.TextLayer
import com.hinterlay.app.editor.TextMaterial
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Single renderer used by both the live preview and the export, so what you see is
 * exactly what gets saved. Draw order:
 *   background photo -> text marked "behind subject" -> subject cut-out -> text in front.
 */
object Compositor {

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val typefaces = HashMap<Triple<String, Int, Boolean>, Typeface>()

    private var assets: AssetManager? = null

    /** Must be called once (Application/Activity) so bundled Google Fonts can load. */
    fun init(context: Context) {
        if (assets == null) assets = context.applicationContext.assets
    }

    fun typeface(font: FontChoice, weight: Int, italic: Boolean): Typeface = synchronized(typefaces) {
        val asset = font.asset
        if (asset != null) {
            return@synchronized typefaces.getOrPut(Triple(asset, 0, false)) {
                runCatching { Typeface.createFromAsset(assets, "fonts/$asset") }.getOrDefault(Typeface.DEFAULT_BOLD)
            }
        }
        typefaces.getOrPut(Triple(font.family, weight, italic)) {
            Typeface.create(Typeface.create(font.family, Typeface.NORMAL), weight, italic)
        }
    }

    class Measured(
        val fill: StaticLayout,
        val stroke: StaticLayout?,
        val width: Float,
        val height: Float,
        /** Bubble passes drawn back-to-front before [fill]. */
        val bubble: List<StaticLayout> = emptyList(),
        /** Specular highlight drawn on top of [fill] (bubble only). */
        val gloss: StaticLayout? = null,
    )

    private fun mix(a: Int, b: Int, t: Float): Int = Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).roundToInt(),
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).roundToInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).roundToInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).roundToInt(),
    )

    fun measure(layer: TextLayer, imageWidth: Int): Measured? {
        if (layer.text.isBlank()) return null
        // A 3D material is shaded from the plain letter shapes, so colour, outline, shadow and bubble are left out here.
        val isMat = layer.material != TextMaterial.NONE
        val shown = layer.textCase.apply(layer.text)
        val size = max(1f, layer.sizeRel * imageWidth)
        val alpha = (255 * layer.opacity).roundToInt().coerceIn(0, 255)
        val strength = layer.shadowStrength.coerceIn(0f, 1f)
        val shadowRadius = size * 0.08f * (0.5f + strength)
        val shadowDy = size * 0.04f * (0.5f + strength)
        val shadowColor = Color.argb(
            (255 * strength * layer.opacity).roundToInt().coerceIn(0, 255),
            Color.red(layer.shadowColor), Color.green(layer.shadowColor), Color.blue(layer.shadowColor),
        )

        val fill = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = typeface(layer.font, layer.weight.weight, layer.italic)
            textSize = size
            letterSpacing = layer.letterSpacing
            color = if (isMat) Color.WHITE else layer.color
            this.alpha = if (isMat) 255 else (Color.alpha(layer.color) * layer.opacity).roundToInt().coerceIn(0, 255)
        }
        val stroke = if (layer.strokeWidth > 0f && !isMat) {
            TextPaint(fill).apply {
                style = Paint.Style.STROKE
                strokeWidth = layer.strokeWidth * size
                strokeJoin = Paint.Join.ROUND
                color = layer.strokeColor
                this.alpha = alpha
                if (layer.shadow) setShadowLayer(shadowRadius, 0f, shadowDy, shadowColor)
            }
        } else null
        if (layer.shadow && stroke == null && !isMat) fill.setShadowLayer(shadowRadius, 0f, shadowDy, shadowColor)

        val strokePad = stroke?.strokeWidth ?: 0f
        val widest = shown.split('\n').maxOf { Layout.getDesiredWidth(it, fill) }
        val width = (ceil(widest + strokePad).toInt() + 2).coerceAtLeast(1)
        val alignment = when (layer.align) {
            TextAlignChoice.START -> Layout.Alignment.ALIGN_NORMAL
            TextAlignChoice.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextAlignChoice.END -> Layout.Alignment.ALIGN_OPPOSITE
        }
        fun build(p: TextPaint) = StaticLayout.Builder.obtain(shown, 0, shown.length, p, width)
            .setAlignment(alignment)
            .setIncludePad(false)
            .build()

        val fillLayout = build(fill)
        if (!layer.bubble || isMat) {
            return Measured(fillLayout, stroke?.let { build(it) }, width.toFloat(), fillLayout.height.toFloat())
        }

        // Bubble: dark rim -> body colour -> glossy light face -> white specular on top.
        val h = fillLayout.height.toFloat()
        fun rim(widthEm: Float, color: Int) = TextPaint(fill).apply {
            style = Paint.Style.STROKE
            strokeWidth = widthEm * size
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            this.color = color
            this.alpha = alpha
            clearShadowLayer()
        }
        val outer = rim(0.2f, mix(layer.color, Color.BLACK, 0.3f)).apply {
            if (layer.shadow) setShadowLayer(shadowRadius * 1.5f, 0f, shadowDy * 1.5f, shadowColor)
        }
        val body = rim(0.1f, layer.color)
        val face = TextPaint(fill).apply {
            clearShadowLayer()
            this.alpha = alpha
            shader = LinearGradient(
                0f, 0f, 0f, h,
                mix(layer.color, Color.WHITE, 0.85f), mix(layer.color, Color.WHITE, 0.3f),
                Shader.TileMode.CLAMP,
            )
        }
        val gloss = TextPaint(face).apply {
            shader = LinearGradient(
                0f, h * 0.05f, 0f, h * 0.5f, Color.argb(210, 255, 255, 255), Color.argb(0, 255, 255, 255),
                Shader.TileMode.CLAMP,
            )
        }
        val pad = outer.strokeWidth
        val bw = (ceil(widest + pad).toInt() + 2).coerceAtLeast(1)
        fun buildW(p: TextPaint) = StaticLayout.Builder.obtain(shown, 0, shown.length, p, bw)
            .setAlignment(alignment).setIncludePad(false).build()
        return Measured(
            fill = buildW(face), stroke = null, width = bw.toFloat(), height = h,
            bubble = listOf(buildW(outer), buildW(body)), gloss = buildW(gloss),
        )
    }

    // ---- Perspective -----------------------------------------------------------------

    /**
     * The four corners (TL, TR, BR, BL) of the text box in layer-local space (origin = box
     * centre, before rotation), including the user's perspective offsets. Offsets are stored
     * in box widths/heights so the warp keeps its shape when the text or size changes.
     */
    fun localCorners(layer: TextLayer, boxW: Float, boxH: Float): FloatArray {
        val base = floatArrayOf(-boxW / 2, -boxH / 2, boxW / 2, -boxH / 2, boxW / 2, boxH / 2, -boxW / 2, boxH / 2)
        val p = layer.perspective ?: return base
        return FloatArray(8) { i -> base[i] + p[i] * (if (i % 2 == 0) boxW else boxH) }
    }

    /** Corners in image pixels (rotation and position applied). Used for handles and hit-testing. */
    fun imageCorners(layer: TextLayer, imageWidth: Int, imageHeight: Int): FloatArray? {
        val m = measure(layer, imageWidth) ?: return null
        val local = localCorners(layer, m.width, m.height)
        val rad = Math.toRadians(layer.rotation.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        return FloatArray(8) { i ->
            val x = local[i - i % 2]
            val y = local[i - i % 2 + 1]
            if (i % 2 == 0) layer.x * imageWidth + x * c - y * s else layer.y * imageHeight + x * s + y * c
        }
    }

    private val warpCache = object : LinkedHashMap<Pair<TextLayer, Int>, Pair<Bitmap, Float>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<TextLayer, Int>, Pair<Bitmap, Float>>?) = size > 6
    }

    /**
     * Phone tilt for the editor preview, about -1..1 on each axis. Materials turn their studio by this much, so reflections
     * slide as you tilt the phone. Exports never use it (always 0), so a saved photo matches the light slider.
     */
    // Per thread: only the preview thread sets it, so an export running on another thread always renders with no tilt.
    private val liveTilt = ThreadLocal.withInitial { floatArrayOf(0f, 0f) }

    fun setLiveTilt(x: Float, y: Float) { liveTilt.get()!!.let { it[0] = x; it[1] = y } }

    /** The 3D look of a material layer as a bitmap (with its soft shadow when the layer has one), plus its padding. */
    private fun materialBitmap(layer: TextLayer, m: Measured, imageWidth: Int): Pair<Bitmap, Float> {
        val pad = max(8f, layer.sizeRel * imageWidth * 0.3f)
        val bw = ceil(m.width + 2 * pad).toInt().coerceAtLeast(1)
        val bh = ceil(m.height + 2 * pad).toInt().coerceAtLeast(1)
        // Only things that change the letter shapes or the material belong in the key, not the light.
        val key = Triple(layer.copy(id = 0, x = 0f, y = 0f, rotation = 0f, perspective = null, behindSubject = true, visible = true,
            eraseMask = null, maskVersion = 0, materialLight = 0f, opacity = 1f, color = 0, shadow = false), imageWidth, bw)
        val sizePx = layer.sizeRel * imageWidth
        val field = TextMaterials.field(key, layer.material, sizePx) {
            val mask = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val c = Canvas(mask)
            c.translate(pad, pad)
            m.fill.draw(c)
            val px = IntArray(bw * bh)
            mask.getPixels(px, 0, bw, 0, 0, bw, bh)
            mask.recycle()
            Triple(ByteArray(bw * bh) { (px[it] ushr 24).toByte() }, bw, bh)
        }
        val tilt = liveTilt.get()!!
        val shaded = TextMaterials.shade(field, layer.material, (layer.materialLight - 0.5f) * 2f + tilt[0], tilt[1], layer.opacity)
        if (!layer.shadow) return shaded to pad
        // Soft shadow under and to the right, in the layer's shadow colour and strength, then the lit letters on top.
        val out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val oc = Canvas(out)
        val strength = layer.shadowStrength.coerceIn(0f, 1f)
        val shadow = TextMaterials.shadowMask(field, sizePx * 0.05f * (0.6f + strength))
        val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((255 * strength * layer.opacity * 0.9f).roundToInt().coerceIn(0, 255),
                Color.red(layer.shadowColor), Color.green(layer.shadowColor), Color.blue(layer.shadowColor))
        }
        oc.drawBitmap(shadow, sizePx * 0.015f, sizePx * 0.045f, sp)
        oc.drawBitmap(shaded, 0f, 0f, bitmapPaint)
        return out to pad
    }

    /** Renders the flat text into a bitmap (so it can be projected). Returns the bitmap and its padding. */
    private fun flatBitmap(layer: TextLayer, m: Measured, imageWidth: Int): Pair<Bitmap, Float> = synchronized(warpCache) {
        val key = layer.copy(id = 0, x = 0f, y = 0f, rotation = 0f, perspective = null, behindSubject = true, visible = true, eraseMask = null, maskVersion = 0) to imageWidth
        warpCache.getOrPut(key) {
            val pad = max(8f, layer.sizeRel * imageWidth * 0.3f)
            val bmp = Bitmap.createBitmap(
                ceil(m.width + 2 * pad).toInt().coerceAtLeast(1), ceil(m.height + 2 * pad).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
            val c = Canvas(bmp)
            c.translate(pad, pad)
            m.bubble.forEach { it.draw(c) }
            m.stroke?.draw(c)
            m.fill.draw(c)
            m.gloss?.draw(c)
            bmp to pad
        }
    }

    private val maskPaint = Paint().apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
    }

    fun drawLayer(canvas: Canvas, layer: TextLayer, imageWidth: Int, imageHeight: Int) {
        if (!layer.visible) return
        val mask = layer.eraseMask
        if (mask == null) {
            drawLayerContent(canvas, layer, imageWidth, imageHeight)
            return
        }
        // Draw the text into its own layer, punch the erased areas out of it, then composite.
        val saved = canvas.saveLayer(null, null)
        drawLayerContent(canvas, layer, imageWidth, imageHeight)
        canvas.drawBitmap(mask, null, Rect(0, 0, imageWidth, imageHeight), maskPaint)
        canvas.restoreToCount(saved)
    }

    private fun drawLayerContent(canvas: Canvas, layer: TextLayer, imageWidth: Int, imageHeight: Int) {
        val m = measure(layer, imageWidth) ?: return
        canvas.save()
        canvas.translate(layer.x * imageWidth, layer.y * imageHeight)
        canvas.rotate(layer.rotation)
        if (layer.perspective != null || layer.material != TextMaterial.NONE) {
            val (bmp, pad) = if (layer.material != TextMaterial.NONE) materialBitmap(layer, m, imageWidth) else flatBitmap(layer, m, imageWidth)
            val dst = localCorners(layer, m.width, m.height)
            val src = floatArrayOf(pad, pad, pad + m.width, pad, pad + m.width, pad + m.height, pad, pad + m.height)
            val matrix = android.graphics.Matrix()
            if (matrix.setPolyToPoly(src, 0, dst, 0, 4)) {
                canvas.drawBitmap(bmp, matrix, bitmapPaint)
            }
        } else {
            canvas.translate(-m.width / 2f, -m.height / 2f)
            m.bubble.forEach { it.draw(canvas) }
            m.stroke?.draw(canvas)
            m.fill.draw(canvas)
            m.gloss?.draw(canvas)
        }
        canvas.restore()
    }

    /**
     * User edits to the subject. [mask] marks erased areas (alpha = removed from the subject, in a
     * low-resolution image-space bitmap); [plate] is the soft clean background shown instead.
     */
    class SubjectEdit(val mask: Bitmap?, val plate: Bitmap?, val smooth: Bitmap? = null)

    private val dstOutPaint = Paint().apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
        isFilterBitmap = true
    }
    private val dstInPaint = Paint().apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        isFilterBitmap = true
    }

    /** Draws the subject cut-out minus any erased areas. */
    private fun drawSubject(canvas: Canvas, foreground: Bitmap, edit: SubjectEdit?, w: Int, h: Int, paint: Paint = bitmapPaint) {
        val mask = edit?.mask
        if (mask == null) {
            canvas.drawBitmap(foreground, null, Rect(0, 0, w, h), paint)
            return
        }
        val saved = canvas.saveLayer(null, null)
        canvas.drawBitmap(foreground, null, Rect(0, 0, w, h), paint)
        canvas.drawBitmap(mask, null, Rect(0, 0, w, h), dstOutPaint)
        canvas.restoreToCount(saved)
    }

    /** Renders the full composition at the background's native resolution. */
    fun render(
        canvas: Canvas, background: Bitmap, foreground: Bitmap?, layers: List<TextLayer>,
        edit: SubjectEdit? = null, adjust: PhotoAdjust? = null,
        clock: com.hinterlay.app.wallpaper.DepthClockConfig? = null,
        clockCal: java.util.Calendar? = null,
    ) {
        val w = background.width
        val h = background.height
        val photoPaint = adjust?.paint(bitmapPaint) ?: bitmapPaint
        canvas.drawBitmap(background, 0f, 0f, photoPaint)
        // Depth-clock back-plane wash (dim + vignette), behind everything, matching the live wallpaper.
        if (clock != null) {
            if (clock.dim > 0f) canvas.drawColor(((clock.dim.coerceIn(0f, 1f) * 255f).toInt() shl 24))
            com.hinterlay.app.wallpaper.DepthFx.drawVignette(canvas, w, h, clock)
        }
        fun drawClock() = clock?.let {
            com.hinterlay.app.wallpaper.ClockRenderer.draw(canvas, w, h, it, clockCal ?: java.util.Calendar.getInstance(), background)
        }
        if (foreground == null) {
            drawSmooth(canvas, edit, adjust, w, h)
            adjust?.drawVignette(canvas, w, h)
            layers.forEach { drawLayer(canvas, it, w, h) }
            drawClock()
            return
        }
        // Erased parts of the subject are covered by the soft clean background, so they truly disappear.
        val mask = edit?.mask
        val plate = edit?.plate
        if (mask != null && plate != null) {
            val saved = canvas.saveLayer(null, null)
            canvas.drawBitmap(plate, null, Rect(0, 0, w, h), photoPaint)
            canvas.drawBitmap(mask, null, Rect(0, 0, w, h), dstInPaint)
            canvas.restoreToCount(saved)
        }
        layers.filter { it.behindSubject }.forEach { drawLayer(canvas, it, w, h) }
        if (clock != null && clock.behind) drawClock()
        drawSubject(canvas, foreground, edit, w, h, photoPaint)
        drawSmooth(canvas, edit, adjust, w, h)
        adjust?.drawVignette(canvas, w, h)
        layers.filterNot { it.behindSubject }.forEach { drawLayer(canvas, it, w, h) }
        if (clock != null && !clock.behind) drawClock()
    }

    private val smoothPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Skin smoothing: the pre-blurred skin layer drawn over the photo at the user's strength. */
    private fun drawSmooth(canvas: Canvas, edit: SubjectEdit?, adjust: PhotoAdjust?, w: Int, h: Int) {
        val layer = edit?.smooth ?: return
        val k = adjust?.smooth ?: return
        if (k <= 0.01f) return
        smoothPaint.alpha = (k * 0.9f * 255f).toInt().coerceIn(0, 255)
        canvas.drawBitmap(layer, null, Rect(0, 0, w, h), smoothPaint)
    }

    /** The subject cut-out with erased areas applied, as a new bitmap. */
    fun bakeSubject(foreground: Bitmap, edit: SubjectEdit?): Bitmap {
        val out = Bitmap.createBitmap(foreground.width, foreground.height, Bitmap.Config.ARGB_8888)
        drawSubject(Canvas(out), foreground, edit, foreground.width, foreground.height)
        return out
    }

    /**
     * Splits the composition into two depth planes for the tilt wallpaper.
     *  - back: the photo, softly blurred, with the subject's area replaced by a heavy blur of the
     *    background around it (so the subject never shows twice), plus any text behind the subject;
     *  - front: the sharp subject cut-out plus text in front, on a transparent layer.
     * Without a subject everything is one plane and front is null.
     */
    fun renderPlanes(
        background: Bitmap, foreground: Bitmap?, layers: List<TextLayer>,
        edit: SubjectEdit? = null, plate: Bitmap? = null,
    ): Pair<Bitmap, Bitmap?> {
        val w = background.width
        val h = background.height
        val back = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val bc = Canvas(back)
        if (foreground == null) {
            bc.drawBitmap(background, 0f, 0f, bitmapPaint)
            layers.forEach { drawLayer(bc, it, w, h) }
            return back to null
        }

        val subject = bakeSubject(foreground, edit)

        // Mild depth-of-field on the whole background: shrink then enlarge with filtering.
        val soft = Bitmap.createScaledBitmap(background, max(1, w / 4), max(1, h / 4), true)
        bc.drawBitmap(soft, null, Rect(0, 0, w, h), bitmapPaint)
        soft.recycle()

        // Hard blur only where the subject stands (slightly grown so no sharp edge peeks out).
        val clean = plate ?: SubjectPlate.build(background, subject)
        val grow = Paint().apply { maskFilter = android.graphics.BlurMaskFilter(max(2f, w * 0.012f), android.graphics.BlurMaskFilter.Blur.SOLID) }
        val region = subject.extractAlpha(grow, IntArray(2))
        val saved = bc.saveLayer(null, null)
        bc.drawBitmap(clean, null, Rect(0, 0, w, h), bitmapPaint)
        bc.drawBitmap(region, 0f, 0f, dstInPaint)
        bc.restoreToCount(saved)
        region.recycle()

        layers.filter { it.behindSubject }.forEach { drawLayer(bc, it, w, h) }

        val front = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val fc = Canvas(front)
        fc.drawBitmap(subject, 0f, 0f, bitmapPaint)
        layers.filterNot { it.behindSubject }.forEach { drawLayer(fc, it, w, h) }
        subject.recycle()
        return back to front
    }

    fun drawSelection(canvas: Canvas, layer: TextLayer, imageWidth: Int, imageHeight: Int, color: Int, strokePx: Float) {
        val m = measure(layer, imageWidth) ?: return
        val pad = imageWidth * 0.012f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            this.color = color
            pathEffect = DashPathEffect(floatArrayOf(strokePx * 5, strokePx * 3), 0f)
        }
        canvas.save()
        canvas.translate(layer.x * imageWidth, layer.y * imageHeight)
        canvas.rotate(layer.rotation)
        if (layer.perspective == null) {
            canvas.drawRect(RectF(-m.width / 2 - pad, -m.height / 2 - pad, m.width / 2 + pad, m.height / 2 + pad), paint)
        } else {
            val q = localCorners(layer, m.width, m.height)
            val path = android.graphics.Path().apply {
                moveTo(q[0], q[1]); lineTo(q[2], q[3]); lineTo(q[4], q[5]); lineTo(q[6], q[7]); close()
            }
            canvas.drawPath(path, paint)
            // Corner handles: filled dot with a contrasting ring so they read on any photo.
            val r = strokePx * 7f
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = Color.WHITE }
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }
            for (i in 0 until 4) {
                canvas.drawCircle(q[i * 2], q[i * 2 + 1], r * 1.25f, ring)
                canvas.drawCircle(q[i * 2], q[i * 2 + 1], r, dot)
            }
        }
        canvas.restore()
    }

    /** Point-in-polygon for the (possibly concave if dragged oddly) 4-corner box. */
    private fun insideQuad(q: FloatArray, px: Float, py: Float): Boolean {
        var inside = false
        var j = 3
        for (i in 0 until 4) {
            val xi = q[i * 2]; val yi = q[i * 2 + 1]
            val xj = q[j * 2]; val yj = q[j * 2 + 1]
            if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** Returns the top-most layer under (px, py) in image pixels, or null. */
    fun hitTest(layers: List<TextLayer>, imageWidth: Int, imageHeight: Int, px: Float, py: Float): Long? {
        val frontToBack = layers.filterNot { it.behindSubject }.asReversed() + layers.filter { it.behindSubject }.asReversed()
        val pad = imageWidth * 0.03f
        for (layer in frontToBack) {
            if (!layer.visible) continue
            val m = measure(layer, imageWidth) ?: continue
            val dx = px - layer.x * imageWidth
            val dy = py - layer.y * imageHeight
            val rad = Math.toRadians(-layer.rotation.toDouble())
            val lx = (dx * cos(rad) - dy * sin(rad)).toFloat()
            val ly = (dx * sin(rad) + dy * cos(rad)).toFloat()
            if (layer.perspective != null) {
                if (insideQuad(localCorners(layer, m.width, m.height), lx, ly)) return layer.id
            } else if (abs(lx) <= m.width / 2 + pad && abs(ly) <= m.height / 2 + pad) return layer.id
        }
        return null
    }

    fun composite(background: Bitmap, foreground: Bitmap?, layers: List<TextLayer>, edit: SubjectEdit? = null, adjust: PhotoAdjust? = null): Bitmap {
        val out = Bitmap.createBitmap(background.width, background.height, Bitmap.Config.ARGB_8888)
        render(Canvas(out), background, foreground, layers, edit, adjust)
        return out
    }

    /**
     * Fits the composite into a social format. Empty space is filled with a blurred,
     * darkened copy of the image instead of black bars.
     */
    fun frame(composite: Bitmap, format: ExportFormat): Bitmap {
        if (format == ExportFormat.ORIGINAL) return composite
        val outH = format.longSide
        val outW = (outH.toFloat() * format.ratioW / format.ratioH).roundToInt()
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        // Cheap, dependency-free blur: shrink hard, then scale back up with filtering.
        val small = Bitmap.createScaledBitmap(
            composite, max(1, composite.width / 32), max(1, composite.height / 32), true,
        )
        val cover = max(outW.toFloat() / composite.width, outH.toFloat() / composite.height)
        val cw = composite.width * cover
        val ch = composite.height * cover
        canvas.drawBitmap(small, null, RectF((outW - cw) / 2, (outH - ch) / 2, (outW + cw) / 2, (outH + ch) / 2), bitmapPaint)
        canvas.drawColor(Color.argb(70, 0, 0, 0))
        small.recycle()

        val fit = min(outW.toFloat() / composite.width, outH.toFloat() / composite.height)
        val fw = composite.width * fit
        val fh = composite.height * fit
        canvas.drawBitmap(composite, null, RectF((outW - fw) / 2, (outH - fh) / 2, (outW + fw) / 2, (outH + fh) / 2), bitmapPaint)
        return out
    }
}
