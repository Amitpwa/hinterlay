package com.hinterlay.app.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.hinterlay.app.BuildConfig
import java.io.File
import java.io.FileOutputStream

object Exporter {

    private const val ALBUM = "Hinterlay"

    fun writeJpeg(context: Context, bitmap: Bitmap): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val dayAgo = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        dir.listFiles()?.filter { it.lastModified() < dayAgo }?.forEach { it.delete() }
        val file = File(dir, "hinterlay_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        return file
    }

    /** Copies the export into Pictures/Hinterlay. No permission needed on Android 10+. */
    fun saveToGallery(context: Context, file: File): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException("No output stream")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            false
        }
    }

    private fun contentUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun shareIntent(context: Context, file: File): Intent {
        val uri = contentUri(context, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share your photo")
    }

    /** A new, empty .mp4 in the cache for the video encoder to fill. */
    fun newVideoFile(context: Context): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        return File(dir, "hinterlay_${System.currentTimeMillis()}.mp4")
    }

    /** Copies a finished video into Movies/Hinterlay. No permission needed on Android 10+. */
    fun saveVideoToGallery(context: Context, file: File): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$ALBUM")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException("No output stream")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            false
        }
    }

    fun shareVideoIntent(context: Context, file: File): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, contentUri(context, file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share your 3D photo")
    }

    /**
     * Opens Instagram's story composer directly when a Facebook App ID is configured
     * (Meta requires it). Otherwise falls back to the system share sheet, where
     * Instagram/WhatsApp/Snapchat still offer their own "Story" targets.
     */
    fun storyIntent(context: Context, file: File, video: Boolean = false): Intent {
        val appId = BuildConfig.FACEBOOK_APP_ID
        if (appId.isNotBlank()) {
            val uri = contentUri(context, file)
            val story = Intent("com.instagram.share.ADD_TO_STORY").apply {
                putExtra("source_application", appId)
                setDataAndType(uri, if (video) "video/mp4" else "image/jpeg")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (story.resolveActivity(context.packageManager) != null) {
                context.grantUriPermission("com.instagram.android", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                return story
            }
        }
        return if (video) shareVideoIntent(context, file) else shareIntent(context, file)
    }

    /**
     * Opens WhatsApp's share screen with the photo or video. WhatsApp has no public way to post a Status directly, so the
     * user taps "My status" at the top of that screen. Returns null when WhatsApp isn't installed.
     */
    fun whatsappIntent(context: Context, file: File, video: Boolean = false): Intent? {
        val uri = contentUri(context, file)
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = if (video) "video/mp4" else "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                setPackage(pkg)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (send.resolveActivity(context.packageManager) != null) return send
        }
        return null
    }
}
