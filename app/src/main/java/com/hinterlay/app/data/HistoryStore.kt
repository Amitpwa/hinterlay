package com.hinterlay.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.hinterlay.app.render.Compositor
import com.hinterlay.app.editor.FontChoice
import com.hinterlay.app.editor.FontWeightChoice
import com.hinterlay.app.editor.TextAlignChoice
import com.hinterlay.app.editor.TextLayer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** One saved creation. Files live in filesDir/history/<id>/. Nothing leaves the device. */
class HistoryItem(val id: String, val createdAt: Long, val dir: File) {
    val result get() = File(dir, "result.jpg")
    val thumb get() = File(dir, "thumb.jpg")
    val source get() = File(dir, "source.jpg")
    val foreground get() = File(dir, "foreground.png")
    val subjectMask get() = File(dir, "submask.png")
}

object HistoryStore {

    private fun root(context: Context) = File(context.filesDir, "history").apply { mkdirs() }

    fun list(context: Context): List<HistoryItem> =
        root(context).listFiles()?.mapNotNull { dir ->
            val meta = File(dir, "meta.json")
            if (!meta.exists()) return@mapNotNull null
            runCatching { HistoryItem(dir.name, JSONObject(meta.readText()).getLong("createdAt"), dir) }.getOrNull()
        }?.sortedByDescending { it.createdAt } ?: emptyList()

