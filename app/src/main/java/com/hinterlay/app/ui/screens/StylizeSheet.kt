package com.hinterlay.app.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hinterlay.app.render.ImageLoader
import com.hinterlay.app.render.PhotoFx
import com.hinterlay.app.render.SubjectDetector
import com.hinterlay.app.render.Toonify
import com.hinterlay.app.sticker.StickerStore
import com.hinterlay.app.ui.components.Chip
import com.hinterlay.app.ui.components.PrimaryButton
import com.hinterlay.app.ui.components.SecondaryButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pick any photo, choose a look (cartoon, comic, pop art, pencil) and get a sticker of its subject for WhatsApp. The subject is
 * found and the drawing is made on the phone; nothing is downloaded or uploaded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StylizeSheet(onDismiss: () -> Unit, onAdded: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var look by remember { mutableStateOf(Toonify.Look.CARTOON) }
    var toon by remember { mutableStateOf<Bitmap?>(null) }
    var subject by remember { mutableStateOf<Bitmap?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var added by remember { mutableStateOf(false) }
    var word by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            toon = null; subject = null; preview = null; added = false; message = null
            photo = runCatching { ImageLoader.decode(context, uri) }.getOrNull()
            if (photo == null) message = "That photo could not be opened."
        }
    }

    fun make() {
        val src = photo ?: return
        scope.launch {
            busy = true; message = null; toon = null; subject = null; preview = null; added = false
            val made = withContext(Dispatchers.Default) {
                val k = minOf(1f, 640f / maxOf(src.width, src.height))
                val small = if (k < 1f) Bitmap.createScaledBitmap(src, (src.width * k).toInt().coerceAtLeast(1), (src.height * k).toInt().coerceAtLeast(1), true) else src
                val detector = SubjectDetector()
                val fg = try { detector.extract(small) { } } catch (e: Exception) { null } finally { detector.close() }
                val drawing = Toonify.apply(small, look, 640)
                Triple(drawing, fg, fg?.let { PhotoFx.maskWith(drawing, it) } ?: drawing)
            }
            toon = made.first; subject = made.second; preview = made.third
            if (made.second == null) message = "No clear subject was found, so the whole picture is used."
            busy = false
        }
    }

    // One tap: three stickers of the same photo in different looks, enough for WhatsApp's 3-sticker minimum.
    fun makePack() {
        val src = photo ?: return
        scope.launch {
            busy = true; message = null; added = false
            val count = withContext(Dispatchers.Default) {
                val k = minOf(1f, 640f / maxOf(src.width, src.height))
                val small = if (k < 1f) Bitmap.createScaledBitmap(src, (src.width * k).toInt().coerceAtLeast(1), (src.height * k).toInt().coerceAtLeast(1), true) else src
                val detector = SubjectDetector()
                val fg = try { detector.extract(small) { } } catch (e: Exception) { null } finally { detector.close() }
                listOf(Toonify.Look.CARTOON, Toonify.Look.COMIC, Toonify.Look.POP).count { l ->
                    StickerStore.addToon(context, Toonify.apply(small, l, 640), fg, "")
                }
            }
            if (count > 0) { onAdded(); message = "Added $count stickers (Cartoon, Comic, Pop art). Open your sticker pack to see them." }
            else message = "Your pack is full. Remove a sticker first."
            busy = false
        }
    }

    // Debug builds only: a photo pushed to cache/exports/stylize_test.jpg is used on open, so this can be tried without the picker.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!com.hinterlay.app.BuildConfig.DEBUG) return@LaunchedEffect
        val f = java.io.File(context.cacheDir, "exports/stylize_test.jpg")
        if (f.exists()) {
            photo = android.graphics.BitmapFactory.decodeFile(f.path)
            // Also write every look to cache/exports/toon_<look>.png so they can be compared at full size.
            withContext(Dispatchers.Default) {
                Toonify.Look.entries.forEach { l ->
                    val t0 = System.currentTimeMillis()
                    Toonify.apply(photo!!, l, 640).let { b -> java.io.FileOutputStream(java.io.File(context.cacheDir, "exports/toon_${l.name}.png")).use { o -> b.compress(Bitmap.CompressFormat.PNG, 100, o) } }
                    android.util.Log.d("Toonify", "${l.name} took ${System.currentTimeMillis() - t0} ms")
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp,
    ) {
        com.hinterlay.app.ui.components.SheetSound()
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Cartoon sticker", style = MaterialTheme.typography.titleLarge)
            Text(
                "Turn any photo into a cartoon, comic, pop-art or pencil sticker for WhatsApp. It works on people, pets and things, and runs on your phone.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (photo == null) {
                PrimaryButton(
                    "Choose a photo", { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    icon = Icons.Outlined.AddPhotoAlternate, modifier = Modifier.fillMaxWidth().height(52.dp),
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(150.dp, 190.dp).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        Image(photo!!.asImageBitmap(), "Your photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(190.dp))
                    }
                    Box(
                        Modifier.size(150.dp, 190.dp).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        val r = preview
                        if (r != null) Image(r.asImageBitmap(), "Sticker preview", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(190.dp).padding(6.dp))
                        else Text(if (busy) "Drawing…" else "Your sticker", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Toonify.Look.entries.forEach { l ->
                        Chip(look == l, { if (!busy) { look = l; toon = null; subject = null; preview = null; added = false; message = null } }, { Text(l.label) })
                    }
                }
                Text(look.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                if (toon == null) {
                    PrimaryButton("Make it", { make() }, enabled = !busy, icon = Icons.Outlined.AutoAwesome, modifier = Modifier.fillMaxWidth().height(52.dp))
                } else {
                    androidx.compose.material3.OutlinedTextField(
                        word, { word = it.take(24); added = false }, label = { Text("Add a word (optional)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    PrimaryButton(
                        if (added) "Added to your stickers" else "Add to my stickers",
                        onClick = {
                            val t = toon ?: return@PrimaryButton
                            scope.launch {
                                val ok = withContext(Dispatchers.Default) { StickerStore.addToon(context, t, subject, word) }
                                if (ok) { added = true; onAdded() } else message = "Your pack is full. Remove a sticker first."
                            }
                        },
                        enabled = !added, modifier = Modifier.fillMaxWidth().height(52.dp),
                    )
                }
                SecondaryButton("Make a pack of 3 (Cartoon, Comic, Pop art)", { makePack() }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                SecondaryButton("Choose another photo", { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !busy, modifier = Modifier.fillMaxWidth())
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
