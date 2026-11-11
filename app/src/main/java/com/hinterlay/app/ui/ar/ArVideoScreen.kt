package com.hinterlay.app.ui.ar

// Real-world AR video — Part B (Simulon-inspired). See docs/PLAN_REALWORLD_AR.md.
//
// A live ARCore scene (SceneView 3.6.0) that tracks the real world, estimates its lighting
// (Environmental HDR → Filament image-based lighting) and depth (occlusion), and lets you tap a
// detected surface to place a real CGI glTF model — or Hinterlay's own 3D text — lit by the real
// scene and hidden by closer real objects. Drag to move, pinch to scale, twist to rotate.
//
// Honest scope: ARCore Environmental HDR is a close on-device approximation of real-scene lighting,
// NOT Simulon's neural physically-based relighting (research-grade, not replicable on-device). The
// UI says so rather than overpromising.
//
// Routing: reached in-app from the radial Create menu's "Video" spoke — MainActivity maps the
// "arvideo" action to Screen.ArVideo and renders ArVideoScreen(onBack = { screen = Screen.Home }).
// The spoke is dimmed only on ARCore-incapable devices; this screen also gates itself on
// ArCoreApk availability below.

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.TrackingFailureReason
import com.hinterlay.app.export.Exporter
import com.hinterlay.app.ui.components.PrimaryButton
import com.hinterlay.app.ui.components.SecondaryButton
import io.github.sceneview.ar.ARScene
import io.github.sceneview.ar.arcore.createAnchorOrNull
import io.github.sceneview.ar.arcore.isValid
import io.github.sceneview.ar.getDescription
import io.github.sceneview.ar.rememberARCameraNode
import io.github.sceneview.ar.rememberARCameraStream
import io.github.sceneview.math.Position
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberView

// ── Placeable content ───────────────────────────────────────────────────────────────────────────

private enum class ArKind { Model, Text }

private data class ArItem(
    val label: String,
    val kind: ArKind,
    /** Asset path for a glTF model (kind == Model). */
    val asset: String? = null,
    /** Target size in metres (longest edge) for a model. */
    val scaleToUnits: Float = 0.3f,
    /** Named clips the user can switch between (first is the default); empty = play the only clip. */
    val clips: List<String> = emptyList(),
    /** True for rigged/animated models. */
    val animated: Boolean = false,
    /** Required visible credit for CC-BY assets; null for CC0 / on-brand content. */
    val attribution: String? = null,
)

// Animated creatures first (echo the Simulon mammoth + caveman), then static PBR props, then 3D text.
// See assets/models/CREDITS.txt for sources + licences.
private val arItems = listOf(
    ArItem("Fox", ArKind.Model, "models/fox.glb", scaleToUnits = 0.5f, clips = listOf("Walk", "Run", "Survey"), animated = true,
        attribution = "Fox by PixelMannen (CC0) + @tomkranis (CC BY 4.0)"),
    ArItem("Figure", ArKind.Model, "models/walking_figure.glb", scaleToUnits = 0.6f, animated = true,
        attribution = "RiggedFigure © Cesium, CC BY 4.0"),
    ArItem("Toy car", ArKind.Model, "models/toy_car.glb", scaleToUnits = 0.3f),
    ArItem("Lamp", ArKind.Model, "models/iridescent_lamp.glb", scaleToUnits = 0.3f,
        attribution = "IridescenceLamp © 2022 Wayfair, CC BY 4.0"),
    ArItem("Chair", ArKind.Model, "models/sheen_chair.glb", scaleToUnits = 0.45f),
    ArItem("Hinterlay", ArKind.Text),
)

/**
 * Real-world AR video screen. Tap a surface to place the selected 3D content, lit by the real scene.
 *
 * @param onBack leave the screen (MainActivity returns to Home).
 */
@Composable
fun ArVideoScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    val availability = remember {
        try {
            ArCoreApk.getInstance().checkAvailability(context)
        } catch (e: Exception) {
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE
        }
    }

    if (availability.isTransient) {
        ArMessage("Checking for AR…", "One moment while we see whether this device supports AR.", onBack)
        return
    }
    if (!availability.isSupported) {
        ArMessage(
            "AR isn’t available here",
            "Real-world video needs ARCore (Google Play Services for AR), which this device doesn’t " +
                "support. The rest of Hinterlay works as usual.",
            onBack,
        )
        return
    }

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (!granted) {
        ArMessage(
            "Camera access needed",
            "Real-world video uses the camera to anchor your creation in the room around you. Nothing " +
                "is recorded until you choose to.",
            onBack,
            actionLabel = "Allow camera",
            onAction = { ask.launch(Manifest.permission.CAMERA) },
        )
        return
    }

    ArContent(onBack)
}