    fun save(
        context: Context,
        source: Bitmap,
        foreground: Bitmap?,
        layers: List<TextLayer>,
        exported: File,
        subjectMask: Bitmap? = null,
        adjust: com.hinterlay.app.render.PhotoAdjust = com.hinterlay.app.render.PhotoAdjust(),
        fx: com.hinterlay.app.render.FxParams = com.hinterlay.app.render.FxParams(),
        blend: Bitmap? = null,
    ): HistoryItem {
        val id = System.currentTimeMillis().toString()
        val dir = File(root(context), id).apply { mkdirs() }
        val item = HistoryItem(id, id.toLong(), dir)
        exported.copyTo(item.result, overwrite = true)
        FileOutputStream(item.source).use { source.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        if (foreground != null) FileOutputStream(item.foreground).use { foreground.compress(Bitmap.CompressFormat.PNG, 100, it) }

        // 320px thumbnail from the exported result.
        BitmapFactory.decodeFile(item.result.path)?.let { full ->
            val scale = 320f / max(full.width, full.height)
            val thumb = Bitmap.createScaledBitmap(
                full, max(1, (full.width * scale).roundToInt()), max(1, (full.height * scale).roundToInt()), true,
            )
            FileOutputStream(item.thumb).use { thumb.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            if (thumb !== full) full.recycle()
        }
        if (subjectMask != null) FileOutputStream(item.subjectMask).use { subjectMask.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (blend != null && fx.blend != null) FileOutputStream(File(dir, "blend.jpg")).use { blend.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        layers.forEachIndexed { i, l ->
            l.eraseMask?.let { m -> FileOutputStream(File(dir, "mask_$i.png")).use { m.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
        File(dir, "meta.json").writeText(
            JSONObject().put("createdAt", item.createdAt).put("layers", layersToJson(layers)).put("adjust", adjustToJson(adjust)).put("fx", fxToJson(fx)).toString()
        )
        com.hinterlay.app.quick.RecentWidget.refresh(context)
        return item
    }

    private fun adjustToJson(a: com.hinterlay.app.render.PhotoAdjust) = JSONObject()
        .put("look", a.look.name).put("brightness", a.brightness.toDouble()).put("contrast", a.contrast.toDouble())
        .put("saturation", a.saturation.toDouble()).put("warmth", a.warmth.toDouble()).put("vignette", a.vignette.toDouble()).put("smooth", a.smooth.toDouble())

    private fun fxToJson(f: com.hinterlay.app.render.FxParams) = JSONObject()
        .put("ambiance", f.ambiance.toDouble()).put("highlights", f.highlights.toDouble()).put("shadows", f.shadows.toDouble())
        .put("temperature", f.temperature.toDouble()).put("tint", f.tint.toDouble())
        .put("sharpen", f.sharpen.toDouble()).put("structure", f.structure.toDouble())
        .put("curve", org.json.JSONArray(f.curve.map { it.toDouble() }))
        .put("glow", f.glow.toDouble()).put("hdr", f.hdr.toDouble()).put("grain", f.grain.toDouble()).put("lensBlur", f.lensBlur.toDouble()).put("glitch", f.glitch.toDouble()).put("faceMask", f.faceMask.name)
        .put("frame", f.frame?.let { JSONObject().put("style", it.style.name).put("color", it.color.name).put("size", it.size.toDouble()) } ?: JSONObject.NULL)
        .put("blend", f.blend?.let { b ->
            JSONObject().put("mode", b.mode.name).put("opacity", b.opacity.toDouble()).put("zoom", b.zoom.toDouble())
                .put("dx", b.dx.toDouble()).put("dy", b.dy.toDouble()).put("flip", b.flip).put("area", b.area.name).put("token", b.token)
        } ?: JSONObject.NULL)
        .put("strokes", org.json.JSONArray(f.strokes.map { s ->
            JSONObject().put("kind", s.kind.name).put("radius", s.radius.toDouble()).put("strength", s.strength.toDouble())
                .put("pts", org.json.JSONArray(s.pts.map { (Math.round(it * 10000f) / 10000f).toDouble() }))
        }))
        .put("points", org.json.JSONArray(f.points.map { p ->
            JSONObject().put("x", p.x.toDouble()).put("y", p.y.toDouble()).put("radius", p.radius.toDouble())
                .put("brightness", p.brightness.toDouble()).put("contrast", p.contrast.toDouble())
                .put("saturation", p.saturation.toDouble()).put("structure", p.structure.toDouble())
        }))

    /** Photo-tool settings of a saved creation. Creations saved before these tools existed open with everything neutral. */
    fun fx(item: HistoryItem): com.hinterlay.app.render.FxParams = runCatching {
        val o = JSONObject(File(item.dir, "meta.json").readText()).getJSONObject("fx")
        val arr = o.optJSONArray("curve")
        val curve = if (arr != null && arr.length() == 5) (0 until 5).map { arr.getDouble(it).toFloat() } else com.hinterlay.app.render.FxParams.IdentityCurve
        com.hinterlay.app.render.FxParams(
            ambiance = o.optDouble("ambiance", 0.0).toFloat(), highlights = o.optDouble("highlights", 0.0).toFloat(),
            shadows = o.optDouble("shadows", 0.0).toFloat(), temperature = o.optDouble("temperature", 0.0).toFloat(),
            tint = o.optDouble("tint", 0.0).toFloat(), sharpen = o.optDouble("sharpen", 0.0).toFloat(),
            structure = o.optDouble("structure", 0.0).toFloat(), curve = curve,
            glow = o.optDouble("glow", 0.0).toFloat(), hdr = o.optDouble("hdr", 0.0).toFloat(), grain = o.optDouble("grain", 0.0).toFloat(), lensBlur = o.optDouble("lensBlur", 0.0).toFloat(), glitch = o.optDouble("glitch", 0.0).toFloat(),
            faceMask = runCatching { com.hinterlay.app.camera.FaceMask.valueOf(o.getString("faceMask")) }.getOrDefault(com.hinterlay.app.camera.FaceMask.NONE),
            frame = o.optJSONObject("frame")?.let { fo ->
                com.hinterlay.app.render.FrameParams(
                    style = runCatching { com.hinterlay.app.render.FrameStyle.valueOf(fo.getString("style")) }.getOrDefault(com.hinterlay.app.render.FrameStyle.WIDE),
                    color = runCatching { com.hinterlay.app.render.FrameColor.valueOf(fo.getString("color")) }.getOrDefault(com.hinterlay.app.render.FrameColor.WHITE),
                    size = fo.optDouble("size", 0.05).toFloat(),
                )
            },
            blend = o.optJSONObject("blend")?.let { b ->
                com.hinterlay.app.render.BlendParams(
                    mode = runCatching { com.hinterlay.app.render.BlendMode.valueOf(b.getString("mode")) }.getOrDefault(com.hinterlay.app.render.BlendMode.SCREEN),
                    opacity = b.optDouble("opacity", 0.7).toFloat(), zoom = b.optDouble("zoom", 1.0).toFloat(),
                    dx = b.optDouble("dx", 0.0).toFloat(), dy = b.optDouble("dy", 0.0).toFloat(), flip = b.optBoolean("flip"),
                    area = runCatching { com.hinterlay.app.render.BlendArea.valueOf(b.getString("area")) }.getOrDefault(com.hinterlay.app.render.BlendArea.EVERYWHERE),
                    token = b.optLong("token", 1L),
                )
            },
            strokes = o.optJSONArray("strokes")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val q = a.getJSONObject(i)
                    val kind = runCatching { com.hinterlay.app.render.BrushKind.valueOf(q.getString("kind")) }.getOrNull() ?: return@mapNotNull null
                    val arr = q.optJSONArray("pts") ?: return@mapNotNull null
                    com.hinterlay.app.render.BrushStroke(
                        kind, q.optDouble("radius", 0.05).toFloat(), q.optDouble("strength", 0.5).toFloat(),
                        (0 until arr.length()).map { arr.getDouble(it).toFloat() },
                    )
                }
            } ?: emptyList(),
            points = o.optJSONArray("points")?.let { a ->
                (0 until a.length()).map { i ->
                    val q = a.getJSONObject(i)
                    com.hinterlay.app.render.SelectivePoint(
                        x = q.optDouble("x", 0.5).toFloat(), y = q.optDouble("y", 0.5).toFloat(), radius = q.optDouble("radius", 0.18).toFloat(),
                        brightness = q.optDouble("brightness", 0.0).toFloat(), contrast = q.optDouble("contrast", 0.0).toFloat(),
                        saturation = q.optDouble("saturation", 0.0).toFloat(), structure = q.optDouble("structure", 0.0).toFloat(),
                    )
                }
            } ?: emptyList(),
        )
    }.getOrDefault(com.hinterlay.app.render.FxParams())

    /** The second photo of a double exposure saved with this creation, if there is one. */
    fun blendFile(item: HistoryItem): File? = File(item.dir, "blend.jpg").takeIf { it.exists() }

    fun adjust(item: HistoryItem): com.hinterlay.app.render.PhotoAdjust = runCatching {
        val o = JSONObject(File(item.dir, "meta.json").readText()).getJSONObject("adjust")
        com.hinterlay.app.render.PhotoAdjust(
            look = runCatching { com.hinterlay.app.render.Look.valueOf(o.getString("look")) }.getOrDefault(com.hinterlay.app.render.Look.NORMAL),
            brightness = o.optDouble("brightness").toFloat(), contrast = o.optDouble("contrast").toFloat(),
            saturation = o.optDouble("saturation").toFloat(), warmth = o.optDouble("warmth").toFloat(),
            vignette = o.optDouble("vignette").toFloat(), smooth = o.optDouble("smooth").toFloat(),
        )
    }.getOrDefault(com.hinterlay.app.render.PhotoAdjust())

    fun layers(item: HistoryItem): List<TextLayer> = runCatching {
        val arr = JSONObject(File(item.dir, "meta.json").readText()).getJSONArray("layers")
        (0 until arr.length()).map { i ->
            val layer = layerFromJson(arr.getJSONObject(i), i + 1L)
            val maskFile = File(item.dir, "mask_$i.png")
            if (!maskFile.exists()) layer
            else {
                // Decode into a mutable ARGB bitmap so further erasing works after reopening.
                val opts = android.graphics.BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888 }
                layer.copy(eraseMask = android.graphics.BitmapFactory.decodeFile(maskFile.path, opts))
            }
        }
    }.getOrDefault(emptyList())

    // The saved subject-erase mask (if any) for sticker and wallpaper rendering. The clean-background
    // fill is rebuilt from the saved cut-out.
    fun subjectEdit(item: HistoryItem): Compositor.SubjectEdit? {
        if (!item.subjectMask.exists() || !item.foreground.exists()) return null
        val mask = BitmapFactory.decodeFile(item.subjectMask.path) ?: return null
        val src = BitmapFactory.decodeFile(item.source.path) ?: return Compositor.SubjectEdit(mask, null)
        val fg = BitmapFactory.decodeFile(item.foreground.path) ?: return Compositor.SubjectEdit(mask, null)
        return Compositor.SubjectEdit(mask, com.hinterlay.app.render.SubjectPlate.build(src, fg))
    }

    fun delete(item: HistoryItem) {
        item.dir.deleteRecursively()
    }

    private fun layersToJson(layers: List<TextLayer>) = JSONArray().also { arr ->
        layers.forEach { l ->
            arr.put(
                JSONObject()
                    .put("text", l.text).put("font", l.font.name).put("weight", l.weight.name)
                    .put("italic", l.italic).put("textCase", l.textCase.name).put("perspective", l.perspective?.let { p -> JSONArray(p.map { it.toDouble() }) } ?: JSONObject.NULL).put("sizeRel", l.sizeRel.toDouble()).put("color", l.color)
                    .put("opacity", l.opacity.toDouble()).put("x", l.x.toDouble()).put("y", l.y.toDouble())
                    .put("rotation", l.rotation.toDouble()).put("letterSpacing", l.letterSpacing.toDouble())
                    .put("align", l.align.name).put("strokeWidth", l.strokeWidth.toDouble())
                    .put("strokeColor", l.strokeColor).put("shadow", l.shadow).put("shadowStrength", l.shadowStrength.toDouble()).put("shadowColor", l.shadowColor).put("bubble", l.bubble)
                    .put("material", l.material.name).put("materialLight", l.materialLight.toDouble())
                    .put("behindSubject", l.behindSubject).put("visible", l.visible)
            )
        }
    }

    private inline fun <reified E : Enum<E>> JSONObject.enum(key: String, default: E): E =
        runCatching { enumValueOf<E>(getString(key)) }.getOrDefault(default)

    private fun layerFromJson(o: JSONObject, id: Long) = TextLayer(
        id = id,
        text = o.getString("text"),
        font = o.enum("font", FontChoice.SANS),
        weight = o.enum("weight", FontWeightChoice.BLACK),
        italic = o.optBoolean("italic"),
        textCase = o.enum("textCase", com.hinterlay.app.editor.TextCase.AS_TYPED),
        sizeRel = o.optDouble("sizeRel", 0.24).toFloat(),
        color = o.optInt("color", 0xFFFFFFFF.toInt()),
        opacity = o.optDouble("opacity", 1.0).toFloat(),
        x = o.optDouble("x", 0.5).toFloat(),
        y = o.optDouble("y", 0.33).toFloat(),
        rotation = o.optDouble("rotation", 0.0).toFloat(),
        letterSpacing = o.optDouble("letterSpacing", 0.0).toFloat(),
        align = o.enum("align", TextAlignChoice.CENTER),
        strokeWidth = o.optDouble("strokeWidth", 0.0).toFloat(),
        strokeColor = o.optInt("strokeColor", 0xFF000000.toInt()),
        shadow = o.optBoolean("shadow"),
        shadowStrength = o.optDouble("shadowStrength", 0.6).toFloat(),
        shadowColor = o.optInt("shadowColor", 0xFF000000.toInt()),
        bubble = o.optBoolean("bubble"),
        material = o.enum("material", com.hinterlay.app.editor.TextMaterial.NONE),
        materialLight = o.optDouble("materialLight", 0.5).toFloat(),
        perspective = o.optJSONArray("perspective")?.let { a -> if (a.length() == 8) List(8) { a.getDouble(it).toFloat() } else null },
        behindSubject = o.optBoolean("behindSubject", true),
        visible = o.optBoolean("visible", true),
    )
}
