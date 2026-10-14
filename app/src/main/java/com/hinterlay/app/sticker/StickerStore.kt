package com.hinterlay.app.sticker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.RectF
import android.os.Build
import com.hinterlay.app.editor.TextLayer
import com.hinterlay.app.render.Compositor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Local WhatsApp sticker pack. WhatsApp's rules: 512x512 WebP stickers of at most 100 KB,
 * a 96x96 tray icon of at most 50 KB, and 3 to 30 stickers per pack.
 */
object StickerStore {
    const val PACK_ID = "hinterlay_pack"
    const val PACK_NAME = "Hinterlay"
    const val MIN_STICKERS = 3
    const val MAX_STICKERS = 30
    const val TRAY_FILE = "tray.png"
    private const val SIZE = 512
    private const val MAX_BYTES = 100 * 1024

    fun dir(context: Context) = File(context.filesDir, "stickers").apply { mkdirs() }

    fun authority(context: Context) = "${context.packageName}.stickercontentprovider"

    fun list(context: Context): List<File> =
        dir(context).listFiles { f -> f.extension == "webp" }?.sortedBy { it.name } ?: emptyList()

    /** Changes whenever the pack changes, so WhatsApp re-reads it. */
    fun dataVersion(context: Context): String =
        (list(context).maxOfOrNull { it.lastModified() } ?: 0L).toString() + "_" + list(context).size

    fun delete(context: Context, file: File) {
        file.delete()
        refreshTray(context)
    }

    enum class Style { WITH_TEXT, SUBJECT_ONLY, PHOTO_WITH_TEXT, PHOTO_ONLY, SQUARE_WITH_TEXT }

    /** Renders a sticker from an edit and saves it. Returns false if the pack is full. */
    fun add(
        context: Context, source: Bitmap, foreground: Bitmap?, layers: List<TextLayer>,
        style: Style = Style.WITH_TEXT, edit: Compositor.SubjectEdit? = null,
    ): Boolean {
        if (list(context).size >= MAX_STICKERS) return false
        val sticker = render(source, foreground, layers, style, edit)
        // Millisecond names can collide when several stickers are made in a row.
        var file = File(dir(context), "sticker_${System.currentTimeMillis()}.webp")
        while (file.exists()) file = File(dir(context), "sticker_${System.currentTimeMillis()}_${(0..999).random()}.webp")
        FileOutputStream(file).use { it.write(encode(sticker)) }
        sticker.recycle()
        com.hinterlay.app.cloud.Track.event("sticker_created", "style" to style.name)
        refreshTray(context)
        return true
    }

    /**
     * A drawing of a photo (see Toonify) as a sticker: the subject cut out with the usual white outline, or the whole picture
     * with rounded corners when no subject was found. An optional short word goes along the bottom.
     */
    fun addToon(context: Context, toon: Bitmap, subject: Bitmap?, caption: String = ""): Boolean {
        val word = caption.trim().take(24)
        val w = toon.width
        val layers = if (word.isEmpty()) emptyList() else {
            // White letters with a dark outline, the usual sticker look, shrunk to fit the picture.
            var layer = TextLayer(
                id = 1L, text = word, font = com.hinterlay.app.editor.FontChoice.LILITA, color = Color.WHITE,
                strokeWidth = 0.12f, strokeColor = Color.BLACK, behindSubject = false, y = 0.9f, sizeRel = 0.18f,
            )
            Compositor.measure(layer, w)?.let { m -> if (m.width > w * 0.92f) layer = layer.copy(sizeRel = layer.sizeRel * w * 0.92f / m.width) }
            listOf(layer)
        }
        val cutout = subject?.let { com.hinterlay.app.render.PhotoFx.maskWith(toon, it) }
        val style = when {
            cutout != null && layers.isEmpty() -> Style.SUBJECT_ONLY
            cutout != null -> Style.WITH_TEXT
            layers.isEmpty() -> Style.PHOTO_ONLY
            else -> Style.PHOTO_WITH_TEXT
        }
        val ok = add(context, toon, cutout, layers, style)
        com.hinterlay.app.cloud.Track.event("sticker_toon")
        return ok
    }

    /**
     * Three different stickers from one creation, so a new pack reaches WhatsApp's 3-sticker
     * minimum in one tap. Returns how many were added.
     */
    fun addStarterPack(context: Context, source: Bitmap, foreground: Bitmap?, layers: List<TextLayer>, edit: Compositor.SubjectEdit? = null): Int {
        val styles = if (foreground != null) listOf(Style.WITH_TEXT, Style.SUBJECT_ONLY, Style.PHOTO_WITH_TEXT)
        else listOf(Style.PHOTO_WITH_TEXT, Style.PHOTO_ONLY, Style.SQUARE_WITH_TEXT)
        return styles.count { add(context, source, foreground, layers, it, edit) }
    }

    // ---- Rendering ---------------------------------------------------------------------

