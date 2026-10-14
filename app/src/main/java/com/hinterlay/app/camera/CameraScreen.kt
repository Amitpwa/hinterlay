package com.hinterlay.app.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.view.TextureView
import android.view.View
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.hinterlay.app.ui.theme.pressScale
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.hinterlay.app.cloud.Track
import com.hinterlay.app.fx.Fx
import com.hinterlay.app.fx.rememberFeedback
import com.hinterlay.app.render.ImageLoader
import com.hinterlay.app.render.Look
import com.hinterlay.app.render.PhotoAdjust
import com.hinterlay.app.ui.components.PrimaryButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val SCAN_SECONDS = 1.15f

private class LiveFaces(val poses: List<FacePose>, val width: Int, val height: Int)

/** Camera with live colour filters and funny face masks. A shot goes straight to the editor. */
@Composable
fun CameraScreen(onClose: () -> Unit, onPhoto: (Uri) -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    if (!granted) {
        Column(
            Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Text("Camera access needed", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text(
                "Hinterlay uses the camera only while this screen is open. Photos stay on your phone.",
                style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.padding(vertical = 12.dp),
            )
            PrimaryButton("Allow camera", onClick = { ask.launch(Manifest.permission.CAMERA) })
            Text("Not now", color = Color.White, modifier = Modifier.padding(top = 20.dp).clickable(onClick = onClose))
        }
        return
    }
    CameraContent(onClose, onPhoto)
}

@Composable
private fun CameraContent(onClose: () -> Unit, onPhoto: (Uri) -> Unit) {
    // The system camera sound: it follows the phone's region and silent-mode rules, so it is not tied to the Sound effects switch.
    val shutterSound = remember { android.media.MediaActionSound().apply { load(android.media.MediaActionSound.SHUTTER_CLICK) } }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { shutterSound.release() } }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val fb = rememberFeedback()

    var front by remember { mutableStateOf(true) }
    var look by remember { mutableStateOf(Look.NORMAL) }
    var mask by remember { mutableStateOf(FaceMask.NONE) }
    var tab by remember { mutableStateOf(0) } // 0 = filters, 1 = masks
    var busy by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf<LiveFaces?>(null) }

    val currentMask by rememberUpdatedState(mask)
    val currentLook by rememberUpdatedState(look)
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val detector = remember {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL) // the face outline, brows, eyes, nose and lips
                .enableTracking() // so each face keeps its identity between frames and can be smoothed
                .build()
        )
    }
    val smoother = remember { FaceSmoother() }
    // When the current mask was chosen: the scan animation restarts, and the mask lands after it.
    var maskSince by remember { mutableStateOf(System.nanoTime()) }
    LaunchedEffect(mask) { maskSince = System.nanoTime(); smoother.reset() }
    // A clock that ticks every frame while a mask is on, so the scan and the mask's movement animate.
    var frameTick by remember { mutableStateOf(0L) }
    LaunchedEffect(mask != FaceMask.NONE) {
        if (mask == FaceMask.NONE) return@LaunchedEffect
        while (true) androidx.compose.runtime.withFrameNanos { frameTick = it }
    }
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE // TextureView, so a live colour filter can be applied
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    DisposableEffect(Unit) { onDispose { executor.shutdown(); detector.close() } }

    // (Re)bind the camera whenever the lens flips.
    LaunchedEffect(front) {
        val provider = suspendCancellableCoroutine<ProcessCameraProvider> { cont ->
            val f = ProcessCameraProvider.getInstance(context)
            f.addListener({ cont.resume(f.get()) }, ContextCompat.getMainExecutor(context))
        }
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build().also { a ->
            a.setAnalyzer(executor) { proxy ->
                val media = proxy.image
                if (media == null || currentMask == FaceMask.NONE) {
                    if (live != null) live = null
                    proxy.close(); return@setAnalyzer
                }
                val rotation = proxy.imageInfo.rotationDegrees
                val w = if (rotation % 180 == 0) proxy.width else proxy.height
                val h = if (rotation % 180 == 0) proxy.height else proxy.width
                detector.process(InputImage.fromMediaImage(media, rotation))
                    .addOnSuccessListener { live = LiveFaces(smoother.update(it, System.nanoTime()), w, h) }
                    .addOnCompleteListener { proxy.close() }
            }
        }
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        // Some devices (and emulators) have a single camera: fall back to the one that exists.
        if (!provider.hasCamera(selector)) {
            val other = if (front) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
            if (provider.hasCamera(other)) {
                front = !front
                return@LaunchedEffect
            }
        }
        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis, capture)
        }.onFailure { Toast.makeText(context, "Couldn't start the camera", Toast.LENGTH_SHORT).show() }
    }

    fun shoot() {
        if (busy) return
        busy = true
        shutterSound.play(android.media.MediaActionSound.SHUTTER_CLICK)
        val tmp = File(context.cacheDir, "shot_${System.currentTimeMillis()}.jpg")
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(tmp).build(), ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(e: ImageCaptureException) { busy = false; Toast.makeText(context, "Couldn't take the photo", Toast.LENGTH_SHORT).show() }
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    scope.launch {
                        val out = runCatching { finishShot(context, tmp, front, currentLook, currentMask, detector) }.getOrNull()
                        tmp.delete()
                        busy = false
                        if (out != null) { fb.success(); Track.event("camera_shot", "mask" to currentMask.name, "look" to currentLook.name); onPhoto(Uri.fromFile(out)) }
                        else Toast.makeText(context, "Couldn't save the photo", Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
            update = { pv ->
                // Live colour filter: the preview is a TextureView, so a hardware layer paint recolours it.
                val paint = PhotoAdjust(look = look).colorMatrix()?.let { m ->
                    Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(m) }
                }
                pv.post { (pv.getChildAt(0) as? TextureView)?.setLayerType(View.LAYER_TYPE_HARDWARE, paint) }
            },
        )

        // Live masks drawn over the preview.
        ComposeCanvas(Modifier.fillMaxSize()) {
            frameTick // read so the canvas redraws every frame while a mask is on
            val lf = live ?: return@ComposeCanvas
            if (mask == FaceMask.NONE) return@ComposeCanvas
            val s = max(size.width / lf.width, size.height / lf.height)
            val dx = (size.width - lf.width * s) / 2f
            val dy = (size.height - lf.height * s) / 2f
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                nc.save()
                if (front) { nc.translate(size.width, 0f); nc.scale(-1f, 1f) } // selfie preview is mirrored
                nc.translate(dx, dy)
                nc.scale(s, s)
                val now = System.nanoTime()
                lf.poses.forEach { pose ->
                    // Scan first (about a second), then the mask lands on the face.
                    val el = (now - maxOf(maskSince, smoother.firstSeen(pose.id))) / 1e9f
                    FaceMesh.draw(nc, pose, el / SCAN_SECONDS)
                    Masks3D.draw(nc, pose, mask, el, ((el - (SCAN_SECONDS - 0.3f)) / 0.45f).coerceIn(0f, 1f))
                }
                nc.restore()
            }
        }

        // Top bar.
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = onClose, modifier = Modifier.clip(CircleShape).background(Color(0x59000000)).border(1.dp, Color(0x40FFFFFF), CircleShape)) {
                Icon(Icons.Filled.Close, "Close camera", tint = Color.White)
            }
            IconButton(onClick = { fb.select(); front = !front }, modifier = Modifier.clip(CircleShape).background(Color(0x59000000)).border(1.dp, Color(0x40FFFFFF), CircleShape)) {
                Icon(Icons.Filled.FlipCameraAndroid, "Switch camera", tint = Color.White)
            }
        }

        // Bottom controls.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (tab == 0) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
                    items(Look.entries) { l ->
                        Chip(l.label, selected = look == l) { fb.select(); look = l }
                    }
                }
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
                    items(FaceMask.entries) { m ->
                        Chip("${m.emoji} ${m.label}", selected = mask == m) { fb.select(); mask = m; if (m == FaceMask.NONE) live = null }
                    }
                }
            }
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("Filters", "Masks").forEachIndexed { i, name ->
                    val on = tab == i
                    val alpha by androidx.compose.animation.core.animateFloatAsState(if (on) 1f else 0.55f, com.hinterlay.app.ui.theme.Motion.enter(), label = "tab")
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { fb.select(); tab = i }.padding(horizontal = 4.dp, vertical = 4.dp)) {
                        Text(name, color = Color.White.copy(alpha = alpha), style = MaterialTheme.typography.titleSmall)
                        Box(Modifier.padding(top = 4.dp).size(width = if (on) 18.dp else 0.dp, height = 2.dp).clip(CircleShape).background(Color.White))
                    }
                }
            }
            val shutterSrc = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Box(
                Modifier.padding(top = 14.dp).size(78.dp)
                    .pressScale(shutterSrc, 0.92f)
                    .clip(CircleShape).border(3.dp, Color.White, CircleShape)
                    .padding(7.dp).clip(CircleShape).background(if (busy) Color(0xFFBBBBBB) else Color.White)
                    .clickable(interactionSource = shutterSrc, indication = null, enabled = !busy) { shoot() },
            )
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        Modifier.pressScale(src, 0.95f).clip(CircleShape)
            .background(if (selected) Color.White else Color(0x59000000))
            .border(1.dp, if (selected) Color.White else Color(0x40FFFFFF), CircleShape)
            .clickable(interactionSource = src, indication = null, onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
    ) { Text(label, color = if (selected) Color.Black else Color.White, style = MaterialTheme.typography.labelLarge) }
}

