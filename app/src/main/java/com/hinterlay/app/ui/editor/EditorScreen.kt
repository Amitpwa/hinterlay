@file:OptIn(ExperimentalMaterial3Api::class)

package com.hinterlay.app.ui.editor

import com.hinterlay.app.ui.theme.frostedPanel
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignRight
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.PhotoFilter
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.ui.graphics.asImageBitmap
import com.hinterlay.app.ui.theme.pressScale
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material.icons.outlined.Tune
import com.hinterlay.app.ui.components.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import com.hinterlay.app.ui.components.Chip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.pointer.PointerEventPass
import kotlin.math.hypot
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import com.hinterlay.app.data.Referral
import com.hinterlay.app.wallpaper.WallpaperStore
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hinterlay.app.ads.AdBanner
import com.hinterlay.app.ads.AdsManager
import com.hinterlay.app.editor.EditorUiState
import com.hinterlay.app.editor.EditorViewModel
import com.hinterlay.app.editor.ExportFormat
import com.hinterlay.app.editor.ExportState
import com.hinterlay.app.editor.FontChoice
import com.hinterlay.app.editor.FontWeightChoice
import com.hinterlay.app.editor.Phase
import com.hinterlay.app.editor.TextAlignChoice
import com.hinterlay.app.editor.TextCase
import com.hinterlay.app.editor.TextLayer
import com.hinterlay.app.export.Exporter
import com.hinterlay.app.render.Compositor
import com.hinterlay.app.ui.components.AdBadge
import com.hinterlay.app.ui.components.LabeledSlider
import com.hinterlay.app.ui.components.PrimaryButton
import com.hinterlay.app.ui.components.SecondaryButton
import com.hinterlay.app.ui.components.SubtleButton
import com.hinterlay.app.ui.components.SwitchRow
import com.hinterlay.app.ui.findActivity
import com.hinterlay.app.ui.safeStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min
import kotlin.math.roundToInt

private enum class Tool(val label: String, val icon: ImageVector) {
    TEXT("Text", Icons.Outlined.TextFields),
    FONT("Font", Icons.Outlined.FontDownload),
    STYLE("Style", Icons.Outlined.Tune),
    COLOR("Color", Icons.Outlined.Palette),
    EFFECTS("Effects", Icons.Outlined.AutoAwesome),
    PERSPECTIVE("Perspective", Icons.Outlined.ViewInAr),
    ERASE("Erase", Icons.Outlined.Brush),
    LAYER("Layer", Icons.Outlined.Layers),
    ADJUST("Adjust", Icons.Outlined.Tune),
    STAMP("Stamp", Icons.Outlined.AutoAwesome),
}

private val Swatches = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFF242424, 0xFF0F6CBD, 0xFF479EF5, 0xFF00B7C3,
    0xFF13A10E, 0xFFFFC83D, 0xFFF7630C, 0xFFC50F1F, 0xFFE3008C, 0xFF8764B8,
).map { it.toInt() }

