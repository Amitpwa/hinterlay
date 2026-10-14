package com.hinterlay.app.sticker

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Read-only provider WhatsApp queries to import the sticker pack. Column names and URI layout
 * are WhatsApp's published Sticker API contract.
 */
class StickerContentProvider : ContentProvider() {

    private lateinit var matcher: UriMatcher

    override fun onCreate(): Boolean {
        val auth = StickerStore.authority(context!!)
        matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(auth, "metadata", METADATA)
            addURI(auth, "metadata/*", METADATA_SINGLE)
            addURI(auth, "stickers/*", STICKERS)
            addURI(auth, "stickers_asset/*/*", ASSET)
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor? =
        when (matcher.match(uri)) {
            METADATA, METADATA_SINGLE -> packCursor()
            STICKERS -> stickersCursor()
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }

    private fun packCursor(): Cursor {
        val ctx = context!!
        val cursor = MatrixCursor(
            arrayOf(
                "sticker_pack_identifier", "sticker_pack_name", "sticker_pack_publisher", "sticker_pack_icon",
                "android_play_store_link", "ios_app_download_link", "sticker_pack_publisher_email",
                "sticker_pack_publisher_website", "sticker_pack_privacy_policy_website",
                "sticker_pack_license_agreement_website", "image_data_version",
                "whatsapp_will_not_cache_stickers", "animated_sticker_pack",
            )
        )
        cursor.addRow(
            arrayOf<Any>(
                StickerStore.PACK_ID, StickerStore.PACK_NAME, "Hinterlay", StickerStore.TRAY_FILE,
                "", "", "", "", "", "", StickerStore.dataVersion(ctx), 0, 0,
            )
        )
        return cursor
    }

    private fun stickersCursor(): Cursor {
        val cursor = MatrixCursor(arrayOf("sticker_file_name", "sticker_emoji", "sticker_accessibility_text"))
        StickerStore.list(context!!).forEach { cursor.addRow(arrayOf<Any>(it.name, "😊", "Hinterlay sticker")) }
        return cursor
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        if (matcher.match(uri) != ASSET) return null
        val name = uri.pathSegments.last()
        val file = File(StickerStore.dir(context!!), File(name).name) // File(name).name blocks path traversal
        if (!file.exists()) throw FileNotFoundException(name)
        return AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun getType(uri: Uri): String? {
        val auth = StickerStore.authority(context!!)
        return when (matcher.match(uri)) {
            METADATA -> "vnd.android.cursor.dir/vnd.$auth.metadata"
            METADATA_SINGLE -> "vnd.android.cursor.item/vnd.$auth.metadata"
            STICKERS -> "vnd.android.cursor.dir/vnd.$auth.stickers"
            ASSET -> if (uri.lastPathSegment?.endsWith(".png") == true) "image/png" else "image/webp"
            else -> null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("read-only")
    override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int = throw UnsupportedOperationException("read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?): Int =
        throw UnsupportedOperationException("read-only")

    private companion object {
        const val METADATA = 1
        const val METADATA_SINGLE = 2
        const val STICKERS = 3
        const val ASSET = 4
    }
}
