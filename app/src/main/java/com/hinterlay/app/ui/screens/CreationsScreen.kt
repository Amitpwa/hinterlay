package com.hinterlay.app.ui.screens

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hinterlay.app.data.HistoryItem
import com.hinterlay.app.data.HistoryStore
import com.hinterlay.app.data.Referral
import com.hinterlay.app.ui.safeStart
import com.hinterlay.app.wallpaper.WallpaperStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Loads saved creations (from this device only). */
@Composable
fun rememberCreations(reloadKey: Any? = Unit): androidx.compose.runtime.snapshots.SnapshotStateList<HistoryItem> {
    val context = LocalContext.current
    val items = remember { mutableStateListOf<HistoryItem>() }
    LaunchedEffect(reloadKey) {
        val loaded = withContext(Dispatchers.IO) { HistoryStore.list(context) }
        items.clear(); items.addAll(loaded)
    }
    return items
}

@Composable
fun CreationThumb(item: HistoryItem, modifier: Modifier = Modifier) {
    var thumb by remember(item.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item.id) {
        thumb = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(item.thumb.path)?.asImageBitmap() }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        thumb?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreationsScreen(
    items: androidx.compose.runtime.snapshots.SnapshotStateList<HistoryItem>,
    onBack: (() -> Unit)?,
    onOpen: (HistoryItem) -> Unit,
    onOpenStickers: () -> Unit,
    onPick: ((android.net.Uri) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    var stickerTarget by remember { mutableStateOf<HistoryItem?>(null) }
    // Ids currently peeling away with the page-curl before they are removed from the list.
    val curling = remember { mutableStateListOf<String>() }

    fun makeStickers(item: HistoryItem, starter: Boolean) {
        scope.launch {
            snackbar.showSnackbar("Making sticker${if (starter) "s" else ""}…", duration = androidx.compose.material3.SnackbarDuration.Indefinite)
        }
        scope.launch {
            val added = withContext(Dispatchers.Default) {
                val src = BitmapFactory.decodeFile(item.source.path) ?: return@withContext 0
                val fg = if (item.foreground.exists()) BitmapFactory.decodeFile(item.foreground.path) else null
                val layers = HistoryStore.layers(item)
                runCatching {
                    if (starter) com.hinterlay.app.sticker.StickerStore.addStarterPack(context, src, fg, layers, HistoryStore.subjectEdit(item))
                    else if (com.hinterlay.app.sticker.StickerStore.add(context, src, fg, layers, com.hinterlay.app.sticker.StickerStore.Style.WITH_TEXT, HistoryStore.subjectEdit(item))) 1 else 0
                }.getOrDefault(0)
            }
            snackbar.currentSnackbarData?.dismiss()
            val total = com.hinterlay.app.sticker.StickerStore.list(context).size
            val min = com.hinterlay.app.sticker.StickerStore.MIN_STICKERS
            val message = when {
                added == 0 -> "Couldn't make a sticker. The pack may be full."
                total >= min -> "Added $added. Your pack is ready for WhatsApp."
                else -> "Added $added. $total of $min needed. Make ${min - total} more."
            }
            val result = snackbar.showSnackbar(message, actionLabel = "Open stickers", duration = androidx.compose.material3.SnackbarDuration.Long)
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) onOpenStickers()
        }
    }

    stickerTarget?.let { target ->
        StickerChoiceDialog(
            existing = com.hinterlay.app.sticker.StickerStore.list(context).size,
            onJustOne = { stickerTarget = null; makeStickers(target, false) },
            onStarterPack = { stickerTarget = null; makeStickers(target, true) },
            onDismiss = { stickerTarget = null },
        )
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(if (onBack == null) "Library" else "My creations") },
                navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            val picker = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
            ) { uri -> if (uri != null) onPick?.invoke(uri) }
            com.hinterlay.app.ui.components.EmptyState(
                title = "Your creations live here",
                body = "Everything you export is kept on this phone only, so you can edit or share it again any time.",
                modifier = Modifier.padding(padding),
                actionLabel = if (onPick != null) "Pick a photo" else null,
                actionIcon = Icons.Outlined.PhotoLibrary,
                onAction = {
                    picker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            )        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    var menu by remember { mutableStateOf(false) }
                    com.hinterlay.app.ui.components.PageCurlBox(
                        curling = curling.contains(item.id),
                        onCurled = {
                            scope.launch(Dispatchers.IO) { HistoryStore.delete(item) }
                            curling.remove(item.id)
                            items.remove(item)
                        },
                        modifier = Modifier.animateItem(),
                    ) {
                    Card(border = com.hinterlay.app.ui.components.hairline(),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.clickable { onOpen(item) },
                    ) {
                        Box {
                            CreationThumb(item, Modifier.fillMaxWidth().aspectRatio(0.8f))
                            Box(Modifier.align(Alignment.TopEnd)) {
                                IconButton(onClick = { menu = true }) {
                                    Icon(Icons.Filled.MoreVert, "Options", tint = androidx.compose.ui.graphics.Color.White)
                                }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Share & earn ad-free") },
                                        leadingIcon = { Icon(Icons.Outlined.Share, null) },
                                        onClick = { menu = false; context.safeStart(Referral.shareIntent(context, item.result)) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Set as 3D wallpaper") },
                                        leadingIcon = { Icon(Icons.Outlined.AutoAwesome, null) },
                                        onClick = {
                                            menu = false
                                            scope.launch {
                                                val ok = withContext(Dispatchers.Default) { WallpaperStore.writeFromHistory(context, item) }
                                                if (ok) context.safeStart(WallpaperStore.setIntent(context))
                                                else Toast.makeText(context, "Couldn't prepare the wallpaper", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Make WhatsApp sticker") },
                                        leadingIcon = { Icon(Icons.Outlined.EmojiEmotions, null) },
                                        onClick = { menu = false; stickerTarget = item },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Delete") },
                                        leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                        onClick = {
                                            menu = false
                                            if (!curling.contains(item.id)) curling.add(item.id)
                                        },
                                    )
                                }
                            }
                        }
                        Text(
                            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.createdAt)),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    }
                }
            }
        }
    }
}
