package com.hinterlay.app.ui.ar

// Real-world AR video — Part B, Phase 1 (AR foundation). See docs/PLAN_REALWORLD_AR.md.
//
// A live ARCore scene (via SceneView 3.6.0) that tracks the real world, estimates its lighting
// (Environmental HDR) and depth (occlusion), and lets you tap a detected surface to drop one test
// 3D object that is lit by the real scene and hidden by closer real objects. Phase 2 replaces the
// test sphere with Hinterlay's 3D text / the depth clock.
//
// TODO(route): MainActivity routing is owned by the radial-menu session, so this screen is NOT wired
// into navigation here. To route to it once both branches merge: add a `MainScreen` enum entry (e.g.
// `ArVideo`) and a deep-link action — handle `hinterlay_action == "arvideo"` in MainActivity's intent
// parsing — then render `ArVideoScreen(onBack = { screen = MainScreen.Home })`. The radial Create menu
// already reserves a disabled "Real-world video" item (Part A) that should point here.

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.TrackingFailureReason
import com.hinterlay.app.ui.components.PrimaryButton
import com.hinterlay.app.ui.components.SecondaryButton
import io.github.sceneview.ar.ARScene
import io.github.sceneview.ar.arcore.createAnchorOrNull
import io.github.sceneview.ar.arcore.isValid
import io.github.sceneview.ar.getDescription
import io.github.sceneview.ar.rememberARCameraNode
import io.github.sceneview.ar.rememberARCameraStream
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberView

/**
 * Real-world AR video screen (Phase 1 — foundation). Tracks the world, places one test object on tap.
 *
 * @param onBack leave the screen (navigation is owned elsewhere — see the TODO(route) above).
 */
@Composable
fun ArVideoScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // 1) Device gate. checkAvailability() may briefly return a transient "checking" state the first
    //    time; treat anything not-yet-known as "still deciding" and show a neutral loading note.
    val availability = remember {
        try {
            ArCoreApk.getInstance().checkAvailability(context)
        } catch (e: Exception) {
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE
        }
    }

    if (availability.isTransient) {
        ArMessage(
            title = "Checking for AR…",
            body = "One moment while we see whether this device supports AR.",
            onBack = onBack,
        )
        return
    }
    if (!availability.isSupported) {
        ArMessage(
            title = "AR isn’t available here",
            body = "Real-world video needs ARCore (Google Play Services for AR), which this device " +
                "doesn’t support. The rest of Hinterlay works as usual.",
            onBack = onBack,
        )
        return
    }

    // 2) Camera gate — ARCore needs the camera; ask in context, same pattern as the camera screen.
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (!granted) {
        ArMessage(
            title = "Camera access needed",
            body = "Real-world video uses the camera to anchor your creation in the room around you. " +
                "Nothing is recorded until you choose to.",
            onBack = onBack,
            actionLabel = "Allow camera",
            onAction = { ask.launch(Manifest.permission.CAMERA) },
        )
        return
    }

    ArContent(onBack = onBack)
}

@Composable
private fun ArContent(onBack: () -> Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val cameraNode = rememberARCameraNode(engine)
    val view = rememberView(engine)
    val collisionSystem = rememberCollisionSystem(view)

    // Occlusion is OFF by default in ARCameraStream; turn it on so real objects can hide the virtual
    // one. It quietly no-ops on devices without Depth support.
    val cameraStream = rememberARCameraStream(materialLoader).apply { isDepthOcclusionEnabled = true }

    // A matte near-white PBL material. Low metallic + mid roughness reads as a clean neutral object
    // that still picks up the real scene's light and soft reflections from the HDR cubemap. On-brand:
    // black/white, no colour.
    val objectMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(
            color = Color(0xFFF2F2F2),
            metallic = 0.0f,
            roughness = 0.35f,
            reflectance = 0.5f,
        )
    }

    var frame by remember { mutableStateOf<Frame?>(null) }
    var anchor by remember { mutableStateOf<Anchor?>(null) }
    var trackingFailure by remember { mutableStateOf<TrackingFailureReason?>(null) }

    DisposableEffect(Unit) { onDispose { anchor?.detach() } }

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
            planeRenderer = true,
            sessionConfiguration = { session, config ->
                // Find horizontal + vertical surfaces.
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                // Depth API (occlusion) when the device supports it, otherwise off.
                config.depthMode =
                    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) Config.DepthMode.AUTOMATIC
                    else Config.DepthMode.DISABLED
                // Environmental HDR: real light direction + intensity + reflection cubemap.
                config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            },
            onTrackingFailureChanged = { trackingFailure = it },
            onSessionUpdated = { _, updatedFrame -> frame = updatedFrame },
            onGestureListener = rememberOnGestureListener(
                onSingleTapConfirmed = { motionEvent, node ->
                    if (node == null) {
                        // Place on a real tracked surface (not a loose depth/feature point).
                        frame?.hitTest(motionEvent.x, motionEvent.y)
                            ?.firstOrNull { it.isValid(depthPoint = false, point = false) }
                            ?.createAnchorOrNull()
                            ?.let { newAnchor ->
                                anchor?.detach()
                                anchor = newAnchor
                            }
                    }
                },
            ),
        ) {
            anchor?.let { placed ->
                AnchorNode(anchor = placed) {
                    // The test object: a small sphere (~14 cm), lit by the real scene, occludable.
                    SphereNode(
                        radius = 0.07f,
                        materialInstance = objectMaterial,
                    )
                }
            }
        }

        // --- Minimal black/white overlay ---------------------------------------------------------

        // Back button (top-left).
        IconButton(
            onClick = onBack,
            modifier = Modifier.statusBarsPadding().padding(8.dp).align(Alignment.TopStart),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }

        // Status pill (top-center): tracking trouble > guidance.
        val status = trackingFailure?.getDescription(LocalContext.current)
            ?: if (anchor == null) "Move your phone, then tap a surface"
            else "Placed — tap again to move it"
        if (status.isNotBlank()) {
            StatusPill(status, Modifier.statusBarsPadding().padding(top = 12.dp).align(Alignment.TopCenter))
        }

        // Remove hint (bottom) once something is placed.
        if (anchor != null) {
            SecondaryButton(
                text = "Remove",
                onClick = { anchor?.detach(); anchor = null },
                modifier = Modifier.navigationBarsPadding().padding(bottom = 24.dp).align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun StatusPill(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.55f),
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
        )
    }
}

/** Full-screen neutral message (unsupported / permission / checking), with Back and optional action. */
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
        if (actionLabel != null && onAction != null) {
            PrimaryButton(actionLabel, onClick = onAction)
        }
        SecondaryButton(text = "Back", onClick = onBack, modifier = Modifier.padding(top = 12.dp))
    }
}
