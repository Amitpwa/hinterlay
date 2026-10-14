package com.hinterlay.app.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Music for videos. Two sources:
 *  - built-in tracks, made here by a small synthesiser (so they are original: no licence to worry about). Each is exactly
 *    two bars long for the clip's length, with its tail wrapped round to the start, so a looping video loops without a click;
 *  - a song the user picks from their phone, decoded and cut to the clip's length.
 * Everything is 44.1 kHz stereo, 16-bit, interleaved.
 */
object Music {

    const val RATE = 44100
    const val CHANNELS = 2

    enum class Track(val label: String) { GLOW("Glow"), PULSE("Pulse"), DRIFT("Drift"), RETRO("Retro") }

    // ---- Built-in tracks ----------------------------------------------------------------------------------

    private fun hz(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)

    private class Mix(val frames: Int) {
        val l = FloatArray(frames)
        val r = FloatArray(frames)
        /** Adds a sample at [i], wrapping past the end so tails continue into the start (a seamless loop). */
        fun add(i: Int, left: Float, right: Float) {
            val k = ((i % frames) + frames) % frames
            l[k] += left; r[k] += right
        }
    }

    /** One note: [wave] gives the waveform for a phase in cycles; the envelope is a quick attack and an exponential or sustained tail. */
    private fun note(
        m: Mix, startSec: Double, lenSec: Double, freq: Double, gain: Float, pan: Float,
        attack: Double = 0.01, release: Double = 0.2, decay: Double = 0.0, wave: (Double) -> Double,
    ) {
        val start = (startSec * RATE).toInt()
        val total = ((lenSec + release) * RATE).toInt()
        val lg = gain * min(1f, 1f - pan * 0.8f) * 0.7f
        val rg = gain * min(1f, 1f + pan * 0.8f) * 0.7f
        for (n in 0 until total) {
            val t = n.toDouble() / RATE
            var env = if (t < attack) t / attack else 1.0
            if (decay > 0.0) env *= exp(-t / decay)
            if (t > lenSec) env *= max(0.0, 1.0 - (t - lenSec) / release)
            val s = (wave(freq * t) * env).toFloat()
            m.add(start + n, s * lg, s * rg)
        }
    }

    private fun sine(p: Double) = sin(2 * PI * p)
    private fun tri(p: Double): Double { val x = p - kotlin.math.floor(p + 0.5); return 4.0 * abs(x) - 1.0 }
    private fun saw(p: Double) = 2.0 * (p - kotlin.math.floor(p + 0.5))
    private fun square(p: Double) = if (p - kotlin.math.floor(p) < 0.5) 1.0 else -1.0

    private var noiseSeed = 1
    private fun noise(): Double { noiseSeed = noiseSeed * 1664525 + 1013904223; return ((noiseSeed ushr 8) and 0xFFFF) / 32768.0 - 1.0 }

    private fun kick(m: Mix, at: Double, gain: Float) {
        val start = (at * RATE).toInt()
        var phase = 0.0
        for (n in 0 until (0.22 * RATE).toInt()) {
            val t = n.toDouble() / RATE
            val f = 45.0 + 95.0 * exp(-t * 28.0)
            phase += f / RATE
            val s = (sin(2 * PI * phase) * exp(-t * 11.0)).toFloat() * gain
            m.add(start + n, s, s)
        }
    }

    private fun hat(m: Mix, at: Double, gain: Float, len: Double = 0.035) {
        val start = (at * RATE).toInt()
        var prev = 0.0
        for (n in 0 until (len * RATE).toInt()) {
            val t = n.toDouble() / RATE
            val x = noise()
            val hp = x - prev * 0.9 // crude high-pass: keeps the fizz
            prev = x
            val s = (hp * exp(-t * 90.0)).toFloat() * gain
            m.add(start + n, s * 0.9f, s * 1.0f)
        }
    }

    private fun clap(m: Mix, at: Double, gain: Float) {
        val start = (at * RATE).toInt()
        for (n in 0 until (0.14 * RATE).toInt()) {
            val t = n.toDouble() / RATE
            val s = ((noise() * 0.8 + sin(2 * PI * 190.0 * t) * 0.3) * exp(-t * 28.0)).toFloat() * gain
            m.add(start + n, s, s)
        }
    }

    /** A feedback echo that wraps around the loop, for space. */
    private fun echo(m: Mix, delaySec: Double, feedback: Float, wet: Float) {
        val d = (delaySec * RATE).toInt()
        val el = FloatArray(m.frames)
        val er = FloatArray(m.frames)
        for (pass in 0 until 3) {
            for (i in 0 until m.frames) {
                val src = (i - d + m.frames * 4) % m.frames
                // the right side echoes the left a touch later, so it widens
                el[i] = (m.l[src] + el[src] * feedback) * wet
                er[i] = (m.r[src] + er[src] * feedback) * wet
            }
        }
        for (i in 0 until m.frames) { m.l[i] += el[i]; m.r[i] += er[i] }
    }

