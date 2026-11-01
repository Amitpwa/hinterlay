package com.hinterlay.app.wallpaper

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import com.hinterlay.app.editor.FontChoice
import com.hinterlay.app.render.Compositor
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar
import kotlin.math.max

/**
 * Depth-effect lock-screen clock wallpaper.
 *
 * A big clock is slipped *behind* the detected subject of a photo: the photo is split into a soft
 * back plane and a sharp subject cut-out (by [Compositor.renderPlanes]), and the live wallpaper
 * draws the clock between the two, so the subject naturally occludes it. The clock reads the real
 * time every frame, and both planes (and the clock) parallax as the phone tilts, like the 3D tilt
 * wallpaper. Everything stays on the phone.
 */

// ---- Config ----------------------------------------------------------------------------------

/**
 * How the clock digits are decorated. One pick at a time (stored by name so new effects don't break
 * old saves). [PHOTO] fills the digits with the photo behind them — a see-through, depth-true look.
 */
enum class ClockEffect(val label: String) {
    NONE("None"),
    SHADOW("Shadow"),
    OUTLINE("Outline"),
    GLOW("Glow"),
    GRADIENT("Gradient"),
    PHOTO("Photo fill"),
}

/**
 * All the styling the depth clock needs, as a value saved to disk. Geometry is relative to the
 * image so the editor preview and the full-size wallpaper render identically. [fontName] stores a
 * [FontChoice] by name so new fonts don't break old saves.
 */
data class DepthClockConfig(
    val twentyFour: Boolean = false,
    /** Stack the hour above the minute (big iOS-style clock) instead of HH:MM on one line. */
    val vertical: Boolean = false,
    /** Show the colon between hour and minute (horizontal layout only). */
    val separator: Boolean = true,
    val showDate: Boolean = true,
    /** A [java.text.SimpleDateFormat] pattern for the date line. */
    val dateFormat: String = "EEE, MMM d",
    val fontName: String = FontChoice.ANTON.name,
    val color: Int = 0xFFFFFFFF.toInt(),
    /** Centre of the clock, 0..1 of image width / height. */
    val x: Float = 0.5f,
    val y: Float = 0.40f,
    /** Height of the time digits as a fraction of image width. */
    val sizeRel: Float = 0.30f,
    /** Date height as a fraction of image width. */
    val dateSizeRel: Float = 0.055f,
    /** Draw the clock behind the subject (the depth effect) rather than in front. */
    val behind: Boolean = true,
    /** Digit decoration ([ClockEffect] by name). */
    val effect: String = ClockEffect.NONE.name,
    /** Darken the back plane (the "wash"), 0 = none .. 1 = black, so the clock reads clearly. */
    val dim: Float = 0f,
    /** Saturation of the back plane, 0 = greyscale, 1 = untouched, up to 2 = punchy. */
    val saturation: Float = 1f,
    /** Darken the corners of the back plane, 0 = none .. 1 = strong. */
    val vignette: Float = 0f,
) {
    val fontChoice: FontChoice
        get() = runCatching { FontChoice.valueOf(fontName) }.getOrDefault(FontChoice.ANTON)

    val clockEffect: ClockEffect
        get() = runCatching { ClockEffect.valueOf(effect) }.getOrDefault(ClockEffect.NONE)

    fun toJson(): String = JSONObject().apply {
        put("twentyFour", twentyFour)
        put("vertical", vertical)
        put("separator", separator)
        put("showDate", showDate)
        put("dateFormat", dateFormat)
        put("fontName", fontName)
        put("color", color)
        put("x", x.toDouble())
        put("y", y.toDouble())
        put("sizeRel", sizeRel.toDouble())
        put("dateSizeRel", dateSizeRel.toDouble())
        put("behind", behind)
        put("effect", effect)
        put("dim", dim.toDouble())
        put("saturation", saturation.toDouble())
        put("vignette", vignette.toDouble())
    }.toString()

    companion object {
        fun fromJson(text: String): DepthClockConfig = runCatching {
            val o = JSONObject(text)
            val d = DepthClockConfig()
            DepthClockConfig(
                twentyFour = o.optBoolean("twentyFour", d.twentyFour),
                vertical = o.optBoolean("vertical", d.vertical),
                separator = o.optBoolean("separator", d.separator),
                showDate = o.optBoolean("showDate", d.showDate),
                dateFormat = o.optString("dateFormat", d.dateFormat),
                fontName = o.optString("fontName", d.fontName),
                color = o.optInt("color", d.color),
                x = o.optDouble("x", d.x.toDouble()).toFloat(),
                y = o.optDouble("y", d.y.toDouble()).toFloat(),
                sizeRel = o.optDouble("sizeRel", d.sizeRel.toDouble()).toFloat(),
                dateSizeRel = o.optDouble("dateSizeRel", d.dateSizeRel.toDouble()).toFloat(),
                behind = o.optBoolean("behind", d.behind),
                effect = o.optString("effect", d.effect),
                dim = o.optDouble("dim", d.dim.toDouble()).toFloat(),
                saturation = o.optDouble("saturation", d.saturation.toDouble()).toFloat(),
                vignette = o.optDouble("vignette", d.vignette.toDouble()).toFloat(),
            )
        }.getOrDefault(DepthClockConfig())
    }
}

