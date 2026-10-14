package com.hinterlay.app.ai

import android.graphics.Bitmap
import androidx.palette.graphics.Palette
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/** On-device suggestions from the photo itself. Nothing is uploaded. */
class Suggestions(val words: List<String>, val palette: List<Int>, val labels: List<String>)

object SmartSuggest {

    // Vibes keyed by what ML Kit sees. First match wins; keeps suggestions relevant, not random.
    private val vibes = listOf(
        setOf("mountain", "hill", "sky", "cloud", "sunset", "beach", "sea", "ocean", "lake", "forest", "tree", "nature", "landscape") to
            listOf("WANDERLUST", "ADVENTURE", "ESCAPE", "WILD"),
        setOf("dog", "puppy", "cat", "kitten", "pet", "bird", "animal") to listOf("GOOD BOY", "BEST FRIEND", "PURR", "MY PERSON"),
        setOf("smile", "face", "person", "selfie", "portrait", "hair", "fashion", "model", "beard") to
            listOf("MAIN CHARACTER", "MOOD", "GOLDEN HOUR", "VIBES"),
        setOf("food", "dish", "cuisine", "pizza", "cake", "coffee", "drink", "dessert", "fruit") to listOf("YUM", "TREAT YOURSELF", "FRESH", "TASTY"),
        setOf("car", "vehicle", "motorcycle", "bicycle", "wheel", "road") to listOf("ROAD TRIP", "FULL SPEED", "DRIVE", "ON THE MOVE"),
        setOf("building", "city", "architecture", "skyscraper", "street", "urban") to listOf("CITY LIGHTS", "URBAN", "DOWNTOWN", "AFTER DARK"),
        setOf("flower", "plant", "garden", "petal") to listOf("BLOOM", "FRESH START", "IN FULL BLOOM"),
    )
    private val fallback = listOf("HELLO", "GOOD VIBES", "NEW DAY", "LIVE LOUD")

    /** Caption shapes that wrap whatever the photo contains, so the ideas change with every photo. */
    private val templates = listOf("MORE {X}", "{X} MODE", "JUST {X}", "{X} DAYS", "ALL ABOUT {X}", "{X} ONLY", "PURE {X}", "HELLO {X}", "THE {X} EDIT")

