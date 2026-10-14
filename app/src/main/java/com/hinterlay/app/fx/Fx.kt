package com.hinterlay.app.fx

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.hinterlay.app.R

/**
 * Sound effects and haptics. Sounds follow the system media volume. One switch in More turns both sounds and haptics off.
 *
 * The vocabulary (so every control feels like part of one instrument):
 *   tick     choosing something: a chip, a tab, a button
 *   toggle   a switch going on or off (a different haptic for each direction)
 *   detent   a slider crossing a notch or its middle
 *   open     a sheet or dialog arriving, close when it leaves
 *   undo     taking something back
 *   success  a finished result (export, saved)
 *   error    something that did not work
 */
object Fx {
    enum class Sound(val res: Int) {
        Tick(R.raw.fx_tick), Tap(R.raw.fx_tap), Scan(R.raw.fx_scan), Done(R.raw.fx_done), Whoosh(R.raw.fx_whoosh),
        Error(R.raw.fx_error), Open(R.raw.fx_open), Close(R.raw.fx_close), Undo(R.raw.fx_undo), Detent(R.raw.fx_detent),
    }

    private var pool: SoundPool? = null
    private val ids = HashMap<Sound, Int>()
    private val ready = HashSet<Int>()
    private lateinit var appContext: Context

    var enabled: Boolean
        get() = appContext.getSharedPreferences("hinterlay_ui", Context.MODE_PRIVATE).getBoolean("sound", true)
        set(value) = appContext.getSharedPreferences("hinterlay_ui", Context.MODE_PRIVATE).edit().putBoolean("sound", value).apply()

    /** True once [init] has run (haptics can be asked for before the first screen is up, e.g. in previews). */
    val isReady get() = pool != null

    fun init(context: Context) {
        if (pool != null) return
        appContext = context.applicationContext
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        pool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs).build().also { p ->
            p.setOnLoadCompleteListener { _, id, status -> if (status == 0) ready.add(id) }
            Sound.entries.forEach { ids[it] = p.load(appContext, it.res, 1) }
        }
    }

    fun play(sound: Sound, volume: Float = 0.7f) {
        if (!enabled) return
        val id = ids[sound] ?: return
        if (id !in ready) return
        pool?.play(id, volume, volume, 1, 0, 1f)
    }
}

/**
 * Sound plus haptic for one action. Cues that arrive within [GAP_MS] of each other are merged into the first, so a control
 * and the code that handles its tap can both ask for feedback without it being felt twice.
 */
class Feedback(private val haptic: androidx.compose.ui.hapticfeedback.HapticFeedback) {

    private fun on() = Fx.isReady && Fx.enabled

    /** True when this cue should play; false when another one just played. */
    private fun gate(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - last < GAP_MS) return false
        last = now
        return true
    }

    private fun feel(type: HapticFeedbackType) { if (on()) haptic.performHapticFeedback(type) }

    fun select() { if (!gate()) return; Fx.play(Fx.Sound.Tick, 0.5f); feel(HapticFeedbackType.SegmentTick) }
    fun toggle(nowOn: Boolean = true) {
        if (!gate()) return
        Fx.play(Fx.Sound.Tap, if (nowOn) 0.6f else 0.45f)
        feel(if (nowOn) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
    }
    fun success() { if (!gate()) return; Fx.play(Fx.Sound.Done, 0.8f); feel(HapticFeedbackType.Confirm) }
    fun error() { last = 0L; if (!gate()) return; Fx.play(Fx.Sound.Error, 0.7f); feel(HapticFeedbackType.Reject) }
    fun undo() { if (!gate()) return; Fx.play(Fx.Sound.Undo, 0.5f); feel(HapticFeedbackType.ContextClick) }
    fun open() { Fx.play(Fx.Sound.Open, 0.5f); feel(HapticFeedbackType.ContextClick) }
    fun close() { Fx.play(Fx.Sound.Close, 0.45f) }
    fun whoosh() = Fx.play(Fx.Sound.Whoosh, 0.5f)

    private var sliderBucket = Int.MIN_VALUE
    private var sliderAt = 0L

    /**
     * A slider moved to [fraction] (0..1). Gives a light tick at every 5 percent, a firmer one (with a tiny sound) when
     * the value crosses [notch] (for example the neutral middle), and nothing when the finger lingers in one place.
     */
    fun slide(fraction: Float, notch: Float? = null) {
        val bucket = (fraction * 20f).toInt()
        if (bucket == sliderBucket) return
        val had = sliderBucket
        sliderBucket = bucket
        if (had == Int.MIN_VALUE) return // the first reading after grabbing the slider
        val now = SystemClock.uptimeMillis()
        if (now - sliderAt < 45) return
        sliderAt = now
        val nb = notch?.let { (it * 20f).toInt() }
        val crossed = nb != null && ((had >= nb) != (bucket >= nb))
        if (crossed) { Fx.play(Fx.Sound.Detent, 0.5f); feel(HapticFeedbackType.GestureThresholdActivate) }
        else feel(HapticFeedbackType.SegmentFrequentTick)
    }
    /** Call when a slider is grabbed, so the first move does not tick by itself. */
    fun slideStart() { sliderBucket = Int.MIN_VALUE }

    private companion object {
        const val GAP_MS = 70L
        @Volatile var last = 0L
    }
}

@Composable
fun rememberFeedback(): Feedback {
    val haptic = LocalHapticFeedback.current
    return androidx.compose.runtime.remember(haptic) { Feedback(haptic) }
}