// ---- Storage ---------------------------------------------------------------------------------

/** Holds the two depth planes and the config the depth-clock wallpaper reads. All local. */
object DepthClockStore {
    private fun dir(context: Context) = File(context.filesDir, "depthclock").apply { mkdirs() }
    fun back(context: Context) = File(dir(context), "back.jpg")
    fun front(context: Context) = File(dir(context), "front.png")
    private fun configFile(context: Context) = File(dir(context), "config.json")

    /** True once a wallpaper has been prepared (used to decide whether "Apply" is available). */
    fun hasWallpaper(context: Context) = back(context).exists()

    /**
     * Splits [source] (with its [foreground] cut-out) into the two planes and saves them with
     * [config]. Any [layers] / subject [edit] / [plate] from the editor are baked into the planes
     * (crop, text, erase, smoothing), so the depth clock inherits the full photo edit; the clock
     * itself is drawn live by the service.
     */
    fun write(
        context: Context, source: Bitmap, foreground: Bitmap?, config: DepthClockConfig,
        layers: List<com.hinterlay.app.editor.TextLayer> = emptyList(),
        edit: Compositor.SubjectEdit? = null, plate: Bitmap? = null,
    ) {
        com.hinterlay.app.cloud.Track.event("depth_clock_set")
        val (back, front) = Compositor.renderPlanes(source, foreground, layers, edit, plate)
        FileOutputStream(back(context)).use { back.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        val f = front(context)
        if (front != null) FileOutputStream(f).use { front.compress(Bitmap.CompressFormat.PNG, 100, it) } else f.delete()
        back.recycle()
        front?.recycle()
        saveConfig(context, config)
    }

    fun saveConfig(context: Context, config: DepthClockConfig) {
        runCatching { configFile(context).writeText(config.toJson()) }
    }

    fun config(context: Context): DepthClockConfig {
        val f = configFile(context)
        return if (f.exists()) DepthClockConfig.fromJson(f.readText()) else DepthClockConfig()
    }

    fun setIntent(context: Context): Intent =
        Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
            android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(context, DepthClockWallpaperService::class.java),
        )

    /**
     * Renders the composite at the current time and sets it directly as a static LOCK-screen
     * wallpaper (leaving the home screen untouched). A still image can't tick, so it shows the
     * time it was set; re-apply to update. Returns true on success.
     */
    fun applyStaticLock(
        context: Context, source: Bitmap, foreground: Bitmap?, config: DepthClockConfig,
        layers: List<com.hinterlay.app.editor.TextLayer> = emptyList(),
        edit: Compositor.SubjectEdit? = null, plate: Bitmap? = null,
    ): Boolean = runCatching {
        saveConfig(context, config)
        val (back, front) = Compositor.renderPlanes(source, foreground, layers, edit, plate)
        val out = Bitmap.createBitmap(back.width, back.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val cal = Calendar.getInstance()
        p.colorFilter = DepthFx.backFilter(config)
        c.drawBitmap(back, 0f, 0f, p)
        p.colorFilter = null
        DepthFx.drawVignette(c, back.width, back.height, config)
        if (config.behind || front == null) ClockRenderer.draw(c, back.width, back.height, config, cal, back)
        front?.let { c.drawBitmap(it, 0f, 0f, p) }
        if (!config.behind && front != null) ClockRenderer.draw(c, back.width, back.height, config, cal, back)
        android.app.WallpaperManager.getInstance(context)
            .setBitmap(out, null, true, android.app.WallpaperManager.FLAG_LOCK)
        back.recycle(); front?.recycle(); out.recycle()
        com.hinterlay.app.cloud.Track.event("depth_clock_lock_still")
        true
    }.getOrDefault(false)
}

// ---- Time formatting -------------------------------------------------------------------------

/** The strings the clock shows: the hour, the minute, and the separator drawn between them. */
data class ClockParts(val hour: String, val minute: String, val separator: String)

object DepthClockFormat {

