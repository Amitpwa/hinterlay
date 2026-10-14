package com.hinterlay.app.ui.editor

import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Deblur
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.FilterFrames
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.material.icons.outlined.HdrOn
import androidx.compose.material.icons.outlined.Healing
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Vignette
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.hinterlay.app.ui.components.Chip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.hinterlay.app.render.BrushKind
import com.hinterlay.app.render.FxParams
import com.hinterlay.app.render.Look
import com.hinterlay.app.render.PhotoAdjust
import com.hinterlay.app.render.SelectivePoint
import com.hinterlay.app.ui.theme.glass
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.remember
import kotlin.math.roundToInt

/** Brush options kept in the editor: what the next stroke does, how big it is and how strong. */
data class BrushSettings(val kind: BrushKind = BrushKind.LIGHTEN, val size: Float = 0.06f, val strength: Float = 0.5f)

enum class PhotoTool(val label: String) {
    TUNE("Tune"), WHITE("White balance"), DETAILS("Details"), CURVES("Curves"), SELECTIVE("Selective"), BRUSH("Brush"), HEAL("Heal"),
    FACES("Faces"), FRAMES("Frames"), LOOKS("Looks"), BLEND("Blend"), BLUR("Blur"), GLOW("Glow"), HDR("HDR"), GRAIN("Grain"), VIGNETTE("Vignette"), GLITCH("Glitch"),
}

private fun pct(v: Float) = "${(v * 100).roundToInt()}"

private fun signed(v: Float, centred: Boolean): String {
    val n = (v * 100).roundToInt()
    return if (centred && n > 0) "+$n" else "$n"
}

// ---- One-setting tools: a single setting at a time, picked by name or by swiping on the photo ----------

/** A setting of a tool. [setA] changes the photo adjustments, [setF] the photo-tool settings (one of them is set). */
class ToolParam(
    val label: String, val min: Float, val max: Float,
    val get: (PhotoAdjust, FxParams) -> Float,
    val setA: ((PhotoAdjust, Float) -> PhotoAdjust)? = null,
    val setF: ((FxParams, Float) -> FxParams)? = null,
) {
    val centred get() = min < 0f
}

fun paramsFor(tool: PhotoTool): List<ToolParam> = when (tool) {
    PhotoTool.TUNE -> listOf(
        ToolParam("Brightness", -1f, 1f, { a, _ -> a.brightness }, setA = { a, v -> a.copy(brightness = v) }),
        ToolParam("Contrast", -1f, 1f, { a, _ -> a.contrast }, setA = { a, v -> a.copy(contrast = v) }),
        ToolParam("Saturation", -1f, 1f, { a, _ -> a.saturation }, setA = { a, v -> a.copy(saturation = v) }),
        ToolParam("Ambiance", -1f, 1f, { _, f -> f.ambiance }, setF = { f, v -> f.copy(ambiance = v) }),
        ToolParam("Highlights", -1f, 1f, { _, f -> f.highlights }, setF = { f, v -> f.copy(highlights = v) }),
        ToolParam("Shadows", -1f, 1f, { _, f -> f.shadows }, setF = { f, v -> f.copy(shadows = v) }),
    )
    PhotoTool.WHITE -> listOf(
        ToolParam("Temperature", -1f, 1f, { _, f -> f.temperature }, setF = { f, v -> f.copy(temperature = v) }),
        ToolParam("Tint", -1f, 1f, { _, f -> f.tint }, setF = { f, v -> f.copy(tint = v) }),
    )
    PhotoTool.DETAILS -> listOf(
        ToolParam("Sharpen", 0f, 1f, { _, f -> f.sharpen }, setF = { f, v -> f.copy(sharpen = v) }),
        ToolParam("Structure", -1f, 1f, { _, f -> f.structure }, setF = { f, v -> f.copy(structure = v) }),
    )
    PhotoTool.BLUR -> listOf(ToolParam("Background blur", 0f, 1f, { _, f -> f.lensBlur }, setF = { f, v -> f.copy(lensBlur = v) }))
    PhotoTool.GLOW -> listOf(ToolParam("Glow", 0f, 1f, { _, f -> f.glow }, setF = { f, v -> f.copy(glow = v) }))
    PhotoTool.HDR -> listOf(ToolParam("HDR", 0f, 1f, { _, f -> f.hdr }, setF = { f, v -> f.copy(hdr = v) }))
    PhotoTool.GRAIN -> listOf(ToolParam("Film grain", 0f, 1f, { _, f -> f.grain }, setF = { f, v -> f.copy(grain = v) }))
    PhotoTool.GLITCH -> listOf(ToolParam("Glitch", 0f, 1f, { _, f -> f.glitch }, setF = { f, v -> f.copy(glitch = v) }))
    PhotoTool.VIGNETTE -> listOf(ToolParam("Vignette", 0f, 1f, { a, _ -> a.vignette }, setA = { a, v -> a.copy(vignette = v) }))
    else -> emptyList()
}