@Composable
fun EditorScreen(uri: Uri, vm: EditorViewModel, onExit: () -> Unit) {
    val context = LocalContext.current
    val fb = com.hinterlay.app.fx.rememberFeedback()
    val ui by vm.state.collectAsStateWithLifecycle()
    val export by vm.export.collectAsStateWithLifecycle()
    val exportVisible by vm.exportVisible.collectAsStateWithLifecycle()

    var tool by rememberSaveable { mutableStateOf(Tool.TEXT) }
    var showExportSheet by rememberSaveable { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var showStickerChoice by remember { mutableStateOf(false) }
    var cropMode by remember { mutableStateOf(false) }
    var magicOpen by remember { mutableStateOf(false) }
    var smoothOpen by remember { mutableStateOf(false) }
    var toolsOpen by remember { mutableStateOf(false) }
    // Progress (0..1) of the face scan animation, driven by the screen's frame clock while it runs.
    var scanProgress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(ui.faceScanning, ui.faceScanStart) {
        if (!ui.faceScanning) { scanProgress = 0f; return@LaunchedEffect }
        while (true) androidx.compose.runtime.withFrameNanos {
            scanProgress = ((System.nanoTime() - ui.faceScanStart) / 1e9f / 0.95f).coerceIn(0f, 1f)
        }
    }
    var wobbleLoading by remember { mutableStateOf(false) }
    // A small copy of the photo for the look previews, made once per photo (and again after a crop).
    val lookPreview = remember(ui.source) {
        ui.source?.let { s -> val k = 160f / maxOf(s.width, s.height); if (k >= 1f) s else Bitmap.createScaledBitmap(s, (s.width * k).toInt().coerceAtLeast(1), (s.height * k).toInt().coerceAtLeast(1), true) }
    }
    var wobblePlanes by remember { mutableStateOf<com.hinterlay.app.render.Wobble.Planes?>(null) }
    var photoTool by rememberSaveable { mutableStateOf(PhotoTool.TUNE) }
    // false = the grid of tools is showing; true = a tool is open (with Cancel and Apply).
    var inTool by remember { mutableStateOf(false) }
    var activeParam by remember { mutableStateOf(0) }
    // Holding the compare button shows the photo as it was opened.
    var showOriginal by remember { mutableStateOf(false) }
    // While Selective is open, taps on the photo place or pick adjustment points instead of picking text.
    val selectiveActive = toolsOpen && inTool && photoTool == PhotoTool.SELECTIVE
    var brushSettings by remember { mutableStateOf(BrushSettings()) }
    // While Brush is open, one finger paints on the photo (the same gesture the eraser uses).
    val brushActive = toolsOpen && inTool && photoTool == PhotoTool.BRUSH
    val healActive = toolsOpen && inTool && photoTool == PhotoTool.HEAL
    var showPushAsk by remember { mutableStateOf(false) }
    val pushPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        com.hinterlay.app.cloud.Push.enabled = granted
        if (granted) com.hinterlay.app.cloud.Track.event("push_opt_in")
    }
    // Ask for notifications only after the user got value (their first finished export), never on launch.
    LaunchedEffect(export) {
        if (export is ExportState.Done) fb.success()
        if (export is ExportState.Failed) fb.error()
        if (export is ExportState.Done && com.hinterlay.app.cloud.Cloud.available && !com.hinterlay.app.cloud.Push.asked) {
            showPushAsk = true
        }
    }
    var eraseRestore by rememberSaveable { mutableStateOf(false) }
    var eraseSubjectMode by rememberSaveable { mutableStateOf(false) }
    var eraseStrength by rememberSaveable { mutableFloatStateOf(1f) }
    var eraseOverlap by rememberSaveable { mutableStateOf(true) }
    androidx.compose.runtime.SideEffect {
        vm.eraseSubject = eraseSubjectMode
        vm.eraseIntensity = eraseStrength
        vm.eraseOverlapOnly = eraseOverlap && ui.foreground != null
    }
    var brushRel by rememberSaveable { mutableFloatStateOf(0.05f) }
    var panelDp by rememberSaveable { mutableFloatStateOf(210f) }
    val maxPanel = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.42f)
    val scope = rememberCoroutineScope()

    LaunchedEffect(uri) { vm.load(uri) }

    // Sound: a scan while the subject is being found, a soft whoosh when it is ready.
    LaunchedEffect(ui.phase) {
        when (ui.phase) {
            Phase.Detecting -> com.hinterlay.app.fx.Fx.play(com.hinterlay.app.fx.Fx.Sound.Scan, 0.55f)
            Phase.Ready -> fb.whoosh()
            else -> Unit
        }
    }

    // Phone tilt, only followed while a 3D text look is in use: the reflections slide as the phone moves.
    var tiltX by remember { mutableFloatStateOf(0f) }
    var tiltY by remember { mutableFloatStateOf(0f) }
    val anyMaterial = ui.layers.any { it.visible && it.material != com.hinterlay.app.editor.TextMaterial.NONE }
    androidx.compose.runtime.DisposableEffect(anyMaterial) {
        if (!anyMaterial) return@DisposableEffect onDispose { }
        val sm = context.getSystemService(android.content.Context.SENSOR_SERVICE) as? android.hardware.SensorManager
        val sensor = sm?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        var sx = 0f
        var sy = 0f
        var last = 0L
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                if (e.timestamp - last < 60_000_000L) return // about 16 updates a second is plenty
                last = e.timestamp
                sx += ((-e.values[0] / 9.81f).coerceIn(-1f, 1f) - sx) * 0.3f
                sy += ((e.values[2] / 9.81f).coerceIn(-1f, 1f) - sy) * 0.3f
                if (kotlin.math.abs(sx - tiltX) > 0.03f || kotlin.math.abs(sy - tiltY) > 0.03f) { tiltX = sx; tiltY = sy }
            }
            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) = Unit
        }
        if (sm != null && sensor != null) sm.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_UI)
        onDispose { sm?.unregisterListener(listener); tiltX = 0f; tiltY = 0f }
    }
    var savingDraft by remember { mutableStateOf(false) }
    // Leaving asks only when there is something to lose: nothing changed since opening, saving or exporting means no question.
    val leave: () -> Unit = { if (vm.hasUnsavedEdits()) confirmExit = true else onExit() }
    BackHandler { leave() }
    // Back inside a tool cancels it; Back on the tool grid closes the grid. (Registered after the one above, so it wins while tools are open.)
    BackHandler(enabled = toolsOpen) {
        if (inTool) { vm.cancelTool(); inTool = false } else toolsOpen = false
    }

    val startExport: (ExportFormat) -> Unit = { format ->
        showExportSheet = false
        vm.beginExport(format) // rendering starts now, in parallel with the ad
        val activity = context.findActivity()
        val adShown = activity != null && AdsManager.showRewarded(activity) { earned ->
            if (earned) {
                vm.revealExport()
            } else {
                vm.cancelExport()
                Toast.makeText(context, "Watch the full ad to export", Toast.LENGTH_SHORT).show()
            }
        }
        if (!adShown) vm.revealExport() // No ad available: never block the user.
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = vm::undoTools, enabled = ui.canUndoTools && ui.phase == Phase.Ready) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo the last tool")
                    }
                    IconButton(onClick = vm::redoTools, enabled = ui.canRedoTools && ui.phase == Phase.Ready) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
                    }
                    // Hold to see the photo as it was opened.
                    Box(
                        Modifier.size(48.dp).pointerInput(Unit) {
                            detectTapGestures(onPress = { showOriginal = true; tryAwaitRelease(); showOriginal = false })
                        },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.Compare, contentDescription = "Hold to compare with the original") }
                    PrimaryButton(
                        text = "Export",
                        enabled = ui.phase == Phase.Ready,
                        onClick = { showExportSheet = true },
                        modifier = Modifier.padding(end = 12.dp),
                    )
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                val source = ui.source
                when {
                    // While cropping, the normal canvas is not composed so it can never steal the drag.
                    source != null && cropMode -> Unit
                    source != null -> EditorCanvas(
                        source = source,
                        foreground = ui.foreground,
                        layers = ui.layers,
                        selectedId = ui.selectedId,
                        onCornerDrag = vm::dragCorner,
                        fgVersion = ui.fgVersion,
                        subjectEdit = ui.subjectEdit,
                        adjust = ui.adjust,
                        fxSource = ui.fxSource,
                        fxForeground = ui.fxForeground,
                        showOriginal = showOriginal,
                        tiltX = tiltX,
                        tiltY = tiltY,
                        scanPoses = if (ui.faceScanning) ui.faces else null,
                        scanProgress = scanProgress,
                        eraseMode = brushActive || healActive || (!toolsOpen && tool == Tool.ERASE && (if (eraseSubjectMode) ui.foreground != null else ui.selected != null)),
                        brushRel = if (brushActive || healActive) brushSettings.size else brushRel,
                        restoreMode = eraseRestore,
                        onEraseStart = {
                            when {
                                brushActive -> vm.beginStroke(brushSettings.kind, brushSettings.size, brushSettings.strength)
                                healActive -> vm.beginHeal(brushSettings.size)
                                else -> vm.beginErase()
                            }
                        },
                        onErase = { x0, y0, x1, y1, size, restore ->
                            when {
                                brushActive -> vm.strokeTo(x1 / source.width, y1 / source.height)
                                healActive -> vm.healTo(x1 / source.width, y1 / source.height)
                                else -> vm.eraseTo(x0, y0, x1, y1, size, restore)
                            }
                        },
                        onEraseEnd = { if (brushActive) vm.endStroke() else if (healActive) vm.endHeal() else vm.endErase() },
                        strokePreview = if (brushActive) ui.fx.strokes.lastOrNull() else if (healActive) ui.heal else null,
                        points = if (selectiveActive) ui.fx.points else emptyList(),
                        selectedPoint = ui.selectedPoint,
                        onPress = { x, y -> if (!selectiveActive) vm.pressAt(x, y) },
                        onTap = { x, y -> if (selectiveActive) vm.tapPoint(x / source.width, y / source.height) else vm.tapAt(x, y) },
                        onDoubleTap = { x, y -> vm.tapAt(x, y); tool = Tool.TEXT },
                        onTransform = vm::transformSelected,
                        modifier = Modifier.padding(12.dp),
                    )
                    ui.phase == Phase.Error -> ErrorState(ui.error ?: "Something went wrong", onExit)
                    else -> CircularProgressIndicator()
                }
                ScanOverlay(
                    visible = ui.phase == Phase.Detecting,
                    label = ui.statusText.ifBlank { "Finding the subject…" },
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                )
                // One-setting tools: swipe on the photo to pick a setting (up/down) and change it (left/right).
                if (toolsOpen && inTool && ui.phase == Phase.Ready && !cropMode) {
                    ToolGestureLayer(
                        photoTool, activeParam, { activeParam = it }, ui.adjust, ui.fx, vm::setAdjust, vm::setFx,
                        Modifier.fillMaxSize(),
                    )
                }
                if (ui.phase == Phase.Ready && !cropMode && !toolsOpen) {
                    val magicSrc = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    Surface(
                        onClick = { fb.toggle(); magicOpen = true },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.onSurface,
                        contentColor = MaterialTheme.colorScheme.surface,
                        interactionSource = magicSrc,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).pressScale(magicSrc),
                    ) {
                        Row(Modifier.padding(horizontal = 20.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Do magic", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                StatusPill(ui, Modifier.align(Alignment.TopCenter).padding(top = 16.dp))

                // Crop and Smooth live on the canvas itself so they are always visible, not hidden in the scrolling tabs.
                val srcForCrop = ui.source
                if (ui.phase == Phase.Ready && srcForCrop != null && !cropMode) {
                    Column(Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(end = 16.dp).horizontalScroll(rememberScrollState()),
                        ) {
                            CanvasPill(Icons.Outlined.Crop, "Crop") { fb.select(); smoothOpen = false; toolsOpen = false; cropMode = true }
                            CanvasPill(Icons.Outlined.AutoFixHigh, "Smooth", active = ui.adjust.smooth > 0.01f || smoothOpen) { fb.select(); toolsOpen = false; smoothOpen = !smoothOpen }
                            CanvasPill(Icons.Outlined.PhotoFilter, "Tools", active = toolsOpen || !ui.fx.isNeutral) {
                                fb.select(); smoothOpen = false
                                if (toolsOpen && inTool) { vm.applyTool(); inTool = false }
                                toolsOpen = !toolsOpen
                                inTool = false
                            }
                            CanvasPill(Icons.Outlined.ViewInAr, if (wobbleLoading) "…" else "3D", active = wobbleLoading) {
                                if (!wobbleLoading) {
                                    fb.select(); smoothOpen = false; toolsOpen = false; wobbleLoading = true
                                    scope.launch {
                                        val built = runCatching { vm.buildWobble(1280) }.getOrNull()
                                        wobbleLoading = false
                                        if (built != null) wobblePlanes = built else Toast.makeText(context, "Couldn't build the 3D photo", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            if (ui.canUndoCrop) CanvasPill(Icons.AutoMirrored.Filled.Undo, "Undo") { fb.undo(); vm.undoCrop() }
                        }
                        if (smoothOpen) {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                                border = com.hinterlay.app.ui.components.hairline(),
                            ) {
                                Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                                    com.hinterlay.app.ui.components.HairlineSlider(
                                        value = ui.adjust.smooth,
                                        onValueChange = { v -> vm.setAdjust { it.copy(smooth = v) } },
                                        range = 0f..1f,
                                        modifier = Modifier.width(210.dp),
                                    )
                                    Text("${(ui.adjust.smooth * 100).roundToInt()}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 10.dp).width(30.dp))
                                }
                            }
                        }
                    }
                }
                if (cropMode && srcForCrop != null) {
                    CropOverlay(
                        source = srcForCrop,
                        onApply = { l, t, r, b -> cropMode = false; vm.applyCrop(l, t, r, b) },
                        onCancel = { cropMode = false },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // Frosted glass: a photo zoomed under the controls shows through softly, and the text stays readable.
            Column(Modifier.fillMaxWidth().frostedPanel()) {
            // Drag handle: slide up for more room for the controls, down for a bigger photo.
            PanelHandle(
                expanded = panelDp > 160f,
                onDrag = { dy -> panelDp = (panelDp - dy).coerceIn(0f, maxPanel) },
                onToggle = { fb.select(); panelDp = if (panelDp > 160f) 0f else 260f },
            )
            LayerStrip(
                layers = ui.layers,
                selectedId = ui.selectedId,
                enabled = ui.phase == Phase.Ready,
                onSelect = vm::select,
                onAdd = { vm.addLayer(); tool = Tool.TEXT },
                onStamp = { text -> vm.addStamp(text); tool = Tool.TEXT },
            )

            val selected = ui.selected
            if (ui.phase == Phase.Ready && toolsOpen && !cropMode) {
                // Photo tools take the place of the text tabs, so the whole photo stays visible while you adjust.
                PhotoToolsPanel(
                    adjust = ui.adjust, fx = ui.fx,
                    onAdjust = vm::setAdjust, onFx = vm::setFx,
                    tool = photoTool,
                    onTool = {
                        photoTool = it; inTool = true; activeParam = 0; vm.beginTool()
                        if (it == PhotoTool.SELECTIVE || it == PhotoTool.BRUSH || it == PhotoTool.HEAL) vm.select(null)
                    },
                    inTool = inTool,
                    onApply = { fb.success(); vm.applyTool(); inTool = false },
                    onCancel = { fb.undo(); vm.cancelTool(); inTool = false },
                    activeParam = activeParam, onActiveParam = { activeParam = it },
                    brush = brushSettings, onBrush = { brushSettings = it },
                    onUndoStroke = vm::undoStroke, onClearStrokes = vm::clearStrokes,
                    canUndoHeal = ui.canUndoHeal, onUndoHeal = vm::undoHeal,
                    previewPhoto = lookPreview,
                    hasSubject = ui.foreground != null,
                    onBlendPhoto = vm::setBlendPhoto, onBlend = vm::setBlend, onBlendRemove = vm::removeBlend,
                    faces = ui.faces, faceScanning = ui.faceScanning, onPickMask = vm::pickFaceMask, onOpenFaces = vm::detectFaces,
                    selectedPoint = ui.selectedPoint,
                    onSelectPoint = vm::selectPoint,
                    onUpdatePoint = vm::updatePoint,
                    onRemovePoint = vm::removeSelectedPoint,
                    onAuto = { fb.success(); vm.autoEnhance() },
                    onClose = { fb.select(); if (inTool) { vm.applyTool(); inTool = false }; toolsOpen = false },
                    contentHeight = (panelDp.coerceAtLeast(280f) - 64f).dp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                )
            } else if (ui.phase == Phase.Ready) {
                ToolTabs(current = tool, onSelect = { fb.select(); tool = it; if (panelDp < 120f) panelDp = 220f })
                if (panelDp > 8f) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .height(panelDp.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        when {
                            tool == Tool.ADJUST -> AdjustPanel(ui.adjust, vm::setAdjust)
                            tool == Tool.STAMP -> StampPanel(onStamp = { text, big -> vm.addStamp(text, big); tool = Tool.TEXT })
                            selected == null -> Text(
                                "Tap a text on the photo to edit it, or add a new one. With nothing selected, pinch to zoom the photo and drag to pan (double-tap to reset).",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            else -> when (tool) {
                                Tool.TEXT -> TextPanel(selected, vm::updateSelected, ui.suggestions)
                                Tool.FONT -> FontPanel(selected, vm::updateSelected)
                                Tool.STYLE -> StylePanel(selected, vm::updateSelected)
                                Tool.COLOR -> ColorPanel(selected, vm::updateSelected, ui.palette)
                                Tool.EFFECTS -> EffectsPanel(selected, vm::updateSelected)
                                Tool.PERSPECTIVE -> PerspectivePanel(selected, vm::updateSelected)
                                Tool.ERASE -> ErasePanel(
                                    layer = selected,
                                    subject = eraseSubjectMode, onTarget = { eraseSubjectMode = it }, subjectAvailable = ui.foreground != null,
                                    restore = eraseRestore, onRestore = { eraseRestore = it },
                                    brush = brushRel, onBrush = { brushRel = it },
                                    strength = eraseStrength, onStrength = { eraseStrength = it },
                                    overlapOnly = eraseOverlap && ui.foreground != null, onOverlapOnly = { eraseOverlap = it },
                                    onUndo = vm::undoErase, onReset = vm::clearErase,
                                )
                                Tool.LAYER -> LayerPanel(selected, ui.subjectFound, vm)
                                else -> Unit
                            }
                        }
                    }
                }
            }
            }
        }
    }

    if (showExportSheet) {
        ExportSheet(onDismiss = { showExportSheet = false }, onExport = startExport)
    }

    if (exportVisible && export != ExportState.Idle) {
        ExportDialog(
            state = export,
            onShare = { file ->
                context.safeStart(Referral.shareIntent(context, file))
            },
            onSticker = { showStickerChoice = true },
            onWallpaper = {
                scope.launch {
                    if (vm.prepareWallpaper()) context.safeStart(WallpaperStore.setIntent(context))
                    else Toast.makeText(context, "Couldn't prepare the wallpaper", Toast.LENGTH_SHORT).show()
                }
            },
            onStory = { file -> context.safeStart(Exporter.storyIntent(context, file)) },
            onWhatsapp = { file ->
                val send = Exporter.whatsappIntent(context, file)
                if (send != null) {
                    Toast.makeText(context, "Choose My status at the top", Toast.LENGTH_SHORT).show()
                    context.safeStart(send)
                } else Toast.makeText(context, "WhatsApp isn't installed", Toast.LENGTH_SHORT).show()
            },
            onDismiss = vm::dismissExport,
        )
    }

    if (magicOpen) MagicSheet(vm, onDismiss = { magicOpen = false })

    if (showPushAsk) {
        AlertDialog(
            onDismissRequest = { showPushAsk = false; com.hinterlay.app.cloud.Push.markAsked() },
            icon = { Icon(Icons.Outlined.AutoAwesome, null) },
            title = { Text("Want fresh ideas?") },
            text = { Text("Get an occasional tip or new style idea. You can turn this off any time in More.") },
            confirmButton = {
                SubtleButton("Yes, notify me", onClick = {
                    showPushAsk = false
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        pushPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    } else com.hinterlay.app.cloud.Push.enabled = true
                })
            },
            dismissButton = { SubtleButton("Not now", onClick = { showPushAsk = false; com.hinterlay.app.cloud.Push.markAsked() }) },
        )
    }

    if (showStickerChoice) {
        val make: (Boolean) -> Unit = { starter ->
            showStickerChoice = false
            scope.launch {
                Toast.makeText(context, "Making sticker${if (starter) "s" else ""}…", Toast.LENGTH_SHORT).show()
                val (added, total) = vm.addSticker(starter)
                val min = com.hinterlay.app.sticker.StickerStore.MIN_STICKERS
                val message = when {
                    added == 0 -> "Couldn't make a sticker. The pack may be full."
                    total >= min -> "Added $added. Your pack is ready: open WhatsApp stickers from the home menu."
                    else -> "Added $added. $total of $min needed for WhatsApp. Make ${min - total} more, or choose Starter pack."
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
        com.hinterlay.app.ui.screens.StickerChoiceDialog(
            existing = com.hinterlay.app.sticker.StickerStore.list(context).size,
            onJustOne = { make(false) },
            onStarterPack = { make(true) },
            onDismiss = { showStickerChoice = false },
        )
    }

    wobblePlanes?.let { planes -> WobbleDialog(planes = planes, onClose = { wobblePlanes = null }) }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { if (!savingDraft) confirmExit = false },
            title = { Text("Leave the editor?") },
            text = { Text("You have changes that aren't saved. Save keeps this edit in your Library so you can finish it later. Photos you already exported stay in your gallery.") },
            confirmButton = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(
                        text = if (savingDraft) "Saving…" else "Save to Library",
                        enabled = !savingDraft,
                        onClick = {
                            savingDraft = true
                            vm.saveDraft { ok ->
                                savingDraft = false
                                confirmExit = false
                                Toast.makeText(context, if (ok) "Saved to your Library" else "Couldn't save. Try again.", Toast.LENGTH_SHORT).show()
                                if (ok) onExit()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    com.hinterlay.app.ui.components.TonalButton("Keep editing", onClick = { confirmExit = false }, modifier = Modifier.fillMaxWidth())
                    SubtleButton("Discard changes", onClick = { confirmExit = false; onExit() }, modifier = Modifier.fillMaxWidth())
                }
            },
            shape = MaterialTheme.shapes.large,
        )
    }
}

// ---------------------------------------------------------------------------------------
// Canvas
// ---------------------------------------------------------------------------------------

@Composable
private fun EditorCanvas(
    source: Bitmap,
    foreground: Bitmap?,
    layers: List<TextLayer>,
    selectedId: Long?,
    onCornerDrag: (Int, Float, Float) -> Unit,
    subjectEdit: Compositor.SubjectEdit?,
    adjust: com.hinterlay.app.render.PhotoAdjust,
    fxSource: Bitmap?, // the photo with the photo tools applied (same size as source), null when none are used
    fxForeground: Bitmap?,
    showOriginal: Boolean, // true while the compare button is held: draw the untouched photo
    tiltX: Float, // phone tilt for 3D text looks (0 when none is in use)
    tiltY: Float,
    scanPoses: List<com.hinterlay.app.camera.FacePose>?, // faces to draw the scan mesh on while a mask is being chosen
    scanProgress: Float,
    points: List<com.hinterlay.app.render.SelectivePoint>, // selective-adjust markers, empty unless that tool is open
    selectedPoint: Int,
    fgVersion: Int, // changes when the subject is edited, so the preview redraws
    eraseMode: Boolean,
    brushRel: Float,
    restoreMode: Boolean,
    onEraseStart: () -> Unit,
    onErase: (Float, Float, Float, Float, Float, Boolean) -> Unit,
    onEraseEnd: () -> Unit, // the finger lifted after painting
    strokePreview: com.hinterlay.app.render.BrushStroke?, // the brush stroke being painted, drawn faintly until it is applied
    onPress: (Float, Float) -> Unit,
    onTap: (Float, Float) -> Unit,
    onDoubleTap: (Float, Float) -> Unit,
    onTransform: (Float, Float, Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val iw = source.width.toFloat()
    val ih = source.height.toFloat()
    val selectionColor = MaterialTheme.colorScheme.primary.toArgb()
    val density = LocalDensity.current.density

    // View zoom/pan (not part of the saved design). Active when no text is selected, so
    // pinching with a text selected resizes the text and pinching on empty space zooms the photo.
    var viewZoom by remember { mutableFloatStateOf(1f) }
    var viewPan by remember { mutableStateOf(Offset.Zero) }
    val hasSelection by rememberUpdatedState(selectedId != null)
    val selectedLayer by rememberUpdatedState(layers.firstOrNull { it.id == selectedId && it.visible })
    var cornerIdx by remember { mutableIntStateOf(-1) }
    val erasing by rememberUpdatedState(eraseMode)
    val brush by rememberUpdatedState(brushRel)
    val restoring by rememberUpdatedState(restoreMode)
    var painting by remember { mutableStateOf(false) }
    var brushPos by remember { mutableStateOf<Offset?>(null) }

    fun scaleFor(size: IntSize) = min(size.width / iw, size.height / ih)
    fun toImage(size: IntSize, screen: Offset): Offset {
        val c = Offset(size.width / 2f, size.height / 2f)
        val p = (screen - viewPan - c) / viewZoom + c
        val s = scaleFor(size)
        val left = (size.width - iw * s) / 2f
        val top = (size.height - ih * s) / 2f
        return Offset((p.x - left) / s, (p.y - top) / s)
    }

    Canvas(
        modifier
            .fillMaxSize()
            // Perspective handles: runs on the Initial pass so a grabbed corner wins over move/zoom.
            .pointerInput(source) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val sel = selectedLayer ?: return@awaitEachGesture
                    if (sel.perspective == null) return@awaitEachGesture
                    val corners = Compositor.imageCorners(sel, iw.toInt(), ih.toInt()) ?: return@awaitEachGesture
                    val p = toImage(size, down.position)
                    val hitRadius = 30.dp.toPx() / (scaleFor(size) * viewZoom)
                    var best = -1
                    var bestDist = hitRadius
                    for (i in 0 until 4) {
                        val d = hypot(corners[i * 2] - p.x, corners[i * 2 + 1] - p.y)
                        if (d <= bestDist) { best = i; bestDist = d }
                    }
                    if (best < 0) return@awaitEachGesture
                    cornerIdx = best
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val q = toImage(size, change.position)
                        onCornerDrag(best, q.x, q.y)
                        change.consume()
                        if (!change.pressed) break
                    }
                    cornerIdx = -1
                }
            }
            // Eraser: one finger paints; a second finger cancels so pinch-zoom still works.
            .pointerInput(source) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (!erasing) return@awaitEachGesture
                    var last = toImage(size, down.position)
                    var started = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.size > 1) { // second finger: hand over to zoom/pan
                            painting = false; brushPos = null
                            if (started) onEraseEnd()
                            return@awaitEachGesture
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!started && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                            started = true
                            painting = true
                            onEraseStart()
                            onErase(last.x, last.y, last.x, last.y, brush, restoring)
                        }
                        if (started) {
                            val q = toImage(size, change.position)
                            onErase(last.x, last.y, q.x, q.y, brush, restoring)
                            last = q
                            brushPos = change.position
                            change.consume()
                        }
                        if (!change.pressed) break
                    }
                    painting = false
                    brushPos = null
                    if (started) onEraseEnd()
                }
            }
            // Touching a layer selects it immediately so a drag moves that layer.
            .pointerInput(source) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (erasing) return@awaitEachGesture // keep the selected layer while erasing
                    val p = toImage(size, down.position)
                    onPress(p.x, p.y)
                }
            }
            .pointerInput(source) {
                detectTapGestures(
                    onTap = { val p = toImage(size, it); onTap(p.x, p.y) },
                    onDoubleTap = {
                        val p = toImage(size, it)
                        onDoubleTap(p.x, p.y)
                        if (!hasSelection) { viewZoom = 1f; viewPan = Offset.Zero } // double-tap empty space resets the view
                    },
                )
            }
            .pointerInput(source) {
                detectTransformGestures { centroid, pan, zoom, rotation ->
                    if (cornerIdx >= 0 || painting) return@detectTransformGestures
                    if (hasSelection && !erasing) {
                        val s = scaleFor(size) * viewZoom
                        onTransform(pan.x / (iw * s), pan.y / (ih * s), zoom, rotation)
                    } else {
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val newZoom = (viewZoom * zoom).coerceIn(1f, 6f)
                        val k = newZoom / viewZoom
                        // Keep the point under the fingers fixed while zooming, then follow the pan.
                        val pan2 = (viewPan + (centroid - c) * (1 - k) + pan * 1f).let { p ->
                            val maxX = size.width * (newZoom - 1f) / 2f
                            val maxY = size.height * (newZoom - 1f) / 2f
                            Offset(p.x.coerceIn(-maxX, maxX), p.y.coerceIn(-maxY, maxY))
                        }
                        viewZoom = newZoom
                        viewPan = if (newZoom <= 1f) Offset.Zero else pan2
                    }
                }
            }
    ) {
        val s = min(size.width / iw, size.height / ih)
        val left = (size.width - iw * s) / 2f
        val top = (size.height - ih * s) / 2f
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            nc.save()
            nc.translate(viewPan.x, viewPan.y)
            nc.scale(viewZoom, viewZoom, size.width / 2f, size.height / 2f)
            nc.translate(left, top)
            nc.scale(s, s)
            nc.clipRect(0f, 0f, iw, ih)
            Compositor.setLiveTilt(tiltX, tiltY)
            if (showOriginal) nc.drawBitmap(source, 0f, 0f, null)
            else Compositor.render(nc, fxSource ?: source, if (fxSource != null) fxForeground ?: foreground else foreground, layers, subjectEdit, adjust)
            layers.firstOrNull { it.id == selectedId && it.visible }?.let {
                Compositor.drawSelection(nc, it, source.width, source.height, selectionColor, 1.5f * density / (s * viewZoom))
            }
            // The face scan: a mesh over each face, sweeping down, just before the mask lands.
            scanPoses?.forEach { com.hinterlay.app.camera.FaceMesh.draw(nc, it, scanProgress) }
            // The brush stroke being painted, so there is feedback before the photo is rebuilt on release.
            if (painting && strokePreview != null && strokePreview.pts.size >= 2) {
                val pts = strokePreview.pts
                val path = android.graphics.Path().apply {
                    moveTo(pts[0] * iw, pts[1] * ih)
                    if (pts.size == 2) lineTo(pts[0] * iw + 0.01f, pts[1] * ih) else for (i in 2 until pts.size step 2) lineTo(pts[i] * iw, pts[i + 1] * ih)
                }
                val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND
                    strokeJoin = android.graphics.Paint.Join.ROUND; strokeWidth = strokePreview.radius * maxOf(iw, ih) * 2f
                    color = android.graphics.Color.argb(70, 255, 255, 255)
                }
                nc.drawPath(path, p)
            }
            // Selective-adjust points: a ring showing the area, a dot at the centre. Light ring over a dark one reads on any photo.
            if (points.isNotEmpty()) {
                val unit = density / (s * viewZoom)
                val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE }
                val dot = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.FILL }
                points.forEachIndexed { i, p ->
                    val cx = p.x * iw
                    val cy = p.y * ih
                    val r = p.radius * maxOf(iw, ih)
                    val on = i == selectedPoint
                    ring.strokeWidth = 3f * unit; ring.color = android.graphics.Color.argb(120, 0, 0, 0)
                    nc.drawCircle(cx, cy, r, ring)
                    ring.strokeWidth = 1.5f * unit; ring.color = android.graphics.Color.argb(if (on) 255 else 150, 255, 255, 255)
                    nc.drawCircle(cx, cy, r, ring)
                    dot.color = android.graphics.Color.argb(160, 0, 0, 0); nc.drawCircle(cx, cy, 9f * unit, dot)
                    dot.color = android.graphics.Color.WHITE; nc.drawCircle(cx, cy, (if (on) 6.5f else 4.5f) * unit, dot)
                }
            }
            nc.restore()
        }
        // Brush preview ring while painting.
        brushPos?.let {
            drawCircle(
                Color.White, radius = brushRel * iw * s * viewZoom, center = it,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

/** A small outlined round button on the canvas with a text label, black and white. */
@Composable
private fun CanvasPill(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface.copy(alpha = 0.80f),
        contentColor = if (active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f), MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)))),
        interactionSource = src,
        modifier = Modifier.pressScale(src),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun StatusPill(ui: EditorUiState, modifier: Modifier) {
    val busy = ui.phase == Phase.Loading || ui.phase == Phase.Detecting
    val text = when (ui.phase) {
        Phase.Loading, Phase.Detecting -> ui.statusText
        Phase.Ready -> if (ui.subjectFound) "Subject found — text sits behind it" else "No clear subject — text goes on top"
        Phase.Error -> null
    }
    var visible by remember(ui.phase) { mutableStateOf(true) }
    LaunchedEffect(ui.phase) {
        if (ui.phase == Phase.Ready) {
            delay(3_500)
            visible = false
        }
    }
    AnimatedVisibility(visible = visible && text != null, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.92f),
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(text.orEmpty(), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun ErrorState(message: String, onBack: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        SecondaryButton("Choose another photo", onClick = onBack)
    }
}

// ---------------------------------------------------------------------------------------
// Layer strip + tabs
// ---------------------------------------------------------------------------------------

@Composable
private fun LayerStrip(
    layers: List<TextLayer>,
    selectedId: Long?,
    enabled: Boolean,
    onSelect: (Long) -> Unit,
    onAdd: () -> Unit,
    onStamp: (String) -> Unit,
) {
    val stamps = rememberStampActions(onStamp)
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            com.hinterlay.app.ui.components.TonalButton(
                "Add text",
                onClick = onAdd,
                enabled = enabled,
                icon = Icons.Filled.Add,
            )
        }
        // One-tap capsules: today's day, date and your place. Action chips, so never "selected".
        item { Chip(selected = false, enabled = enabled, onClick = stamps.day, label = { Text(com.hinterlay.app.util.Place.dayName()) }) }
        item { Chip(selected = false, enabled = enabled, onClick = stamps.date, label = { Text(com.hinterlay.app.util.Place.dateText()) }) }
        item { Chip(selected = false, enabled = enabled, onClick = stamps.place, label = { Text("📍 Place") }) }
        items(layers.asReversed(), key = { it.id }) { layer ->
            Chip(
                selected = layer.id == selectedId,
                onClick = { onSelect(layer.id) },
                label = {
                    Text(
                        layer.text.ifBlank { "Empty" }.replace('\n', ' ').take(14),
                        maxLines = 1,
                    )
                },
                leadingIcon = {
                    Icon(
                        if (layer.behindSubject) Icons.Outlined.Layers else Icons.Outlined.TextFields,
                        contentDescription = if (layer.behindSubject) "Behind subject" else "In front",
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
    }
}

/** Fluent-style pivot: icon + label with an underline indicator. */
@Composable
private fun ToolTabs(current: Tool, onSelect: (Tool) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
    ) {
        Tool.entries.forEach { t ->
            val selected = t == current
            // Material 3 navigation-bar item: icon sits in a pill that fills when selected.
            Column(
                Modifier
                    .clip(MaterialTheme.shapes.large)
                    .selectable(selected = selected, onClick = { onSelect(t) }, role = Role.Tab)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(width = 56.dp, height = 28.dp)
                        .clip(CircleShape)
                        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        t.icon, contentDescription = null, modifier = Modifier.size(20.dp),
                        tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    t.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp),
                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Tool panels
// ---------------------------------------------------------------------------------------

private typealias Edit = ((TextLayer) -> TextLayer) -> Unit

@Composable
private fun TextPanel(layer: TextLayer, onChange: Edit, suggestions: List<String>) {
    OutlinedTextField(
        value = layer.text,
        onValueChange = { v -> onChange { it.copy(text = v) } },
        label = { Text("Text") },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 3,
        shape = MaterialTheme.shapes.small,
    )
    if (suggestions.isNotEmpty()) {
        // On-device AI: caption ideas based on what is in the photo.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoAwesome, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text("Ideas for your photo", style = MaterialTheme.typography.labelLarge)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(suggestions) { word ->
                androidx.compose.material3.SuggestionChip(
                    onClick = { onChange { it.copy(text = word) } },
                    label = { Text(word) },
                )
            }
        }
    }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        val options = TextAlignChoice.entries
        options.forEachIndexed { index, choice ->
            SegmentedButton(
                selected = layer.align == choice,
                onClick = { onChange { it.copy(align = choice) } },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                val icon = when (choice) {
                    TextAlignChoice.START -> Icons.Filled.FormatAlignLeft
                    TextAlignChoice.CENTER -> Icons.Filled.FormatAlignCenter
                    TextAlignChoice.END -> Icons.Filled.FormatAlignRight
                }
                Icon(icon, contentDescription = choice.label, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun FontPanel(layer: TextLayer, onChange: Edit) {
    Text("Typeface", style = MaterialTheme.typography.labelLarge)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(FontChoice.picker) { font ->
            val selected = layer.font == font
            val family = remember(font) { FontFamily(Compositor.typeface(font, 400, false)) }
            Surface(
                onClick = { onChange { it.copy(font = font) } },
                shape = MaterialTheme.shapes.medium,
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                border = BorderStroke(
                    if (selected) 2.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ),
                modifier = Modifier.size(width = 132.dp, height = 70.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    // The same sample word in every font, so they can be compared at a glance.
                    Text(com.hinterlay.app.editor.DEFAULT_TEXT, fontFamily = family, fontSize = 22.sp, maxLines = 1)
                    Text(font.label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
    }
    Text("Letter case", style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextCase.entries.forEach { c ->
            Chip(
                selected = layer.textCase == c,
                onClick = { onChange { it.copy(textCase = c) } },
                label = { Text(c.label) },
            )
        }
    }
    // Google Fonts are single-weight files, so weight/italic only apply to the system fonts.
    val styleable = layer.font.asset == null
    Text("Weight & slant", style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FontWeightChoice.entries.forEach { w ->
            Chip(
                selected = layer.weight == w,
                enabled = styleable,
                onClick = { onChange { it.copy(weight = w) } },
                label = { Text(w.label) },
            )
        }
        Chip(
            selected = layer.italic,
            enabled = styleable,
            onClick = { onChange { it.copy(italic = !it.italic) } },
            label = { Text("Italic") },
        )
    }
    if (!styleable) {
        Text(
            "${layer.font.label} has one built-in style. Pick Sans, Serif, Condensed or Mono to change weight.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErasePanel(
    layer: TextLayer,
    subject: Boolean,
    onTarget: (Boolean) -> Unit,
    subjectAvailable: Boolean,
    restore: Boolean,
    onRestore: (Boolean) -> Unit,
    brush: Float,
    onBrush: (Float) -> Unit,
    strength: Float,
    onStrength: (Float) -> Unit,
    overlapOnly: Boolean,
    onOverlapOnly: (Boolean) -> Unit,
    onUndo: () -> Unit,
    onReset: () -> Unit,
) {
    if (subjectAvailable) {
        // What the brush works on: the text, or the cut-out of the person/object itself.
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !subject, onClick = { onTarget(false) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                icon = { SegmentedButtonDefaults.Icon(!subject) },
            ) { Text("Text") }
            SegmentedButton(
                selected = subject, onClick = { onTarget(true) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                icon = { SegmentedButtonDefaults.Icon(subject) },
            ) { Text("Subject") }
        }
    }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = !restore, onClick = { onRestore(false) },
            shape = SegmentedButtonDefaults.itemShape(0, 2),
            icon = { SegmentedButtonDefaults.Icon(!restore) },
        ) { Text("Erase") }
        SegmentedButton(
            selected = restore, onClick = { onRestore(true) },
            shape = SegmentedButtonDefaults.itemShape(1, 2),
            icon = { SegmentedButtonDefaults.Icon(restore) },
        ) { Text("Bring back") }
    }
    LabeledSlider("Brush size", brush, 0.01f..0.15f, "${(brush * 100).roundToInt()}") { onBrush(it) }
    LabeledSlider("Strength", strength, 0.05f..1f, "${(strength * 100).roundToInt()}%") { onStrength(it) }
    if (subjectAvailable) {
        SwitchRow(
            "Only where text and subject overlap", overlapOnly, onOverlapOnly,
            subtitle = if (subject) "Erases the subject only over the text behind it, at the strength above, so the text shows through."
            else "Erases the text only where the subject is, so the rest of the text stays.",
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton("Undo", onClick = onUndo)
        SecondaryButton("Reset", onClick = onReset, enabled = subject || layer.eraseMask != null)
    }
    Text(
        if (subject) "Rub over the person or object to remove that part: the photo behind it is filled in softly, and text behind shows through. Bring back restores it."
        else if (layer.eraseMask == null) "Drag a finger over the text to rub parts of it out. Pinch with two fingers to zoom in for detail."
        else "Erased parts are hidden. Use Bring back to restore them.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "Tip: put the text In front, then erase the part that should sit behind the subject.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val PerspectivePresets = listOf(
    "Wall (right)" to listOf(0f, 0.02f, 0f, 0.18f, 0f, -0.18f, 0f, -0.02f),
    "Wall (left)" to listOf(0f, 0.18f, 0f, 0.02f, 0f, -0.02f, 0f, -0.18f),
    "Floor" to listOf(0.28f, 0.1f, -0.28f, 0.1f, 0f, 0f, 0f, 0f),
    "Ceiling" to listOf(0f, 0f, 0f, 0f, -0.28f, -0.1f, 0.28f, -0.1f),
)

@Composable
private fun PerspectivePanel(layer: TextLayer, onChange: Edit) {
    SwitchRow(
        "Perspective warp", layer.perspective != null,
        { on ->
            if (on) com.hinterlay.app.cloud.Track.event("perspective_used")
            onChange { it.copy(perspective = if (on) List(8) { 0f } else null) }
        },
        subtitle = "Drag the 4 corner dots to match a wall, sign or floor",
    )
    if (layer.perspective != null) {
        Text("Quick angles", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PerspectivePresets.forEach { (name, offsets) ->
                Chip(selected = false, onClick = { onChange { it.copy(perspective = offsets) } }, label = { Text(name) })
            }
            Chip(selected = false, onClick = { onChange { it.copy(perspective = List(8) { 0f }) } }, label = { Text("Reset") })
        }
        Text(
            "Tip: turn on Behind subject, then line the corners up with the edges of the surface so the text looks painted on.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StylePanel(layer: TextLayer, onChange: Edit) {
    LabeledSlider("Size", layer.sizeRel, 0.03f..0.6f, "${(layer.sizeRel * 100).roundToInt()}") { v ->
        onChange { it.copy(sizeRel = v) }
    }
    LabeledSlider("Letter spacing", layer.letterSpacing, -0.1f..0.5f, "%.2f".format(layer.letterSpacing)) { v ->
        onChange { it.copy(letterSpacing = v) }
    }
    LabeledSlider("Opacity", layer.opacity, 0.1f..1f, "${(layer.opacity * 100).roundToInt()}%") { v ->
        onChange { it.copy(opacity = v) }
    }
    LabeledSlider("Rotation", layer.rotation, -180f..180f, "${layer.rotation.roundToInt()}°") { v ->
        onChange { it.copy(rotation = v) }
    }
}

@Composable
private fun SwatchRow(selected: Int, colors: List<Int> = Swatches, onPick: (Int) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(colors) { c ->
            val isSelected = c == selected
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .border(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        shape = CircleShape,
                    )
                    .clickable { onPick(c) }
            )
        }
    }
}

@Composable
private fun ColorPanel(layer: TextLayer, onChange: Edit, palette: List<Int>) {
    if (palette.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoAwesome, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text("Colours from your photo", style = MaterialTheme.typography.labelLarge)
        }
        SwatchRow(layer.color, palette) { c -> onChange { it.copy(color = c) } }
    }
    SwatchRow(layer.color) { c -> onChange { it.copy(color = c) } }
    val hsl = remember(layer.color) { FloatArray(3).also { ColorUtils.colorToHSL(layer.color, it) } }
    LabeledSlider("Hue", hsl[0], 0f..360f, "${hsl[0].roundToInt()}°") { h ->
        val lightness = if (hsl[2] in 0.15f..0.85f) hsl[2] else 0.5f
        onChange { it.copy(color = ColorUtils.HSLToColor(floatArrayOf(h, 0.85f, lightness))) }
    }
    LabeledSlider("Lightness", hsl[2], 0f..1f, "${(hsl[2] * 100).roundToInt()}%") { l ->
        onChange { it.copy(color = ColorUtils.HSLToColor(floatArrayOf(hsl[0], hsl[1], l))) }
    }
}

@Composable
private fun EffectsPanel(layer: TextLayer, onChange: Edit) {
    Text("3D look", style = MaterialTheme.typography.labelLarge)
    MaterialStrip(layer, onChange)
    val isMaterial = layer.material != com.hinterlay.app.editor.TextMaterial.NONE
    if (isMaterial) {
        LabeledSlider("Light", layer.materialLight, 0f..1f, "${(layer.materialLight * 100).roundToInt()}") { v ->
            onChange { it.copy(materialLight = v) }
        }
        Text(
            "Tilt your phone and the reflections move. The Light slider sets how the saved photo looks. This look replaces the text colour, outline and bubble.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val outlined = layer.strokeWidth > 0f
    if (!isMaterial) SwitchRow("Outline", outlined, { on -> onChange { it.copy(strokeWidth = if (on) 0.04f else 0f) } })
    if (outlined && !isMaterial) {
        LabeledSlider("Outline width", layer.strokeWidth, 0.01f..0.15f, "${(layer.strokeWidth * 100).roundToInt()}") { v ->
            onChange { it.copy(strokeWidth = v) }
        }
        SwatchRow(layer.strokeColor) { c -> onChange { it.copy(strokeColor = c) } }
    }
    SwitchRow("Drop shadow", layer.shadow, { on -> onChange { it.copy(shadow = on) } })
    if (layer.shadow) {
        LabeledSlider("Shadow strength", layer.shadowStrength, 0.05f..1f, "${(layer.shadowStrength * 100).roundToInt()}%") { v ->
            onChange { it.copy(shadowStrength = v) }
        }
        Text("Shadow colour", style = MaterialTheme.typography.labelLarge)
        SwatchRow(layer.shadowColor) { c -> onChange { it.copy(shadowColor = c) } }
    }
    if (!isMaterial) SwitchRow(
        "Bubble", layer.bubble, { on -> onChange { it.copy(bubble = on) } },
        subtitle = "Glossy inflated look, uses the text colour",
    )
}

/** The 3D looks as small previews: tap one to apply it to the selected text. */
@Composable
private fun MaterialStrip(layer: TextLayer, onChange: Edit) {
    val typeface = remember { Compositor.typeface(com.hinterlay.app.editor.FontChoice.BOWLBY, 400, false) }
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(com.hinterlay.app.editor.TextMaterial.entries.toList()) { m ->
            val selected = layer.material == m
            val thumb by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, m) {
                value = if (m == com.hinterlay.app.editor.TextMaterial.NONE) null
                else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    com.hinterlay.app.render.TextMaterials.thumbnail(m, typeface).asImageBitmap()
                }
            }
            Surface(
                onClick = { onChange { it.copy(material = m) } },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.width(92.dp).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(76.dp, 56.dp), contentAlignment = Alignment.Center) {
                        val t = thumb
                        if (t != null) androidx.compose.foundation.Image(bitmap = t, contentDescription = null, modifier = Modifier.fillMaxSize())
                        else Text("Ab", style = MaterialTheme.typography.headlineSmall)
                    }
                    Text(m.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun LayerPanel(layer: TextLayer, subjectFound: Boolean, vm: EditorViewModel) {
    SwitchRow(
        title = "Behind subject",
        checked = layer.behindSubject && subjectFound,
        onCheckedChange = { on -> vm.updateSelected { it.copy(behindSubject = on) } },
        subtitle = if (subjectFound) "Text tucks behind the detected subject" else "No subject was detected in this photo",
        enabled = subjectFound,
    )
    SwitchRow("Visible", layer.visible, { on -> vm.updateSelected { it.copy(visible = on) } })
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SecondaryButton("Duplicate", onClick = vm::duplicateSelected, icon = Icons.Outlined.ContentCopy)
        SecondaryButton("Forward", onClick = { vm.moveSelected(1) }, icon = Icons.Outlined.ArrowUpward)
        SecondaryButton("Backward", onClick = { vm.moveSelected(-1) }, icon = Icons.Outlined.ArrowDownward)
        SecondaryButton(
            "Delete",
            onClick = vm::deleteSelected,
            icon = Icons.Outlined.Delete,
            contentColor = MaterialTheme.colorScheme.error,
        )
    }
}

// ---------------------------------------------------------------------------------------
// Export
// ---------------------------------------------------------------------------------------

@Composable
private fun ExportSheet(onDismiss: () -> Unit, onExport: (ExportFormat) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var format by rememberSaveable { mutableStateOf(ExportFormat.ORIGINAL) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
    ) {
        com.hinterlay.app.ui.components.SheetSound()
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Export", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            ExportFormat.entries.forEach { f ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .selectable(selected = format == f, onClick = { format = f }, role = Role.RadioButton)
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = format == f, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(f.label, style = MaterialTheme.typography.bodyLarge)
                        Text(f.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AdBadge()
                Spacer(Modifier.width(8.dp))
                Text(
                    "Watch a short ad to export. Your photo is prepared while the ad plays.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            PrimaryButton(
                text = "Watch ad & export",
                icon = Icons.Outlined.PlayCircleOutline,
                onClick = { onExport(format) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            )
        }
    }
}

@Composable
private fun ExportDialog(
    state: ExportState,
    onShare: (java.io.File) -> Unit,
    onStory: (java.io.File) -> Unit,
    onWhatsapp: (java.io.File) -> Unit,
    onWallpaper: () -> Unit,
    onSticker: () -> Unit,
    onDismiss: () -> Unit,
) {
    val running = state is ExportState.Running
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !running,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = com.hinterlay.app.ui.components.hairline(),
            modifier = Modifier.padding(20.dp).fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (state) {
                    is ExportState.Running -> {
                        val progress by animateFloatAsState(state.progress, tween(500), label = "export")
                        Text("Exporting your photo", style = MaterialTheme.typography.titleMedium)
                        com.hinterlay.app.ui.components.HairlineProgress(
                            progress = progress,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${(progress * 100).roundToInt()}% · ${state.stage}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is ExportState.Done -> {
                        Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                        Text("Ready to share", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (state.savedToGallery) "Saved to Pictures/Hinterlay" else "Couldn't save to the gallery, but you can still share it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            PrimaryButton("Share to Story", onClick = { onStory(state.file) }, modifier = Modifier.weight(1f))
                            SecondaryButton("Share", onClick = { onShare(state.file) }, icon = Icons.Outlined.Share, modifier = Modifier.weight(1f))
                        }
                        SecondaryButton(
                            "WhatsApp status", onClick = { onWhatsapp(state.file) },
                            icon = Icons.Outlined.Share, modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Sharing with a friend earns you an ad-free session.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SecondaryButton(
                            "Make WhatsApp sticker", onClick = onSticker,
                            icon = Icons.Outlined.EmojiEmotions, modifier = Modifier.fillMaxWidth(),
                        )
                        SecondaryButton(
                            "Set as 3D tilt wallpaper", onClick = onWallpaper,
                            icon = Icons.Outlined.AutoAwesome, modifier = Modifier.fillMaxWidth(),
                        )
                        SubtleButton("Done", onClick = onDismiss)
                    }
                    is ExportState.Failed -> {
                        Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp))
                        Text(state.message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                        SubtleButton("Close", onClick = onDismiss)
                    }
                    ExportState.Idle -> Unit
                }
                // Banner sits beside the progress, never over it, and is labelled by the SDK.
                AdBanner(Modifier.padding(top = 4.dp))
            }
        }
    }
}
