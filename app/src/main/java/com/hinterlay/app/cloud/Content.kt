package com.hinterlay.app.cloud

import android.graphics.Color
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.hinterlay.app.editor.FontChoice
import com.hinterlay.app.editor.TextCase
import com.hinterlay.app.editor.TextLayer
import com.hinterlay.app.editor.TextMaterial
import com.hinterlay.app.render.FrameParams
import com.hinterlay.app.render.FrameStyle
import com.hinterlay.app.render.FxParams
import com.hinterlay.app.render.Look
import com.hinterlay.app.render.PhotoAdjust
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which drawn scene a template card shows (see ui/screens/TemplateArt.kt). */
enum class Art { DUSK, OCEAN, DESERT, FOREST, SNOW, NEON, CITY, SUNRISE }

/**
 * A ready-made style the user can try on their own photo: the text and how it looks, where it sits (including perspective),
 * and optionally a look and a few photo tools. Every field after [behindSubject] is optional.
 */
data class Preset(
    val text: String,
    val font: FontChoice = FontChoice.SANS,
    val color: Int = Color.WHITE,
    val bubble: Boolean = false,
    val textCase: TextCase = TextCase.AS_TYPED,
    val behindSubject: Boolean = true,
    val material: TextMaterial = TextMaterial.NONE,
    /** Corner offsets TL, TR, BR, BL as (dx, dy) in box widths / heights, or null for flat text. */
    val perspective: List<Float>? = null,
    val rotation: Float = 0f,
    val y: Float = 0.33f,
    val sizeRel: Float = 0.24f,
    val letterSpacing: Float = 0f,
    val strokeWidth: Float = 0f,
    val strokeColor: Int = Color.BLACK,
    val shadow: Boolean? = null,
    val look: Look = Look.NORMAL,
    val vignette: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val glow: Float = 0f,
    val hdr: Float = 0f,
    val grain: Float = 0f,
    val glitch: Float = 0f,
    val frame: FrameStyle? = null,
    /** The drawn scene shown on this template's card. */
    val art: Art = Art.DUSK,
    /** A bundled photo (assets/templates/<name>.jpg) to show the template on, instead of a drawn scene. */
    val photo: String? = null,
) {
    /** The text layer this style describes. Text goes behind the subject only when there is one. */
    fun toLayer(id: Long, hasSubject: Boolean) = TextLayer(
        id = id, text = text, font = font, color = color, bubble = bubble, textCase = textCase,
        behindSubject = behindSubject && hasSubject, shadow = shadow ?: !behindSubject,
        material = material, perspective = perspective, rotation = rotation, y = y, sizeRel = sizeRel,
        letterSpacing = letterSpacing, strokeWidth = strokeWidth, strokeColor = strokeColor,
    )

    val adjust get() = PhotoAdjust(look = look, contrast = contrast, saturation = saturation, warmth = warmth, vignette = vignette)

    val fx get() = FxParams(glow = glow, hdr = hdr, grain = grain, glitch = glitch, frame = frame?.let { FrameParams(it) })
}

/** Owner-curated inspiration item (uploaded from the admin panel). */
data class Inspiration(
    val id: String,
    val title: String,
    val caption: String,
    val imageUrl: String?,
    val tags: List<String>,
    val preset: Preset,
)

/** Server-driven card for the home screen (announcements, new features, promos). */
data class LiveCard(
    val id: String,
    val title: String,
    val body: String,
    val imageUrl: String?,
    val ctaLabel: String?,
    val ctaAction: String?,
    val priority: Int,
)

/**
 * Inspirations and live cards. They stream from Firestore when the cloud is configured and
 * fall back to built-in content otherwise (first launch, offline, or no Firebase yet), so
 * these sections are never empty.
 */
object ContentRepo {
    private val _inspirations = MutableStateFlow(BuiltIn.inspirations)
    val inspirations: StateFlow<List<Inspiration>> = _inspirations.asStateFlow()