    /**
     * The hour text, minute text, and separator for the current time, honouring the 12/24-hour
     * and separator settings in [config].
     */
    fun timeParts(config: DepthClockConfig, cal: Calendar): ClockParts {
        val h24 = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val hour = if (config.twentyFour) {
            String.format(java.util.Locale.US, "%02d", h24)
        } else {
            // 12-hour: 0 and 12 both read as "12"; 13..23 wrap to 1..11. No leading zero, no AM/PM on the big digits.
            ((h24 % 12).let { if (it == 0) 12 else it }).toString()
        }
        return ClockParts(hour, String.format(java.util.Locale.US, "%02d", minute), ":")
    }

    /** The date line (empty when hidden or the pattern is invalid). */
    fun dateText(config: DepthClockConfig, cal: Calendar): String =
        if (!config.showDate) "" else runCatching {
            java.text.SimpleDateFormat(config.dateFormat, java.util.Locale.getDefault()).format(cal.time)
        }.getOrDefault("")
}

// ---- Renderer --------------------------------------------------------------------------------

/**
 * Draws the clock in image-pixel space. The same routine is used by the editor preview and the
 * live wallpaper, so what you style is exactly what you get. The caller sets up the canvas
 * transform (scale / position / parallax); this just paints into image coordinates.
 */
object ClockRenderer {

    private class Line(val text: String, val paint: Paint, val big: Boolean)

    /**
     * @param fillSource the back-plane photo, used only by [ClockEffect.PHOTO] to fill the digits
     *   with the image behind them. Everything else ignores it.
     */
    fun draw(canvas: Canvas, imgW: Int, imgH: Int, config: DepthClockConfig, cal: Calendar, fillSource: Bitmap? = null) {
        val parts = DepthClockFormat.timeParts(config, cal)
        val size = (config.sizeRel * imgW).coerceAtLeast(8f)
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Compositor.typeface(config.fontChoice, 900, false)
            textSize = size
            color = config.color
            textAlign = Paint.Align.CENTER
        }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Compositor.typeface(config.fontChoice, 700, false)
            textSize = (config.dateSizeRel * imgW).coerceAtLeast(6f)
            color = config.color
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.08f
        }

        val lines = ArrayList<Line>()
        if (config.vertical) {
            lines.add(Line(parts.hour, big, true))
            lines.add(Line(parts.minute, big, true))
        } else {
            val sep = if (config.separator) parts.separator else " "
            lines.add(Line("${parts.hour}$sep${parts.minute}", big, true))
        }
        val date = DepthClockFormat.dateText(config, cal)
        if (date.isNotEmpty()) lines.add(Line(date, small, false))

        // Shrink to fit: the size setting is a target, but a wide line (e.g. "03:22") must never
        // overflow the frame. Scale every line by the same factor so proportions are kept, and
        // only ever shrink — never enlarge past the chosen size.
        val widest = lines.maxOf { it.paint.measureText(it.text) }.coerceAtLeast(1f)
        var totalH = lines.fold(0f) { acc, l -> val fm = l.paint.fontMetrics; acc + (fm.descent - fm.ascent) }
        val fit = kotlin.math.min(1f, kotlin.math.min(imgW * 0.92f / widest, imgH * 0.9f / totalH.coerceAtLeast(1f)))
        if (fit < 1f) {
            lines.forEach { it.paint.textSize = it.paint.textSize * fit }
            totalH *= fit
        }

        // Geometry of the stacked block, centred on (x, y).
        val heights = lines.map { val fm = it.paint.fontMetrics; fm.descent - fm.ascent }
        val cx = config.x * imgW
        val blockTop = config.y * imgH - totalH / 2f

        // Decorate the digit paints for the chosen effect (shadow/glow/gradient/photo). The big
        // digits take every effect; the date shares shadow/glow but keeps a solid fill so it stays
        // legible. Outline is drawn as a separate stroke pass below.
        applyEffect(config.clockEffect, big, small, cx, blockTop, totalH, config.color, fillSource)
        val stroke: Paint? = if (config.clockEffect == ClockEffect.OUTLINE) {
            Paint(big).apply {
                style = Paint.Style.STROKE
                strokeWidth = big.textSize * 0.045f
                color = outlineColor(config.color)
                shader = null
                clearShadowLayer()
            }
        } else null

