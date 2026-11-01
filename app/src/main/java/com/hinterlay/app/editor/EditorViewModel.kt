package com.hinterlay.app.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hinterlay.app.ai.SmartSuggest
import com.hinterlay.app.data.HistoryStore
import com.hinterlay.app.export.Exporter
import com.hinterlay.app.sticker.StickerStore
import com.hinterlay.app.wallpaper.WallpaperStore
import com.hinterlay.app.render.Compositor
import com.hinterlay.app.render.ImageLoader
import com.hinterlay.app.render.FxParams
import com.hinterlay.app.render.PhotoAdjust
import com.hinterlay.app.render.PhotoFx
import com.hinterlay.app.render.SubjectDetector
import com.hinterlay.app.render.SkinSmooth
import com.hinterlay.app.render.SubjectPlate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Phase { Loading, Detecting, Ready, Error }

data class EditorUiState(
    val phase: Phase = Phase.Loading,
    val statusText: String = "Opening photo…",
    val source: Bitmap? = null,
    val foreground: Bitmap? = null,
    val subjectFound: Boolean = false,
    val layers: List<TextLayer> = emptyList(),
    val selectedId: Long? = null,
    val error: String? = null,
    /** On-device AI: caption ideas and photo colours, filled in shortly after the photo opens. */
    val suggestions: List<String> = emptyList(),
    val palette: List<Int> = emptyList(),
    val labels: List<String> = emptyList(),
    // Bumped on every subject-eraser stroke so the preview redraws (the mask bitmap is edited in place).
    val fgVersion: Int = 0,
    // Erased parts of the subject (alpha = removed) and the soft clean background shown in their place.
    val subjectMask: Bitmap? = null,
    val plate: Bitmap? = null,
    // Colour look and manual sliders for the whole photo.
    val adjust: PhotoAdjust = PhotoAdjust(),
    // Pre-blurred skin layer for the Smooth control (built the first time it is used).
    val smooth: Bitmap? = null,
    // True after a crop, so it can be undone once.
    val canUndoCrop: Boolean = false,
    // Photo tools that need neighbouring pixels (details, curves, glow, HDR, grain...). The edited photo and
    // the subject matched to it are rebuilt in the background; both are null while nothing is applied.
    val fx: FxParams = FxParams(),
    val fxSource: Bitmap? = null,
    val fxForeground: Bitmap? = null,
    // Which selective-adjust point is being edited (-1 = none).
    val selectedPoint: Int = -1,
    // The second photo for double exposure (its settings are in fx.blend).
    val blendBitmap: Bitmap? = null,
    // Faces found in the photo (null until the Faces tool is first used), and the end time of the running face scan.
    val faces: List<com.hinterlay.app.camera.FacePose>? = null,
    val faceScanStart: Long = 0L,
    val faceScanning: Boolean = false,
    // Undo and redo of applied photo tools (see applyTool / undoTools).
    val canUndoTools: Boolean = false,
    val canRedoTools: Boolean = false,
    // The heal stroke being painted (only for the faint on-screen preview), and whether a heal can be undone.
    val heal: com.hinterlay.app.render.BrushStroke? = null,
    val canUndoHeal: Boolean = false,
    // Depth-clock mode: non-null means a live clock is slipped behind the subject, styled here and
    // applied as a wallpaper. Null = ordinary photo editing.
    val clock: com.hinterlay.app.wallpaper.DepthClockConfig? = null,
) {
    val selected: TextLayer? get() = layers.firstOrNull { it.id == selectedId }
    val subjectEdit: Compositor.SubjectEdit? get() = if (subjectMask != null || smooth != null) Compositor.SubjectEdit(subjectMask, plate, smooth) else null
}

sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val progress: Float, val stage: String) : ExportState
    data class Done(val file: File, val savedToGallery: Boolean) : ExportState
    data class Failed(val message: String) : ExportState
}

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val detector = SubjectDetector()
    private var loadJob: Job? = null
    private var exportJob: Job? = null
    private var loadedUri: Uri? = null
    private var startAdShownFor: Uri? = null
    private var nextId = 1L

    /** Layers to apply instead of the default "HELLO" when re-opening a saved creation. */
    var pendingRestore: List<TextLayer>? = null

    // Saved subject cut-out (with any edits) to reuse when re-opening a creation, instead of re-detecting.
    var pendingForeground: File? = null

    // Which thing the Erase tool paints on: the selected text, or the subject cut-out.
    var eraseSubject = false

    // Saved subject-erase mask to reuse when re-opening a creation.
    var pendingSubjectMask: File? = null
    // Snapshots of the subject-erase mask for Undo (null = nothing was erased yet).
    private val subjectUndo = ArrayDeque<Bitmap?>()

    /** A style picked from Inspire, applied to the first text layer of the next photo. */
    var pendingPreset: com.hinterlay.app.cloud.Preset? = null

    /** When true, the next photo opens in depth-clock mode (a live clock behind the subject). */
    var pendingClock: Boolean = false

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val _export = MutableStateFlow<ExportState>(ExportState.Idle)
    val export: StateFlow<ExportState> = _export.asStateFlow()

    /** The export dialog appears only after the rewarded ad closes (or when no ad was available). */
    private val _exportVisible = MutableStateFlow(false)
    val exportVisible: StateFlow<Boolean> = _exportVisible.asStateFlow()

    fun load(uri: Uri) {
        if (uri == loadedUri) return
        loadedUri = uri
        loadJob?.cancel()
        nextId = 1L
        _state.value = EditorUiState()
        loadJob = viewModelScope.launch {
            val source = try {
                ImageLoader.decode(getApplication(), uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(phase = Phase.Error, error = "Couldn't open this photo. Try another one.") }
                return@launch
            }
            _state.update { it.copy(source = source, phase = Phase.Detecting, statusText = "Finding the subject…") }

            // AI ideas run alongside subject detection and never block editing.
            viewModelScope.launch {
                runCatching { SmartSuggest.analyze(source) }.getOrNull()?.let { s ->
                    _state.update { it.copy(suggestions = s.words, palette = s.palette, labels = s.labels) }
                }
            }

            val savedFg = pendingForeground?.takeIf { it.exists() }
            pendingForeground = null
            val detected = try {
                if (savedFg != null) {
                    withContext(Dispatchers.IO) { android.graphics.BitmapFactory.decodeFile(savedFg.path) }
                } else {
                    withContext(Dispatchers.Default) {
                        detector.extract(source) { msg -> _state.update { it.copy(statusText = msg) } }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // Editing still works; text simply sits on top.
            }
            val foreground = detected
            subjectUndo.clear()
            // Re-opened creation: bring back what was erased from the subject.
            val savedMask = pendingSubjectMask?.takeIf { it.exists() }
            pendingSubjectMask = null
            if (savedMask != null && foreground != null) {
                val opts = android.graphics.BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888 }
                val m = withContext(Dispatchers.IO) { android.graphics.BitmapFactory.decodeFile(savedMask.path, opts) }
                _state.update { it.copy(subjectMask = m) }
            }
            // Soft clean background used when parts of the subject are erased. Built off the main thread.
            if (foreground != null) {
                viewModelScope.launch {
                    val plate = runCatching { withContext(Dispatchers.Default) { SubjectPlate.build(source, foreground) } }.getOrNull()
                    _state.update { it.copy(plate = plate, fgVersion = it.fgVersion + 1) }
                    if (_state.value.fx.lensBlur > 0f) scheduleFx()
                }
            }
            pendingAdjust?.let { a -> _state.update { it.copy(adjust = a) } }
            pendingAdjust = null
            pendingFx?.let { f -> _state.update { it.copy(fx = f) } }
            pendingFx = null
            pendingBlend?.takeIf { it.exists() }?.let { file ->
                val bmp = withContext(Dispatchers.IO) { android.graphics.BitmapFactory.decodeFile(file.path) }
                // Without the saved photo the blend settings mean nothing, so they are dropped.
                _state.update { if (bmp != null) it.copy(blendBitmap = bmp) else it.copy(fx = it.fx.copy(blend = null)) }
            }
            pendingBlend = null
            _state.update { if (it.fx.blend != null && it.blendBitmap == null) it.copy(fx = it.fx.copy(blend = null)) else it }
            ensureSmooth()
            val restored = pendingRestore
            pendingRestore = null
            if (restored != null) {
                nextId = (restored.maxOfOrNull { it.id } ?: 0L) + 1
                _state.update {
                    it.copy(
                        phase = Phase.Ready, foreground = foreground, subjectFound = foreground != null,
                        layers = restored, selectedId = null,
                    )
                }
                scheduleFx()
                markClean()
                return@launch
            }
            val preset = pendingPreset
            pendingPreset = null
            if (pendingClock) {
                pendingClock = false
                _state.update {
                    it.copy(
                        phase = Phase.Ready, foreground = foreground, subjectFound = foreground != null,
                        layers = emptyList(), selectedId = null,
                        clock = com.hinterlay.app.wallpaper.DepthClockConfig(behind = foreground != null),
                    )
                }
                scheduleFx()
                markClean()
                return@launch
            }
            val first = if (preset != null) preset.toLayer(nextId++, foreground != null)
            else TextLayer(id = nextId++, text = DEFAULT_TEXT, behindSubject = foreground != null)
            _state.update {
                it.copy(
                    phase = Phase.Ready,
                    foreground = foreground,
                    subjectFound = foreground != null,
                    layers = listOf(fitToWidth(first, source.width)),
                    selectedId = first.id,
                    // A template can bring a look and a few photo tools along with the text.
                    adjust = preset?.adjust ?: it.adjust,
                    fx = preset?.fx ?: it.fx,
                )
            }
            if (preset != null) ensureSmooth()
            scheduleFx()
            markClean()
        }
    }

    /** True exactly once per photo, so rotation or recomposition never repeats the ad. */
    fun shouldShowStartAd(uri: Uri): Boolean {
        if (startAdShownFor == uri) return false
        startAdShownFor = uri
        return true
    }

    // ---- Layers ---------------------------------------------------------------------

    private var smoothJob: Job? = null

    // ---- Do magic ---------------------------------------------------------------------

    private var scene: com.hinterlay.app.ai.Scene? = null
    private var magicRound = 0

    /** Reads the photo and returns the next three complete text suggestions. more moves on to a fresh set. */
    suspend fun magic(more: Boolean): List<com.hinterlay.app.ai.MagicStyle> {
        val s = _state.value
        val src = s.source ?: return emptyList()
        return withContext(Dispatchers.Default) {
            val sc = scene ?: com.hinterlay.app.ai.Magic.analyze(src, s.foreground, s.labels, s.palette).also { scene = it }
            if (more) magicRound++ else magicRound = 0
            com.hinterlay.app.cloud.Track.event(if (more) "magic_more" else "magic_opened")
            com.hinterlay.app.ai.Magic.suggest(sc, magicRound)
        }
    }

    /** Applies a suggestion to the photo. Replaces the first untouched "HELLO" or adds a new text. */
    fun applyMagic(style: com.hinterlay.app.ai.MagicStyle) {
        val s = _state.value
        val src = s.source ?: return
        val first = s.layers.firstOrNull { it.text == DEFAULT_TEXT || it.text == "HELLO" }
        val base = style.layer.copy(id = first?.id ?: nextId++, behindSubject = style.layer.behindSubject && s.foreground != null)
        val fitted = fitToWidth(base, src.width)
        _state.update { st ->
            val list = if (first != null) st.layers.map { if (it.id == first.id) fitted else it } else st.layers + fitted
            st.copy(layers = list, selectedId = fitted.id)
        }
        com.hinterlay.app.cloud.Track.event("magic_applied", "font" to style.layer.font.name)
    }
    /** Draws a small preview of a suggestion on the photo. */
    fun magicPreview(style: com.hinterlay.app.ai.MagicStyle): Bitmap? {
        val s = _state.value
        val src = s.source ?: return null
        val k = 420f / maxOf(src.width, src.height)
        val w = (src.width * k).toInt().coerceAtLeast(2); val h = (src.height * k).toInt().coerceAtLeast(2)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val fg = s.foreground?.let { Bitmap.createScaledBitmap(it, w, h, true) }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val layer = style.layer.copy(behindSubject = style.layer.behindSubject && fg != null)
        Compositor.render(android.graphics.Canvas(out), small, fg, listOf(fitToWidth(layer, w)), null, s.adjust)
        small.recycle(); fg?.recycle()
        return out
    }

    fun setAdjust(transform: (PhotoAdjust) -> PhotoAdjust) {
        _state.update { it.copy(adjust = transform(it.adjust)) }
        ensureSmooth()
    }

    // ---- Photo tools (PhotoFx) ------------------------------------------------------------------

    private var fxJob: Job? = null

    fun setFx(transform: (FxParams) -> FxParams) {
        _state.update { it.copy(fx = transform(it.fx)) }
        scheduleFx()
    }

    /** Rebuilds the edited photo shortly after the last slider move. A newer change cancels an older one. */
    private fun scheduleFx() {
        fxJob?.cancel()
        val s = _state.value
        val src = s.source ?: return
        val params = s.fx
        if (params.isNeutral) {
            _state.update { it.copy(fxSource = null, fxForeground = null, fgVersion = it.fgVersion + 1) }
            return
        }
        val fg = s.foreground
        fxJob = viewModelScope.launch {
            kotlinx.coroutines.delay(50)
            val (edited, editedFg) = withContext(Dispatchers.Default) { PhotoFx.render(src, fg, s.plate, params, s.blendBitmap, s.faces) }
            ensureActive()
            // Drop the result if the photo changed (for example it was cropped) while this was running.
            _state.update { if (it.source === src && it.fx == params) it.copy(fxSource = edited, fxForeground = editedFg, fgVersion = it.fgVersion + 1) else it }
        }
    }

    /** Saved photo-tool settings to reuse when re-opening a creation. */
    var pendingFx: FxParams? = null

    /** The saved second photo (double exposure) of a creation being re-opened. */
    var pendingBlend: File? = null

    // ---- Tool mode: apply, cancel, undo, redo -------------------------------------------------------

    private class ToolSnap(val fx: FxParams, val adjust: PhotoAdjust)
    private val toolUndo = ArrayDeque<ToolSnap>()
    private val toolRedo = ArrayDeque<ToolSnap>()
    private var toolEntry: ToolSnap? = null

    private fun snapNow() = _state.value.let { ToolSnap(it.fx, it.adjust) }
    private fun sameSnap(a: ToolSnap, b: ToolSnap) = a.fx == b.fx && a.adjust == b.adjust

    private fun flagsUpdate() = _state.update { it.copy(canUndoTools = toolUndo.isNotEmpty(), canRedoTools = toolRedo.isNotEmpty()) }

    private fun restore(s: ToolSnap) {
        _state.update { it.copy(fx = s.fx, adjust = s.adjust, selectedPoint = -1) }
        scheduleFx()
        ensureSmooth()
    }

    /**
     * Looks at the photo's brightness spread and colour cast and sets Tune / White balance to match, as one undo step.
     * Gentle on purpose: it only fixes what is clearly off. Runs on the phone.
     */
    fun autoEnhance() {
        val src = _state.value.source ?: return
        viewModelScope.launch {
            val r = withContext(Dispatchers.Default) {
                val k = minOf(1f, 200f / maxOf(src.width, src.height))
                val b = android.graphics.Bitmap.createScaledBitmap(src, (src.width * k).toInt().coerceAtLeast(1), (src.height * k).toInt().coerceAtLeast(1), true)
                val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
                if (b !== src) b.recycle()
                val hist = IntArray(256); var sr = 0.0; var sg = 0.0; var sb = 0.0; var sat = 0.0
                for (c in px) {
                    val rr = (c shr 16) and 255; val gg = (c shr 8) and 255; val bb = c and 255
                    hist[((0.299 * rr + 0.587 * gg + 0.114 * bb)).toInt().coerceIn(0, 255)]++
                    sr += rr; sg += gg; sb += bb
                    val mx = maxOf(rr, gg, bb); val mn = minOf(rr, gg, bb); sat += if (mx == 0) 0.0 else (mx - mn).toDouble() / mx
                }
                val n = px.size.toDouble()
                fun pct(q: Double): Float { var acc = 0; val t = n * q; for (i in 0..255) { acc += hist[i]; if (acc >= t) return i / 255f }; return 1f }
                val mean = (0.299 * sr + 0.587 * sg + 0.114 * sb) / n / 255.0
                doubleArrayOf(mean, pct(0.02).toDouble(), pct(0.98).toDouble(), pct(0.1).toDouble(), pct(0.95).toDouble(), sat / n, sr / n, sb / n)
            }
            val mean = r[0]; val lo = r[1]; val hi = r[2]; val p10 = r[3]; val p95 = r[4]; val sat = r[5]; val mr = r[6]; val mb = r[7]
            val bright = ((0.46 - mean) * 0.9).toFloat().coerceIn(-0.25f, 0.25f)
            val range = hi - lo
            val contrast = if (range < 0.85) ((0.85 - range) * 0.7).toFloat().coerceIn(0f, 0.35f) else 0f
            val shadows = if (p10 < 0.12) ((0.12 - p10) * 4.0).toFloat().coerceIn(0f, 0.5f) else 0f
            val highlights = if (p95 > 0.94) -((p95 - 0.94) * 8.0).toFloat().coerceIn(0f, 0.4f) else 0f
            val satAdd = if (sat < 0.28) ((0.28 - sat) * 1.2).toFloat().coerceIn(0f, 0.25f) else 0f
            val temp = (-(mr - mb) / (mr + mb) * 0.8).toFloat().coerceIn(-0.2f, 0.2f)
            beginTool()
            _state.update { it.copy(adjust = it.adjust.copy(brightness = bright, contrast = contrast, saturation = satAdd)) }
            setFx { it.copy(shadows = shadows, highlights = highlights, temperature = if (kotlin.math.abs(temp) > 0.04f) temp else 0f, ambiance = 0.15f) }
            ensureSmooth()
            applyTool()
        }
    }

    /** A tool was opened: remember how things were, so Cancel can put them back and Apply can make one undo step. */
    fun beginTool() { toolEntry = snapNow() }

    /** The tick: keeps the tool's changes as one step that Undo can take back. */
    fun applyTool() {
        val entry = toolEntry ?: return
        toolEntry = null
        if (!sameSnap(entry, snapNow())) {
            toolUndo.addLast(entry); if (toolUndo.size > 40) toolUndo.removeFirst()
            toolRedo.clear()
            flagsUpdate()
            com.hinterlay.app.cloud.Track.event("tool_applied")
        }
    }

    /** The cross: puts everything back as it was when the tool was opened. */
    fun cancelTool() {
        val entry = toolEntry ?: return
        toolEntry = null
        if (!sameSnap(entry, snapNow())) restore(entry)
    }

    fun undoTools() {
        val prev = toolUndo.removeLastOrNull() ?: return
        toolRedo.addLast(snapNow())
        restore(prev); flagsUpdate()
    }

    fun redoTools() {
        val next = toolRedo.removeLastOrNull() ?: return
        toolUndo.addLast(snapNow())
        restore(next); flagsUpdate()
    }

    // ---- Face masks -------------------------------------------------------------------------------

    private var facesJob: Job? = null

    /** Finds the faces in the photo (once; again after a crop). Safe to call whenever the Faces tool opens. */
    fun detectFaces() {
        val s = _state.value
        val src = s.source ?: return
        if (s.faces != null || facesJob?.isActive == true) return
        facesJob = viewModelScope.launch {
            val poses = withContext(Dispatchers.Default) { com.hinterlay.app.camera.FaceFx.detect(src) }
            _state.update { if (it.source === src) it.copy(faces = poses) else it }
            // A mask chosen earlier (a reopened creation) needs the faces before it can be drawn.
            if (_state.value.fx.faceMask != com.hinterlay.app.camera.FaceMask.NONE) scheduleFx()
        }
    }

    /** Chooses a mask. The face scan plays first (about a second), then the mask lands on every face. */
    fun pickFaceMask(mask: com.hinterlay.app.camera.FaceMask) {
        if (mask == com.hinterlay.app.camera.FaceMask.NONE) { setFx { it.copy(faceMask = mask) }; return }
        viewModelScope.launch {
            if (_state.value.faces == null) {
                detectFaces()
                facesJob?.join()
            }
            val faces = _state.value.faces
            if (faces.isNullOrEmpty()) return@launch
            _state.update { it.copy(faceScanStart = System.nanoTime(), faceScanning = true) }
            com.hinterlay.app.cloud.Track.event("face_mask_used", "mask" to mask.name)
            kotlinx.coroutines.delay(950)
            setFx { it.copy(faceMask = mask) }
            _state.update { it.copy(faceScanning = false) }
        }
    }

    /** After the photo's shape changes the saved face positions no longer fit: find them again if a mask is on. */
    private fun refreshFaces() {
        facesJob?.cancel()
        _state.update { it.copy(faces = null) }
        if (_state.value.fx.faceMask != com.hinterlay.app.camera.FaceMask.NONE) detectFaces()
    }

    // ---- Double exposure --------------------------------------------------------------------------

    /** Loads the second photo from [uri] (kept at most 1600 px on its long side) and turns the blend on with default settings. */
    fun setBlendPhoto(uri: Uri) {
        viewModelScope.launch {
            val bmp = runCatching {
                withContext(Dispatchers.IO) {
                    val full = ImageLoader.decode(getApplication(), uri)
                    val k = 1600f / maxOf(full.width, full.height)
                    if (k >= 1f) full else Bitmap.createScaledBitmap(full, (full.width * k).toInt().coerceAtLeast(1), (full.height * k).toInt().coerceAtLeast(1), true).also { full.recycle() }
                }
            }.getOrNull() ?: return@launch
            _state.update {
                val old = it.fx.blend
                it.copy(blendBitmap = bmp, fx = it.fx.copy(blend = (old ?: com.hinterlay.app.render.BlendParams()).copy(token = System.nanoTime())))
            }
            scheduleFx()
            com.hinterlay.app.cloud.Track.event("blend_used")
        }
    }

    fun setBlend(transform: (com.hinterlay.app.render.BlendParams) -> com.hinterlay.app.render.BlendParams) {
        val cur = _state.value.fx.blend ?: return
        setFx { it.copy(blend = transform(cur)) }
    }

    fun removeBlend() {
        _state.update { it.copy(blendBitmap = null, fx = it.fx.copy(blend = null)) }
        scheduleFx()
    }

    // ---- Selective points -----------------------------------------------------------------------

    private companion object { const val MAX_POINTS = 6 }

    /** A tap on the photo while Selective is open: picks the point under the finger, or places a new one there. */
    fun tapPoint(xRel: Float, yRel: Float) {
        val s = _state.value
        val src = s.source ?: return
        val aspect = src.width.toFloat() / src.height
        // Distances are measured in units of the photo's longer side so a tap lands the same way on any photo.
        fun dist(p: com.hinterlay.app.render.SelectivePoint): Float {
            val dx = (p.x - xRel) * (if (aspect >= 1f) 1f else aspect)
            val dy = (p.y - yRel) * (if (aspect >= 1f) 1f / aspect else 1f)
            return kotlin.math.hypot(dx, dy)
        }
        val near = s.fx.points.withIndex().minByOrNull { dist(it.value) }?.takeIf { dist(it.value) < 0.06f }
        if (near != null) { _state.update { it.copy(selectedPoint = near.index) }; return }
        if (s.fx.points.size >= MAX_POINTS) return
        setFx { it.copy(points = it.points + com.hinterlay.app.render.SelectivePoint(xRel.coerceIn(0f, 1f), yRel.coerceIn(0f, 1f))) }
        _state.update { it.copy(selectedPoint = it.fx.points.lastIndex) }
    }

    fun updatePoint(transform: (com.hinterlay.app.render.SelectivePoint) -> com.hinterlay.app.render.SelectivePoint) {
        val i = _state.value.selectedPoint
        if (i !in _state.value.fx.points.indices) return
        setFx { f -> f.copy(points = f.points.mapIndexed { j, p -> if (j == i) transform(p) else p }) }
    }

    // ---- Brush strokes --------------------------------------------------------------------------

    /** Starts a new painted stroke. The photo is only rebuilt when the finger lifts, so painting stays smooth. */
    fun beginStroke(kind: com.hinterlay.app.render.BrushKind, radius: Float, strength: Float) {
        _state.update { it.copy(fx = it.fx.copy(strokes = it.fx.strokes + com.hinterlay.app.render.BrushStroke(kind, radius, strength, emptyList()))) }
    }

    /** Adds a point to the stroke being painted (skipped when it is almost on top of the last one). */
    fun strokeTo(xRel: Float, yRel: Float) {
        _state.update { s ->
            val last = s.fx.strokes.lastOrNull() ?: return@update s
            val n = last.pts.size
            if (n >= 2 && kotlin.math.hypot(last.pts[n - 2] - xRel, last.pts[n - 1] - yRel) < 0.003f) return@update s
            val grown = last.copy(pts = last.pts + xRel.coerceIn(0f, 1f) + yRel.coerceIn(0f, 1f))
            s.copy(fx = s.fx.copy(strokes = s.fx.strokes.dropLast(1) + grown))
        }
    }

    fun endStroke() = scheduleFx()

    fun undoStroke() {
        if (_state.value.fx.strokes.isEmpty()) return
        setFx { it.copy(strokes = it.strokes.dropLast(1)) }
    }

    fun clearStrokes() = setFx { it.copy(strokes = emptyList()) }

    // ---- Healing --------------------------------------------------------------------------------

    // The photo (and subject) as they were before each of the last few heals, newest last.
    private val healUndo = ArrayDeque<Pair<Bitmap, Bitmap?>>()

    fun beginHeal(radius: Float) {
        _state.update { it.copy(heal = com.hinterlay.app.render.BrushStroke(com.hinterlay.app.render.BrushKind.LIGHTEN, radius, 1f, emptyList())) }
    }

    fun healTo(xRel: Float, yRel: Float) {
        _state.update { s ->
            val cur = s.heal ?: return@update s
            val n = cur.pts.size
            if (n >= 2 && kotlin.math.hypot(cur.pts[n - 2] - xRel, cur.pts[n - 1] - yRel) < 0.003f) return@update s
            s.copy(heal = cur.copy(pts = cur.pts + xRel.coerceIn(0f, 1f) + yRel.coerceIn(0f, 1f)))
        }
    }

    /** The finger lifted: fills the painted area from its surroundings, then rebuilds everything that depends on the photo. */
    fun endHeal() {
        val s = _state.value
        val stroke = s.heal ?: return
        val src = s.source ?: return
        val fg = s.foreground
        _state.update { it.copy(heal = null) }
        if (stroke.pts.size < 2) return
        viewModelScope.launch {
            val healed = withContext(Dispatchers.Default) { com.hinterlay.app.render.Healer.heal(src, stroke.pts, stroke.radius) }
            val healedFg = fg?.let { f -> withContext(Dispatchers.Default) { PhotoFx.maskWith(healed, f) } }
            healUndo.addLast(src to fg)
            while (healUndo.size > 3) healUndo.removeFirst()
            smoothJob?.cancel()
            _state.update {
                if (it.source !== src) it else it.copy(
                    source = healed, foreground = healedFg ?: it.foreground, plate = null, smooth = null,
                    fxSource = null, fxForeground = null, fgVersion = it.fgVersion + 1, canUndoHeal = true,
                )
            }
            rebuildAfterPhotoChange()
            com.hinterlay.app.cloud.Track.event("heal_used")
        }
    }

    fun undoHeal() {
        val (src, fg) = healUndo.removeLastOrNull() ?: return
        smoothJob?.cancel()
        _state.update {
            it.copy(
                source = src, foreground = fg, plate = null, smooth = null, fxSource = null, fxForeground = null,
                fgVersion = it.fgVersion + 1, canUndoHeal = healUndo.isNotEmpty(),
            )
        }
        rebuildAfterPhotoChange()
    }

    /** Photo-derived layers (subject-erase fill, skin smoothing, photo tools) are rebuilt in the background. */
    private fun rebuildAfterPhotoChange() {
        val s = _state.value
        val src = s.source ?: return
        val fg = s.foreground
        if (fg != null) {
            viewModelScope.launch {
                val plate = runCatching { withContext(Dispatchers.Default) { SubjectPlate.build(src, fg) } }.getOrNull()
                _state.update { if (it.source === src) it.copy(plate = plate, fgVersion = it.fgVersion + 1) else it }
                if (_state.value.fx.lensBlur > 0f) scheduleFx()
            }
        }
        ensureSmooth()
        scheduleFx()
    }

    fun selectPoint(i: Int) = _state.update { it.copy(selectedPoint = i) }

    fun removeSelectedPoint() {
        val i = _state.value.selectedPoint
        if (i !in _state.value.fx.points.indices) return
        setFx { f -> f.copy(points = f.points.filterIndexed { j, _ -> j != i }) }
        _state.update { it.copy(selectedPoint = -1) }
    }

    /** Builds the skin-smoothing layer the first time it is needed (off the main thread). */
    private fun ensureSmooth() {
        val s = _state.value
        val src = s.source ?: return
        if (s.adjust.smooth <= 0f || s.smooth != null || smoothJob?.isActive == true) return
        val fg = s.foreground
        smoothJob = viewModelScope.launch {
            val layer = runCatching { withContext(Dispatchers.Default) { SkinSmooth.build(src, fg) } }.getOrNull()
            // Drop the result if the photo changed (for example it was cropped) while it was building.
            _state.update { if (it.source === src) it.copy(smooth = layer, fgVersion = it.fgVersion + 1) else it }
        }
    }

    // ---- Crop -----------------------------------------------------------------------

    private var cropBackup: EditorUiState? = null

    private fun cropBitmap(b: Bitmap, l: Float, t: Float, r: Float, bt: Float): Bitmap {
        val x = (l * b.width).toInt().coerceIn(0, b.width - 2)
        val y = (t * b.height).toInt().coerceIn(0, b.height - 2)
        val w = ((r - l) * b.width).toInt().coerceIn(2, b.width - x)
        val h = ((bt - t) * b.height).toInt().coerceIn(2, b.height - y)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).drawBitmap(b, -x.toFloat(), -y.toFloat(), null)
        return out
    }

    /** Crops the photo, the subject and the erase masks, and moves every text so it stays on the same spot. */
    fun applyCrop(l: Float, t: Float, r: Float, b: Float) {
        val s = _state.value
        val src = s.source ?: return
        if (r - l > 0.995f && b - t > 0.995f) return
        val newSrc = cropBitmap(src, l, t, r, b)
        val fw = r - l
        val fh = b - t
        val newLayers = s.layers.map { layer ->
            layer.copy(
                x = (layer.x - l) / fw,
                y = (layer.y - t) / fh,
                sizeRel = layer.sizeRel / fw,
                eraseMask = layer.eraseMask?.let { cropBitmap(it, l, t, r, b) },
                maskVersion = layer.maskVersion + 1,
            )
        }
        cropBackup = s
        subjectUndo.clear()
        maskUndo.clear()
        smoothJob?.cancel()
        val newFg = s.foreground?.let { cropBitmap(it, l, t, r, b) }
        _state.update {
            it.copy(
                source = newSrc, foreground = newFg, layers = newLayers,
                subjectMask = s.subjectMask?.let { m -> cropBitmap(m, l, t, r, b) },
                plate = null, smooth = null, fxSource = null, fxForeground = null, fgVersion = it.fgVersion + 1, canUndoCrop = true,
                faces = null,
            )
        }
        fxJob?.cancel()
        facesJob?.cancel()
        // The mask (if any) must be drawn on the new, cropped faces, so they are found again before the picture is rebuilt.
        if (_state.value.fx.faceMask != com.hinterlay.app.camera.FaceMask.NONE) detectFaces() else scheduleFx()
        if (newFg != null) {
            viewModelScope.launch {
                val plate = runCatching { withContext(Dispatchers.Default) { SubjectPlate.build(newSrc, newFg) } }.getOrNull()
                _state.update { if (it.source === newSrc) it.copy(plate = plate, fgVersion = it.fgVersion + 1) else it }
            }
        }
        ensureSmooth()
        com.hinterlay.app.cloud.Track.event("crop_used")
    }

    fun undoCrop() {
        val back = cropBackup ?: return
        cropBackup = null
        subjectUndo.clear()
        maskUndo.clear()
        smoothJob?.cancel()
        _state.update { back.copy(adjust = it.adjust, fx = it.fx, fxSource = null, fxForeground = null, fgVersion = it.fgVersion + 1, canUndoCrop = false) }
        ensureSmooth()
        scheduleFx()
    }

    /** Saved colour adjustments to reuse when re-opening a creation. */
    var pendingAdjust: PhotoAdjust? = null

    /** Adds a ready-made text (a day name, a place, an emoji...) in the middle-lower part of the photo. */
    fun addStamp(text: String, big: Boolean = false) {
        val s = _state.value
        val src = s.source ?: return
        val offset = (s.layers.size % 5) * 0.07f
        val layer = TextLayer(
            id = nextId++, text = text, weight = FontWeightChoice.BOLD,
            sizeRel = if (big) 0.22f else 0.07f,
            y = (0.78f + offset - 0.2f).coerceIn(0.2f, 0.92f),
            behindSubject = false, shadow = true,
        )
        _state.update { it.copy(layers = it.layers + fitToWidth(layer, src.width), selectedId = layer.id) }
    }

    fun addLayer() {
        val s = _state.value
        if (s.source == null) return
        val offset = (s.layers.size % 5) * 0.08f
        val layer = TextLayer(
            id = nextId++,
            text = "Your text",
            weight = FontWeightChoice.BOLD,
            sizeRel = 0.12f,
            y = (0.5f + offset).coerceAtMost(0.9f),
            behindSubject = false,
            shadow = true,
        )
        _state.update { it.copy(layers = it.layers + layer, selectedId = layer.id) }
    }

    fun select(id: Long?) = _state.update { it.copy(selectedId = id) }

    fun updateSelected(transform: (TextLayer) -> TextLayer) = _state.update { s ->
        val id = s.selectedId ?: return@update s
        val width = s.source?.width
        s.copy(layers = s.layers.map {
            if (it.id != id) it else transform(it).let { l -> if (width != null) fitToWidth(l, width) else l }
        })
    }

    // ---- Eraser ---------------------------------------------------------------------

    private class MaskUndo(val layerId: Long, val mask: Bitmap?)
    private val maskUndo = ArrayDeque<MaskUndo>()

    private fun replaceMask(layerId: Long, mask: Bitmap?) = _state.update { s ->
        s.copy(layers = s.layers.map { if (it.id == layerId) it.copy(eraseMask = mask, maskVersion = it.maskVersion + 1) else it })
    }

    /** How strongly the brush erases, 0.05..1. Below 1 the text or subject is left partly see-through. */
    var eraseIntensity = 1f

    /** When on, erasing only works where the text and the subject overlap (see [prepareStroke]). */
    var eraseOverlapOnly = false

    // The stroke being painted. The brush draws at full strength into [strokeLayer]; after every segment the visible mask is
    // rebuilt as: the mask as it was when the stroke began, plus (or minus, when bringing back) the stroke at the chosen
    // strength. So one stroke can never go past the strength you set, however often it crosses itself.
    private var strokeLayer: Bitmap? = null
    private var strokeBase: Bitmap? = null
    private var strokeCover: Bitmap? = null
    private var strokeTmp: Bitmap? = null

    private fun releaseStroke() {
        strokeLayer?.recycle(); strokeBase?.recycle(); strokeCover?.recycle(); strokeTmp?.recycle()
        strokeLayer = null; strokeBase = null; strokeCover = null; strokeTmp = null
    }

    /** Sets up a stroke for the mask [current] (null if nothing is erased yet). Mask pixels are at most 1024 on the long side. */
    private fun prepareStroke(current: Bitmap?, forSubject: Boolean) {
        releaseStroke()
        val s = _state.value
        val src = s.source ?: return
        val k = minOf(1f, 1024f / maxOf(src.width, src.height))
        val w = (src.width * k).toInt().coerceAtLeast(1)
        val h = (src.height * k).toInt().coerceAtLeast(1)
        strokeLayer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        strokeTmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        strokeBase = current?.copy(Bitmap.Config.ARGB_8888, false)
        strokeCover = if (!eraseOverlapOnly) null else Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { cover ->
            val c = android.graphics.Canvas(cover)
            if (forSubject) {
                // Erasing the subject: only where text that sits behind it is.
                c.scale(k, k)
                s.layers.filter { it.visible && it.behindSubject }.forEach { Compositor.drawLayer(c, it, src.width, src.height) }
            } else {
                // Erasing text: only where the subject is.
                s.foreground?.let { c.drawBitmap(it, null, android.graphics.Rect(0, 0, w, h), null) }
            }
        }
    }

    /**
     * Draws one segment (image pixels) into the stroke and rebuilds [mask] around it. [mask] is edited in place.
     * Returns false if there is nothing to draw on.
     */
    private fun paintSegment(mask: Bitmap, x0: Float, y0: Float, x1: Float, y1: Float, radiusRel: Float, restore: Boolean, soft: Float): Boolean {
        val src = _state.value.source ?: return false
        val layer = strokeLayer ?: return false
        val tmp = strokeTmp ?: return false
        val k = layer.width.toFloat() / src.width
        val r = (radiusRel * src.width * k).coerceAtLeast(1f)
        val brush = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            strokeWidth = r * 2
            color = android.graphics.Color.BLACK
            maskFilter = android.graphics.BlurMaskFilter(maxOf(1f, r * soft), android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        val path = android.graphics.Path().apply {
            moveTo(x0 * k, y0 * k)
            lineTo(x1 * k + 0.01f, y1 * k + 0.01f) // tiny offset so a tap still draws a round dot
        }
        android.graphics.Canvas(layer).drawPath(path, brush)

        // Only the area around this segment changes, so only that is rebuilt.
        val pad = (r * 1.6f).toInt() + 2
        val dirty = android.graphics.Rect(
            (minOf(x0, x1) * k).toInt() - pad, (minOf(y0, y1) * k).toInt() - pad,
            (maxOf(x0, x1) * k).toInt() + pad, (maxOf(y0, y1) * k).toInt() + pad,
        )
        if (!dirty.intersect(0, 0, layer.width, layer.height)) return true
        val clear = android.graphics.Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR) }
        // 1. the stroke (cut down to the overlap when that is on) into tmp
        val tc = android.graphics.Canvas(tmp)
        tc.save(); tc.clipRect(dirty)
        tc.drawRect(dirty, clear)
        tc.drawBitmap(layer, 0f, 0f, null)
        strokeCover?.let { tc.drawBitmap(it, 0f, 0f, android.graphics.Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN) }) }
        tc.restore()
        // 2. the mask as it was, then the stroke at the chosen strength
        val mc = android.graphics.Canvas(mask)
        mc.save(); mc.clipRect(dirty)
        mc.drawRect(dirty, clear)
        strokeBase?.let { mc.drawBitmap(it, 0f, 0f, null) }
        val strength = android.graphics.Paint().apply {
            alpha = (eraseIntensity.coerceIn(0.05f, 1f) * 255f).toInt()
            xfermode = android.graphics.PorterDuffXfermode(if (restore) android.graphics.PorterDuff.Mode.DST_OUT else android.graphics.PorterDuff.Mode.SRC_OVER)
        }
        mc.drawBitmap(tmp, 0f, 0f, strength)
        mc.restore()
        return true
    }

    /** Call once when a stroke begins so it can be undone as a unit. */
    fun beginErase() {
        if (eraseSubject) { beginSubjectStroke(); return }
        val layer = _state.value.selected ?: return
        if (maskUndo.isEmpty()) com.hinterlay.app.cloud.Track.event("erase_used")
        maskUndo.addLast(MaskUndo(layer.id, layer.eraseMask?.copy(Bitmap.Config.ARGB_8888, true)))
        if (maskUndo.size > 12) maskUndo.removeFirst().mask?.recycle()
        prepareStroke(layer.eraseMask, forSubject = false)
    }

    /** The finger lifted after an erase stroke: frees the stroke's working bitmaps. */
    fun endErase() = releaseStroke()

    /** Paints (or restores) a stroke segment, in image pixels. radiusRel is a fraction of image width. */
    fun eraseTo(x0: Float, y0: Float, x1: Float, y1: Float, radiusRel: Float, restore: Boolean) {
        if (eraseSubject) { eraseSubjectTo(x0, y0, x1, y1, radiusRel, restore); return }
        val s = _state.value
        val src = s.source ?: return
        val layer = s.selected ?: return
        if (strokeLayer == null) prepareStroke(layer.eraseMask, forSubject = false)
        val k = minOf(1f, 1024f / maxOf(src.width, src.height))
        val mask = layer.eraseMask
            ?: Bitmap.createBitmap((src.width * k).toInt().coerceAtLeast(1), (src.height * k).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        if (!paintSegment(mask, x0, y0, x1, y1, radiusRel, restore, soft = 0.3f)) return
        replaceMask(layer.id, mask)
    }

    fun undoErase() {
        if (eraseSubject) { undoSubject(); return }
        val last = maskUndo.removeLastOrNull() ?: return
        replaceMask(last.layerId, last.mask)
    }

    private fun bumpSubject() = _state.update { it.copy(fgVersion = it.fgVersion + 1) }

    private fun undoSubject() {
        if (subjectUndo.isEmpty()) return
        val snap = subjectUndo.removeLast()
        _state.update { it.copy(subjectMask = snap, fgVersion = it.fgVersion + 1) }
    }

    private fun beginSubjectStroke() {
        val s = _state.value
        if (s.foreground == null) return
        if (subjectUndo.isEmpty()) com.hinterlay.app.cloud.Track.event("erase_used", "target" to "subject")
        subjectUndo.addLast(s.subjectMask?.copy(Bitmap.Config.ARGB_8888, false))
        if (subjectUndo.size > 12) subjectUndo.removeFirst()?.recycle()
        prepareStroke(s.subjectMask, forSubject = true)
    }

    // Erasing paints into a low-resolution mask; the renderer cuts those areas out of the subject
    // and shows the soft clean background there, so the removed part really disappears.
    private fun eraseSubjectTo(x0: Float, y0: Float, x1: Float, y1: Float, radiusRel: Float, restore: Boolean) {
        val s = _state.value
        val src = s.source ?: return
        if (s.foreground == null) return
        if (strokeLayer == null) prepareStroke(s.subjectMask, forSubject = true)
        val k = minOf(1f, 1024f / maxOf(src.width, src.height))
        val mask = s.subjectMask
            ?: Bitmap.createBitmap((src.width * k).toInt().coerceAtLeast(1), (src.height * k).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        if (!paintSegment(mask, x0, y0, x1, y1, radiusRel, restore, soft = 0.25f)) return
        _state.update { it.copy(subjectMask = mask, fgVersion = it.fgVersion + 1) }
    }

    fun clearErase() {
        if (eraseSubject) {
            if (_state.value.subjectMask == null) return
            beginSubjectStroke()
            _state.update { it.copy(subjectMask = null, fgVersion = it.fgVersion + 1) }
            return
        }
        val layer = _state.value.selected ?: return
        if (layer.eraseMask == null) return
        beginErase()
        replaceMask(layer.id, null)
    }

    /** Moves one perspective corner (0=TL,1=TR,2=BR,3=BL) to a point given in image pixels. */
    fun dragCorner(index: Int, imgX: Float, imgY: Float) = _state.update { s ->
        val src = s.source ?: return@update s
        val layer = s.selected ?: return@update s
        val m = Compositor.measure(layer, src.width) ?: return@update s
        // Image -> layer-local (undo position and rotation).
        val dx = imgX - layer.x * src.width
        val dy = imgY - layer.y * src.height
        val rad = Math.toRadians(-layer.rotation.toDouble())
        val lx = (dx * kotlin.math.cos(rad) - dy * kotlin.math.sin(rad)).toFloat()
        val ly = (dx * kotlin.math.sin(rad) + dy * kotlin.math.cos(rad)).toFloat()
        val baseX = if (index == 0 || index == 3) -m.width / 2 else m.width / 2
        val baseY = if (index < 2) -m.height / 2 else m.height / 2
        val p = (layer.perspective ?: List(8) { 0f }).toMutableList()
        p[index * 2] = ((lx - baseX) / m.width).coerceIn(-1.5f, 1.5f)
        p[index * 2 + 1] = ((ly - baseY) / m.height).coerceIn(-1.5f, 1.5f)
        s.copy(layers = s.layers.map { if (it.id == layer.id) it.copy(perspective = p) else it })
    }

    /** Shrinks a layer just enough that its text never runs past the photo's edges. */
    private fun fitToWidth(layer: TextLayer, imageWidth: Int): TextLayer {
        val m = Compositor.measure(layer, imageWidth) ?: return layer
        val limit = imageWidth * 0.98f
        if (m.width <= limit) return layer
        return layer.copy(sizeRel = layer.sizeRel * limit / m.width)
    }

    fun pressAt(px: Float, py: Float) {
        val s = _state.value
        val src = s.source ?: return
        Compositor.hitTest(s.layers, src.width, src.height, px, py)?.let { select(it) }
    }

    fun tapAt(px: Float, py: Float) {
        val s = _state.value
        val src = s.source ?: return
        select(Compositor.hitTest(s.layers, src.width, src.height, px, py))
    }

    /** Pan is in fractions of image width/height. */
    fun transformSelected(panX: Float, panY: Float, zoom: Float, rotation: Float) = updateSelected {
        var r = it.rotation + rotation
        if (r > 180f) r -= 360f
        if (r < -180f) r += 360f
        it.copy(
            x = (it.x + panX).coerceIn(-0.2f, 1.2f),
            y = (it.y + panY).coerceIn(-0.2f, 1.2f),
            sizeRel = (it.sizeRel * zoom).coerceIn(0.02f, 1.2f),
            rotation = r,
        )
    }

    fun duplicateSelected() {
        val layer = _state.value.selected ?: return
        val copy = layer.copy(
            id = nextId++, x = (layer.x + 0.04f).coerceAtMost(1f), y = (layer.y + 0.04f).coerceAtMost(1f),
            eraseMask = layer.eraseMask?.copy(Bitmap.Config.ARGB_8888, true), // never share a mutable mask
        )
        _state.update { it.copy(layers = it.layers + copy, selectedId = copy.id) }
    }

    fun deleteSelected() = _state.update { s ->
        val id = s.selectedId ?: return@update s
        val rest = s.layers.filterNot { it.id == id }
        s.copy(layers = rest, selectedId = rest.lastOrNull()?.id)
    }

    /** +1 brings forward, -1 sends backward. */
    fun moveSelected(direction: Int) = _state.update { s ->
        val i = s.layers.indexOfFirst { it.id == s.selectedId }
        val j = i + direction
        if (i < 0 || j !in s.layers.indices) return@update s
        val list = s.layers.toMutableList()
        val tmp = list[i]; list[i] = list[j]; list[j] = tmp
        s.copy(layers = list)
    }

    // ---- 3D photo -------------------------------------------------------------------------------

    /** Builds the 3D photo layers from the current edit (photo tools, adjustments, erased subject and text all included). */
    suspend fun buildWobble(maxSide: Int): com.hinterlay.app.render.Wobble.Planes? {
        val s = _state.value
        val source = s.source ?: return null
        return withContext(Dispatchers.Default) {
            val fg = s.foreground
            val (photo, photoFg) = if (s.fx.isNeutral) source to fg else if (s.fxSource != null) s.fxSource to (s.fxForeground ?: fg) else PhotoFx.render(source, fg, s.plate, s.fx, s.blendBitmap, s.faces)
            com.hinterlay.app.render.Wobble.build(photo, photoFg, s.layers, s.subjectEdit, s.adjust, s.plate, maxSide)
        }
    }

    // ---- Leaving the editor -----------------------------------------------------------

    // A fingerprint of everything the user can change, taken when the photo opens and after each save or export.
    private var savedHash = 0

    private fun currentHash(): Int {
        val s = _state.value
        return listOf(s.layers, s.adjust, s.fx, s.source?.let { System.identityHashCode(it) }, subjectUndo.size, maskUndo.size).hashCode()
    }

    private fun markClean() { savedHash = currentHash() }

    /** False right after opening, saving or exporting, so leaving then asks nothing. */
    fun hasUnsavedEdits(): Boolean = _state.value.phase == Phase.Ready && currentHash() != savedHash

    /** Keeps the current edit in the Library (no export, no ad), then calls [onDone] with whether it worked. */
    fun saveDraft(onDone: (Boolean) -> Unit) {
        val s = _state.value
        val source = s.source
        if (source == null) { onDone(false); return }
        val foreground = s.foreground
        val layers = s.layers
        val edit = s.subjectEdit
        val subjectMask = s.subjectMask
        val adjust = s.adjust
        val fx = s.fx
        val plateForDraft = s.plate
        val blendForDraft = s.blendBitmap
        val facesForDraft = s.faces
        viewModelScope.launch(Dispatchers.Default) {
            val ok = runCatching {
                val (photo, photoFg) = if (fx.isNeutral) source to foreground else PhotoFx.render(source, foreground, plateForDraft, fx, blendForDraft, facesForDraft)
                val composite = Compositor.composite(photo, photoFg, layers, edit, adjust)
                if (photo !== source) { photo.recycle(); if (photoFg !== foreground) photoFg?.recycle() }
                val app = getApplication<Application>()
                val file = withContext(Dispatchers.IO) { Exporter.writeJpeg(app, composite) }
                composite.recycle()
                withContext(Dispatchers.IO) { HistoryStore.save(app, source, foreground, layers, file, subjectMask, adjust, fx, blendForDraft) }
                com.hinterlay.app.cloud.Track.event("draft_saved")
            }.isSuccess
            withContext(Dispatchers.Main) { if (ok) markClean(); onDone(ok) }
        }
    }

    // ---- Export ---------------------------------------------------------------------

    /**
     * Starts rendering immediately, so the work happens while the rewarded ad plays.
     * No artificial delays: progress reflects real work.
     */
    fun beginExport(format: ExportFormat) {
        val s = _state.value
        val source = s.source ?: return
        val foreground = s.foreground
        val layers = s.layers
        val edit = s.subjectEdit
        val subjectMask = s.subjectMask
        val adjust = s.adjust
        val fx = s.fx
        val plateForExport = s.plate
        val blendForExport = s.blendBitmap
        val facesForExport = s.faces
        exportJob?.cancel()
        _exportVisible.value = false
        _export.value = ExportState.Running(0.05f, "Preparing")
        exportJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                // Photo tools are applied at full size so the saved file matches the preview.
                val (photo, photoFg) = if (fx.isNeutral) source to foreground else PhotoFx.render(source, foreground, plateForExport, fx, blendForExport, facesForExport)
                val composite = Compositor.composite(photo, photoFg, layers, edit, adjust)
                if (photo !== source) { photo.recycle(); if (photoFg !== foreground) photoFg?.recycle() }
                ensureActive()
                _export.value = ExportState.Running(0.35f, "Placing text behind your subject")
                val framed = Compositor.frame(composite, format)
                if (framed !== composite) composite.recycle()
                ensureActive()
                _export.value = ExportState.Running(0.6f, "Encoding high-quality JPEG")
                val app = getApplication<Application>()
                val file = withContext(Dispatchers.IO) { Exporter.writeJpeg(app, framed) }
                framed.recycle()
                ensureActive()
                _export.value = ExportState.Running(0.85f, "Saving to your gallery")
                val saved = withContext(Dispatchers.IO) { Exporter.saveToGallery(app, file) }
                withContext(Dispatchers.IO) {
                    runCatching { HistoryStore.save(app, source, foreground, layers, file, subjectMask, adjust, fx, blendForExport) }
                }
                com.hinterlay.app.cloud.Track.event("export_completed", "format" to format.name, "subject" to (foreground != null).toString())
                withContext(Dispatchers.Main) { markClean() }
                _export.value = ExportState.Done(file, saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                _export.value = ExportState.Failed("Not enough memory to export. Close other apps and try again.")
            } catch (e: Exception) {
                _export.value = ExportState.Failed("Export failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    /** Prepares the tilt wallpaper from the current edit. Returns false on failure. */
    suspend fun prepareWallpaper(): Boolean {
        val s = _state.value
        val source = s.source ?: return false
        return withContext(Dispatchers.Default) {
            runCatching { WallpaperStore.write(getApplication(), source, s.foreground, s.layers, s.subjectEdit, s.plate) }.isSuccess
        }
    }

    /** Update the depth-clock styling (no-op when not in clock mode). */
    fun setClock(transform: (com.hinterlay.app.wallpaper.DepthClockConfig) -> com.hinterlay.app.wallpaper.DepthClockConfig) {
        _state.update { s -> s.clock?.let { s.copy(clock = transform(it)) } ?: s }
    }

    /** Sets the edited composition + clock as the live depth-clock wallpaper (lock & home). */
    suspend fun prepareDepthClock(): Boolean {
        val s = _state.value
        val source = s.source ?: return false
        val config = s.clock ?: return false
        return withContext(Dispatchers.Default) {
            runCatching {
                com.hinterlay.app.wallpaper.DepthClockStore.write(getApplication(), source, s.foreground, config, s.layers, s.subjectEdit, s.plate)
            }.isSuccess
        }
    }

    /** Sets the edited composition + clock as a still lock-screen wallpaper. */
    suspend fun applyDepthClockLock(): Boolean {
        val s = _state.value
        val source = s.source ?: return false
        val config = s.clock ?: return false
        return withContext(Dispatchers.Default) {
            com.hinterlay.app.wallpaper.DepthClockStore.applyStaticLock(getApplication(), source, s.foreground, config, s.layers, s.subjectEdit, s.plate)
        }
    }

    /** Adds the current edit to the local WhatsApp sticker pack. Returns (success, count). */
    suspend fun addSticker(starterPack: Boolean): Pair<Int, Int> {
        val s = _state.value
        val source = s.source ?: return 0 to 0
        val app = getApplication<Application>()
        return withContext(Dispatchers.Default) {
            val added = runCatching {
                if (starterPack) StickerStore.addStarterPack(app, source, s.foreground, s.layers, s.subjectEdit)
                else if (StickerStore.add(app, source, s.foreground, s.layers, StickerStore.Style.WITH_TEXT, s.subjectEdit)) 1 else 0
            }.getOrDefault(0)
            added to StickerStore.list(app).size
        }
    }

    fun revealExport() {
        _exportVisible.value = true
    }

    fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
        _export.value = ExportState.Idle
        _exportVisible.value = false
    }

    fun dismissExport() {
        if (_export.value is ExportState.Running) return
        cancelExport()
    }

    fun reset() {
        cropBackup = null
        smoothJob?.cancel()
        loadJob?.cancel()
        cancelExport()
        loadedUri = null
        startAdShownFor = null
        _state.value = EditorUiState()
    }

    override fun onCleared() {
        detector.close()
    }
}
