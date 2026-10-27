package com.hinterlay.app.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.hinterlay.app.cloud.ContentRepo
import com.hinterlay.app.editor.FontChoice
import com.hinterlay.app.ui.screens.SceneRig
import com.hinterlay.app.ui.screens.SceneState
import com.hinterlay.app.ui.screens.cinematic
import com.hinterlay.app.ui.screens.wave
import com.hinterlay.app.cloud.Track
import com.hinterlay.app.data.HistoryItem
import com.hinterlay.app.ui.components.PrimaryButton
import com.hinterlay.app.ui.components.halftone
import com.hinterlay.app.ui.screens.CreationThumb

/** "Create" tab: live cards from the owner, one clear primary action, and recent work. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateTab(
    creations: List<HistoryItem>,
    onPhotoPicked: (Uri) -> Unit,
    onOpenCreation: (HistoryItem) -> Unit,
    onSeeAll: () -> Unit,
    onCardAction: (String) -> Unit,
    onDepthClock: () -> Unit,
) {
    // System Photo Picker: user grants access to just the photo they choose. No storage permission.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) { Track.event("photo_selected"); onPhotoPicked(uri) }
    }
    val cards by ContentRepo.cards.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hinterlay") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize()
                .halftone(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f), spacing = 12.dp, maxRadius = 1.7.dp, fromTop = false)
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            LiveCards(cards, onCardAction)

            val openPicker = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            if (creations.isNotEmpty()) {
                // Returning users land on their work: one tap on the big card opens a photo, one tap on a thumbnail resumes an edit.
                Box(
                    Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(24.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp))
                        .clickable(onClick = openPicker),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(30.dp))
                        Text("Tap to open a photo", style = MaterialTheme.typography.titleMedium)
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Continue editing", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    com.hinterlay.app.ui.components.SubtleButton("See all", onClick = onSeeAll)
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = 4.dp)) {
                    items(creations.take(8), key = { it.id }) { item ->
                        CreationThumb(
                            item,
                            Modifier.size(136.dp, 176.dp).clip(MaterialTheme.shapes.large).clickable { onOpenCreation(item) },
                        )
                    }
                }
            }

            HeroReveal(Modifier.fillMaxWidth().aspectRatio(0.82f))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Put words behind anything", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Pick a photo and Hinterlay tucks your text behind the subject, right on your phone.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Depth-clock wallpaper: a sibling creation mode, kept here on the main screen so it is found.
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp))
                    .clickable(onClick = onDepthClock).padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(28.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Depth clock wallpaper", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "A live clock tucked behind your photo's subject",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (creations.isEmpty()) PrimaryButton(
                text = "Choose a photo",
                icon = Icons.Outlined.AddPhotoAlternate,
                onClick = { openPicker() },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            )
            Text(
                "Your photos are processed on this device and are never uploaded.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(100.dp)) // clears the floating bar and its raised button
        }
    }
}
