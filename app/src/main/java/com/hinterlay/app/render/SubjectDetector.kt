package com.hinterlay.app.render

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await

/**
 * Finds the main subject(s) on-device with ML Kit Subject Segmentation and returns a
 * same-size bitmap containing only the subject (transparent elsewhere). The photo never
 * leaves the phone.
 */
class SubjectDetector {

    private val client = SubjectSegmentation.getClient(
        SubjectSegmenterOptions.Builder()
            .enableForegroundBitmap()
            .build()
    )

    /** @return the subject cut-out, or null when no clear subject is found. */
    suspend fun extract(bitmap: Bitmap, onStatus: (String) -> Unit): Bitmap? {
        var lastError: Exception? = null
        // First run on a fresh install may need Play services to finish downloading the model.
        for (attempt in 0 until 20) {
            try {
                val result = client.process(InputImage.fromBitmap(bitmap, 0)).await()
                val fg = result.foregroundBitmap ?: return null
                return if (hasSubject(fg)) fg else null
            } catch (e: MlKitException) {
                if (e.errorCode != MlKitException.UNAVAILABLE) throw e
                lastError = e
                onStatus("Downloading the subject model…")
                delay(2_000)
            }
        }
        throw lastError ?: IllegalStateException("Subject model unavailable")
    }

    fun close() = client.close()

    /** Treat masks covering under 1% of the frame as "no subject". */
    private fun hasSubject(fg: Bitmap): Boolean {
        val steps = 64
        var opaque = 0
        for (iy in 0 until steps) {
            val y = (iy + 0.5f) / steps * fg.height
            for (ix in 0 until steps) {
                val x = (ix + 0.5f) / steps * fg.width
                if (Color.alpha(fg.getPixel(x.toInt(), y.toInt())) > 128) opaque++
            }
        }
        return opaque > steps * steps / 100
    }
}
