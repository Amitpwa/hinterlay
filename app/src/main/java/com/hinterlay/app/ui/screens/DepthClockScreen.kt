package com.hinterlay.app.ui.screens

import android.graphics.Bitmap
import android.graphics.Paint
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hinterlay.app.editor.FontChoice
import com.hinterlay.app.render.Compositor
import com.hinterlay.app.render.ImageLoader
import com.hinterlay.app.render.SubjectDetector
import com.hinterlay.app.ui.components.AlertDialog
import com.hinterlay.app.ui.components.Chip
import com.hinterlay.app.ui.components.LabeledSlider
import com.hinterlay.app.ui.components.PrimaryButton
import com.hinterlay.app.ui.components.SecondaryButton
import com.hinterlay.app.ui.components.SubtleButton
import com.hinterlay.app.ui.components.SwitchRow
import com.hinterlay.app.ui.safeStart
import com.hinterlay.app.wallpaper.ClockEffect
import com.hinterlay.app.wallpaper.ClockRenderer
import com.hinterlay.app.wallpaper.DepthClockConfig
import com.hinterlay.app.wallpaper.DepthFx
import com.hinterlay.app.wallpaper.DepthClockStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

private enum class ClockTab(val label: String) {
    Clock("Clock"), Font("Font"), Color("Color"), Effects("Effects"), Background("Background"), Position("Position")
}

/** Colours offered for the clock. Content, not UI chrome, so a small tasteful palette is fine (no purple). */
private val CLOCK_COLORS = listOf(
    0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFEDEDED.toInt(), 0xFFFFD54F.toInt(),
    0xFFEF5350.toInt(), 0xFF42A5F5.toInt(), 0xFF66BB6A.toInt(), 0xFFFF7043.toInt(),
)

