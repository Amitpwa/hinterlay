package com.hinterlay.app.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import com.hinterlay.app.cloud.Art
import com.hinterlay.app.cloud.Preset
import com.hinterlay.app.render.Compositor
import com.hinterlay.app.render.FxParams
import com.hinterlay.app.render.PhotoFx
import kotlin.math.sin

/**
 * Drawn scenes for the Inspire cards and the home demo: a sky, sun, hills and one big "subject" shape, so a template can show
 * text tucked behind something without using anybody's photo. Everything is drawn in code and the same every time.
 */
object TemplateArt {

    private class Look(
        val sky: IntArray, val skyStops: FloatArray,
        val sun: Int, val sunX: Float, val sunY: Float, val sunR: Float,
        val hills: List<Pair<Int, Float>>, // colour, base height (0..1 from the top)
        val subject: Subject, val subjectColor: Int, val ground: Int? = null, val stars: Boolean = false,
    )

    private enum class Subject { PEAK, PINE, TOWER, ARCH, LIGHTHOUSE, BALLOON, SKYLINE }

    private fun c(hex: String) = Color.parseColor(hex)

    private val looks: Map<Art, Look> = mapOf(
        Art.DUSK to Look(
            intArrayOf(c("#0B1B3A"), c("#1E4C7A"), c("#E8794A"), c("#FFD39A")), floatArrayOf(0f, 0.4f, 0.75f, 1f),
            c("#FFE2B0"), 0.7f, 0.62f, 0.12f, listOf(c("#35597D") to 0.62f, c("#223B5C") to 0.72f), Subject.PEAK, c("#0A1424"), stars = true,
        ),
        Art.OCEAN to Look(
            intArrayOf(c("#1B2A49"), c("#C8573E"), c("#FFC27A")), floatArrayOf(0f, 0.6f, 1f),
            c("#FFE9B8"), 0.3f, 0.55f, 0.1f, emptyList(), Subject.LIGHTHOUSE, c("#101A2B"), ground = c("#134B5F"),
        ),
        Art.DESERT to Look(
            intArrayOf(c("#F7B267"), c("#F4845F"), c("#F9DCC4")), floatArrayOf(0f, 0.55f, 1f),
            c("#FFF1D0"), 0.75f, 0.5f, 0.1f, listOf(c("#D98E5A") to 0.7f, c("#B8643E") to 0.8f), Subject.ARCH, c("#4A2418"),
        ),
        Art.FOREST to Look(
            intArrayOf(c("#9ED9C3"), c("#DFF3E4")), floatArrayOf(0f, 1f),
            c("#FFFFFF"), 0.3f, 0.35f, 0.14f, listOf(c("#7FBF9E") to 0.6f, c("#4E9A78") to 0.72f), Subject.PINE, c("#0F3B2C"),
        ),
        Art.SNOW to Look(
            intArrayOf(c("#9CB8D6"), c("#E9F1F8")), floatArrayOf(0f, 1f),
            c("#FFFFFF"), 0.78f, 0.3f, 0.1f, listOf(c("#DCE8F2") to 0.62f, c("#BFD2E4") to 0.74f), Subject.PINE, c("#1D4A44"), ground = c("#F4F8FB"),
        ),
        Art.NEON to Look(
            intArrayOf(c("#070B18"), c("#0F3057"), c("#127C86"), c("#FF6B4A")), floatArrayOf(0f, 0.45f, 0.78f, 1f),
            c("#FFB36B"), 0.5f, 0.7f, 0.16f, listOf(c("#0B2A48") to 0.7f), Subject.TOWER, c("#050914"), stars = true,
        ),
        Art.CITY to Look(
            intArrayOf(c("#0E1722"), c("#35617F"), c("#F2B77A")), floatArrayOf(0f, 0.62f, 1f),
            c("#FFE3B0"), 0.25f, 0.62f, 0.11f, listOf(c("#2A4560") to 0.66f, c("#1A2D42") to 0.76f), Subject.SKYLINE, c("#0A111A"),
        ),
        Art.SUNRISE to Look(
            intArrayOf(c("#23395B"), c("#EF8A3C"), c("#FFE29A")), floatArrayOf(0f, 0.6f, 1f),
            c("#FFF4C8"), 0.5f, 0.72f, 0.13f, listOf(c("#5C6E8F") to 0.78f), Subject.BALLOON, c("#1A2438"),
        ),
    )