private fun paramNote(tool: PhotoTool, hasSubject: Boolean): String? = when (tool) {
    PhotoTool.BLUR -> if (hasSubject) "The subject stays sharp and the background softens, like a portrait lens."
    else "No subject was found, so the edges soften and the middle stays sharp."
    else -> null
}

private fun PhotoTool.icon(): ImageVector = when (this) {
    PhotoTool.TUNE -> Icons.Outlined.Tune
    PhotoTool.WHITE -> Icons.Outlined.WbSunny
    PhotoTool.DETAILS -> Icons.Outlined.Deblur
    PhotoTool.CURVES -> Icons.Outlined.ShowChart
    PhotoTool.SELECTIVE -> Icons.Outlined.MyLocation
    PhotoTool.BRUSH -> Icons.Outlined.Brush
    PhotoTool.HEAL -> Icons.Outlined.Healing
    PhotoTool.FACES -> Icons.Outlined.Face
    PhotoTool.FRAMES -> Icons.Outlined.FilterFrames
    PhotoTool.LOOKS -> Icons.Outlined.AutoAwesome
    PhotoTool.BLEND -> Icons.Outlined.Layers
    PhotoTool.BLUR -> Icons.Outlined.BlurOn
    PhotoTool.GLOW -> Icons.Outlined.LightMode
    PhotoTool.HDR -> Icons.Outlined.HdrOn
    PhotoTool.GRAIN -> Icons.Outlined.Grain
    PhotoTool.VIGNETTE -> Icons.Outlined.Vignette
    PhotoTool.GLITCH -> Icons.Outlined.Bolt
}

/** All the tools as a grid of icons with names. */
@Composable
private fun ToolGrid(onTool: (PhotoTool) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth(), maxItemsInEachRow = 4,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PhotoTool.entries.forEach { t ->
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable { onTool(t) }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(46.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(t.icon(), contentDescription = null, modifier = Modifier.size(22.dp)) }
                Text(t.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.padding(top = 4.dp), textAlign = TextAlign.Center)
            }
        }
    }
}

/** One setting at a time: the setting's name chips, one big slider, and a Reset. */
@Composable
private fun ParamControls(
    params: List<ToolParam>, active: Int, onActive: (Int) -> Unit,
    adjust: PhotoAdjust, fx: FxParams,
    onAdjust: ((PhotoAdjust) -> PhotoAdjust) -> Unit, onFx: ((FxParams) -> FxParams) -> Unit,
    note: String?,
) {
    val i = active.coerceIn(0, params.lastIndex)
    val p = params[i]
    if (params.size > 1) {
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(params.size) { k -> Chip(selected = k == i, onClick = { onActive(k) }, label = { Text(params[k].label) }) }
        }
    }
    val v = p.get(adjust, fx)
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(p.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(signed(v, p.centred), style = MaterialTheme.typography.titleMedium)
    }
    com.hinterlay.app.ui.components.HairlineSlider(
        v, { nv -> applyParam(p, nv, onAdjust, onFx) }, p.min..p.max, Modifier.fillMaxWidth().height(44.dp),
    )
    Text(
        (note?.let { "$it " } ?: "") + "Tip: swipe up or down on the photo to switch setting, left or right to change it.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Reset { params.forEach { applyParam(it, 0f, onAdjust, onFx) } }
}

private fun applyParam(p: ToolParam, v: Float, onAdjust: ((PhotoAdjust) -> PhotoAdjust) -> Unit, onFx: ((FxParams) -> FxParams) -> Unit) {
    p.setA?.let { s -> onAdjust { s(it, v) } }
    p.setF?.let { s -> onFx { s(it, v) } }
}