        var top = blockTop
        for (i in lines.indices) {
            val l = lines[i]
            val fm = l.paint.fontMetrics
            val baseline = top - fm.ascent
            if (stroke != null && l.big) {
                stroke.textSize = l.paint.textSize
                canvas.drawText(l.text, cx, baseline, stroke)
            }
            canvas.drawText(l.text, cx, baseline, l.paint)
            top += heights[i]
        }
    }

    /** Configure [big] (and, where it helps, [small]) in place for [effect]. */
    private fun applyEffect(
        effect: ClockEffect,
        big: Paint,
        small: Paint,
        cx: Float,
        blockTop: Float,
        blockH: Float,
        color: Int,
        fillSource: Bitmap?,
    ) {
        when (effect) {
            ClockEffect.NONE, ClockEffect.OUTLINE -> Unit // solid fill (outline adds a stroke pass)
            ClockEffect.SHADOW -> {
                big.setShadowLayer(big.textSize * 0.06f, 0f, big.textSize * 0.045f, 0x80000000.toInt())
                small.setShadowLayer(small.textSize * 0.08f, 0f, small.textSize * 0.06f, 0x80000000.toInt())
            }
            ClockEffect.GLOW -> {
                big.setShadowLayer(big.textSize * 0.18f, 0f, 0f, glowColor(color))
                small.setShadowLayer(small.textSize * 0.18f, 0f, 0f, glowColor(color))
            }
            ClockEffect.PHOTO -> {
                // Digits become a window onto the photo behind them. The shader samples the back
                // plane in image coordinates, so it lines up with the scene under the clock.
                if (fillSource != null) {
                    big.shader = BitmapShader(fillSource, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                }
            }
            ClockEffect.GRADIENT -> {
                // Fade the big digits from a lighter tint at the top to a darker shade at the
                // bottom — a quiet "metallic" fall. Both stops are blended from the chosen colour
                // (toward white, toward black), so it stays on-brand and still reads whether the
                // clock is white, black, or an accent. The date keeps its solid fill.
                val top = blend(color, 0xFFFFFFFF.toInt(), 0.40f)
                val bottom = blend(color, 0xFF000000.toInt(), 0.30f)
                big.shader = LinearGradient(cx, blockTop, cx, blockTop + blockH, top, bottom, Shader.TileMode.CLAMP)
            }
        }
    }

    /** Linear blend of [a] toward [b] by [t] (0 = a, 1 = b), keeping a's alpha. */
    private fun blend(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun mix(shift: Int): Int {
            val ca = (a ushr shift) and 0xFF
            val cb = (b ushr shift) and 0xFF
            return (ca + (cb - ca) * k).toInt().coerceIn(0, 255)
        }
        val alpha = (a ushr 24) and 0xFF
        return (alpha shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private fun luminance(color: Int): Float {
        val r = ((color ushr 16) and 0xFF) / 255f
        val g = ((color ushr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    /** Glow colour: a near-black clock glows white, otherwise the digits glow in their own colour. */
    private fun glowColor(color: Int): Int {
        val base = if (luminance(color) < 0.25f) 0xFFFFFFFF.toInt() else color
        return (base and 0x00FFFFFF) or (0xCC shl 24)
    }

    /** Outline colour: dark around light digits, light around dark ones. */
    private fun outlineColor(color: Int): Int =
        if (luminance(color) > 0.5f) 0xCC000000.toInt() else 0xCCFFFFFF.toInt()
}

// ---- Background treatment --------------------------------------------------------------------

/**
 * The "wash" over the back plane: saturation + dim as one colour-matrix filter, and a vignette as
 * a radial overlay. Applied to the soft back plane only, so the sharp subject keeps its full colour
 * and stands out. Both the editor preview and the live wallpaper call these, so they match.
 */
object DepthFx {

    /** A colour filter for the back plane from [DepthClockConfig.saturation] + [DepthClockConfig.dim], or null when both are neutral. */
    fun backFilter(config: DepthClockConfig): ColorMatrixColorFilter? {
        val sat = config.saturation.coerceIn(0f, 2f)
        val dim = config.dim.coerceIn(0f, 1f)
        if (sat == 1f && dim <= 0f) return null
        val cm = ColorMatrix().apply { setSaturation(sat) }
        if (dim > 0f) {
            val k = 1f - dim
            cm.postConcat(ColorMatrix(floatArrayOf(
                k, 0f, 0f, 0f, 0f,
                0f, k, 0f, 0f, 0f,
                0f, 0f, k, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )))
        }
        return ColorMatrixColorFilter(cm)
    }

    /** Darken the corners of the back plane, in image coordinates. No-op when vignette is off. */
    fun drawVignette(canvas: Canvas, imgW: Int, imgH: Int, config: DepthClockConfig) {
        val v = config.vignette.coerceIn(0f, 1f)
        if (v <= 0f) return
        val cx = imgW / 2f
        val cy = imgH / 2f
        val radius = kotlin.math.hypot(cx.toDouble(), cy.toDouble()).toFloat()
        val edge = ((v * 210f).toInt().coerceIn(0, 255) shl 24) // black, rising alpha toward the edge
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(0x00000000, 0x00000000, edge),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, imgW.toFloat(), imgH.toFloat(), p)
    }
}

// ---- Live wallpaper service ------------------------------------------------------------------

/**
 * Live wallpaper that draws back plane -> clock -> subject cut-out, so the clock sits behind the
 * subject, and reads the real time every frame. Both planes and the clock parallax on tilt.
 * Mirrors [TiltWallpaperService]'s engine; the only addition is the live clock between the planes.
 */
class DepthClockWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = ClockEngine()

    private inner class ClockEngine : Engine(), SensorEventListener {
        private val handler = Handler(Looper.getMainLooper())
        private val sensors by lazy { getSystemService(Context.SENSOR_SERVICE) as SensorManager }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private var back: Bitmap? = null
        private var front: Bitmap? = null
        private var config = DepthClockConfig()
        private var width = 0
        private var height = 0

        private var tx = 0f
        private var ty = 0f
        private var cx = 0f
        private var cy = 0f
        private var baseY = Float.NaN
        private var visible = false

        private val frame = object : Runnable {
            override fun run() {
                draw()
                if (visible) handler.postDelayed(this, 16)
            }
        }

        private fun load() {
            back = BitmapFactory.decodeFile(DepthClockStore.back(applicationContext).path)
            val f = DepthClockStore.front(applicationContext)
            front = if (f.exists()) BitmapFactory.decodeFile(f.path) else null
            config = DepthClockStore.config(applicationContext)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            width = w; height = h
            draw()
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            handler.removeCallbacks(frame)
            sensors.unregisterListener(this)
            if (isVisible) {
                load()
                baseY = Float.NaN
                val sensor = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)
                    ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                if (sensor != null) sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
                handler.post(frame)
            }
        }

        override fun onDestroy() {
            visible = false
            handler.removeCallbacks(frame)
            sensors.unregisterListener(this)
        }

        override fun onSensorChanged(e: SensorEvent) {
            if (baseY.isNaN()) baseY = e.values[1]
            baseY += (e.values[1] - baseY) * 0.002f
            tx = (-e.values[0] / 4f).coerceIn(-1f, 1f)
            ty = ((e.values[1] - baseY) / 4f).coerceIn(-1f, 1f)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        private fun draw() {
            val b = back ?: return
            if (width == 0) return
            cx += (tx - cx) * 0.12f
            cy += (ty - cy) * 0.12f
            val holder = surfaceHolder
            val canvas: Canvas = runCatching { holder.lockCanvas() }.getOrNull() ?: return
            try {
                canvas.drawColor(0xFF000000.toInt())
                val scale = max(width / b.width.toFloat(), height / b.height.toFloat()) * 1.14f
                val sw = b.width * scale
                val sh = b.height * scale
                val maxX = (sw - width) / 2f
                val maxY = (sh - height) / 2f
                val f = front
                drawPlane(canvas, b, scale, sw, sh, cx * maxX * 0.45f, cy * maxY * 0.45f, back = true)
                // Clock sits between the planes when behind, with parallax midway between them.
                if (config.behind || f == null) drawClock(canvas, b, scale, sw, sh, maxX, maxY, 0.7f)
                f?.let { drawPlane(canvas, it, scale, sw, sh, cx * maxX, cy * maxY) }
                if (!config.behind && f != null) drawClock(canvas, b, scale, sw, sh, maxX, maxY, 1f)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }

        private fun drawPlane(c: Canvas, bmp: Bitmap, scale: Float, sw: Float, sh: Float, dx: Float, dy: Float, back: Boolean = false) {
            val left = (width - sw) / 2f + dx
            val top = (height - sh) / 2f + dy
            c.save()
            c.translate(left, top)
            c.scale(scale, scale)
            if (back) paint.colorFilter = DepthFx.backFilter(config)
            c.drawBitmap(bmp, 0f, 0f, paint)
            if (back) {
                paint.colorFilter = null
                DepthFx.drawVignette(c, bmp.width, bmp.height, config)
            }
            c.restore()
        }

        private fun drawClock(c: Canvas, b: Bitmap, scale: Float, sw: Float, sh: Float, maxX: Float, maxY: Float, factor: Float) {
            val left = (width - sw) / 2f + cx * maxX * factor
            val top = (height - sh) / 2f + cy * maxY * factor
            c.save()
            c.translate(left, top)
            c.scale(scale, scale)
            ClockRenderer.draw(c, b.width, b.height, config, Calendar.getInstance(), b)
            c.restore()
        }
    }
}
