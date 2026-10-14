package com.hinterlay.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.hinterlay.app.cloud.ContentRepo
import com.hinterlay.app.cloud.Inspiration
import com.hinterlay.app.cloud.Preset
import com.hinterlay.app.cloud.Track
import com.hinterlay.app.render.Compositor
import com.hinterlay.app.ui.components.PrimaryButton

private val Gradients = listOf(
    listOf(Color(0xFF0D0D0D), Color(0xFF4A4A4A)),
    listOf(Color(0xFF1A1A1A), Color(0xFF8A8A8A)),
    listOf(Color(0xFF2A2A2A), Color(0xFFB8B8B8)),
    listOf(Color(0xFF111111), Color(0xFF5E5E5E)),
    listOf(Color(0xFF3A3A3A), Color(0xFF9E9E9E)),
    listOf(Color(0xFF181818), Color(0xFF6F6F6F)),
)

/** "Inspire" tab: styles curated by the owner. Tap one, pick a photo, and it is applied. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InspireScreen(onUseIdea: (Preset, Uri) -> Unit) {
    val items by ContentRepo.inspirations.collectAsState()
    var selected by remember { mutableStateOf<Inspiration?>(null) }
    var category by remember { mutableStateOf<String?>(null) }
    var shareOpen by remember { mutableStateOf(false) }
    var stylizeOpen by remember { mutableStateOf(false) }
    val categories = remember(items) { items.flatMap { it.tags }.distinct().sorted() }
    val shown = remember(items, category) { if (category == null) items else items.filter { category in it.tags } }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Inspire") }) },
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { com.hinterlay.app.ui.components.Chip(category == null, { category = null }, { Text("All") }) }
                    rowItems(categories) { c -> com.hinterlay.app.ui.components.Chip(category == c, { category = if (category == c) null else c }, { Text(c) }) }
                }
            }
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Column(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Cartoon stickers", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Turn any photo into a cartoon, comic, pop-art or pencil sticker for WhatsApp. It all runs on your phone.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    com.hinterlay.app.ui.components.SecondaryButton("Make a cartoon sticker", { stylizeOpen = true }, Modifier.fillMaxWidth())
                }
            }
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Column(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Share your creation", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Made something great? Send it in. If it meets our creation standard it can be featured here, and creators whose styles others love may be invited to earn from them.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    com.hinterlay.app.ui.components.SecondaryButton("Share with the world", { shareOpen = true }, Modifier.fillMaxWidth())
                }
            }
            items(shown, key = { it.id }) { item ->
                InspirationCard(item, Modifier.clickable { selected = item; Track.event("inspiration_viewed", "id" to item.id) })
            }
        }
    }

    if (shareOpen) ShareSheet(onDismiss = { shareOpen = false })
    if (stylizeOpen) StylizeSheet(onDismiss = { stylizeOpen = false }, onAdded = {})

    selected?.let { item ->
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                Track.event("inspiration_used", "id" to item.id)
                selected = null
                onUseIdea(item.preset, uri)
            }
        }
        ModalBottomSheet(onDismissRequest = { selected = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
            com.hinterlay.app.ui.components.SheetSound()
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                InspirationCard(item, Modifier.fillMaxWidth().height(280.dp), large = true)
                Text(item.title, style = MaterialTheme.typography.titleLarge)
                if (item.caption.isNotBlank()) {
                    Text(item.caption, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (item.tags.isNotEmpty()) {
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowItems(item.tags) { tag -> AssistChip(onClick = {}, label = { Text(tag) }) }
                    }
                }
                item.preset.photo?.let { name ->
                    Text(
                        "Photo by ${TemplateArt.credits[name] ?: "unknown"} (free to use, CC0 / public domain)",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PrimaryButton(
                    "Use this idea on my photo",
                    icon = Icons.Outlined.AddPhotoAlternate,
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                )
            }
        }
    }
}

/** The owner's image when there is one; otherwise a gradient card showing the style itself. */
@Composable
private fun InspirationCard(item: Inspiration, modifier: Modifier = Modifier, large: Boolean = false) {
    val gradient = Gradients[(item.id.hashCode() and 0x7fffffff) % Gradients.size]
    Box(
        modifier.then(if (large) Modifier else Modifier.aspectRatio(0.8f)).clip(MaterialTheme.shapes.large)
            .background(Brush.verticalGradient(gradient)),
        contentAlignment = Alignment.Center,
    ) {
        if (item.imageUrl != null) {
            AsyncImage(item.imageUrl, item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            val ctx = androidx.compose.ui.platform.LocalContext.current.applicationContext
            // The template drawn on its own scene (built on a background thread, then kept in a small cache).
            val preview by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, item.preset, large) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    if (large) TemplateArt.preview(ctx, item.preset, 640, 800) else TemplateArt.preview(ctx, item.preset, 360, 450)
                }
            }
            preview?.let {
                androidx.compose.foundation.Image(
                    it.asImageBitmap(), item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (!large) {
            Text(
                item.title,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000))))
                    .padding(12.dp),
            )
        }
    }
}