/**
 * Covers the photo while a one-setting tool is open: drag up or down to move between the tool's settings, drag left or
 * right to change the current one, with the name and value shown at the top while you do.
 */
@Composable
fun ToolGestureLayer(
    tool: PhotoTool, active: Int, onActive: (Int) -> Unit,
    adjust: PhotoAdjust, fx: FxParams,
    onAdjust: ((PhotoAdjust) -> PhotoAdjust) -> Unit, onFx: ((FxParams) -> FxParams) -> Unit,
    modifier: Modifier = Modifier,
) {
    val params = paramsFor(tool)
    if (params.isEmpty()) return
    val fb = com.hinterlay.app.fx.rememberFeedback()
    val curActive by androidx.compose.runtime.rememberUpdatedState(active)
    val curAdjust by androidx.compose.runtime.rememberUpdatedState(adjust)
    val curFx by androidx.compose.runtime.rememberUpdatedState(fx)
    var showHud by remember { mutableStateOf(false) }
    var hudTick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(hudTick) {
        if (hudTick == 0) return@LaunchedEffect
        showHud = true
        kotlinx.coroutines.delay(900)
        showHud = false
    }
    Box(
        modifier.pointerInput(tool) {
            var axis = 0 // 0 undecided, 1 horizontal, 2 vertical
            var vertical = 0f
            var travelled = 0f
            // One finger swipes the settings; a second finger hands the gesture to the photo underneath (pinch to zoom).
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                axis = 0; vertical = 0f; travelled = 0f; fb.slideStart()
                var multi = false
                do {
                    val event = awaitPointerEvent()
                    if (event.changes.size > 1) multi = true
                    val change = event.changes.first()
                    val drag = change.positionChange()
                    if (multi || !change.pressed || drag == androidx.compose.ui.geometry.Offset.Zero) continue
                    change.consume()
                    travelled += kotlin.math.abs(drag.x) + kotlin.math.abs(drag.y)
                    if (axis == 0 && travelled > 14.dp.toPx()) axis = if (kotlin.math.abs(drag.x) >= kotlin.math.abs(drag.y)) 1 else 2
                when (axis) {
                    2 -> {
                        vertical += drag.y
                        val step = 44.dp.toPx()
                        while (vertical > step) { vertical -= step; if (curActive < params.lastIndex) { onActive(curActive + 1); fb.select() } }
                        while (vertical < -step) { vertical += step; if (curActive > 0) { onActive(curActive - 1); fb.select() } }
                        hudTick++
                    }
                    1 -> {
                        val p = params[curActive.coerceIn(0, params.lastIndex)]
                        val now = p.get(curAdjust, curFx)
                        val nv = (now + drag.x / size.width * (p.max - p.min) * 0.9f).coerceIn(p.min, p.max)
                        applyParam(p, nv, onAdjust, onFx)
                        fb.slide((nv - p.min) / (p.max - p.min), if (p.centred) -p.min / (p.max - p.min) else null)
                        hudTick++
                    }
                }
                } while (event.changes.any { it.pressed })
            }
        },
    ) {
        if (showHud) {
            val p = params[active.coerceIn(0, params.lastIndex)]
            Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                border = com.hinterlay.app.ui.components.hairline(),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 76.dp),
            ) {
                Text(
                    "${p.label}  ${signed(p.get(adjust, fx), p.centred)}",
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
                )
            }
        }
    }
}