    /**
     * Subject cut-out plus its text on a transparent background, trimmed, with a white
     * sticker outline, centred in 512x512. Without a detected subject the whole photo is used
     * with rounded corners.
     */
    private fun render(source: Bitmap, foreground: Bitmap?, layers: List<TextLayer>, style: Style, edit: Compositor.SubjectEdit?): Bitmap {
        val w = source.width
        val h = source.height
        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(full)
        val r = min(w, h) * 0.08f
        fun roundedPhoto(withText: Boolean, fg: Bitmap?) {
            c.save()
            c.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, Path.Direction.CW) })
            Compositor.render(c, source, fg, if (withText) layers else emptyList(), edit)
            c.restore()
        }
        when {
            style == Style.PHOTO_ONLY -> roundedPhoto(false, null)
            style == Style.PHOTO_WITH_TEXT || style == Style.SQUARE_WITH_TEXT -> roundedPhoto(true, foreground)
            foreground == null -> roundedPhoto(style != Style.SUBJECT_ONLY, null)
            style == Style.SUBJECT_ONLY -> {
                val baked = Compositor.bakeSubject(foreground, edit)
                c.drawBitmap(baked, null, android.graphics.Rect(0, 0, w, h), null)
                baked.recycle()
            }
            else -> { // WITH_TEXT: subject cut-out with its text, on transparent
                layers.filter { it.behindSubject }.forEach { Compositor.drawLayer(c, it, w, h) }
                val baked = Compositor.bakeSubject(foreground, edit)
                c.drawBitmap(baked, null, android.graphics.Rect(0, 0, w, h), null)
                baked.recycle()
                layers.filterNot { it.behindSubject }.forEach { Compositor.drawLayer(c, it, w, h) }
            }
        }

        val bounds = if (style == Style.SQUARE_WITH_TEXT) {
            val side = min(w, h)
            android.graphics.Rect((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2)
        } else contentBounds(full)
        val cropped = Bitmap.createBitmap(full, bounds.left, bounds.top, bounds.width(), bounds.height())
        full.recycle()

        val margin = 20f
        val fit = min((SIZE - 2 * margin) / cropped.width, (SIZE - 2 * margin) / cropped.height)
        val fw = max(1, (cropped.width * fit).roundToInt())
        val fh = max(1, (cropped.height * fit).roundToInt())
        val scaled = Bitmap.createScaledBitmap(cropped, fw, fh, true)
        if (scaled !== cropped) cropped.recycle()

        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val oc = Canvas(out)
        val left = (SIZE - fw) / 2f
        val top = (SIZE - fh) / 2f
        // White outline: a blurred, solid copy of the sticker's alpha, drawn a few times to harden it.
        val blur = Paint().apply { maskFilter = BlurMaskFilter(7f, BlurMaskFilter.Blur.SOLID) }
        val offset = IntArray(2)
        val alpha = scaled.extractAlpha(blur, offset)
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        repeat(3) { oc.drawBitmap(alpha, left + offset[0], top + offset[1], white) }
        oc.drawBitmap(scaled, left, top, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        alpha.recycle()
        scaled.recycle()
        return out
    }

    /** Bounding box of non-transparent pixels, found on a small copy for speed. */
    private fun contentBounds(bmp: Bitmap): android.graphics.Rect {
        val k = 160f / max(bmp.width, bmp.height)
        val sw = max(1, (bmp.width * k).roundToInt())
        val sh = max(1, (bmp.height * k).roundToInt())
        val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        var minX = sw; var minY = sh; var maxX = -1; var maxY = -1
        for (y in 0 until sh) for (x in 0 until sw) {
            if (Color.alpha(px[y * sw + x]) > 24) {
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
            }
        }
        small.recycle()
        if (maxX < 0) return android.graphics.Rect(0, 0, bmp.width, bmp.height)
        val l = (minX / k).toInt().coerceAtLeast(0)
        val t = (minY / k).toInt().coerceAtLeast(0)
        val r = ((maxX + 1) / k).toInt().coerceAtMost(bmp.width)
        val b = ((maxY + 1) / k).toInt().coerceAtMost(bmp.height)
        return android.graphics.Rect(l, t, max(l + 1, r), max(t + 1, b))
    }

    @Suppress("DEPRECATION")
    private fun encode(bmp: Bitmap): ByteArray {
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        var quality = 90
        while (true) {
            val bytes = ByteArrayOutputStream().also { bmp.compress(format, quality, it) }.toByteArray()
            if (bytes.size <= MAX_BYTES || quality <= 20) return bytes
            quality -= 10
        }
    }

    private fun refreshTray(context: Context) {
        val tray = File(dir(context), TRAY_FILE)
        val first = list(context).firstOrNull() ?: run { tray.delete(); return }
        val bmp = BitmapFactory.decodeFile(first.path) ?: return
        val small = Bitmap.createScaledBitmap(bmp, 96, 96, true)
        FileOutputStream(tray).use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