/**
 * Pick one of your creations and send it for review. Nothing leaves the phone until you tick the boxes and tap Send, and what
 * is sent is only that image and its text style.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareSheet(onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val creations = remember { com.hinterlay.app.data.HistoryStore.list(ctx) }
    var picked by remember { mutableStateOf<com.hinterlay.app.data.HistoryItem?>(null) }
    var caption by remember { mutableStateOf("") }
    var handle by remember { mutableStateOf("") }
    var own by remember { mutableStateOf(false) }
    var allow by remember { mutableStateOf(false) }
    var contact by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<com.hinterlay.app.cloud.ShareResult?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val emailOk = !contact || android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
    val ready = picked != null && own && allow && emailOk && !busy && result == null

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
            Text("Share with the world", style = MaterialTheme.typography.titleLarge)
            Text(
                "Pick a creation. We review every entry. Featured ones appear in Inspire, and creators whose styles other people use may be invited to earn from them.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (creations.isEmpty()) {
                Text("You have no saved creations yet. Export one first, then come back.", style = MaterialTheme.typography.bodyMedium)
            } else {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowItems(creations.take(20), key = { it.id }) { c ->
                        val on = picked?.id == c.id
                        CreationThumb(
                            c,
                            Modifier.size(96.dp, 120.dp).clip(MaterialTheme.shapes.medium)
                                .border(if (on) 2.dp else 1.dp, if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                                .clickable { picked = c },
                        )
                    }
                }
                androidx.compose.material3.OutlinedTextField(caption, { caption = it.take(200) }, label = { Text("A line about it (optional)") }, modifier = Modifier.fillMaxWidth())
                androidx.compose.material3.OutlinedTextField(handle, { handle = it.take(40) }, label = { Text("Name to credit (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                com.hinterlay.app.ui.components.SwitchRow("This photo is mine to share", own, { own = it }, subtitle = "I took it, or I have permission, and nobody in it objects.")
                com.hinterlay.app.ui.components.SwitchRow("Hinterlay may feature it", allow, { allow = it }, subtitle = "Show it in the app with my credit. I can ask for it to be removed.")
                com.hinterlay.app.ui.components.SwitchRow("Contact me about earning from my styles", contact, { contact = it }, subtitle = "Optional. We only use your email for this.")
                if (contact) androidx.compose.material3.OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(
                    "Only the picked image and its text style are sent, and only when you tap Send. Your other photos are never uploaded.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                result?.let {
                    Text(
                        when (it) {
                            com.hinterlay.app.cloud.ShareResult.SENT -> "Sent for review. Thank you!"
                            com.hinterlay.app.cloud.ShareResult.SAVED -> "Sharing opens soon. Your entry is saved on this phone and is only sent once it opens."
                            com.hinterlay.app.cloud.ShareResult.FAILED -> "That entry could not be prepared. Please try again."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                PrimaryButton(
                    if (result == null) "Send for review" else "Done",
                    onClick = {
                        if (result != null) { onDismiss(); return@PrimaryButton }
                        val c = picked ?: return@PrimaryButton
                        busy = true
                        scope.launch { result = com.hinterlay.app.cloud.Community.submit(ctx, c, caption.trim(), handle.trim(), contact, email.trim()); busy = false }
                    },
                    enabled = ready || result != null,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                )
            }
        }
    }
}
