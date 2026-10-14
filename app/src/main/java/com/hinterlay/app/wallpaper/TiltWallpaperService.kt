package com.hinterlay.app.wallpaper

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import com.hinterlay.app.data.HistoryStore
import com.hinterlay.app.data.HistoryItem
import com.hinterlay.app.editor.TextLayer
import com.hinterlay.app.render.Compositor
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/** Stores the two depth planes the live wallpaper reads. All local. */
object WallpaperStore {
    private fun dir(context: Context) = File(context.filesDir, "wallpaper").apply { mkdirs() }
    fun back(context: Context) = File(dir(context), "back.jpg")
    fun front(context: Context) = File(dir(context), "front.png")

    fun write(
        context: Context, source: Bitmap, foreground: Bitmap?, layers: List<TextLayer>,
        edit: Compositor.SubjectEdit? = null, plate: Bitmap? = null,
    ) {
        com.hinterlay.app.cloud.Track.event("wallpaper_set")
        val (back, front) = Compositor.renderPlanes(source, foreground, layers, edit, plate)
        FileOutputStream(back(context)).use { back.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        val f = front(context)
        if (front != null) FileOutputStream(f).use { front.compress(Bitmap.CompressFormat.PNG, 100, it) } else f.delete()
        back.recycle()
        front?.recycle()
    }

    fun writeFromHistory(context: Context, item: HistoryItem): Boolean {
        val source = BitmapFactory.decodeFile(item.source.path) ?: return false
        val fg = if (item.foreground.exists()) BitmapFactory.decodeFile(item.foreground.path) else null
        write(context, source, fg, HistoryStore.layers(item), HistoryStore.subjectEdit(item))
        return true
    }

    fun setIntent(context: Context): Intent =
        Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
            android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(context, TiltWallpaperService::class.java),
        )
}

/**
 * Parallax "3D" wallpaper: the photo and the subject cut-out sit on separate planes and shift
 * by different amounts as the device tilts, so text appears to float between them.
 */
class TiltWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = TiltEngine()

    private inner class TiltEngine : Engine(), SensorEventListener {
        private val handler = Handler(Looper.getMainLooper())
        private val sensors by lazy { getSystemService(Context.SENSOR_SERVICE) as SensorManager }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private var back: Bitmap? = null
        private var front: Bitmap? = null
        private var width = 0
        private var height = 0

        // Target tilt (-1..1) from the sensor and the smoothed value actually drawn.
        private var tx = 0f
        private var ty = 0f
        private var cx = 0f
        private var cy = 0f
        private var baseY = Float.NaN
        private var visible = false

        private val frame = object : Runnable {
            override fun run() {
                draw()
                if (visible) handler.postDelayed(this, 16)
            }
        }

        private fun loadBitmaps() {
            back = BitmapFactory.decodeFile(WallpaperStore.back(applicationContext).path)
            val f = WallpaperStore.front(applicationContext)
            front = if (f.exists()) BitmapFactory.decodeFile(f.path) else null
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            width = w; height = h
            draw()
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            handler.removeCallbacks(frame)
            sensors.unregisterListener(this)
            if (isVisible) {
                loadBitmaps()
                baseY = Float.NaN
                val sensor = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)
                    ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                if (sensor != null) sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
                handler.post(frame)
            }
        }

        override fun onDestroy() {
            visible = false
            handler.removeCallbacks(frame)
            sensors.unregisterListener(this)
        }

        override fun onSensorChanged(e: SensorEvent) {
            // x: left/right tilt, y: forward/back tilt relative to how the phone was first held.
            if (baseY.isNaN()) baseY = e.values[1]
            baseY += (e.values[1] - baseY) * 0.002f // slowly re-centres to the user's resting angle
            tx = (-e.values[0] / 4f).coerceIn(-1f, 1f)
            ty = ((e.values[1] - baseY) / 4f).coerceIn(-1f, 1f)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        private fun draw() {
            val b = back ?: return
            if (width == 0) return
            cx += (tx - cx) * 0.12f
            cy += (ty - cy) * 0.12f
            val holder = surfaceHolder
            val canvas: Canvas = runCatching { holder.lockCanvas() }.getOrNull() ?: return
            try {
                canvas.drawColor(0xFF000000.toInt())
                // Cover-fit with 14% headroom so shifting never reveals an edge.
                val scale = max(width / b.width.toFloat(), height / b.height.toFloat()) * 1.14f
                val sw = b.width * scale
                val sh = b.height * scale
                val maxX = (sw - width) / 2f
                val maxY = (sh - height) / 2f
                drawPlane(canvas, b, scale, sw, sh, cx * maxX * 0.45f, cy * maxY * 0.45f)
                front?.let { drawPlane(canvas, it, scale, sw, sh, cx * maxX, cy * maxY) }
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }

        private fun drawPlane(c: Canvas, bmp: Bitmap, scale: Float, sw: Float, sh: Float, dx: Float, dy: Float) {
            val left = (width - sw) / 2f + dx
            val top = (height - sh) / 2f + dy
            c.save()
            c.translate(left, top)
            c.scale(scale, scale)
            c.drawBitmap(bmp, 0f, 0f, paint)
            c.restore()
        }
    }
}