    /** Faces in the photo: how many, and how many are smiling. Runs on the phone. */
    private suspend fun faceMood(small: Bitmap): Pair<Int, Int> = runCatching {
        val detector = com.google.mlkit.vision.face.FaceDetection.getClient(
            com.google.mlkit.vision.face.FaceDetectorOptions.Builder()
                .setPerformanceMode(com.google.mlkit.vision.face.FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(com.google.mlkit.vision.face.FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.1f).build()
        )
        try {
            val faces = detector.process(InputImage.fromBitmap(small, 0)).await()
            faces.size to faces.count { (it.smilingProbability ?: 0f) > 0.6f }
        } finally { detector.close() }
    }.getOrDefault(0 to 0)

    /** Average brightness (0..1) and warmth (-1 cool .. 1 warm) of the photo. */
    private fun tone(small: Bitmap): Pair<Float, Float> {
        val w = 24; val h = 24
        val t = Bitmap.createScaledBitmap(small, w, h, true)
        var l = 0f; var warm = 0f
        for (y in 0 until h) for (x in 0 until w) {
            val c = t.getPixel(x, y)
            val r = ((c shr 16) and 255) / 255f; val g = ((c shr 8) and 255) / 255f; val b = (c and 255) / 255f
            l += 0.299f * r + 0.587f * g + 0.114f * b; warm += r - b
        }
        t.recycle()
        return (l / (w * h)) to (warm / (w * h))
    }

    /**
     * Builds the idea list from what is in the photo (objects, faces and smiles, light and colour) plus the moment
     * (time of day, weekday, month). A seed from the photo's content picks a different mix for different photos.
     */
    private fun ideas(labels: List<String>, faces: Pair<Int, Int>, brightness: Float, warmth: Float, portrait: Boolean): List<String> {
        val lower = labels.map { it.lowercase() }
        val cal = java.util.Calendar.getInstance()
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val day = java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault()).format(cal.time).uppercase()
        val month = java.text.SimpleDateFormat("MMMM", java.util.Locale.getDefault()).format(cal.time).uppercase()
        val rnd = kotlin.random.Random((labels.joinToString().hashCode().toLong() * 31) + faces.first * 7 + (brightness * 100).toInt())

        val pool = ArrayList<String>()
        // What the photo shows: every theme that matches contributes, not just the first.
        vibes.filter { (keys, _) -> lower.any { it in keys } }.forEach { (_, words) -> pool += words.shuffled(rnd).take(2) }
        // What it is about, wrapped in a caption shape.
        labels.take(3).forEach { label -> pool += templates.random(rnd).replace("{X}", label.uppercase()) }
        // People.
        val (count, smiling) = faces
        when {
            count >= 4 -> pool += listOf("SQUAD GOALS", "THE CREW")
            count == 3 -> pool += listOf("TRIO", "THREE OF A KIND")
            count == 2 -> pool += listOf("BEST DUO", "PARTNERS IN CRIME")
            count == 1 && smiling == 1 -> pool += listOf("SMILE MORE", "HAPPY PLACE")
            count == 1 -> pool += listOf("QUIET MOOD", "MAIN CHARACTER")
        }
        if (count > 0 && smiling == count) pool += "PURE JOY"
        // Light and colour.
        if (brightness < 0.28f) pool += listOf("AFTER DARK", "NIGHT MODE")
        else if (brightness > 0.72f) pool += listOf("SUNNY SIDE UP", "BRIGHT SIDE")
        if (warmth > 0.12f) pool += listOf("WARM VIBES", "GOLDEN HOUR") else if (warmth < -0.1f) pool += listOf("BLUE HOUR", "COOL HEAD")
        // The moment.
        pool += when (hour) {
            in 5..10 -> listOf("GOOD MORNING", "$day MORNING")
            in 11..16 -> listOf("$day VIBES", "MIDDAY MOOD")
            in 17..19 -> listOf("GOLDEN HOUR", "EVENING LIGHT")
            else -> listOf("$day NIGHT", "LATE NIGHT")
        }
        pool += "$month MOOD"
        if (portrait && count == 0) pool += "STAND TALL"
        // The photo's own words lead; a few steady fallbacks only fill gaps.
        val picked = pool.distinct().shuffled(rnd).take(8)
        return (picked + fallback).distinct().take(8)
    }

    suspend fun analyze(source: Bitmap): Suggestions = withContext(Dispatchers.Default) {
        // Work on a small copy: faster and plenty for labels and colours.
        val k = 480f / max(source.width, source.height)
        val small = if (k < 1f) Bitmap.createScaledBitmap(source, (source.width * k).roundToInt(), (source.height * k).roundToInt(), true) else source

        val labels = runCatching {
            val labeler = ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.6f).build())
            val result = labeler.process(InputImage.fromBitmap(small, 0)).await()
            labeler.close()
            result.sortedByDescending { it.confidence }.map { it.text }
        }.getOrDefault(emptyList())

        val faces = faceMood(small)
        val (brightness, warmth) = tone(small)
        val words = ideas(labels, faces, brightness, warmth, source.height > source.width)

        val swatch = Palette.from(small).maximumColorCount(12).generate()
        val colors = listOfNotNull(
            swatch.getVibrantColor(0).takeIf { it != 0 },
            swatch.getLightVibrantColor(0).takeIf { it != 0 },
            swatch.getDominantColor(0).takeIf { it != 0 },
            swatch.getDarkVibrantColor(0).takeIf { it != 0 },
            swatch.getMutedColor(0).takeIf { it != 0 },
            swatch.getLightMutedColor(0).takeIf { it != 0 },
        ).distinct()

        if (small !== source) small.recycle()
        Suggestions(words, colors, labels)
    }
}