/**
 * Phase 1 editor for the depth-effect clock wallpaper: pick a photo, the subject is detected, a
 * live clock is slipped behind it, and the result can be applied as a live wallpaper.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DepthClockScreen(onBack: () -> Unit, initialUri: Uri? = null) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var source by remember { mutableStateOf<Bitmap?>(null) }
    var foreground by remember { mutableStateOf<Bitmap?>(null) }
    var back by remember { mutableStateOf<Bitmap?>(null) }
    var front by remember { mutableStateOf<Bitmap?>(null) }
    var config by remember { mutableStateOf(DepthClockStore.config(context)) }
    var status by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(ClockTab.Clock) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var showApply by remember { mutableStateOf(false) }

    // Tick so the preview clock stays current.
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) }
    }

    val detector = remember { SubjectDetector() }

    suspend fun prepare(uri: Uri) {
        status = "Loading photo…"
        val src = withContext(Dispatchers.IO) { runCatching { ImageLoader.decode(context, uri) }.getOrNull() }
        if (src == null) { status = "Couldn't open that photo"; return }
        status = "Finding the subject…"
        val fg = runCatching { detector.extract(src) { status = it } }.getOrNull()
        val (b, f) = withContext(Dispatchers.Default) { Compositor.renderPlanes(src, fg, emptyList()) }
        source = src; foreground = fg; back = b; front = f
        status = if (fg == null) "No clear subject — the clock will sit on top" else null
    }

    LaunchedEffect(initialUri) { if (initialUri != null) prepare(initialUri) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked ->
        if (picked != null) scope.launch { prepare(picked) }
    }
    fun pick() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            detector.close()
            source?.recycle(); foreground?.recycle(); back?.recycle(); front?.recycle()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Depth clock") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
        containerColor = Color.Transparent,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val b = back
            if (b == null) {
                // ---- Pick / loading state fills the screen -----------------------------
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (status != null) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurface, strokeWidth = 2.dp)
                            Text(status!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text("Pick a photo to begin", style = MaterialTheme.typography.bodyLarge)
                            PrimaryButton("Choose photo", onClick = { pick() })
                        }
                    }
                }
            } else {
                // ---- Preview: bounded height, fit-centred so the controls always fit ----
                val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
                val f = front
                val cfg = config
                val millis = now
                Canvas(
                    Modifier.fillMaxWidth().height(340.dp).padding(16.dp).clip(MaterialTheme.shapes.large),
                ) {
                    drawIntoCanvas { c ->
                        val nc = c.nativeCanvas
                        val scale = kotlin.math.min(size.width / b.width, size.height / b.height)
                        val dx = (size.width - b.width * scale) / 2f
                        val dy = (size.height - b.height * scale) / 2f
                        val cal = Calendar.getInstance().apply { timeInMillis = millis }
                        nc.save()
                        nc.translate(dx, dy)
                        nc.scale(scale, scale)
                        paint.colorFilter = DepthFx.backFilter(cfg)
                        nc.drawBitmap(b, 0f, 0f, paint)
                        paint.colorFilter = null
                        DepthFx.drawVignette(nc, b.width, b.height, cfg)
                        if (cfg.behind || f == null) ClockRenderer.draw(nc, b.width, b.height, cfg, cal, b)
                        f?.let { nc.drawBitmap(it, 0f, 0f, paint) }
                        if (!cfg.behind && f != null) ClockRenderer.draw(nc, b.width, b.height, cfg, cal, b)
                        nc.restore()
                    }
                }

                // ---- Scrollable controls so every option and Apply stay reachable ------
                Column(
                    Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

                    SwitchRow(
                        title = "Behind the subject",
                        subtitle = "Tuck the clock behind the person or object",
                        checked = config.behind,
                        onCheckedChange = { config = config.copy(behind = it) },
                        enabled = front != null,
                    )

                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ClockTab.entries.forEach { t ->
                            Chip(selected = tab == t, onClick = { tab = t }, label = { Text(t.label) })
                        }
                    }

                    when (tab) {
                        ClockTab.Clock -> {
                            SwitchRow("24-hour time", config.twentyFour, { config = config.copy(twentyFour = it) })
                            SwitchRow("Stack hour over minute", config.vertical, { config = config.copy(vertical = it) })
                            SwitchRow("Show separator", config.separator, { config = config.copy(separator = it) }, enabled = !config.vertical)
                            SwitchRow("Show date", config.showDate, { config = config.copy(showDate = it) })
                        }
                        ClockTab.Font -> {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FontChoice.picker.forEach { fontChoice ->
                                    Chip(
                                        selected = config.fontName == fontChoice.name,
                                        onClick = { config = config.copy(fontName = fontChoice.name) },
                                        label = { Text(fontChoice.label) },
                                    )
                                }
                            }
                        }
                        ClockTab.Color -> {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CLOCK_COLORS.forEach { c ->
                                    val selected = config.color == c
                                    Box(
                                        Modifier.size(34.dp).clip(CircleShape)
                                            .background(Color(c))
                                            .border(
                                                width = if (selected) 2.dp else 1.dp,
                                                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                                                shape = CircleShape,
                                            )
                                            .clickable { config = config.copy(color = c) },
                                    )
                                }
                            }
                        }
                        ClockTab.Effects -> {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ClockEffect.entries.forEach { e ->
                                    Chip(
                                        selected = config.clockEffect == e,
                                        onClick = { config = config.copy(effect = e.name) },
                                        label = { Text(e.label) },
                                    )
                                }
                            }
                            if (config.clockEffect == ClockEffect.PHOTO) {
                                Text(
                                    "The digits reveal the photo behind them.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        ClockTab.Background -> {
                            LabeledSlider("Wash", config.dim, 0f..0.85f, "${(config.dim * 100).toInt()}%") { config = config.copy(dim = it) }
                            LabeledSlider("Saturation", config.saturation, 0f..1.8f, "${(config.saturation * 100).toInt()}%") { config = config.copy(saturation = it) }
                            LabeledSlider("Vignette", config.vignette, 0f..1f, "${(config.vignette * 100).toInt()}%") { config = config.copy(vignette = it) }
                        }
                        ClockTab.Position -> {
                            LabeledSlider("Horizontal", config.x, 0f..1f, "${(config.x * 100).toInt()}%") { config = config.copy(x = it) }
                            LabeledSlider("Vertical", config.y, 0f..1f, "${(config.y * 100).toInt()}%") { config = config.copy(y = it) }
                            LabeledSlider("Size", config.sizeRel, 0.15f..0.7f, "${(config.sizeRel * 100).toInt()}") { config = config.copy(sizeRel = it) }
                        }
                    }

                    PrimaryButton(
                        "Apply as wallpaper",
                        onClick = { showApply = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SubtleButton("Change photo", onClick = { pick() }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    if (showApply) {
        val src = source
        AlertDialog(
            onDismissRequest = { showApply = false },
            title = { Text("Set depth clock") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Your phone's own lock-screen clock, notification icons and widgets stay under " +
                            "Android's control — this app can't move or remove them. Place the depth clock " +
                            "where your phone's clock isn't, or hide the system clock in your phone's " +
                            "lock-screen settings.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    PrimaryButton(
                        "Live — lock & home (ticks)",
                        onClick = {
                            showApply = false
                            if (src != null) scope.launch {
                                withContext(Dispatchers.Default) { DepthClockStore.write(context, src, foreground, config) }
                                context.safeStart(DepthClockStore.setIntent(context))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SecondaryButton(
                        "Lock screen only (still image)",
                        onClick = {
                            showApply = false
                            if (src != null) scope.launch {
                                val ok = withContext(Dispatchers.Default) { DepthClockStore.applyStaticLock(context, src, foreground, config) }
                                Toast.makeText(
                                    context,
                                    if (ok) "Set on your lock screen" else "Couldn't set the lock screen",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Frozen snapshot — the time won't update. Re-apply to refresh it, or use Live above.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { SubtleButton("Cancel", onClick = { showApply = false }) },
        )
    }
}