    private fun finish(m: Mix, peak: Float = 0.72f): ShortArray {
        var mx = 0f
        for (i in 0 until m.frames) mx = max(mx, max(abs(m.l[i]), abs(m.r[i])))
        val k = if (mx > 0f) peak / mx else 1f
        val out = ShortArray(m.frames * 2)
        for (i in 0 until m.frames) {
            // A soft knee so loud bits round off instead of crackling.
            out[2 * i] = (tanh((m.l[i] * k * 1.1f).toDouble()) * 0.9 * 32767).toInt().toShort()
            out[2 * i + 1] = (tanh((m.r[i] * k * 1.1f).toDouble()) * 0.9 * 32767).toInt().toShort()
        }
        return out
    }

    /**
     * [track] as exactly [seconds] of stereo PCM. The music is eight beats (two bars of four), so the tempo follows the
     * length: 4.5 seconds is about 107 BPM. The first bar sits on A minor, the second on F, which resolves back into A minor
     * when the loop restarts.
     */
    fun synth(track: Track, seconds: Double): ShortArray {
        noiseSeed = 7
        val frames = (seconds * RATE).toInt()
        val m = Mix(frames)
        val beat = seconds / 8.0
        val bar = beat * 4
        // MIDI: A minor then F major.
        val chords = listOf(intArrayOf(57, 60, 64), intArrayOf(53, 57, 60))
        val roots = intArrayOf(45, 41)
        when (track) {
            Track.GLOW -> {
                // Warm pads with a slow attack, a soft pluck line on top, and a gentle echo.
                for (b in 0 until 2) {
                    val t0 = b * bar
                    for ((i, nt) in chords[b].withIndex()) {
                        val f = hz(nt)
                        note(m, t0, bar, f, 0.20f, (i - 1) * 0.35f, attack = 0.35, release = 0.6) { p -> 0.55 * tri(p) + 0.35 * tri(p * 1.004) + 0.2 * sine(p * 0.5) }
                    }
                    note(m, t0, bar, hz(roots[b]), 0.34f, 0f, attack = 0.08, release = 0.5) { p -> sine(p) }
                    val arp = intArrayOf(chords[b][0] + 12, chords[b][2] + 12, chords[b][1] + 24, chords[b][2] + 12)
                    for (k in 0 until 8) note(m, t0 + k * beat * 0.5, beat * 0.4, hz(arp[k % 4]), 0.20f, if (k % 2 == 0) -0.5f else 0.5f, release = 0.15, decay = 0.22) { p -> sine(p) + 0.3 * sine(p * 2) }
                }
                echo(m, beat * 0.75, 0.38f, 0.45f)
            }
            Track.PULSE -> {
                for (b in 0 until 8) {
                    kick(m, b * beat, 0.95f)
                    hat(m, b * beat + beat * 0.5, 0.20f)
                    if (b % 2 == 1) clap(m, b * beat, 0.30f)
                }
                for (b in 0 until 2) {
                    val t0 = b * bar
                    for (k in 0 until 8) note(m, t0 + k * beat * 0.5, beat * 0.42, hz(roots[b]), 0.40f, 0f, attack = 0.005, release = 0.06, decay = 0.25) { p -> saw(p) * 0.6 + sine(p) * 0.6 }
                    val arp = intArrayOf(chords[b][0] + 12, chords[b][1] + 12, chords[b][2] + 12, chords[b][1] + 24)
                    for (k in 0 until 16) note(m, t0 + k * beat * 0.25, beat * 0.2, hz(arp[k % 4]), 0.16f, if (k % 2 == 0) -0.6f else 0.6f, attack = 0.003, release = 0.05, decay = 0.1) { p -> saw(p) * 0.5 }
                    for ((i, nt) in chords[b].withIndex()) note(m, t0, bar, hz(nt), 0.10f, (i - 1) * 0.5f, attack = 0.1, release = 0.3) { p -> saw(p) * 0.4 + tri(p) * 0.4 }
                }
                echo(m, beat * 0.75, 0.30f, 0.30f)
            }
            Track.DRIFT -> {
                // Sparse bell notes on a pentatonic scale over a low drone, with a long echo.
                val scale = intArrayOf(69, 72, 74, 76, 79, 81)
                val seq = intArrayOf(0, 2, 4, 3, 1, 4, 5, 2)
                for (k in 0 until 8) {
                    val f = hz(scale[seq[k]])
                    val t0 = k * beat
                    for ((j, mult) in doubleArrayOf(1.0, 2.76, 5.4).withIndex()) {
                        note(m, t0, beat * 0.2, f * mult, 0.22f / (1 + j * 1.6f), if (k % 2 == 0) -0.4f else 0.4f, attack = 0.002, release = 0.1, decay = 0.55 / (1 + j)) { p -> sine(p) }
                    }
                }
                for (b in 0 until 2) {
                    note(m, b * bar, bar, hz(roots[b] - 12), 0.38f, 0f, attack = 0.5, release = 0.8) { p -> sine(p) + 0.2 * sine(p * 2) }
                    for (nt in chords[b]) note(m, b * bar, bar, hz(nt), 0.08f, 0f, attack = 0.6, release = 0.9) { p -> sine(p) }
                }
                echo(m, beat * 1.5, 0.50f, 0.55f)
            }
            Track.RETRO -> {
                for (b in 0 until 2) {
                    val t0 = b * bar
                    val arp = intArrayOf(chords[b][0] + 12, chords[b][1] + 12, chords[b][2] + 12, chords[b][1] + 12)
                    for (k in 0 until 16) note(m, t0 + k * beat * 0.25, beat * 0.22, hz(arp[k % 4]), 0.22f, if (k % 2 == 0) -0.3f else 0.3f, attack = 0.002, release = 0.03, decay = 0.12) { p -> square(p) * 0.45 }
                    for (k in 0 until 4) note(m, t0 + k * beat, beat * 0.9, hz(roots[b]), 0.34f, 0f, attack = 0.004, release = 0.04, decay = 0.4) { p -> square(p * 0.5) * 0.4 + tri(p) * 0.5 }
                    kick(m, t0, 0.6f); kick(m, t0 + beat * 2, 0.6f)
                    hat(m, t0 + beat * 1, 0.16f, 0.05); hat(m, t0 + beat * 3, 0.16f, 0.05)
                }
                echo(m, beat * 0.5, 0.25f, 0.25f)
            }
        }
        return finish(m)
    }

