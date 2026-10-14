package com.hinterlay.app.cloud

import android.content.Context
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.hinterlay.app.BuildConfig
import com.hinterlay.app.data.HistoryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** What happened to a share request. */
enum class ShareResult {
    /** Uploaded for review. */
    SENT,
    /** Kept on this phone because sharing is not switched on yet; it is sent once it is. */
    SAVED,
    FAILED,
}

/**
 * "Share with the world": a creation the user chooses to send for review. Only that one exported image and its text style
 * leave the phone, only after the user ticks the consent boxes, and only when [live] is true. Otherwise the entry waits in an
 * outbox on the phone (nothing is uploaded).
 */
object Community {

    /** True when uploads are really switched on (Firebase configured and COMMUNITY_SHARING=true in gradle.properties). */
    val live: Boolean get() = Cloud.available && BuildConfig.COMMUNITY_SHARING

    private fun outbox(context: Context) = File(context.filesDir, "community_outbox").apply { mkdirs() }

    fun pending(context: Context): Int = outbox(context).listFiles()?.count { it.isDirectory } ?: 0

    suspend fun submit(
        context: Context, item: HistoryItem, caption: String, handle: String, contactOk: Boolean, email: String,
    ): ShareResult = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val dir = File(outbox(context), id).apply { mkdirs() }
        try {
            item.result.copyTo(File(dir, "image.jpg"), overwrite = true)
            val metaFile = File(item.dir, "meta.json")
            val meta = if (metaFile.exists()) metaFile.readText().take(30_000) else ""
            File(dir, "entry.json").writeText(
                JSONObject()
                    .put("caption", caption.take(200)).put("handle", handle.take(40))
                    .put("contactOk", contactOk).put("email", if (contactOk) email.take(120) else "")
                    .put("meta", meta).toString()
            )
        } catch (e: Exception) {
            dir.deleteRecursively()
            return@withContext ShareResult.FAILED
        }
        if (!live) return@withContext ShareResult.SAVED
        if (sendOne(dir)) ShareResult.SENT else ShareResult.SAVED
    }

    /** Sends everything waiting in the outbox. Call when sharing is live and the phone is online. */
    suspend fun flush(context: Context) = withContext(Dispatchers.IO) {
        if (!live) return@withContext
        outbox(context).listFiles()?.filter { it.isDirectory }?.forEach { sendOne(it) }
    }

    private suspend fun sendOne(dir: File): Boolean = try {
        val entry = JSONObject(File(dir, "entry.json").readText())
        val auth = FirebaseAuth.getInstance()
        val uid = auth.currentUser?.uid ?: auth.signInAnonymously().await().user!!.uid
        val path = "submissions/$uid/${dir.name}.jpg"
        FirebaseStorage.getInstance().reference.child(path).putFile(android.net.Uri.fromFile(File(dir, "image.jpg"))).await()
        val doc = hashMapOf<String, Any>(
            "uid" to uid, "status" to "pending", "createdAt" to Timestamp.now(),
            "caption" to entry.optString("caption"), "handle" to entry.optString("handle"),
            "contactOk" to entry.optBoolean("contactOk"), "meta" to entry.optString("meta"),
            "imagePath" to path, "appVersion" to BuildConfig.VERSION_NAME,
        )
        if (entry.optBoolean("contactOk")) doc["email"] = entry.optString("email")
        FirebaseFirestore.getInstance().collection("submissions").document(dir.name).set(doc).await()
        dir.deleteRecursively()
        true
    } catch (e: Exception) {
        false
    }
}
