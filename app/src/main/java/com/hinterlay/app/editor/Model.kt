package com.hinterlay.app.editor

/**
 * System font families (no download) plus Google Fonts bundled in assets/fonts.
 * Bundled fonts have a single weight, so the weight/italic chips don't change them.
 */
enum class FontChoice(val label: String, val family: String, val asset: String? = null, val hidden: Boolean = false) {
    // Only families that look clearly different on every device. "cursive", "casual" and
    // "smallcaps" often fall back to the default font, so they read as broken buttons.
    SANS("Sans", "sans-serif"),
    // Hidden from the picker (they looked too much like Sans) but kept so older saved creations still open.
    SERIF("Serif", "serif", hidden = true),
    CONDENSED("Condensed", "sans-serif-condensed", hidden = true),
    MONO("Mono", "monospace"),
    BEBAS("Bebas Neue", "", "bebas_neue.ttf"),
    ANTON("Anton", "", "anton.ttf"),
    BANGERS("Bangers", "", "bangers.ttf"),
    LILITA("Lilita One", "", "lilita_one.ttf"),
    LUCKIEST("Luckiest Guy", "", "luckiest_guy.ttf"),
    CHEWY("Chewy", "", "chewy.ttf"),
    BUNGEE("Bungee", "", "bungee.ttf"),
    BOWLBY("Bowlby One", "", "bowlby_one.ttf"),
    RIGHTEOUS("Righteous", "", "righteous.ttf"),
    ABRIL("Abril Fatface", "", "abril_fatface.ttf"),
    SHRIKHAND("Shrikhand", "", "shrikhand.ttf"),
    LOBSTER("Lobster", "", "lobster.ttf"),
    PACIFICO("Pacifico", "", "pacifico.ttf"),
    MARKER("Permanent Marker", "", "permanent_marker.ttf"),
    PIXEL("Press Start 2P", "", "press_start_2p.ttf"),
    ARCHIVO("Archivo Black", "", "archivo_black.ttf"),
    ALFA("Alfa Slab One", "", "alfa_slab_one.ttf"),
    SIGMAR("Sigmar One", "", "sigmar_one.ttf"),
    PASSION("Passion One", "", "passion_one.ttf"),
    YESEVA("Yeseva One", "", "yeseva_one.ttf"),
    KAUSHAN("Kaushan Script", "", "kaushan_script.ttf"),
    CAVEAT("Caveat", "", "caveat.ttf"),
    RYE("Rye", "", "rye.ttf"),
    BLACKOPS("Black Ops One", "", "black_ops_one.ttf"),
    FASCINATE("Fascinate", "", "fascinate.ttf"),
    MONOTON("Monoton", "", "monoton.ttf"),
    BUNGEE_SHADE("Bungee Shade", "", "bungee_shade.ttf"),
    RUBIK_MONO("Rubik Mono One", "", "rubik_mono_one.ttf");

    companion object {
        /** The fonts offered in the picker, without the hidden look-alikes. */
        val picker: List<FontChoice> = entries.filterNot { it.hidden }
    }
}

enum class FontWeightChoice(val label: String, val weight: Int) {
    LIGHT("Light", 300),
    REGULAR("Regular", 400),
    BOLD("Bold", 700),
    BLACK("Black", 900),
}

/** The text every new photo starts with, and the sample shown for each font. "Do magic" replaces it while it is still untouched. */
const val DEFAULT_TEXT = "Hinterlay"

/** Letter casing, independent of the typeface. */
enum class TextCase(val label: String, val sample: String) {
    AS_TYPED("As typed", "Ab"),
    UPPER("UPPERCASE", "AB"),
    LOWER("lowercase", "ab"),
    TITLE("Title Case", "Ab Cd");

    fun apply(text: String): String = when (this) {
        AS_TYPED -> text
        UPPER -> text.uppercase()
        LOWER -> text.lowercase()
        TITLE -> text.split(' ').joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
    }
}

enum class TextAlignChoice(val label: String) { START("Left"), CENTER("Center"), END("Right") }

/** Rendered 3D looks for text. The letters are inflated and shaded by a studio reflection, so the colour setting does not apply. */
enum class TextMaterial(val label: String) {
    NONE("Flat"), SILVER("Silver balloon"), GOLD("Gold balloon"), ROSE("Rose balloon"),
    CHROME("Chrome"), ICE("Ice"), HOLO("Holo"),
}

/**
 * One text layer. All geometry is relative to the image so preview and full-resolution
 * export render identically.
 */
data class TextLayer(
    val id: Long,
    val text: String,
    val font: FontChoice = FontChoice.SANS,
    val weight: FontWeightChoice = FontWeightChoice.BLACK,
    val italic: Boolean = false,
    val textCase: TextCase = TextCase.AS_TYPED,
    /**
     * Perspective warp: (dx, dy) offsets of the corners TL, TR, BR, BL, in box widths/heights.
     * Null = flat text.
     */
    val perspective: List<Float>? = null,
    // Erase mask in image space (alpha = erased): lets the user rub out parts of the text, e.g. to
    // tuck part of front text behind the subject. maskVersion changes on every stroke so the
    // preview redraws.
    val eraseMask: android.graphics.Bitmap? = null,
    val maskVersion: Int = 0,
    /** Text size as a fraction of image width. */
    val sizeRel: Float = 0.24f,
    val color: Int = 0xFFFFFFFF.toInt(),
    val opacity: Float = 1f,
    /** Centre position, 0..1 of image width / height. */
    val x: Float = 0.5f,
    val y: Float = 0.33f,
    val rotation: Float = 0f,
    /** Extra tracking in em. */
    val letterSpacing: Float = 0f,
    val align: TextAlignChoice = TextAlignChoice.CENTER,
    /** Outline width as a fraction of text size; 0 = off. */
    val strokeWidth: Float = 0f,
    val strokeColor: Int = 0xFF000000.toInt(),
    val shadow: Boolean = false,
    /** Drop-shadow darkness and softness, 0..1. */
    val shadowStrength: Float = 0.6f,
    val shadowColor: Int = 0xFF000000.toInt(),
    /** Glossy inflated "bubble" look: thick rounded rim in [color] with a light, shiny face. */
    val bubble: Boolean = false,
    /** 3D material (balloon, chrome...). Anything but NONE replaces the colour, outline and bubble looks. */
    val material: TextMaterial = TextMaterial.NONE,
    /** Where the studio light sits for a material, 0..1 (0.5 = straight on). Reflections also move with phone tilt in the editor. */
    val materialLight: Float = 0.5f,
    /** Drawn between the background and the detected subject. */
    val behindSubject: Boolean = true,
    val visible: Boolean = true,
)

enum class ExportFormat(val label: String, val detail: String, val ratioW: Int, val ratioH: Int, val longSide: Int) {
    ORIGINAL("Original", "Same shape as your photo", 0, 0, 0),
    STORY("Story 9:16", "1080 × 1920 for Instagram, WhatsApp, Snapchat", 9, 16, 1920),
    PORTRAIT("Portrait 4:5", "1080 × 1350 feed post", 4, 5, 1350),
    SQUARE("Square 1:1", "1080 × 1080", 1, 1, 1080),
}
