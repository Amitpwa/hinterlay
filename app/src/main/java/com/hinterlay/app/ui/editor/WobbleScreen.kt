package com.hinterlay.app.ui.editor

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import com.hinterlay.app.ui.components.Chip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hinterlay.app.audio.Music
import com.hinterlay.app.export.Exporter
import com.hinterlay.app.export.VideoEncoder
import com.hinterlay.app.render.Wobble
import com.hinterlay.app.ui.components.LabeledSlider
import com.hinterlay.app.ui.components.PrimaryButton
import com.hinterlay.app.ui.components.TonalButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private const val LOOP_SECONDS = 4.5f
private const val FPS = 30

/** What plays under the video: nothing, one of the built-in tracks, or a song from the phone. */
private sealed interface MusicChoice {
    data object None : MusicChoice
    data class Built(val track: Music.Track) : MusicChoice
    data class Mine(val uri: Uri, val name: String, val lengthMs: Long) : MusicChoice
}

/** The 3D photo: a live preview of the layers moving, with a few motions and music, and a button that saves it as a video. */
@Composable
fun WobbleDialog(planes: Wobble.Planes, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fb = com.hinterlay.app.fx.rememberFeedback()
    var style by remember { mutableStateOf(Wobble.Style.WOBBLE) }
    var strength by remember { mutableFloatStateOf(0.6f) }
    var tiltMode by remember { mutableStateOf(false) }
    var time by remember { mutableFloatStateOf(0f) }
    var tx by remember { mutableFloatStateOf(0f) }
    var ty by remember { mutableFloatStateOf(0f) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var saved by remember { mutableStateOf<File?>(null) }
    var music by remember { mutableStateOf<MusicChoice>(MusicChoice.None) }
    var startSec by remember { mutableLongStateOf(0L) }
    var sound by remember { mutableStateOf<android.media.AudioTrack?>(null) }

    val pickSong = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: "Your song"
            scope.launch {
                val len = withContext(Dispatchers.IO) { Music.durationMs(context, uri) }
                if (len <= 0L) Toast.makeText(context, "Couldn't read that file", Toast.LENGTH_SHORT).show()
                else { startSec = 0; saved = null; music = MusicChoice.Mine(uri, name, len) }
            }
        }
    }

    /** The music as PCM for the chosen option, exactly as long as the video, faded at both ends. Null for no music or a failed decode. */
    suspend fun musicPcm(choice: MusicChoice, startAtSec: Long): ShortArray? = withContext(Dispatchers.Default) {
        val pcm = when (choice) {
            MusicChoice.None -> null
            is MusicChoice.Built -> Music.synth(choice.track, LOOP_SECONDS.toDouble())
            is MusicChoice.Mine -> Music.decode(context, choice.uri, startAtSec * 1000, (LOOP_SECONDS * 1000).toLong())
        }
        pcm?.also { Music.fade(it, if (choice is MusicChoice.Built) 0 else 120, if (choice is MusicChoice.Built) 0 else 600) }
    }

    // Preview sound: the chosen music looping while the picture moves.
    LaunchedEffect(music, startSec) {
        sound?.let { runCatching { it.stop(); it.release() } }
        sound = null
        val pcm = musicPcm(music, startSec) ?: return@LaunchedEffect
        val at = runCatching {
            android.media.AudioTrack.Builder()
                .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(Music.RATE).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO).setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                .build().also { it.write(pcm, 0, pcm.size); it.setLoopPoints(0, pcm.size / 2, -1); it.play() }
        }.getOrNull()
        sound = at
    }
    DisposableEffect(Unit) { onDispose { sound?.let { runCatching { it.stop(); it.release() } } } }

    // The clock for the automatic motion.
    LaunchedEffect(Unit) {
        val start = System.nanoTime()
        while (true) {
            androidx.compose.runtime.withFrameNanos { now -> time = (((now - start) / 1e9f) / LOOP_SECONDS) % 1f }
        }
    }
    // Phone tilt, when that mode is chosen.
    DisposableEffect(tiltMode) {
        if (!tiltMode) return@DisposableEffect onDispose { }
        val sm = context.getSystemService(android.content.Context.SENSOR_SERVICE) as? android.hardware.SensorManager
        val sensor = sm?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        var sx = 0f
        var sy = 0f
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                sx += ((-e.values[0] / 5f).coerceIn(-1f, 1f) - sx) * 0.25f
                sy += ((e.values[2] / 5f).coerceIn(-1f, 1f) - sy) * 0.25f
                tx = sx; ty = sy
            }
            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) = Unit
        }
        if (sm != null && sensor != null) sm.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm?.unregisterListener(listener) }
    }

    fun save() {
        progress = 0f
        saved = null
        val choice = music
        val from = startSec
        scope.launch {
            val file = Exporter.newVideoFile(context)
            val w = planes.width
            val h = planes.height
            val frames = (LOOP_SECONDS * FPS).roundToInt()
            val st = style
            val k = strength
            val pcm = musicPcm(choice, from)
            val aac = pcm?.let { withContext(Dispatchers.Default) { VideoEncoder.encodeAac(it) } }
            if (choice !is MusicChoice.None && aac == null) Toast.makeText(context, "Couldn't add the music, saving without it", Toast.LENGTH_SHORT).show()
            val ok = withContext(Dispatchers.Default) {
                VideoEncoder.encode(file, w, h, FPS, frames, { i, canvas ->
                    val m = Wobble.motion(st, i / frames.toFloat())
                    Wobble.drawFrame(canvas, planes, canvas.width, canvas.height, m[0], m[1], m[2], k)
                }, { p -> progress = p }, aac)
            }
            if (ok) {
                val inGallery = withContext(Dispatchers.IO) { Exporter.saveVideoToGallery(context, file) }
                saved = file
                fb.success()
                Toast.makeText(context, if (inGallery) "Saved to Movies/Hinterlay" else "Video ready", Toast.LENGTH_SHORT).show()
            } else {
                fb.error()
                Toast.makeText(context, "Couldn't make the video on this phone", Toast.LENGTH_LONG).show()
            }
            progress = null
        }
    }

    Dialog(
        onDismissRequest = { if (progress == null) onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (progress == null) onClose() }) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                    Text("3D photo", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
                }
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    Canvas(
                        Modifier
                            .aspectRatio(planes.width.toFloat() / planes.height)
                            .clip(RoundedCornerShape(20.dp)),
                    ) {
                        val m = if (tiltMode) floatArrayOf(tx, ty * 0.7f, 0f) else Wobble.motion(style, time)
                        drawIntoCanvas { c ->
                            c.nativeCanvas.save()
                            c.nativeCanvas.clipRect(0f, 0f, size.width, size.height)
                            Wobble.drawFrame(c.nativeCanvas, planes, size.width.toInt(), size.height.toInt(), m[0], m[1], m[2], strength)
                            c.nativeCanvas.restore()
                        }
                    }
                    if (!planes.hasDepth) {
                        Surface(
                            shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                        ) {
                            Text(
                                "No subject was found, so the whole photo moves as one. Photos with a person, pet or object look best.",
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp),
                            )
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Wobble.Style.entries.forEach { s ->
                            Chip(selected = style == s && !tiltMode, onClick = { style = s; tiltMode = false }, label = { Text(s.label) })
                        }
                        Chip(selected = tiltMode, onClick = { tiltMode = !tiltMode }, label = { Text("Tilt") })
                    }
                    LabeledSlider("Depth", strength, 0.1f..1f, "${(strength * 100).roundToInt()}") { strength = it }

                    Text("Music", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(selected = music is MusicChoice.None, onClick = { saved = null; music = MusicChoice.None }, label = { Text("None") })
                        Music.Track.entries.forEach { t ->
                            Chip(selected = (music as? MusicChoice.Built)?.track == t, onClick = { saved = null; music = MusicChoice.Built(t) }, label = { Text(t.label) })
                        }
                        Chip(
                            selected = music is MusicChoice.Mine, onClick = { pickSong.launch(arrayOf("audio/*")) },
                            label = { Text((music as? MusicChoice.Mine)?.name?.take(18) ?: "My song…") },
                        )
                    }
                    (music as? MusicChoice.Mine)?.let { mine ->
                        val maxStart = ((mine.lengthMs - (LOOP_SECONDS * 1000).toLong()) / 1000).coerceAtLeast(0)
                        if (maxStart > 0) {
                            LabeledSlider("Start at", startSec.toFloat(), 0f..maxStart.toFloat(), "${startSec}s") { startSec = it.toLong() }
                        }
                        Text(
                            "Use music you have the right to share. Songs from streaming apps are usually not free to use in posts.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    val p = progress
                    val file = saved
                    when {
                        p != null -> {
                            com.hinterlay.app.ui.components.HairlineProgress(progress = p, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                            Text("Making your video… ${(p * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
                        }
                        file != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                PrimaryButton("Instagram story", onClick = { context.startActivityOrToast(Exporter.storyIntent(context, file, video = true)) }, modifier = Modifier.weight(1f))
                                TonalButton("WhatsApp status", onClick = {
                                    val send = Exporter.whatsappIntent(context, file, video = true)
                                    if (send != null) {
                                        Toast.makeText(context, "Choose My status at the top", Toast.LENGTH_SHORT).show()
                                        context.startActivityOrToast(send)
                                    } else Toast.makeText(context, "WhatsApp isn't installed", Toast.LENGTH_SHORT).show()
                                }, modifier = Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                TonalButton("Share", onClick = { context.startActivityOrToast(Exporter.shareVideoIntent(context, file)) }, modifier = Modifier.weight(1f))
                                TonalButton("Make another", onClick = { saved = null }, modifier = Modifier.weight(1f))
                            }
                        }
                        else -> PrimaryButton("Save as video", onClick = { save() }, modifier = Modifier.fillMaxWidth().height(52.dp))
                    }
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}

private fun android.content.Context.startActivityOrToast(intent: android.content.Intent) {
    try { startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (e: Exception) { Toast.makeText(this, "No app can open that", Toast.LENGTH_SHORT).show() }
}
