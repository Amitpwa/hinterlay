package com.hinterlay.app.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import com.hinterlay.app.ai.MagicStyle
import com.hinterlay.app.editor.EditorViewModel
import com.hinterlay.app.fx.rememberFeedback
import com.hinterlay.app.ui.components.PrimaryButton
import com.hinterlay.app.ui.components.SecondaryButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class Card3(val style: MagicStyle, val preview: Bitmap?)

/** Shows three finished looks for this photo. Tap one to use it; "More ideas" generates a fresh set. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MagicSheet(vm: EditorViewModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val fb = rememberFeedback()
    var cards by remember { mutableStateOf<List<Card3>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var round by remember { mutableStateOf(0) }

    suspend fun load(more: Boolean) {
        busy = true
        val styles = vm.magic(more)
        cards = withContext(Dispatchers.Default) { styles.map { Card3(it, vm.magicPreview(it)) } }
        busy = false
        if (cards.isNotEmpty()) fb.success()
    }
    LaunchedEffect(Unit) { load(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
        com.hinterlay.app.ui.components.SheetSound()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, null)
                Text("  Magic suggestions", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                "Picked from what the AI sees in your photo. Tap one to use it.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (busy) {
                Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                        Text("Reading the scene…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    cards.forEach { c ->
                        Card(
                            onClick = { fb.toggle(); vm.applyMagic(c.style); onDismiss() },
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.large,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        ) {
                            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                c.preview?.let {
                                    Image(
                                        it.asImageBitmap(), c.style.layer.text, contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxWidth().aspectRatio(0.72f).clip(MaterialTheme.shapes.medium),
                                    )
                                }
                                Text(c.style.layer.text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                                Text(c.style.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                            }
                        }
                    }
                }
                cards.firstOrNull()?.let {
                    Text(it.style.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SecondaryButton(
                    "More ideas",
                    onClick = { fb.select(); round++; scope.launch { load(true) } },
                    icon = Icons.Outlined.Refresh,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                )
            }
        }
    }
}
