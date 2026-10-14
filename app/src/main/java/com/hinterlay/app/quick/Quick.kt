package com.hinterlay.app.quick

import android.app.KeyguardManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hinterlay.app.MainActivity
import com.hinterlay.app.R
import com.hinterlay.app.camera.CameraScreen
import com.hinterlay.app.cloud.Notifier
import com.hinterlay.app.data.HistoryStore
import com.hinterlay.app.fx.Fx
import com.hinterlay.app.ui.theme.HinterlayTheme

/** Deep links used by widgets, tiles and shortcuts. They map to actions routed in MainActivity. */
object QuickIntents {
    fun open(context: Context, action: String, requestCode: Int): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            this.action = Intent.ACTION_MAIN
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(Notifier.EXTRA_ACTION, action)
        }
        return PendingIntent.getActivity(context, requestCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Opens the camera screen that can show over the lock screen. */
    fun lockscreenCamera(context: Context): PendingIntent {
        val i = Intent(context, QuickCameraActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        return PendingIntent.getActivity(context, 77, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

/** Four big buttons: photo, camera, library, stickers. Works on the home screen and, where the phone allows it, the lock screen. */
class QuickActionsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            val v = RemoteViews(context.packageName, R.layout.widget_quick)
            v.setOnClickPendingIntent(R.id.w_photo, QuickIntents.open(context, "pick", 1))
            v.setOnClickPendingIntent(R.id.w_camera, QuickIntents.lockscreenCamera(context))
            v.setOnClickPendingIntent(R.id.w_library, QuickIntents.open(context, "wallpaper", 3))
            v.setOnClickPendingIntent(R.id.w_sticker, QuickIntents.open(context, "stickers", 4))
            manager.updateAppWidget(id, v)
        }
    }
}

/** Shows the latest creation; tap to open the library. Refreshed whenever a new creation is saved. */
class RecentWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, build(context)) }
    }

    companion object {
        fun build(context: Context): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_recent)
            val latest = runCatching { HistoryStore.list(context).firstOrNull() }.getOrNull()
            val bmp = latest?.thumb?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
            if (bmp != null) {
                v.setImageViewBitmap(R.id.w_image, bmp)
                v.setViewVisibility(R.id.w_image, android.view.View.VISIBLE)
                v.setViewVisibility(R.id.w_empty, android.view.View.GONE)
                v.setOnClickPendingIntent(R.id.w_root, QuickIntents.open(context, "wallpaper", 5))
            } else {
                v.setViewVisibility(R.id.w_image, android.view.View.GONE)
                v.setViewVisibility(R.id.w_empty, android.view.View.VISIBLE)
                v.setOnClickPendingIntent(R.id.w_root, QuickIntents.lockscreenCamera(context))
            }
            return v
        }

        /** Call after a creation is saved so the widget shows it straight away. */
        fun refresh(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, RecentWidget::class.java))
            if (ids.isNotEmpty()) ids.forEach { mgr.updateAppWidget(it, build(context)) }
        }
    }
}

/** Quick Settings tile. Available on the lock screen's quick panel as well; tapping opens the camera. */
class QuickCameraTile : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_INACTIVE; label = getString(R.string.tile_label); updateTile() }
    }

    override fun onClick() {
        val intent = Intent(this, QuickCameraActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}

/**
 * The camera on its own, allowed to appear over the lock screen so it opens in a second. It only holds
 * the camera: nothing else from the app is shown while the phone is locked. When the user taps a photo
 * to edit, the phone asks for the usual unlock first.
 */
class QuickCameraActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        Fx.init(applicationContext)
        com.hinterlay.app.cloud.Insights.screen("quick_camera")
        setContent {
            HinterlayTheme {
                CameraScreen(onClose = { finish() }, onPhoto = { uri -> openEditor(uri) })
            }
        }
    }

    private fun openEditor(uri: Uri) {
        val open = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { startActivity(open); finish() }
                override fun onDismissCancelled() = Unit
            })
        } else {
            startActivity(open)
            finish()
        }
    }
}