/** Decodes the shot, mirrors selfies, applies the look, draws masks on detected faces and saves a JPEG. */
/** Decodes the shot, mirrors selfies, applies the look, draws masks on detected faces and saves a JPEG. */
private suspend fun finishShot(
    context: android.content.Context, file: File, front: Boolean, look: Look, mask: FaceMask,
    detector: com.google.mlkit.vision.face.FaceDetector,
): File = withContext(Dispatchers.Default) {
    val raw = ImageLoader.decode(context, Uri.fromFile(file))
    val bmp = Bitmap.createBitmap(raw.width, raw.height, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    PhotoAdjust(look = look).colorMatrix()?.let { paint.colorFilter = android.graphics.ColorMatrixColorFilter(it) }
    val m = Matrix().apply { if (front) { postScale(-1f, 1f); postTranslate(raw.width.toFloat(), 0f) } }
    c.drawBitmap(raw, m, paint)
    if (mask != FaceMask.NONE) {
        // Detect on the final (mirrored) pixels so the mask lands exactly where the face is.
        val faces = runCatching { detector.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, 0)).await() }.getOrDefault(emptyList())
        faces.forEach { FaceMasks.draw(c, it, mask) }
    }
    raw.recycle()
    val dir = File(context.cacheDir, "captures").apply { mkdirs() }
    dir.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 24L * 3600 * 1000 }?.forEach { it.delete() }
    val out = File(dir, "cam_${System.currentTimeMillis()}.jpg")
    FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    bmp.recycle()
    out
}
