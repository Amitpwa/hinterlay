package com.hinterlay.app.ui.screens

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.EmojiEmotions
import com.hinterlay.app.ui.components.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hinterlay.app.sticker.StickerStore
import com.hinterlay.app.ui.components.PrimaryButton
import com.hinterlay.app.ui.components.SubtleButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Asks how many stickers to make. Explains WhatsApp's 3-sticker minimum up front and offers a
 * one-tap way to meet it, so nobody hits that rule later by surprise.
 */
@Composable
fun StickerChoiceDialog(existing: Int, onJustOne: () -> Unit, onStarterPack: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.EmojiEmotions, null) },
        title = { Text("Make WhatsApp stickers") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "WhatsApp needs at least 3 stickers in a pack." +
                        if (existing > 0) " You have $existing so far." else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
                ChoiceCard(
                    "Starter pack (3 stickers)", "With text, subject only, and full photo. Ready for WhatsApp right away.",
                    recommended = true, onClick = onStarterPack,
                )
                ChoiceCard("Just this one", "Add a single sticker now, make more later.", recommended = false, onClick = onJustOne)
            }
        },
        confirmButton = {},
        dismissButton = { SubtleButton("Cancel", onClick = onDismiss) },
    )
}

@Composable
private fun ChoiceCard(title: String, body: String, recommended: Boolean, onClick: () -> Unit) {
    Card(border = com.hinterlay.app.ui.components.hairline(), 
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (recommended) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(title + if (recommended) "  (recommended)" else "", style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickersScreen(onBack: () -> Unit, onOpenCreations: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val stickers = remember { mutableStateListOf<File>().also { it.addAll(StickerStore.list(context)) } }
    val min = StickerStore.MIN_STICKERS
    val ready = stickers.size >= min

    var launching by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var notInstalled by remember { mutableStateOf(false) }
    var added by remember { mutableStateOf(false) }
    var stylizeOpen by remember { mutableStateOf(false) }

    // WhatsApp reports the outcome of the import here; surface every case instead of failing silently.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        launching = false
        when {
            result.resultCode == Activity.RESULT_OK -> added = true
            result.data?.getStringExtra("validation_error") != null ->
                errorText = result.data?.getStringExtra("validation_error")
            else -> scope.launch { snackbar.showSnackbar("Pack wasn't added. You can try again any time.") }
        }
    }
    // Never leave the button spinning if WhatsApp never answers.
    LaunchedEffect(launching) { if (launching) { delay(6000); launching = false } }

    fun addToWhatsApp() {
        val intent = Intent("com.whatsapp.intent.action.ENABLE_STICKER_PACK").apply {
            putExtra("sticker_pack_id", StickerStore.PACK_ID)
            putExtra("sticker_pack_authority", StickerStore.authority(context))
            putExtra("sticker_pack_name", StickerStore.PACK_NAME)
        }
        val pm = context.packageManager
        val target = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { pkg ->
            intent.setPackage(pkg); intent.resolveActivity(pm) != null
        }
        if (target == null) { notInstalled = true; return }
        intent.setPackage(target)
        launching = true
        try { launcher.launch(intent) } catch (e: Exception) { launching = false; notInstalled = true }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WhatsApp stickers") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column(Modifier.padding(16.dp)) {
                if (ready) {
                    PrimaryButton(
                        if (launching) "Opening WhatsApp…" else "Add to WhatsApp",
                        onClick = { if (!launching) addToWhatsApp() },
                        icon = if (launching) null else Icons.Outlined.EmojiEmotions,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    )
                } else {
                    PrimaryButton(
                        if (stickers.isEmpty()) "Pick a creation to start" else "Make ${min - stickers.size} more",
                        onClick = onOpenCreations,
                        icon = Icons.Outlined.AddPhotoAlternate,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    )
                    Text(
                        "WhatsApp needs $min stickers. Choose a creation and tap “Make WhatsApp sticker”, then pick Starter pack.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Progress toward the 3-sticker minimum: visible status, and the goal is explicit.
            Card(border = com.hinterlay.app.ui.components.hairline(), 
                colors = CardDefaults.cardColors(
                    containerColor = if (ready) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                ),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (ready) Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.tertiary)
                        if (ready) Spacer(Modifier.width(8.dp))
                        Text(
                            if (ready) "Your pack is ready" else "${stickers.size} of $min stickers made",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text("${stickers.size}/${StickerStore.MAX_STICKERS}", style = MaterialTheme.typography.labelLarge)
                    }
                    com.hinterlay.app.ui.components.HairlineProgress(
                        progress = stickers.size / min.toFloat(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        if (ready) "Tap Add to WhatsApp. WhatsApp will ask you to confirm."
                        else "WhatsApp only accepts packs with at least $min stickers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            com.hinterlay.app.ui.components.SecondaryButton(
                "Cartoon sticker from a photo", { stylizeOpen = true },
                icon = Icons.Outlined.EmojiEmotions, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            if (stickers.isEmpty()) {
                HowItWorks(Modifier.weight(1f))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(stickers, key = { it.name }) { file ->
                        val bmp = remember(file.name) { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }
                        Box(
                            Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium),
                        ) {
                            bmp?.let { Image(it, "Sticker", modifier = Modifier.fillMaxSize().padding(6.dp)) }
                            IconButton(
                                onClick = {
                                    StickerStore.delete(context, file)
                                    stickers.remove(file)
                                    scope.launch {
                                        snackbar.showSnackbar("Sticker removed", duration = SnackbarDuration.Short)
                                    }
                                },
                                modifier = Modifier.align(Alignment.TopEnd).size(36.dp),
                            ) { Icon(Icons.Filled.Close, "Remove sticker", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        }
    }

    if (stylizeOpen) {
        StylizeSheet(
            onDismiss = { stylizeOpen = false },
            onAdded = { stickers.clear(); stickers.addAll(StickerStore.list(context)) },
        )
    }
    if (added) {
        AlertDialog(
            onDismissRequest = { added = false },
            icon = { Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.tertiary) },
            title = { Text("Added to WhatsApp") },
            text = { Text("Open any chat, tap the emoji button, then the sticker tab to find your Hinterlay pack.") },
            confirmButton = { SubtleButton("Got it", onClick = { added = false }) },
        )
    }
    errorText?.let { err ->
        AlertDialog(
            onDismissRequest = { errorText = null },
            title = { Text("WhatsApp couldn't add the pack") },
            text = { Text("WhatsApp said: $err\n\nTry removing the stickers and making them again.") },
            confirmButton = { SubtleButton("OK", onClick = { errorText = null }) },
        )
    }
    if (notInstalled) {
        AlertDialog(
            onDismissRequest = { notInstalled = false },
            title = { Text("WhatsApp not found") },
            text = { Text("Install or update WhatsApp (or WhatsApp Business) to add your stickers.") },
            confirmButton = {
                SubtleButton("Open Play Store", onClick = {
                    notInstalled = false
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.whatsapp"))) }
                })
            },
            dismissButton = { SubtleButton("Cancel", onClick = { notInstalled = false }) },
        )
    }
}

/** Empty state: shows the whole path instead of a dead screen. */
@Composable
private fun HowItWorks(modifier: Modifier) {
    Column(modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Turn your creations into stickers", style = MaterialTheme.typography.titleLarge)
        StepRow(1, "Pick a creation", "Open My creations, tap the menu on any item, choose Make WhatsApp sticker.")
        StepRow(2, "Choose Starter pack", "Hinterlay makes 3 stickers at once, the minimum WhatsApp accepts.")
        StepRow(3, "Add to WhatsApp", "Come back here and tap Add to WhatsApp, then confirm in WhatsApp.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(
                "Photos with a clear subject make the best stickers.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StepRow(n: Int, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(28.dp).background(MaterialTheme.colorScheme.primaryContainer, androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("$n", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer) }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
