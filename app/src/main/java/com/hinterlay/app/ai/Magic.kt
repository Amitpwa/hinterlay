package com.hinterlay.app.ai

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import com.hinterlay.app.editor.FontChoice
import com.hinterlay.app.editor.TextCase
import com.hinterlay.app.editor.TextLayer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** What the on-device AI understood about a photo. */
class Scene(
    val labels: List<String>,
    val palette: List<Int>,
    val mood: Mood,
    val subjectBox: FloatArray?,   // left, top, right, bottom as 0..1, or null
    val topLuma: Float,            // brightness 0..1 of the upper third
    val midLuma: Float,
    val lowLuma: Float,
)

enum class Mood { ADVENTURE, PORTRAIT, PET, FOOD, CITY, NATURE, SPORT, PARTY, CALM, GENERAL }

/** One finished suggestion: a ready-to-apply text layer and the reason it was picked. */
class MagicStyle(val layer: TextLayer, val title: String, val why: String)

/**
 * Looks at the photo (labels from ML Kit, colours, where the subject stands, how bright each part is)
 * and builds complete text styles: words, typeface, colour, size and where to place it. Everything
 * runs on the phone. Asking for more returns different combinations each time.
 */
object Magic {

    fun analyze(source: Bitmap, foreground: Bitmap?, labels: List<String>, palette: List<Int>): Scene {
        val small = Bitmap.createScaledBitmap(source, 48, 64, true)
        fun luma(y0: Int, y1: Int): Float {
            var sum = 0f; var n = 0
            for (y in y0 until y1) for (x in 0 until 48) {
                val c = small.getPixel(x, y)
                sum += (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f; n++
            }
            return if (n == 0) 0.5f else sum / n
        }
        val top = luma(0, 21); val mid = luma(21, 43); val low = luma(43, 64)
        small.recycle()

        var box: FloatArray? = null
        if (foreground != null) {
            val fs = Bitmap.createScaledBitmap(foreground, 48, 64, true)
            var minX = 48; var minY = 64; var maxX = -1; var maxY = -1
            for (y in 0 until 64) for (x in 0 until 48) if (Color.alpha(fs.getPixel(x, y)) > 60) {
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
            }
            fs.recycle()
            if (maxX >= 0) box = floatArrayOf(minX / 48f, minY / 64f, (maxX + 1) / 48f, (maxY + 1) / 64f)
        }
        return Scene(labels, palette, moodOf(labels), box, top, mid, low)
    }

    private fun moodOf(labels: List<String>): Mood {
        val l = labels.map { it.lowercase() }
        fun has(vararg k: String) = l.any { it in k }
        return when {
            has("dog", "puppy", "cat", "kitten", "pet", "bird", "animal") -> Mood.PET
            has("food", "dish", "cuisine", "pizza", "cake", "coffee", "drink", "dessert", "fruit", "meal") -> Mood.FOOD
            has("smile", "face", "selfie", "portrait", "beard", "hair", "shirt", "jeans", "hand", "forehead", "chin") -> Mood.PORTRAIT
            has("sport", "ball", "gym", "running", "bicycle", "player", "fitness") -> Mood.SPORT
            has("party", "event", "concert", "dance", "fun", "crowd", "festival") -> Mood.PARTY
            has("mountain", "hill", "beach", "sea", "ocean", "lake", "sky", "sunset", "cloud", "landscape") -> Mood.ADVENTURE
            has("flower", "plant", "tree", "forest", "garden", "grass", "nature") -> Mood.NATURE
            has("building", "city", "architecture", "skyscraper", "street", "urban", "car", "vehicle") -> Mood.CITY
            has("smile", "face", "person", "selfie", "portrait", "hair", "fashion", "beard", "hand", "shirt", "jeans") -> Mood.PORTRAIT
            has("room", "wall", "furniture", "indoor", "interior") -> Mood.CALM
            else -> Mood.GENERAL
        }
    }

    // Punchy words per mood. Short, strong, and different enough to feel like choices.
    private val words = mapOf(
        Mood.ADVENTURE to listOf("WANDERLUST", "GO FAR", "WILD AT HEART", "ESCAPE", "ADVENTURE AWAITS", "NOT ALL WHO WANDER", "HORIZON"),
        Mood.PORTRAIT to listOf("MAIN CHARACTER", "UNBOTHERED", "THAT LOOK", "SELF MADE", "BE REAL", "QUIET POWER", "NEW ERA"),
        Mood.PET to listOf("GOOD BOY", "BEST FRIEND", "PURE JOY", "FUR REAL", "MY PERSON", "SOFT LIFE"),
        Mood.FOOD to listOf("YUM", "TREAT YOURSELF", "FRESH", "SAVOUR IT", "TASTE OF HOME", "SECOND HELPING"),
        Mood.CITY to listOf("CITY LIGHTS", "AFTER DARK", "URBAN", "NEVER SLEEP", "DOWNTOWN", "ON THE MOVE"),
        Mood.NATURE to listOf("IN BLOOM", "FRESH START", "GROW", "BREATHE", "WILD & FREE", "GREEN SEASON"),
        Mood.SPORT to listOf("NO LIMITS", "GAME ON", "PUSH HARDER", "ALL IN", "BORN TO MOVE", "STRONGER"),
        Mood.PARTY to listOf("LIVE LOUD", "TURN UP", "GOOD VIBES", "TONIGHT", "NO REGRETS", "MAKE NOISE"),
        Mood.CALM to listOf("SLOW DOWN", "STAY SOFT", "QUIET MIND", "JUST BE", "THIS MOMENT", "EASY"),
        Mood.GENERAL to listOf("HELLO", "GOOD VIBES", "NEW DAY", "LIVE LOUD", "MAKE IT COUNT", "PURE ENERGY"),
    )

    private class FontPick(val font: FontChoice, val vibe: String, val caps: TextCase, val bubble: Boolean = false)

    // Typefaces that suit each mood, strongest match first.
    private val fonts = mapOf(
        Mood.ADVENTURE to listOf(FontPick(FontChoice.BEBAS, "Tall and cinematic", TextCase.UPPER), FontPick(FontChoice.ANTON, "Heavy poster", TextCase.UPPER), FontPick(FontChoice.ABRIL, "Editorial", TextCase.UPPER)),
        Mood.PORTRAIT to listOf(FontPick(FontChoice.ANTON, "Magazine cover", TextCase.UPPER), FontPick(FontChoice.ABRIL, "Fashion serif", TextCase.UPPER), FontPick(FontChoice.BEBAS, "Clean and bold", TextCase.UPPER)),
        Mood.PET to listOf(FontPick(FontChoice.LILITA, "Friendly and round", TextCase.UPPER, bubble = true), FontPick(FontChoice.CHEWY, "Playful", TextCase.TITLE), FontPick(FontChoice.PACIFICO, "Soft script", TextCase.LOWER)),
        Mood.FOOD to listOf(FontPick(FontChoice.SHRIKHAND, "Warm and tasty", TextCase.UPPER), FontPick(FontChoice.LOBSTER, "Café script", TextCase.TITLE), FontPick(FontChoice.LILITA, "Bold and fun", TextCase.UPPER)),
        Mood.CITY to listOf(FontPick(FontChoice.BUNGEE, "Street sign", TextCase.UPPER), FontPick(FontChoice.BEBAS, "Skyline", TextCase.UPPER), FontPick(FontChoice.RIGHTEOUS, "Neon", TextCase.UPPER)),
        Mood.NATURE to listOf(FontPick(FontChoice.PACIFICO, "Gentle script", TextCase.LOWER), FontPick(FontChoice.ABRIL, "Botanical serif", TextCase.TITLE), FontPick(FontChoice.BEBAS, "Fresh and light", TextCase.UPPER)),
        Mood.SPORT to listOf(FontPick(FontChoice.ANTON, "Stadium", TextCase.UPPER), FontPick(FontChoice.BOWLBY, "Impact", TextCase.UPPER), FontPick(FontChoice.LUCKIEST, "Energy", TextCase.UPPER)),
        Mood.PARTY to listOf(FontPick(FontChoice.LUCKIEST, "Loud", TextCase.UPPER, bubble = true), FontPick(FontChoice.BANGERS, "Comic burst", TextCase.UPPER), FontPick(FontChoice.PIXEL, "Arcade", TextCase.UPPER)),
        Mood.CALM to listOf(FontPick(FontChoice.ABRIL, "Quiet serif", TextCase.TITLE), FontPick(FontChoice.PACIFICO, "Easy script", TextCase.LOWER), FontPick(FontChoice.YESEVA, "Classic", TextCase.TITLE)),
        Mood.GENERAL to listOf(FontPick(FontChoice.ANTON, "Bold", TextCase.UPPER), FontPick(FontChoice.LILITA, "Friendly", TextCase.UPPER, bubble = true), FontPick(FontChoice.PACIFICO, "Script", TextCase.TITLE)),
    )

    /**
     * Builds [count] different suggestions. [round] picks the next set: 0 the first three, 1 the next
     * three, and so on, rotating through words, typefaces and placements.
     */
    fun suggest(scene: Scene, round: Int, count: Int = 3): List<MagicStyle> {
        val w = words.getValue(scene.mood)
        val f = fonts.getValue(scene.mood)
        return (0 until count).map { i ->
            val k = round * count + i
            val word = w[k % w.size]
            val pick = f[(k + i) % f.size]
            build(scene, word, pick, k)
        }
    }

    private fun build(scene: Scene, word: String, pick: FontPick, k: Int): MagicStyle {
        val text = pick.caps.apply(word)
        val box = scene.subjectBox
        // Placement: with a subject, tuck the text behind its head and shoulders; otherwise use the calmest third.
        val behind = box != null
        val y: Float
        val region: Float
        if (box != null) {
            val h = box[3] - box[1]
            // Centre the words around the top of the head so most of them stay readable and only the
            // middle letters slip behind it.
            y = (box[1] + h * (0.02f + 0.05f * (k % 3))).coerceIn(0.12f, 0.7f)
            region = if (y < 0.34f) scene.topLuma else if (y < 0.67f) scene.midLuma else scene.lowLuma
        } else {
            val topCalm = abs(scene.topLuma - 0.5f)
            val lowCalm = abs(scene.lowLuma - 0.5f)
            if (k % 2 == 0 || lowCalm > topCalm) { y = 0.2f; region = scene.topLuma } else { y = 0.8f; region = scene.lowLuma }
        }

        // Colour: readable against that part of the photo. Alternate white/black with a punchy colour from the photo.
        val readable = if (region > 0.58f) Color.rgb(17, 17, 17) else Color.WHITE
        // A photo colour is only used if it stands out clearly from the part of the photo it sits on.
        val bg = Color.rgb((region * 255).toInt(), (region * 255).toInt(), (region * 255).toInt())
        val accent = scene.palette.firstOrNull { c -> ColorUtils.calculateContrast(c, bg) >= 4.0 }
        val useAccent = accent != null && (k % 3 == 1) && !pick.bubble
        val color = when {
            pick.bubble -> accent ?: if (region > 0.5f) Color.rgb(20, 20, 20) else Color.WHITE
            useAccent -> accent!!
            else -> readable
        }
        val big = text.length <= 6
        val layer = TextLayer(
            id = 0, text = text, font = pick.font, textCase = TextCase.AS_TYPED,
            color = color, sizeRel = if (big) 0.34f else if (text.length <= 12) 0.22f else 0.15f,
            x = 0.5f, y = y, bubble = pick.bubble,
            behindSubject = behind && !pick.bubble || behind && k % 2 == 0,
            shadow = !behind || pick.bubble,
            strokeWidth = if (region in 0.35f..0.65f && !pick.bubble && color == Color.WHITE) 0.0f else 0f,
        )
        val where = if (behind) "behind you" else if (y < 0.5f) "at the top" else "at the bottom"
        val why = "${pick.vibe}, set $where for a ${scene.mood.name.lowercase()} photo"
        return MagicStyle(layer, pick.vibe, why)
    }
}
