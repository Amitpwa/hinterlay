package com.hinterlay.app.ui.ar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import com.hinterlay.app.export.Exporter
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * Records the real-world AR scene to an MP4 by screen capture (MediaProjection → MediaRecorder on a
 * mirrored VirtualDisplay). Screen capture is used deliberately: it grabs the composited output —
 * the camera feed plus the Filament-rendered CGI — exactly as shown. The AR screen hides its own
 * controls while recording so only the scene is captured.
 *
 * MediaProjection on modern Android requires a foreground service typed `mediaProjection` started
 * before the projection is obtained, which is why this runs as a service.
 */
class ScreenRecordService : Service() {

    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var outFile: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_STOP -> stopRecording()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(intent: Intent) {
        startForeground(NOTIF_ID, buildNotification())

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) { fail(); return }

        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mpm.getMediaProjection(resultCode, data) ?: run { fail(); return }
            projection = proj
            // A projection stopped from the system UI must tear everything down cleanly.
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopRecording() }
            }, null)

            val (w, h, dpi) = screenMetrics()
            val file = Exporter.newVideoFile(this)
            outFile = file

            val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setVideoEncodingBitRate(12_000_000)
            rec.setVideoFrameRate(30)
            rec.setVideoSize(w, h)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            recorder = rec

            virtualDisplay = proj.createVirtualDisplay(
                "hinterlay-ar",
                w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                rec.surface, null, null,
            )
            rec.start()
            recording.value = true
        } catch (e: Exception) {
            fail()
        }
    }

    private fun stopRecording() {
        if (!recording.value && recorder == null) { stopSelf(); return }
        val file = outFile
        runCatching { recorder?.stop() }
        runCatching { recorder?.reset(); recorder?.release() }
        recorder = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { projection?.stop() }
        projection = null
        recording.value = false

        // Move the finished clip into Movies/Hinterlay and hand the UI the result.
        if (file != null && file.exists() && file.length() > 0) {
            val ok = runCatching { Exporter.saveVideoToGallery(this, file) }.getOrDefault(false)
            lastResult.value = RecResult(file, savedToGallery = ok)
        } else {
            lastResult.value = RecResult(null, savedToGallery = false)
        }
        stopForegroundCompat()
        stopSelf()
    }

    private fun fail() {
        recording.value = false
        lastResult.value = RecResult(null, savedToGallery = false)
        stopForegroundCompat()
        stopSelf()
    }

    private fun screenMetrics(): Triple<Int, Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
        // H.264 encoders want even dimensions.
        val w = metrics.widthPixels and 1.inv()
        val h = metrics.heightPixels and 1.inv()
        return Triple(w, h, metrics.densityDpi)
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Screen recording", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setContentTitle("Recording AR video")
            .setContentText("Hinterlay is recording the scene")
            .setSmallIcon(com.hinterlay.app.R.drawable.ic_launcher_monochrome)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        if (recording.value) stopRecording()
        super.onDestroy()
    }

    data class RecResult(val file: File?, val savedToGallery: Boolean)

    companion object {
        const val ACTION_START = "com.hinterlay.app.ar.RECORD_START"
        const val ACTION_STOP = "com.hinterlay.app.ar.RECORD_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "screen_record"
        private const val NOTIF_ID = 7711

        /** True while a recording is in progress. */
        val recording = MutableStateFlow(false)

        /** The most recent finished recording (file + whether it reached the gallery), or null. */
        val lastResult = MutableStateFlow<RecResult?>(null)
    }
}