    /** Two copies of the same picture: the whole scene, and only the subject shape on a transparent layer. */
    fun scene(art: Art, w: Int, h: Int): Pair<Bitmap, Bitmap> {
        val look = looks.getValue(art)
        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val fg = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawBackdrop(Canvas(full), w.toFloat(), h.toFloat(), look)
        drawSubject(Canvas(full), w.toFloat(), h.toFloat(), look)
        drawSubject(Canvas(fg), w.toFloat(), h.toFloat(), look)
        return full to fg
    }

    private fun drawBackdrop(c: Canvas, w: Float, h: Float, l: Look) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h, l.sky, l.skyStops, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null
        if (l.stars) {
            p.color = Color.argb(180, 255, 255, 255)
            for (i in 0 until 40) c.drawCircle(((i * 97) % 100) / 100f * w, ((i * 53) % 45) / 100f * h, w * 0.004f + (i % 3) * 0.6f, p)
        }
        val sx = l.sunX * w; val sy = l.sunY * h; val sr = l.sunR * w
        p.shader = RadialGradient(sx, sy, sr * 3.2f, intArrayOf(Color.argb(190, Color.red(l.sun), Color.green(l.sun), Color.blue(l.sun)), Color.argb(0, Color.red(l.sun), Color.green(l.sun), Color.blue(l.sun))), null, Shader.TileMode.CLAMP)
        c.drawCircle(sx, sy, sr * 3.2f, p)
        p.shader = null; p.color = l.sun
        c.drawCircle(sx, sy, sr, p)
        l.hills.forEachIndexed { i, (color, base) ->
            val path = Path().apply {
                moveTo(0f, h)
                var x = 0f
                while (x <= w + 4f) {
                    val t = x / w
                    val y = h * base - h * 0.07f * (0.55f * sin(t * 6f + i * 1.7f) + 0.3f * sin(t * 13f + i * 3.1f) + 0.15f * sin(t * 27f + i))
                    lineTo(x, y); x += 4f
                }
                lineTo(w, h); close()
            }
            p.color = color; c.drawPath(path, p)
        }
        l.ground?.let {
            p.shader = LinearGradient(0f, h * 0.78f, 0f, h, Color.argb(255, Color.red(it), Color.green(it), Color.blue(it)), Color.argb(255, (Color.red(it) * 0.7f).toInt(), (Color.green(it) * 0.7f).toInt(), (Color.blue(it) * 0.7f).toInt()), Shader.TileMode.CLAMP)
            c.drawRect(0f, h * 0.78f, w, h, p); p.shader = null
        }
    }

    /** The big shape in front. It reaches up into the area where the template's text sits, so the text can pass behind it. */
    private fun drawSubject(c: Canvas, w: Float, h: Float, l: Look) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = l.subjectColor }
        when (l.subject) {
            Subject.PEAK -> {
                val path = Path().apply {
                    moveTo(w * 0.1f, h); lineTo(w * 0.34f, h * 0.55f); lineTo(w * 0.43f, h * 0.5f); lineTo(w * 0.5f, h * 0.26f)
                    lineTo(w * 0.57f, h * 0.44f); lineTo(w * 0.66f, h * 0.4f); lineTo(w * 0.9f, h); close()
                }
                c.drawPath(path, p)
                p.color = Color.argb(70, 255, 255, 255)
                c.drawPath(Path().apply { moveTo(w * 0.5f, h * 0.26f); lineTo(w * 0.57f, h * 0.44f); lineTo(w * 0.5f, h * 0.5f); lineTo(w * 0.46f, h * 0.4f); close() }, p)
            }
            Subject.PINE -> {
                val cx = w * 0.5f
                p.color = l.subjectColor
                for (i in 0 until 5) {
                    val top = h * (0.2f + i * 0.14f); val half = w * (0.1f + i * 0.055f); val bottom = top + h * 0.2f
                    c.drawPath(Path().apply { moveTo(cx, top); lineTo(cx - half, bottom); lineTo(cx + half, bottom); close() }, p)
                }
                c.drawRect(cx - w * 0.03f, h * 0.85f, cx + w * 0.03f, h, p)
            }
            Subject.TOWER -> {
                c.drawPath(Path().apply {
                    moveTo(w * 0.4f, h); lineTo(w * 0.42f, h * 0.38f); lineTo(w * 0.47f, h * 0.32f); lineTo(w * 0.49f, h * 0.18f)
                    lineTo(w * 0.51f, h * 0.18f); lineTo(w * 0.53f, h * 0.32f); lineTo(w * 0.58f, h * 0.38f); lineTo(w * 0.6f, h); close()
                }, p)
                p.color = Color.argb(200, 255, 214, 130)
                for (r in 0 until 9) for (k in 0 until 3) c.drawRect(w * (0.44f + k * 0.045f), h * (0.42f + r * 0.055f), w * (0.44f + k * 0.045f + 0.02f), h * (0.42f + r * 0.055f + 0.03f), p)
            }
            Subject.ARCH -> {
                // One path with a hole (even-odd), so the sky shows through the arch.
                val path = Path().apply {
                    fillType = Path.FillType.EVEN_ODD
                    addRoundRect(RectF(w * 0.26f, h * 0.5f, w * 0.74f, h * 1.1f), w * 0.16f, w * 0.16f, Path.Direction.CW)
                    addOval(RectF(w * 0.38f, h * 0.7f, w * 0.62f, h * 1.2f), Path.Direction.CW)
                }
                c.drawPath(path, p)
            }
            Subject.LIGHTHOUSE -> {
                // A rock, then a tall tower with a white stripe and a lamp room.
                c.drawPath(Path().apply { moveTo(w * 0.15f, h); lineTo(w * 0.3f, h * 0.84f); lineTo(w * 0.7f, h * 0.84f); lineTo(w * 0.88f, h); close() }, p)
                c.drawPath(Path().apply { moveTo(w * 0.43f, h * 0.84f); lineTo(w * 0.46f, h * 0.3f); lineTo(w * 0.54f, h * 0.3f); lineTo(w * 0.57f, h * 0.84f); close() }, p)
                p.color = Color.parseColor("#F2F2F2")
                c.drawPath(Path().apply { moveTo(w * 0.435f, h * 0.7f); lineTo(w * 0.442f, h * 0.58f); lineTo(w * 0.558f, h * 0.58f); lineTo(w * 0.565f, h * 0.7f); close() }, p)
                p.color = Color.parseColor("#FFD27A")
                c.drawRect(w * 0.46f, h * 0.24f, w * 0.54f, h * 0.3f, p)
                p.color = l.subjectColor
                c.drawPath(Path().apply { moveTo(w * 0.45f, h * 0.24f); lineTo(w * 0.5f, h * 0.17f); lineTo(w * 0.55f, h * 0.24f); close() }, p)
            }
            Subject.BALLOON -> {
                p.color = l.subjectColor
                c.drawOval(RectF(w * 0.3f, h * 0.18f, w * 0.7f, h * 0.62f), p)
                c.drawRect(w * 0.45f, h * 0.7f, w * 0.55f, h * 0.76f, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = w * 0.006f
                c.drawLine(w * 0.38f, h * 0.55f, w * 0.45f, h * 0.7f, p); c.drawLine(w * 0.62f, h * 0.55f, w * 0.55f, h * 0.7f, p)
            }
            Subject.SKYLINE -> {
                val widths = floatArrayOf(0.1f, 0.08f, 0.12f, 0.09f, 0.11f, 0.1f, 0.08f, 0.12f, 0.1f)
                val tops = floatArrayOf(0.6f, 0.45f, 0.3f, 0.5f, 0.24f, 0.4f, 0.56f, 0.34f, 0.62f)
                var x = -0.02f
                for (i in widths.indices) {
                    c.drawRect(w * x, h * tops[i], w * (x + widths[i]), h, p)
                    p.color = Color.argb(170, 255, 214, 130)
                    for (r in 0 until 6) for (k in 0 until 2) c.drawRect(w * (x + 0.015f + k * 0.035f), h * (tops[i] + 0.03f + r * 0.05f), w * (x + 0.03f + k * 0.035f), h * (tops[i] + 0.05f + r * 0.05f), p)
                    p.color = l.subjectColor
                    x += widths[i] - 0.004f
                }
            }
        }
    }

    // ---- Template previews ---------------------------------------------------------------------

    private val cache = LruCache<String, Bitmap>(24)

    /** Who took each bundled photo (all CC0 or public domain, from Wikimedia Commons). */
    val credits = mapOf(
        "balloon" to "Kupono Kuwamura", "lighthouse" to "Holly's Coastal View", "surfer" to "Austin Neill", "cat" to "Chinda Sam",
        "horse" to "Melissa Regina", "astronaut" to "NASA", "flamingo" to "Doug Swinson", "dog" to "Rodion Kutsaev",
    )

    /**
     * A bundled photo and its subject cut-out at [w] x [h]. The cut-out comes from assets (made once with ML Kit), or from a
     * saved copy, or is found on the phone now and saved. Null cut-out = no clear subject.
     */
    private suspend fun photoScene(context: android.content.Context, name: String, w: Int, h: Int): Pair<Bitmap, Bitmap?> {
        val am = context.assets
        val raw = am.open("templates/$name.jpg").use { android.graphics.BitmapFactory.decodeStream(it) }
        val bg = Bitmap.createScaledBitmap(raw, w, h, true)
        val fgFile = java.io.File(context.filesDir, "templates/${name}_fg.png")
        var fgRaw: Bitmap? = runCatching { am.open("templates/${name}_fg.png").use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull()
        if (fgRaw == null && fgFile.exists()) fgRaw = android.graphics.BitmapFactory.decodeFile(fgFile.path)
        if (fgRaw == null) {
            val detector = com.hinterlay.app.render.SubjectDetector()
            fgRaw = try { detector.extract(raw) { } } catch (e: Exception) { null } finally { detector.close() }
            fgRaw?.let { f -> fgFile.parentFile?.mkdirs(); java.io.FileOutputStream(fgFile).use { f.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
        val fg = fgRaw?.let { Bitmap.createScaledBitmap(it, w, h, true) }
        if (raw !== bg) raw.recycle()
        return bg to fg
    }

    /** The "before" picture for a template: its photo, or its drawn scene. */
    suspend fun before(context: android.content.Context, preset: Preset, w: Int, h: Int): Bitmap =
        if (preset.photo != null) photoScene(context, preset.photo, w, h).first else scene(preset.art, w, h).first

    /** The template [preset] shown on its photo or scene, at [w] x [h]. Cached by size and style. */
    suspend fun preview(context: android.content.Context, preset: Preset, w: Int, h: Int): Bitmap {
        val key = "${preset.hashCode()}:$w:$h"
        cache.get(key)?.let { return it }
        val (bg, fg) = if (preset.photo != null) photoScene(context, preset.photo, w, h) else scene(preset.art, w, h)
        val layer = fit(preset.toLayer(1L, fg != null), w)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        Compositor.render(canvas, bg, fg, listOf(layer), null, preset.adjust)
        bg.recycle(); fg?.recycle()
        val fx = preset.fx
        val finished = if (fx.copy(frame = null) != FxParams() || fx.frame != null) {
            val toned = PhotoFx.apply(out, fx.copy(frame = null))
            if (fx.frame != null) PhotoFx.frameOn(toned, fx.frame!!) else toned
        } else out
        if (finished !== out) out.recycle()
        cache.put(key, finished)
        return finished
    }

    private fun fit(layer: com.hinterlay.app.editor.TextLayer, w: Int): com.hinterlay.app.editor.TextLayer {
        val m = Compositor.measure(layer, w) ?: return layer
        val limit = w * 0.94f
        return if (m.width <= limit) layer else layer.copy(sizeRel = layer.sizeRel * limit / m.width)
    }
}
