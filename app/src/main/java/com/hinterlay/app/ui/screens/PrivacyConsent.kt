package com.hinterlay.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hinterlay.app.ui.components.PrimaryButton
import com.hinterlay.app.ui.components.SubtleButton
import com.hinterlay.app.ui.components.TonalButton

/** Raise this when the policy changes in a way users should re-accept. Also update the date in both policy files. */
const val PRIVACY_POLICY_VERSION = 1

/**
 * Shown once (and again after a version bump) before anything is collected. Accepting is required to use the app;
 * the analytics switch is a separate, optional choice.
 */
@Composable
fun PrivacyConsentScreen(onAccept: (shareUsage: Boolean) -> Unit, onDecline: () -> Unit) {
    var reading by rememberSaveable { mutableStateOf(false) }
    var shareUsage by rememberSaveable { mutableStateOf(true) }

    if (reading) {
        BackHandler { reading = false }
        PrivacyScreen(onBack = { reading = false })
        return
    }

    // Surface (not just a background) so text defaults to onSurface and stays readable in dark mode.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Spacer(Modifier.height(32.dp))
            Text("Your privacy", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Before you start, here is what matters. The full policy is one tap away.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Point("Your photos stay on your phone", "They are processed on the device and never uploaded.")
            Point("No account", "You do not sign up or log in.")
            Point("Ads", "Shown by Google AdMob. In regions that require it you will choose your ad consent next.")
            Point("Camera and location are optional", "Asked only when you use them.")
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Share anonymous usage data", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Counts of actions such as exports. Never photos or text. Off on the editor and camera. Optional, change any time in More.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    com.hinterlay.app.ui.components.ThemedSwitch(checked = shareUsage, onCheckedChange = { shareUsage = it }, modifier = Modifier.padding(start = 12.dp))
                }
            }
            SubtleButton("Read the full privacy policy", onClick = { reading = true })
        }
        Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "By tapping Accept you agree to the privacy policy.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            PrimaryButton("Accept and continue", onClick = { onAccept(shareUsage) }, modifier = Modifier.fillMaxWidth().height(52.dp))
            TonalButton("Decline and close the app", onClick = onDecline, modifier = Modifier.fillMaxWidth().height(48.dp))
        }
    }
    }
}

@Composable
private fun Point(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
