package com.hinterlay.app.cloud

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.hinterlay.app.MainActivity
import com.hinterlay.app.R

object Notifier {
    const val CHANNEL_ID = "updates"
    const val EXTRA_ACTION = "hinterlay_action"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Tips and updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "New features, inspiration and tips from Hinterlay"
                }
            )
        }
    }

    fun canPost(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun show(context: Context, title: String, body: String, action: String?) {
        if (!canPost(context)) return
        ensureChannel(context)
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_ACTION, action)
        }
        val pi = PendingIntent.getActivity(
            context, (System.currentTimeMillis() % Int.MAX_VALUE).toInt(), open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(System.currentTimeMillis().toInt(), n)
    }
}

/** Receives data pushes sent to topic "all" (from the admin panel or Firebase Console). */
class HinterlayMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val title = data["title"] ?: message.notification?.title ?: return
        val body = data["body"] ?: message.notification?.body ?: ""
        Notifier.show(this, title, body, data["action"])
    }

    override fun onNewToken(token: String) = Unit // topic subscription survives token refresh
}