    // ---- A song from the phone ----------------------------------------------------------------------------

    /** Length of the audio at [uri] in milliseconds, or 0 when it can't be read. */
    fun durationMs(context: Context, uri: Uri): Long = runCatching {
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally { r.release() }
    }.getOrDefault(0L)

    /**
     * Decodes [lengthMs] of the audio at [uri] starting at [offsetMs], as 44.1 kHz stereo PCM. Returns null if the
     * file can't be decoded. If the song is shorter than asked, the rest is silence.
     */
    fun decode(context: Context, uri: Uri, offsetMs: Long, lengthMs: Long): ShortArray? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            ex.setDataSource(context, uri, null)
            val idx = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            ex.selectTrack(idx)
            val fmt = ex.getTrackFormat(idx)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return null
            ex.seekTo(offsetMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec = MediaCodec.createDecoderByType(mime).also { it.configure(fmt, null, null, 0); it.start() }
            val c = codec!!
            var srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var srcCh = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val want = (lengthMs * srcRate / 1000).toInt() + srcRate // a little extra for resampling
            val pcm = ArrayList<Short>(want * srcCh)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val skipUs = offsetMs * 1000
            while (!outputDone) {
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = c.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { c.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = c.outputFormat
                    srcRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); srcCh = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    val out = c.getOutputBuffer(o)!!
                    if (info.size > 0 && info.presentationTimeUs >= skipUs - 50_000) {
                        out.position(info.offset); out.limit(info.offset + info.size)
                        val sb = out.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                        while (sb.hasRemaining() && pcm.size < want * srcCh) pcm.add(sb.get())
                    }
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || pcm.size >= want * srcCh) outputDone = true
                }
            }
            // To stereo at 44.1 kHz (linear resampling is plenty for a short clip under video).
            val srcFrames = pcm.size / max(1, srcCh)
            val outFrames = (lengthMs * RATE / 1000).toInt()
            val out = ShortArray(outFrames * 2)
            for (i in 0 until outFrames) {
                val sp = i.toDouble() * srcRate / RATE
                val i0 = sp.toInt()
                if (i0 + 1 >= srcFrames) break
                val f = (sp - i0).toFloat()
                for (chn in 0 until 2) {
                    val sc = min(chn, srcCh - 1)
                    val a = pcm[i0 * srcCh + sc].toFloat()
                    val b = pcm[(i0 + 1) * srcCh + sc].toFloat()
                    out[2 * i + chn] = (a + (b - a) * f).toInt().toShort()
                }
            }
            out
        } catch (e: Exception) {
            null
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }; runCatching { ex.release() }
        }
    }

    /** Fades the start and end so the music never starts or stops with a click. */
    fun fade(pcm: ShortArray, fadeInMs: Int, fadeOutMs: Int) {
        val frames = pcm.size / 2
        val fi = fadeInMs * RATE / 1000
        val fo = fadeOutMs * RATE / 1000
        for (i in 0 until frames) {
            var g = 1f
            if (i < fi) g = i / fi.toFloat()
            if (i > frames - fo) g = min(g, (frames - i) / fo.toFloat())
            if (g < 1f) { pcm[2 * i] = (pcm[2 * i] * g).toInt().toShort(); pcm[2 * i + 1] = (pcm[2 * i + 1] * g).toInt().toShort() }
        }
    }
}
