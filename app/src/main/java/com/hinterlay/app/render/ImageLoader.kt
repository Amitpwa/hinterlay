package com.hinterlay.app.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

object ImageLoader {
    /** Longest side we edit at. Keeps memory safe on mid-range phones and exports stay sharp. */
    const val MAX_SIDE = 2560

    suspend fun decode(context: Context, uri: Uri): Bitmap = withContext(Dispatchers.IO) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // ML Kit + Canvas need a software bitmap
            val w = info.size.width
            val h = info.size.height
            val scale = MAX_SIDE.toFloat() / max(w, h)
            if (scale < 1f) {
                decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
            }
        } // ImageDecoder applies EXIF rotation automatically.
        if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
        else bitmap.copy(Bitmap.Config.ARGB_8888, false).also { bitmap.recycle() }
    }
}