@Composable
private fun ArContent(onBack: () -> Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val cameraNode = rememberARCameraNode(engine)
    val view = rememberView(engine)
    val collisionSystem = rememberCollisionSystem(view)

    // Occlusion off by default in ARCameraStream; turn it on so real objects can hide the virtual one.
    val cameraStream = rememberARCameraStream(materialLoader).apply { isDepthOcclusionEnabled = true }

    // Soft contact shadow: a few stacked translucent discs under the object, each a little smaller,
    // so the darkness builds toward the centre and fades at the rim. Not a true cast shadow (there's
    // no real geometry to receive one in AR) — a grounding cue. Relighting comes from HDR below.
    val shadowRings = remember { contactShadowRings() }
    val shadowMaterials = remember(materialLoader) {
        shadowRings.map { (_, alpha) ->
            materialLoader.createColorInstance(
                color = Color(0f, 0f, 0f, alpha), metallic = 0f, roughness = 1f, reflectance = 0f,
            )
        }
    }

    var selected by remember { mutableIntStateOf(0) }
    // Which animation clip is playing for the selected model (resets when the model changes).
    var clipIndex by remember(selected) { mutableIntStateOf(0) }
    var frame by remember { mutableStateOf<Frame?>(null) }
    var anchor by remember { mutableStateOf<Anchor?>(null) }
    var trackingFailure by remember { mutableStateOf<TrackingFailureReason?>(null) }

    DisposableEffect(Unit) { onDispose { anchor?.detach() } }

    // Filament fidelity levers that are safe on the AR passthrough view: screen-space ambient
    // occlusion (grounds CGI into contact with surfaces) and FXAA (smooths CGI edges so they blend
    // with the camera feed). Guarded — a renderer quirk must never take down the scene. ACES
    // tonemapping + bloom are intentionally NOT applied here: they'd also re-grade the camera feed.
    LaunchedEffect(view) {
        runCatching {
            view.setAntiAliasing(com.google.android.filament.View.AntiAliasing.FXAA)
            view.setAmbientOcclusionOptions(
                com.google.android.filament.View.AmbientOcclusionOptions().apply { enabled = true },
            )
        }
    }

    // A faint, static film-grain texture reused for the blend overlay (see below).
    val grain = remember { makeGrain(96) }

    // ── Recording (MediaProjection screen capture) ───────────────────────────────────────────────
    val context = LocalContext.current
    val recording by ScreenRecordService.recording.collectAsState()
    val lastResult by ScreenRecordService.lastResult.collectAsState()
    // User grants screen capture each time; on OK we hand the token to the foreground service.
    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ScreenRecordService::class.java).apply {
                    action = ScreenRecordService.ACTION_START
                    putExtra(ScreenRecordService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenRecordService.EXTRA_DATA, result.data)
                },
            )
        } else {
            Toast.makeText(context, "Recording needs screen-capture permission", Toast.LENGTH_SHORT).show()
        }
    }
    val startRecording: () -> Unit = {
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(mpm.createScreenCaptureIntent())
    }
    val stopRecording: () -> Unit = {
        context.startService(
            Intent(context, ScreenRecordService::class.java).apply { action = ScreenRecordService.ACTION_STOP },
        )
    }
    // Elapsed time for the stop pill, plus a safety cap so a forgotten recording can't run on.
    var recSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(recording) {
        recSeconds = 0
        while (recording) {
            kotlinx.coroutines.delay(1000)
            recSeconds++
            if (recSeconds >= MAX_RECORD_SECONDS) stopRecording()
        }
    }
    // The last finished clip, kept so it can be shared from here.
    var lastClip by remember { mutableStateOf<java.io.File?>(null) }
    // When a recording finishes, tell the user and offer to share it.
    LaunchedEffect(lastResult) {
        lastResult?.let { res ->
            if (res.file != null) {
                lastClip = res.file
                // Match the app's other video exports: save + a quiet toast (no forced share sheet).
                Toast.makeText(
                    context,
                    if (res.savedToGallery) "Saved to Movies/Hinterlay" else "Video saved",
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                Toast.makeText(context, "Couldn't record on this device", Toast.LENGTH_LONG).show()
            }
            ScreenRecordService.lastResult.value = null
        }
    }
    // Stop recording if the user leaves the screen or backgrounds the app. ON_STOP fires when the
    // app is no longer visible (e.g. the user switches to another app), so an entire-screen capture
    // can never keep recording other apps. onDispose covers navigating away within Hinterlay.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP && ScreenRecordService.recording.value) {
                stopRecording()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (ScreenRecordService.recording.value) stopRecording()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        ARScene(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            view = view,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            collisionSystem = collisionSystem,
            cameraNode = cameraNode,
            cameraStream = cameraStream,
            // The dot grid is a placement aid: hide it once something is placed and while recording,
            // so neither the view nor the video shows it.
            planeRenderer = anchor == null && !recording,
            sessionConfiguration = { session, config ->
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                config.depthMode =
                    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) Config.DepthMode.AUTOMATIC
                    else Config.DepthMode.DISABLED
                // Instant Placement: a tap can place right away (estimated depth), refining as
                // tracking improves — robust in poor light / before a plane has converged.
                config.instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
                // Environmental HDR → Filament IBL (reflection cubemap + ambient SH + main light).
                config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            },
            onTrackingFailureChanged = { trackingFailure = it },
            onSessionUpdated = { _, updatedFrame -> frame = updatedFrame },
            onGestureListener = rememberOnGestureListener(
                onSingleTapConfirmed = { motionEvent, node ->
                    // Tap empty space to place / move the anchor. Taps that land on the object itself
                    // (node != null) are left for the editable drag/scale/rotate.
                    if (node == null) {
                        frame?.let { f ->
                            // Prefer a tracked plane; else the depth map's real surface under the
                            // tap; only then Instant Placement at a guessed 1 m. The depth step
                            // matters: a guessed distance beyond the real surface puts the model
                            // behind it, where depth occlusion hides it entirely.
                            val hits = f.hitTest(motionEvent.x, motionEvent.y)
                            val hit = hits.firstOrNull { it.isValid(depthPoint = false, point = false) }
                                ?: hits.firstOrNull { it.isValid(depthPoint = true, point = false) }
                                ?: f.hitTestInstantPlacement(motionEvent.x, motionEvent.y, 1f).firstOrNull()
                            hit?.createAnchorOrNull()?.let { newAnchor ->
                                anchor?.detach()
                                anchor = newAnchor
                            }
                        }
                    }
                },
            ),
        ) {
            anchor?.let { placed ->
                AnchorNode(anchor = placed) {
                    val item = arItems[selected]
                    // Grounding contact shadow, sized to the content (see contactShadowRings).
                    val shadowRadius = if (item.kind == ArKind.Model) item.scaleToUnits * 0.45f else 0.12f
                    shadowRings.forEachIndexed { i, (fraction, _) ->
                        CylinderNode(
                            radius = shadowRadius * fraction,
                            height = 0.001f,
                            materialInstance = shadowMaterials[i],
                            // Stack the discs a hair apart so they don't z-fight.
                            position = Position(y = 0.001f + i * 0.0006f),
                        )
                    }
                    when (item.kind) {
                        ArKind.Model -> {
                            val instance = rememberModelInstance(modelLoader, item.asset!!)
                            instance?.let {
                                ModelNode(
                                    modelInstance = it,
                                    scaleToUnits = item.scaleToUnits,
                                    // Loop the chosen named clip (Fox: Walk / Run / Survey); for a rigged
                                    // model with a single unnamed clip, autoAnimate plays it.
                                    autoAnimate = item.animated && item.clips.isEmpty(),
                                    animationName = item.clips.getOrNull(clipIndex),
                                    animationLoop = true,
                                    isEditable = true,
                                    // The pinch range is in the node's own scale, and scaleToUnits
                                    // leaves that anywhere from ~0.005 to ~1 depending on how the mesh
                                    // was authored — so make the limits relative to it.
                                    apply = { editableScaleRange = (scale.x * 0.4f)..(scale.x * 3f) },
                                )
                            }
                        }
                        ArKind.Text -> {
                            // Hinterlay's on-brand 3D text (flat panel, white on transparent).
                            TextNode(
                                text = "Hinterlay",
                                textColor = android.graphics.Color.WHITE,
                                backgroundColor = android.graphics.Color.TRANSPARENT,
                                widthMeters = 0.4f,
                                heightMeters = 0.12f,
                                position = Position(y = 0.1f),
                            )
                        }
                    }
                }
            }
        }

        // Film-grain overlay over the whole frame (camera + CGI) — a subtle shared texture helps the
        // rendered models sit in the photographed scene instead of looking pasted on. Very low alpha.
        Box(
            Modifier.fillMaxSize().drawWithCache {
                val brush = ShaderBrush(ImageShader(grain, TileMode.Repeated, TileMode.Repeated))
                onDrawBehind { drawRect(brush, alpha = 0.05f) }
            },
        )

        // ── Minimal black/white overlay (kept legible over live camera) ──────────────────────────

        if (recording) {
            // While recording, strip the chrome so the capture is just the scene — leave only a
            // small, tappable "stop" pill at the top.
            Surface(
                onClick = stopRecording,
                modifier = Modifier.statusBarsPadding().padding(top = 12.dp).align(Alignment.TopCenter),
                color = Color.Black.copy(alpha = 0.55f),
                contentColor = Color.White,
                shape = RoundedCornerShape(50),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                ) {
                    Icon(Icons.Filled.FiberManualRecord, null, tint = Color(0xFFE53935), modifier = Modifier.size(14.dp))
                    Text(
                        "%d:%02d · tap to stop".format(recSeconds / 60, recSeconds % 60),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Icon(Icons.Filled.Stop, null, modifier = Modifier.size(16.dp))
                }
            }
        } else {
            IconButton(
                onClick = onBack,
                modifier = Modifier.statusBarsPadding().padding(8.dp).align(Alignment.TopStart),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }

            val status = trackingFailure?.getDescription(LocalContext.current)
                ?: if (anchor == null) "Move your phone, then tap a surface"
                else "Tap to move · pinch to scale · twist to rotate"
            if (status.isNotBlank()) {
                Pill(status, Modifier.statusBarsPadding().padding(top = 12.dp).align(Alignment.TopCenter))
            }

            Column(
                modifier = Modifier.navigationBarsPadding().fillMaxWidth().padding(bottom = 40.dp)
                    .align(Alignment.BottomCenter),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Honest lighting note + CC-BY attribution, shown once something is placed.
                if (anchor != null) {
                    val note = arItems[selected].attribution
                        ?.let { "Lit by your room (ARCore HDR — a close match, not film-grade) · $it" }
                        ?: "Lit by your room (ARCore HDR) — a close match, not film-grade relighting"
                    Pill(note, textAlpha = 0.85f)
                }

                // Animation picker for models with more than one clip (Fox: Walk / Run / Survey).
                val clips = arItems[selected].clips
                if (anchor != null && clips.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        clips.forEachIndexed { i, name ->
                            PickPill(label = name, selected = i == clipIndex, onClick = { clipIndex = i })
                        }
                    }
                }

                // Record, share the last clip, remove.
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PrimaryButton("Record", onClick = { startRecording() }, icon = Icons.Outlined.Videocam)
                    lastClip?.let { clip ->
                        SecondaryButton(text = "Share", contentColor = Color.White, onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent.createChooser(Exporter.shareVideoIntent(context, clip), "Share AR video"),
                                )
                            }
                        })
                    }
                    if (anchor != null) {
                        SecondaryButton(text = "Remove", contentColor = Color.White, onClick = { anchor?.detach(); anchor = null })
                    }
                }

                // Content picker — translucent pills for contrast over the camera feed.
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    arItems.forEachIndexed { i, item ->
                        PickPill(label = item.label, selected = i == selected, onClick = { selected = i })
                    }
                }
            }
        }
    }
}