    private val _cards = MutableStateFlow(BuiltIn.cards)
    val cards: StateFlow<List<LiveCard>> = _cards.asStateFlow()

    private var started = false

    fun start() {
        if (started || !Cloud.available) return
        started = true
        val db = FirebaseFirestore.getInstance()
        // Single-field filters only, so no composite index is needed; sorting happens here.
        db.collection("inspirations").whereEqualTo("active", true).addSnapshotListener { snap, _ ->
            val list = snap?.documents?.mapNotNull(::parseInspiration)?.sortedByDescending { it.second }?.map { it.first }
            if (!list.isNullOrEmpty()) _inspirations.value = list
        }
        db.collection("cards").whereEqualTo("active", true).addSnapshotListener { snap, _ ->
            val now = System.currentTimeMillis()
            val list = snap?.documents?.mapNotNull { parseCard(it, now) }?.sortedByDescending { it.priority }
            if (list != null) _cards.value = list
        }
    }

    private fun parseInspiration(d: DocumentSnapshot): Pair<Inspiration, Long>? = runCatching {
        val p = d.get("preset") as? Map<*, *> ?: emptyMap<String, Any>()
        val preset = Preset(
            text = (p["text"] as? String) ?: d.getString("title").orEmpty(),
            font = runCatching { FontChoice.valueOf(p["font"] as String) }.getOrDefault(FontChoice.SANS),
            color = runCatching { Color.parseColor(p["color"] as String) }.getOrDefault(Color.WHITE),
            bubble = p["bubble"] as? Boolean ?: false,
            textCase = runCatching { TextCase.valueOf(p["textCase"] as String) }.getOrDefault(TextCase.AS_TYPED),
            behindSubject = p["behindSubject"] as? Boolean ?: true,
        )
        @Suppress("UNCHECKED_CAST")
        val item = Inspiration(
            id = d.id,
            title = d.getString("title").orEmpty(),
            caption = d.getString("caption").orEmpty(),
            imageUrl = d.getString("imageUrl"),
            tags = (d.get("tags") as? List<String>).orEmpty(),
            preset = preset,
        )
        item to (d.getTimestamp("createdAt")?.toDate()?.time ?: 0L)
    }.getOrNull()

    private fun parseCard(d: DocumentSnapshot, now: Long): LiveCard? = runCatching {
        val expires: Timestamp? = d.getTimestamp("expiresAt")
        if (expires != null && expires.toDate().time < now) return null
        LiveCard(
            id = d.id,
            title = d.getString("title").orEmpty(),
            body = d.getString("body").orEmpty(),
            imageUrl = d.getString("imageUrl"),
            ctaLabel = d.getString("ctaLabel"),
            ctaAction = d.getString("ctaAction"),
            priority = (d.getLong("priority") ?: 0L).toInt(),
        )
    }.getOrNull()
}

/** Offline / not-yet-configured content so the sections always have something good to show. */
private object BuiltIn {
    private val FLOOR = listOf(0.24f, 0f, -0.24f, 0f, 0f, 0f, 0f, 0f)
    private val WALL_R = listOf(0f, 0.2f, 0f, -0.25f, 0f, 0.25f, 0f, -0.2f)
    private val WALL_L = listOf(0f, -0.25f, 0f, 0.2f, 0f, -0.2f, 0f, 0.25f)
    private val SKEW = listOf(0.12f, 0f, 0.12f, 0f, -0.12f, 0f, -0.12f, 0f)
    private fun c(hex: String) = Color.parseColor(hex)