/** One line per control (name, slider, value), so several fit in the lower panel at once. */
@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onValueChange: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(96.dp), maxLines = 1)
        com.hinterlay.app.ui.components.HairlineSlider(value, onValueChange, range, Modifier.weight(1f))
        Text(
            display, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

/**
 * Photo tools laid over the lower part of the photo, so the picture stays visible while you drag a slider.
 * A strip of tools on top, that tool's controls below. Nothing here is a bottom tab (the panel is closed
 * with Done), in line with the rule that the editor's tab row does not grow.
 */
@Composable
fun PhotoToolsPanel(
    adjust: PhotoAdjust,
    fx: FxParams,
    onAdjust: ((PhotoAdjust) -> PhotoAdjust) -> Unit,
    onFx: ((FxParams) -> FxParams) -> Unit,
    tool: PhotoTool,
    onTool: (PhotoTool) -> Unit,
    inTool: Boolean,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    activeParam: Int,
    onActiveParam: (Int) -> Unit,
    selectedPoint: Int,
    onSelectPoint: (Int) -> Unit,
    onUpdatePoint: ((SelectivePoint) -> SelectivePoint) -> Unit,
    onRemovePoint: () -> Unit,
    brush: BrushSettings,
    onBrush: (BrushSettings) -> Unit,
    onUndoStroke: () -> Unit,
    onClearStrokes: () -> Unit,
    canUndoHeal: Boolean,
    onUndoHeal: () -> Unit,
    previewPhoto: android.graphics.Bitmap?,
    hasSubject: Boolean,
    onBlendPhoto: (android.net.Uri) -> Unit,
    onBlend: ((com.hinterlay.app.render.BlendParams) -> com.hinterlay.app.render.BlendParams) -> Unit,
    onBlendRemove: () -> Unit,
    faces: List<com.hinterlay.app.camera.FacePose>?,
    faceScanning: Boolean,
    onPickMask: (com.hinterlay.app.camera.FaceMask) -> Unit,
    onOpenFaces: () -> Unit,
    onAuto: () -> Unit,
    onClose: () -> Unit,
    contentHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier.glass(RoundedCornerShape(24.dp), fill = 0.94f)) {
        Column(Modifier.padding(top = 8.dp, bottom = 6.dp)) {
            // The grid has a title and Done. Inside a tool the bar is Cancel (cross), the tool's name and Apply (tick).
            if (!inTool) {
                Row(Modifier.padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Tools", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    com.hinterlay.app.ui.components.ActionText(onClick = onAuto) { Text("Auto") }
                    com.hinterlay.app.ui.components.ActionText(onClick = onClose) { Text("Done") }
                }
            } else {
                Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
                    Text(tool.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    IconButton(onClick = onApply) { Icon(Icons.Filled.Check, contentDescription = "Apply") }
                }
            }
            Column(
                // Fixed height: the photo above must not jump when a tool shows fewer or more controls.
                Modifier.height(contentHeight).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val params = paramsFor(tool)
                if (!inTool) ToolGrid(onTool)
                else if (params.isNotEmpty()) ParamControls(params, activeParam, onActiveParam, adjust, fx, onAdjust, onFx, paramNote(tool, hasSubject))
                else when (tool) {
                    PhotoTool.CURVES -> CurvesControls(fx, onFx)
                    PhotoTool.SELECTIVE -> SelectiveControls(fx, selectedPoint, onSelectPoint, onUpdatePoint, onRemovePoint)
                    PhotoTool.HEAL -> {
                        LabeledSlider("Size", brush.size, 0.01f..0.2f, pct(brush.size)) { v -> onBrush(brush.copy(size = v)) }
                        Text(
                            "Paint over a blemish or a small object, then lift your finger. It is filled from the area around it. Works best on an even background.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                        com.hinterlay.app.ui.components.ActionText(onClick = onUndoHeal, enabled = canUndoHeal) { Text("Undo heal") }
                    }
                    PhotoTool.BRUSH -> {
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            BrushKind.entries.forEach { k ->
                                Chip(selected = brush.kind == k, onClick = { onBrush(brush.copy(kind = k)) }, label = { Text(k.label) })
                            }
                        }
                        LabeledSlider("Size", brush.size, 0.01f..0.2f, pct(brush.size)) { v -> onBrush(brush.copy(size = v)) }
                        LabeledSlider("Strength", brush.strength, 0.1f..1f, pct(brush.strength)) { v -> onBrush(brush.copy(strength = v)) }
                        Text(
                            "Paint on the photo with one finger. Pinch with two to zoom.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row {
                            com.hinterlay.app.ui.components.ActionText(onClick = onUndoStroke, enabled = fx.strokes.isNotEmpty()) { Text("Undo stroke") }
                            com.hinterlay.app.ui.components.ActionText(onClick = onClearStrokes, enabled = fx.strokes.isNotEmpty()) { Text("Clear all") }
                        }
                    }
                    PhotoTool.LOOKS -> LookStrip(adjust.look, previewPhoto) { l -> onAdjust { it.copy(look = l) } }
                    PhotoTool.BLEND -> BlendControls(fx.blend, hasSubject, onBlendPhoto, onBlend, onBlendRemove)
                    PhotoTool.FRAMES -> {
                        val fr = fx.frame
                        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            item { Chip(selected = fr == null, onClick = { onFx { it.copy(frame = null) } }, label = { Text("None") }) }
                            items(com.hinterlay.app.render.FrameStyle.entries.toList()) { s ->
                                Chip(
                                    selected = fr?.style == s,
                                    onClick = { onFx { it.copy(frame = (it.frame ?: com.hinterlay.app.render.FrameParams()).copy(style = s)) } },
                                    label = { Text(s.label) },
                                )
                            }
                        }
                        if (fr != null) {
                            Text("Colour", style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                com.hinterlay.app.render.FrameColor.entries.forEach { col ->
                                    Chip(selected = fr.color == col, onClick = { onFx { it.copy(frame = fr.copy(color = col)) } }, label = { Text(col.label) })
                                }
                            }
                            LabeledSlider("Width", fr.size, 0.01f..0.15f, pct(fr.size)) { v -> onFx { it.copy(frame = fr.copy(size = v)) } }
                        } else {
                            Text("Add a border around the photo: thin, wide, rounded, polaroid, film or double.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    PhotoTool.FACES -> {
                        androidx.compose.runtime.LaunchedEffect(Unit) { onOpenFaces() }
                        when {
                            faces == null -> Text("Looking for faces…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            faces.isEmpty() -> Text(
                                "No face was found in this photo. Masks need a face that is clearly visible and not too small.",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            else -> {
                                Text(
                                    if (faces.size == 1) "1 face found. Pick a mask: it follows the face and head." else "${faces.size} faces found. A mask goes on every face.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(com.hinterlay.app.camera.FaceMask.entries.toList()) { m ->
                                        Chip(
                                            selected = fx.faceMask == m, enabled = !faceScanning,
                                            onClick = { onPickMask(m) }, label = { Text("${m.emoji} ${m.label}") },
                                        )
                                    }
                                }
                                if (faceScanning) Text("Scanning the face…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    else -> Unit // the other tools are one-setting tools, handled by ParamControls above
                }
            }
        }
    }
}

/** Double exposure: pick a second photo, choose how and where it shows. */
@Composable
private fun BlendControls(
    blend: com.hinterlay.app.render.BlendParams?,
    hasSubject: Boolean,
    onPhoto: (android.net.Uri) -> Unit,
    onChange: ((com.hinterlay.app.render.BlendParams) -> com.hinterlay.app.render.BlendParams) -> Unit,
    onRemove: () -> Unit,
) {
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) onPhoto(uri) }
    val pick = { picker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    if (blend == null) {
        Text(
            "Mix a second photo into this one. Try a skyline, clouds or a texture over a portrait.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        com.hinterlay.app.ui.components.PrimaryButton("Pick a photo to blend", onClick = { pick() }, modifier = Modifier.fillMaxWidth())
        return
    }
    Text("Mode", style = MaterialTheme.typography.labelLarge)
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(com.hinterlay.app.render.BlendMode.entries.toList()) { m ->
            Chip(selected = blend.mode == m, onClick = { onChange { it.copy(mode = m) } }, label = { Text(m.label) })
        }
    }
    Text("Where it shows", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        com.hinterlay.app.render.BlendArea.entries.forEach { a ->
            Chip(
                selected = blend.area == a, enabled = hasSubject || a == com.hinterlay.app.render.BlendArea.EVERYWHERE,
                onClick = { onChange { it.copy(area = a) } }, label = { Text(a.label) },
            )
        }
    }
    if (!hasSubject) Text("No subject was found, so it shows everywhere.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    LabeledSlider("Strength", blend.opacity, 0f..1f, pct(blend.opacity)) { v -> onChange { it.copy(opacity = v) } }
    LabeledSlider("Zoom", blend.zoom, 1f..3f, "${(blend.zoom * 100).roundToInt()}%") { v -> onChange { it.copy(zoom = v) } }
    LabeledSlider("Move across", blend.dx, -1f..1f, pct(blend.dx)) { v -> onChange { it.copy(dx = v) } }
    LabeledSlider("Move up/down", blend.dy, -1f..1f, pct(blend.dy)) { v -> onChange { it.copy(dy = v) } }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip(selected = blend.flip, onClick = { onChange { it.copy(flip = !it.flip) } }, label = { Text("Flip") })
        com.hinterlay.app.ui.components.ActionText(onClick = { pick() }) { Text("Change photo") }
        com.hinterlay.app.ui.components.ActionText(onClick = onRemove) { Text("Remove") }
    }
}

/** Every look shown on the user's own photo, so choosing one is a matter of picking the one that looks right. */
@Composable
private fun LookStrip(current: Look, photo: android.graphics.Bitmap?, onPick: (Look) -> Unit) {
    val image = remember(photo) { photo?.asImageBitmap() }
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(Look.entries.toList()) { l ->
            val on = current == l
            val filter = remember(l) {
                if (l == Look.NORMAL) null else androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(l.matrix().array))
            }
            Column(
                Modifier.clip(RoundedCornerShape(14.dp)).clickable { onPick(l) }.padding(2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(width = 68.dp, height = 88.dp).clip(RoundedCornerShape(12.dp))
                        .border(if (on) 2.dp else 1.dp, if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                ) {
                    if (image != null) androidx.compose.foundation.Image(
                        bitmap = image, contentDescription = l.label, colorFilter = filter,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                    )
                }
                Text(l.label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp), maxLines = 1,
                    color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Reset(onClick: () -> Unit) {
    com.hinterlay.app.ui.components.ActionText(onClick = onClick) { Text("Reset") }
}

/** Tap the photo to place a point, tap a point to pick it, then change only the area around it. */
@Composable
private fun SelectiveControls(
    fx: FxParams,
    selected: Int,
    onSelect: (Int) -> Unit,
    onUpdate: ((SelectivePoint) -> SelectivePoint) -> Unit,
    onRemove: () -> Unit,
) {
    val point = fx.points.getOrNull(selected)
    if (fx.points.isEmpty()) {
        Text(
            "Tap the photo to place a point, then change the light and colour just around it. Up to 6 points.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        return
    }
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        fx.points.forEachIndexed { i, _ ->
            Chip(selected = i == selected, onClick = { onSelect(i) }, label = { Text("Point ${i + 1}") })
        }
    }
    if (point == null) {
        Text("Pick a point, or tap the photo to add one.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LabeledSlider("Size", point.radius, 0.05f..0.45f, pct(point.radius)) { v -> onUpdate { it.copy(radius = v) } }
    LabeledSlider("Brightness", point.brightness, -1f..1f, pct(point.brightness)) { v -> onUpdate { it.copy(brightness = v) } }
    LabeledSlider("Contrast", point.contrast, -1f..1f, pct(point.contrast)) { v -> onUpdate { it.copy(contrast = v) } }
    LabeledSlider("Saturation", point.saturation, -1f..1f, pct(point.saturation)) { v -> onUpdate { it.copy(saturation = v) } }
    LabeledSlider("Structure", point.structure, -1f..1f, pct(point.structure)) { v -> onUpdate { it.copy(structure = v) } }
    com.hinterlay.app.ui.components.ActionText(onClick = onRemove) { Text("Remove point") }
}

/** Five points on the tone curve: blacks, shadows, mids, lights, whites. The line shows the result. */
@Composable
private fun CurvesControls(fx: FxParams, onFx: ((FxParams) -> FxParams) -> Unit) {
    val names = listOf("Blacks", "Shadows", "Mids", "Lights", "Whites")
    val line = MaterialTheme.colorScheme.onSurface
    val faint = MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(72.dp)) {
            val s = size.minDimension
            drawRect(faint, style = Stroke(1.dp.toPx()))
            drawLine(faint, Offset(0f, s), Offset(s, 0f), strokeWidth = 1.dp.toPx())
            val path = Path()
            fx.curve.forEachIndexed { i, y ->
                val p = Offset(i / 4f * s, s - y.coerceIn(0f, 1f) * s)
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(path, line, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        Column(Modifier.weight(1f)) {
            names.forEachIndexed { i, n ->
                val identity = i / 4f
                val offset = fx.curve[i] - identity
                LabeledSlider(n, offset, -0.3f..0.3f, "${(offset * 100).roundToInt()}") { v ->
                    onFx { f ->
                        val c = f.curve.toMutableList()
                        c[i] = (identity + v).coerceIn(0f, 1f)
                        f.copy(curve = c)
                    }
                }
            }
        }
    }
    Reset { onFx { it.copy(curve = FxParams.IdentityCurve) } }
}