/** Longest a single recording may run before it stops itself. */
private const val MAX_RECORD_SECONDS = 60

/**
 * The contact shadow's falloff, as stacked discs: each entry is (radius as a fraction of the shadow's
 * full radius, alpha of that disc), largest first. Where discs overlap their alphas add up, so the
 * centre ends up darkest and the rim faintest.
 */
private fun contactShadowRings(): List<Pair<Float, Float>> =
    // TODO(human): tune this falloff — see the Learn by Doing note. These values are a plain default.
    listOf(1.0f to 0.07f, 0.8f to 0.08f, 0.6f to 0.09f, 0.4f to 0.10f)

/** A small tileable grey-noise bitmap used as a film-grain overlay. Built once. */
private fun makeGrain(size: Int): ImageBitmap {
    val px = IntArray(size * size)
    val rnd = java.util.Random(7)
    for (i in px.indices) {
        val v = rnd.nextInt(256)
        px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    return android.graphics.Bitmap.createBitmap(px, size, size, android.graphics.Bitmap.Config.ARGB_8888)
        .asImageBitmap()
}

@Composable
private fun Pill(text: String, modifier: Modifier = Modifier, textAlpha: Float = 1f) {
    Surface(modifier = modifier, color = Color.Black.copy(alpha = 0.55f), shape = RoundedCornerShape(50)) {
        Text(
            text = text,
            color = Color.White.copy(alpha = textAlpha),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun PickPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val fb = com.hinterlay.app.fx.rememberFeedback()
    Surface(
        onClick = { onClick(); fb.select() },
        color = if (selected) Color.White else Color.Black.copy(alpha = 0.5f),
        contentColor = if (selected) Color.Black else Color.White,
        shape = RoundedCornerShape(50),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
    }
}

/** Full-screen neutral message (unsupported / permission / checking). */
@Composable
private fun ArMessage(
    title: String,
    body: String,
    onBack: () -> Unit,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
        )
        if (actionLabel != null && onAction != null) PrimaryButton(actionLabel, onClick = onAction)
        SecondaryButton(text = "Back", onClick = onBack, modifier = Modifier.padding(top = 12.dp))
    }
}