    val inspirations = listOf(
        // Real photos (CC0 / public domain, see assets/templates/CREDITS.txt) with the text tucked behind the subject.
        Inspiration("p1", "Wild and free", "A loose script note over the mane, with a soft matte grade.", null, listOf("Photo", "Elegant"), Preset("wild & free", FontChoice.PACIFICO, Color.WHITE, behindSubject = false, photo = "horse", look = Look.MATTE, grain = 0.2f, rotation = -5f, y = 0.88f, sizeRel = 0.2f)),
        Inspiration("p2", "Iceland", "A wide-spaced cinema caption along the bottom.", null, listOf("Photo", "Cinematic"), Preset("ICELAND", FontChoice.BEBAS, Color.WHITE, behindSubject = false, photo = "horse", letterSpacing = 0.35f, look = Look.CINEMA, vignette = 0.4f, y = 0.92f, sizeRel = 0.16f)),
        Inspiration("p3", "Meow", "Glossy pink bubble letters peeking over a curious cat.", null, listOf("Photo", "Fun"), Preset("MEOW", FontChoice.LILITA, c("#FF4081"), bubble = true, photo = "cat", y = 0.2f, sizeRel = 0.3f)),
        Inspiration("p4", "Who, me?", "A marker note in front, slightly crooked.", null, listOf("Photo", "Fun"), Preset("who, me?", FontChoice.MARKER, Color.WHITE, behindSubject = false, photo = "cat", rotation = -8f, y = 0.8f, sizeRel = 0.2f)),
        Inspiration("p5", "Flamingo", "A rose-gold balloon word behind the long neck.", null, listOf("Photo", "3D"), Preset("FLAMINGO", FontChoice.SHRIKHAND, material = TextMaterial.ROSE, photo = "flamingo", y = 0.2f, sizeRel = 0.22f, glow = 0.2f)),
        Inspiration("p6", "Good boy", "Loud yellow caps with an outline, warm grade.", null, listOf("Photo", "Fun"), Preset("GOOD BOY", FontChoice.BOWLBY, c("#FFE066"), strokeWidth = 0.04f, photo = "dog", y = 0.2f, sizeRel = 0.22f, look = Look.WARM)),
        Inspiration("p7", "Zero G", "Chrome letters floating behind an astronaut.", null, listOf("Photo", "3D"), Preset("ZERO G", FontChoice.ANTON, material = TextMaterial.CHROME, photo = "astronaut", y = 0.4f, sizeRel = 0.34f, glow = 0.2f)),
        Inspiration("p8", "Orbit", "Holographic type with a glitchy edge.", null, listOf("Photo", "Neon"), Preset("ORBIT", FontChoice.MONOTON, material = TextMaterial.HOLO, photo = "astronaut", y = 0.55f, sizeRel = 0.26f, glow = 0.4f, glitch = 0.15f)),
        Inspiration("p9", "Greetings from the coast", "A faded postcard with a Polaroid border.", null, listOf("Photo", "Retro"), Preset("Greetings", FontChoice.LOBSTER, c("#FFF3D6"), behindSubject = false, photo = "lighthouse", y = 0.78f, sizeRel = 0.24f, look = Look.VINTAGE, grain = 0.35f, frame = FrameStyle.POLAROID, shadow = true)),
        Inspiration("p10", "Last light", "Elegant serif tucked behind the tower.", null, listOf("Photo", "Elegant"), Preset("LAST LIGHT", FontChoice.ABRIL, Color.WHITE, photo = "lighthouse", y = 0.22f, sizeRel = 0.2f, letterSpacing = 0.08f, vignette = 0.35f)),
        Inspiration("p11", "Up, up", "A gold balloon word behind a hot air balloon.", null, listOf("Photo", "3D"), Preset("UP", FontChoice.BEBAS, material = TextMaterial.GOLD, photo = "balloon", y = 0.3f, sizeRel = 0.55f)),
        Inspiration("p12", "Sky high", "Bold shaded caps with an HDR boost.", null, listOf("Photo", "Bold"), Preset("SKY HIGH", FontChoice.BUNGEE_SHADE, Color.WHITE, photo = "balloon", y = 0.18f, sizeRel = 0.2f, hdr = 0.35f)),
        Inspiration("p13", "Surf's up", "Comic caps behind the rider, vivid colour.", null, listOf("Photo", "Fun"), Preset("SURF'S UP", FontChoice.BANGERS, Color.WHITE, strokeWidth = 0.04f, photo = "surfer", y = 0.25f, sizeRel = 0.22f, look = Look.VIVID)),
        Inspiration("p14", "Wave", "Icy letters lying flat on the water.", null, listOf("Photo", "3D"), Preset("WAVE", FontChoice.ANTON, material = TextMaterial.ICE, perspective = FLOOR, photo = "surfer", y = 0.74f, sizeRel = 0.34f)),
        Inspiration("b1", "Wanderlust", "Big travel energy behind the mountain.", null, listOf("Travel"), Preset("WANDERLUST", FontChoice.BEBAS, Color.WHITE, textCase = TextCase.UPPER, art = Art.DUSK, letterSpacing = 0.08f, sizeRel = 0.26f)),
        Inspiration("b9", "Gold rush", "A gold balloon word lying on the dunes.", null, listOf("3D", "Travel"), Preset("GOLD", FontChoice.BEBAS, material = TextMaterial.GOLD, perspective = FLOOR, y = 0.4f, art = Art.DESERT, sizeRel = 0.3f)),
        Inspiration("b2", "Pop bubble", "Glossy, inflated type that stands out.", null, listOf("Fun", "3D"), Preset("Pop!", FontChoice.LILITA, c("#FF4081"), bubble = true, behindSubject = false, art = Art.SUNRISE, rotation = -6f)),
        Inspiration("b10", "Chrome city", "Liquid chrome turned toward the skyline.", null, listOf("3D", "Bold"), Preset("DOWNTOWN", FontChoice.BUNGEE, material = TextMaterial.CHROME, perspective = WALL_R, art = Art.CITY, sizeRel = 0.2f, contrast = 0.1f)),
        Inspiration("b3", "Golden hour", "Warm script for sunset portraits.", null, listOf("Portrait"), Preset("golden hour", FontChoice.PACIFICO, c("#FFC83D"), textCase = TextCase.LOWER, art = Art.DESERT, rotation = -6f, warmth = 0.25f, glow = 0.25f)),
        Inspiration("b4", "Main character", "Magazine-cover caps behind you.", null, listOf("Portrait", "Bold"), Preset("MAIN CHARACTER", FontChoice.ANTON, Color.WHITE, art = Art.CITY, sizeRel = 0.2f, y = 0.28f)),
        Inspiration("b11", "Ice cold", "Frozen letters with a cool, crisp grade.", null, listOf("3D", "Bold"), Preset("FROZEN", FontChoice.ANTON, material = TextMaterial.ICE, art = Art.SNOW, look = Look.COOL, hdr = 0.25f)),
        Inspiration("b5", "Retro arcade", "Pixel type for gaming and nostalgia.", null, listOf("Retro", "Neon"), Preset("LEVEL UP", FontChoice.PIXEL, c("#00E5FF"), art = Art.NEON, glitch = 0.25f, grain = 0.2f, sizeRel = 0.13f)),
        Inspiration("b12", "Holo dreams", "Iridescent letters with a soft glow.", null, listOf("3D", "Neon"), Preset("DREAMS", FontChoice.RIGHTEOUS, material = TextMaterial.HOLO, art = Art.NEON, glow = 0.45f)),
        Inspiration("b13", "Silver balloon", "Puffy silver, floating over the sky.", null, listOf("3D", "Fun"), Preset("FLY", FontChoice.LILITA, material = TextMaterial.SILVER, behindSubject = false, art = Art.SUNRISE, rotation = -8f, sizeRel = 0.34f, y = 0.3f)),
        Inspiration("b14", "Rose balloon", "Rose-gold balloon type for love notes.", null, listOf("3D", "Elegant"), Preset("love", FontChoice.LILITA, material = TextMaterial.ROSE, behindSubject = false, art = Art.OCEAN, sizeRel = 0.34f)),
        Inspiration("b15", "On the road", "Type lying flat on the ground, receding away.", null, listOf("Travel", "Bold"), Preset("ON THE ROAD", FontChoice.ANTON, Color.WHITE, perspective = FLOOR, y = 0.5f, art = Art.DESERT, sizeRel = 0.2f)),
        Inspiration("b16", "Big city wall", "Billboard-style text turned along a wall.", null, listOf("Bold"), Preset("BIG CITY", FontChoice.BEBAS, Color.WHITE, perspective = WALL_L, art = Art.CITY, sizeRel = 0.3f, strokeWidth = 0.03f)),
        Inspiration("b17", "Glitch signal", "Split colours and sliding bands.", null, listOf("Neon", "Retro"), Preset("SIGNAL", FontChoice.BANGERS, c("#00E5FF"), art = Art.NEON, glitch = 0.55f, grain = 0.3f, look = Look.CINEMA)),
        Inspiration("b18", "The summit", "Cinema title card with a film grade.", null, listOf("Cinematic"), Preset("THE SUMMIT", FontChoice.ABRIL, Color.WHITE, art = Art.DUSK, letterSpacing = 0.18f, sizeRel = 0.17f, look = Look.CINEMA, vignette = 0.45f)),
        Inspiration("b19", "Vintage postcard", "Script greeting on a faded, framed print.", null, listOf("Retro", "Elegant"), Preset("Greetings", FontChoice.LOBSTER, c("#FFF3D6"), art = Art.OCEAN, look = Look.VINTAGE, grain = 0.35f, frame = FrameStyle.POLAROID, shadow = true, sizeRel = 0.22f)),
        Inspiration("b20", "Noir headline", "Black and white, hard and dramatic.", null, listOf("Cinematic", "Bold"), Preset("NO WAY OUT", FontChoice.BEBAS, Color.WHITE, art = Art.CITY, look = Look.NOIR, strokeWidth = 0.02f, sizeRel = 0.26f, vignette = 0.5f)),
        Inspiration("b21", "Misty forest", "A soft script tucked into the mist.", null, listOf("Portrait", "Elegant"), Preset("wander", FontChoice.SHRIKHAND, Color.WHITE, textCase = TextCase.LOWER, art = Art.FOREST, hdr = 0.35f, glow = 0.3f)),
        Inspiration("b6", "Soft hello", "A friendly marker greeting.", null, listOf("Fun"), Preset("Hello there", FontChoice.MARKER, Color.WHITE, behindSubject = false, art = Art.SNOW, rotation = -5f)),
        Inspiration("b7", "Weekend mode", "Chunky and cheerful.", null, listOf("Fun"), Preset("WEEKEND", FontChoice.LUCKIEST, c("#FF9F1C"), bubble = true, art = Art.OCEAN, perspective = SKEW)),
        Inspiration("b22", "Tilted pop", "Loud, slanted and impossible to miss.", null, listOf("Fun", "Bold"), Preset("WOW", FontChoice.BOWLBY, c("#FFE066"), art = Art.SUNRISE, rotation = -10f, strokeWidth = 0.04f, shadow = true, sizeRel = 0.34f)),
        Inspiration("b8", "Quiet luxury", "Elegant serif behind your subject.", null, listOf("Elegant"), Preset("Atelier", FontChoice.ABRIL, Color.WHITE, art = Art.FOREST, look = Look.MATTE, letterSpacing = 0.05f)),
    )

    val cards = listOf(
        LiveCard("c1", "Tuck text behind anything", "Try the new Erase brush to hide just part of a word.", null, "Try it", "create", 10),
        LiveCard("c2", "Make WhatsApp stickers", "Turn any creation into a sticker pack in one tap.", null, "Open stickers", "stickers", 5),
    )
}
