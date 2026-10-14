package com.hinterlay.app.ui.editor

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import com.hinterlay.app.ui.components.Chip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hinterlay.app.render.Look
import com.hinterlay.app.render.PhotoAdjust
import com.hinterlay.app.ui.components.LabeledSlider
import com.hinterlay.app.util.Place
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Pill at the top of the controls: drag it up for more room, down for a bigger photo, or tap to hide/show. */
@Composable
fun PanelHandle(expanded: Boolean, onDrag: (Float) -> Unit, onToggle: () -> Unit) {
    val density = LocalDensity.current
    Box(
        Modifier.fillMaxWidth().height(26.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onToggle)
            .pointerInput(Unit) { detectVerticalDragGestures { _, dy -> onDrag(with(density) { dy.toDp().value }) } },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(44.dp).height(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outline))
    }
}

/** A capsule that stamps today's day, date, time or place on the photo with one tap. */
@Composable
fun rememberStampActions(onStamp: (String) -> Unit): StampActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val placeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) scope.launch { stampPlace(context, onStamp) }
        else Toast.makeText(context, "Location is off. You can type a place instead.", Toast.LENGTH_SHORT).show()
    }
    return StampActions(
        day = { onStamp(Place.dayName()) },
        date = { onStamp(Place.dateText()) },
        time = { onStamp(Place.timeText()) },
        place = {
            if (Place.hasPermission(context)) scope.launch { stampPlace(context, onStamp) }
            else placeLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        },
    )
}

class StampActions(val day: () -> Unit, val date: () -> Unit, val time: () -> Unit, val place: () -> Unit)

private suspend fun stampPlace(context: android.content.Context, onStamp: (String) -> Unit) {
    Toast.makeText(context, "Finding your place...", Toast.LENGTH_SHORT).show()
    val name = Place.name(context)
    if (name != null) onStamp(name)
    else Toast.makeText(context, "Couldn't find your location. Check GPS and try again.", Toast.LENGTH_LONG).show()
}

private val Emojis = listOf("😀", "😎", "😍", "🔥", "✨", "❤️", "👑", "🎉", "💯", "🌈", "⭐", "🌴", "☀️", "🌙", "🍕", "🎶")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StampPanel(onStamp: (String, Boolean) -> Unit) {
    val stamps = rememberStampActions { onStamp(it, false) }
    Text("Tap to add to your photo", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(onClick = stamps.day, label = { Text(Place.dayName()) })
        AssistChip(onClick = stamps.date, label = { Text(Place.dateText()) })
        AssistChip(onClick = stamps.time, label = { Text(Place.timeText()) })
        AssistChip(onClick = stamps.place, label = { Text("📍 My location") })
    }
    Text("Emoji", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Emojis.forEach { e ->
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onStamp(e, true) },
                contentAlignment = Alignment.Center,
            ) { Text(e, fontSize = 24.sp) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdjustPanel(adjust: PhotoAdjust, onChange: ((PhotoAdjust) -> PhotoAdjust) -> Unit) {
    Text("Look", style = MaterialTheme.typography.labelLarge)
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(Look.entries) { l ->
            Chip(selected = adjust.look == l, onClick = { onChange { it.copy(look = l) } }, label = { Text(l.label) })
        }
    }
    LabeledSlider("Brightness", adjust.brightness, -1f..1f, "${(adjust.brightness * 100).roundToInt()}") { v -> onChange { it.copy(brightness = v) } }
    LabeledSlider("Contrast", adjust.contrast, -1f..1f, "${(adjust.contrast * 100).roundToInt()}") { v -> onChange { it.copy(contrast = v) } }
    LabeledSlider("Saturation", adjust.saturation, -1f..1f, "${(adjust.saturation * 100).roundToInt()}") { v -> onChange { it.copy(saturation = v) } }
    LabeledSlider("Warmth", adjust.warmth, -1f..1f, "${(adjust.warmth * 100).roundToInt()}") { v -> onChange { it.copy(warmth = v) } }
    LabeledSlider("Vignette", adjust.vignette, 0f..1f, "${(adjust.vignette * 100).roundToInt()}") { v -> onChange { it.copy(vignette = v) } }
    com.hinterlay.app.ui.components.ActionText(onClick = { onChange { PhotoAdjust() } }, enabled = !adjust.isNeutral) { Text("Reset photo adjustments") }
}
