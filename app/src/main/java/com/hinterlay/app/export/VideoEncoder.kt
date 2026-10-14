package com.hinterlay.app.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/**
 * Turns a series of drawn frames into an H.264 .mp4. Each frame is drawn by the caller onto a canvas, converted to
 * YUV and handed to the phone's video encoder with an exact timestamp, so the speed is right however long drawing takes.
 */
object VideoEncoder {

    /** Compressed audio ready to go into the MP4: the codec's format plus every encoded frame with its timestamp. */
    class AacTrack(val format: MediaFormat, val frames: List<Pair<ByteArray, MediaCodec.BufferInfo>>)

    /** Compresses 44.1 kHz stereo 16-bit [pcm] to AAC (128 kbps). Returns null if the phone can't do it. */
    fun encodeAac(pcm: ShortArray, sampleRate: Int = 44100): AacTrack? {
        var codec: MediaCodec? = null
        return try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec = c
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            val bytes = java.nio.ByteBuffer.allocate(pcm.size * 2).order(java.nio.ByteOrder.nativeOrder())
            bytes.asShortBuffer().put(pcm)
            val total = pcm.size * 2
            var pos = 0
            var eos = false
            var outFormat: MediaFormat? = null
            val out = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>()
            val info = MediaCodec.BufferInfo()
            while (true) {
                if (!eos) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = c.getInputBuffer(i)!!
                        buf.clear()
                        val n = minOf(buf.remaining(), total - pos)
                        val pts = (pos / 4L) * 1_000_000L / sampleRate // 4 bytes per stereo frame
                        if (n <= 0) { c.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos = true }
                        else {
                            bytes.position(pos); bytes.limit(pos + n)
                            buf.put(bytes); bytes.clear()
                            c.queueInputBuffer(i, 0, n, pts, 0)
                            pos += n
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) outFormat = c.outputFormat
                else if (o >= 0) {
                    val buf = c.getOutputBuffer(o)!!
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        val data = ByteArray(info.size)
                        buf.get(data)
                        out.add(data to MediaCodec.BufferInfo().also { it.set(0, data.size, info.presentationTimeUs, info.flags) })
                    }
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            outFormat?.let { AacTrack(it, out) }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
    }

    /**
     * Encodes [frameCount] frames at [fps]. [draw] is called once per frame, in order, with a canvas the size of the video.
     * [onProgress] gets 0..1. Returns true when the file is complete; on failure the file is deleted.
     */
    fun encode(
        out: File, width: Int, height: Int, fps: Int, frameCount: Int,
        draw: (index: Int, canvas: Canvas) -> Unit, onProgress: (Float) -> Unit,
        audio: AacTrack? = null,
    ): Boolean {
        val w = width and 1.inv() // encoders need even sizes
        val h = height and 1.inv()
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        return try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, (w.toLong() * h * fps * 0.14).toInt().coerceIn(2_000_000, 18_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also {
                it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                it.start()
            }
            muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val px = IntArray(w * h)
            val info = MediaCodec.BufferInfo()
            var sent = 0
            var eosSent = false
            var track = -1
            val c = codec!!
            val mx = muxer!!
            loop@ while (true) {
                if (!eosSent) {
                    val idx = c.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val pts = sent * 1_000_000L / fps
                        if (sent >= frameCount) {
                            c.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosSent = true
                        } else {
                            draw(sent, canvas)
                            bmp.getPixels(px, 0, w, 0, 0, w, h)
                            fillYuv(c.getInputImage(idx) ?: throw IllegalStateException("No input image"), px, w, h)
                            c.queueInputBuffer(idx, 0, w * h * 3 / 2, pts, 0)
                            sent++
                            onProgress(sent / frameCount.toFloat() * 0.96f)
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 10_000)
                when {
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = mx.addTrack(c.outputFormat)
                        val audioTrack = audio?.let { mx.addTrack(it.format) } ?: -1
                        mx.start()
                        muxerStarted = true
                        // The music is short, so it goes in right away; the muxer lays the two tracks out in time order.
                        if (audio != null && audioTrack >= 0) {
                            for ((data, bi) in audio.frames) mx.writeSampleData(audioTrack, java.nio.ByteBuffer.wrap(data), bi)
                        }
                    }
                    oi >= 0 -> {
                        val buf = c.getOutputBuffer(oi)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (buf != null && info.size > 0 && muxerStarted) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            mx.writeSampleData(track, buf, info)
                        }
                        c.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break@loop
                    }
                }
            }
            bmp.recycle()
            onProgress(1f)
            true
        } catch (e: Exception) {
            out.delete()
            false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    /** ARGB pixels to the encoder's YUV 4:2:0 planes (BT.601, video range), whatever their strides are. */
    private fun fillYuv(img: Image, px: IntArray, w: Int, h: Int) {
        val yp = img.planes[0]
        val up = img.planes[1]
        val vp = img.planes[2]
        val yb = yp.buffer
        val ub = up.buffer
        val vb = vp.buffer
        for (y in 0 until h) {
            val row = y * yp.rowStride
            val src = y * w
            for (x in 0 until w) {
                val c = px[src + x]
                val r = (c shr 16) and 255
                val g = (c shr 8) and 255
                val b = c and 255
                yb.put(row + x * yp.pixelStride, (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte())
            }
        }
        for (y in 0 until h / 2) {
            val urow = y * up.rowStride
            val vrow = y * vp.rowStride
            for (x in 0 until w / 2) {
                // Average of the 2x2 block, so edges stay clean.
                val i = (2 * y) * w + 2 * x
                val c0 = px[i]; val c1 = px[i + 1]; val c2 = px[i + w]; val c3 = px[i + w + 1]
                val r = (((c0 shr 16) and 255) + ((c1 shr 16) and 255) + ((c2 shr 16) and 255) + ((c3 shr 16) and 255)) shr 2
                val g = (((c0 shr 8) and 255) + ((c1 shr 8) and 255) + ((c2 shr 8) and 255) + ((c3 shr 8) and 255)) shr 2
                val b = ((c0 and 255) + (c1 and 255) + (c2 and 255) + (c3 and 255)) shr 2
                ub.put(urow + x * up.pixelStride, (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte())
                vb.put(vrow + x * vp.pixelStride, (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte())
            }
        }
    }
}
