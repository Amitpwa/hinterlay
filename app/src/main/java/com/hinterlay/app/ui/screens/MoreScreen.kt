package com.hinterlay.app.ui.screens

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hinterlay.app.BuildConfig
import com.hinterlay.app.ads.ConsentManager
import com.hinterlay.app.cloud.Cloud
import com.hinterlay.app.cloud.Notifier
import com.hinterlay.app.cloud.Push
import com.hinterlay.app.ui.findActivity

/** "More" tab: everything that is not a main task, in one predictable place. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    consent: ConsentManager,
    onStickers: () -> Unit,
    onSupport: () -> Unit,
    onHowItWorks: () -> Unit,
    onPrivacy: () -> Unit,
) {
    val context = LocalContext.current
    var push by remember { mutableStateOf(Push.enabled && Notifier.canPost(context)) }
    var analytics by remember { mutableStateOf(Cloud.analyticsEnabled) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Push.enabled = granted
        push = granted
        if (granted) com.hinterlay.app.cloud.Track.event("push_opt_in")
        else Toast.makeText(context, "Notifications are off. You can enable them in system settings.", Toast.LENGTH_LONG).show()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("More") }) },
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Row("WhatsApp stickers", "Turn creations into a sticker pack", Icons.Outlined.EmojiEmotions, onStickers)
            Row("Support Hinterlay", "Tip the developer or go ad-free for life", Icons.Outlined.Favorite, onSupport)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            ListItem(
                headlineContent = { Text("Tips and updates") },
                supportingContent = { Text("Occasional notifications about new features and ideas") },
                leadingContent = { Icon(Icons.Outlined.Notifications, null) },
                trailingContent = {
                    com.hinterlay.app.ui.components.ThemedSwitch(checked = push, onCheckedChange = { on ->
                        if (!on) { Push.enabled = false; push = false }
                        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Notifier.canPost(context)) {
                            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else { Push.enabled = true; push = true }
                    })
                },
            )
            var sound by remember { mutableStateOf(com.hinterlay.app.fx.Fx.enabled) }
            ListItem(
                headlineContent = { Text("Sound effects") },
                supportingContent = { Text("Subtle sounds and haptics for taps and results") },
                leadingContent = { Icon(Icons.Outlined.GraphicEq, null) },
                trailingContent = { com.hinterlay.app.ui.components.ThemedSwitch(checked = sound, onCheckedChange = { sound = it; com.hinterlay.app.fx.Fx.enabled = it; if (it) com.hinterlay.app.fx.Fx.play(com.hinterlay.app.fx.Fx.Sound.Tap) }) },
            )
            ListItem(
                headlineContent = { Text("Share anonymous usage data") },
                supportingContent = { Text("Helps improve Hinterlay. Never includes your photos.") },
                leadingContent = { Icon(Icons.Outlined.Insights, null) },
                trailingContent = { com.hinterlay.app.ui.components.ThemedSwitch(checked = analytics, onCheckedChange = { analytics = it; Cloud.analyticsEnabled = it; com.hinterlay.app.cloud.Insights.setEnabled(context, it) }) },
            )
            if (consent.isPrivacyOptionsRequired) {
                Row("Ad choices", "Review your advertising consent", Icons.Outlined.Tune) {
                    context.findActivity()?.let { consent.showPrivacyOptions(it) }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row("How it works", "Replay the quick tour", Icons.Outlined.PlayCircleOutline, onHowItWorks)
            Row("Privacy policy", "How your data is handled", Icons.Outlined.PrivacyTip, onPrivacy)
            Text(
                "Hinterlay ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun Row(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
