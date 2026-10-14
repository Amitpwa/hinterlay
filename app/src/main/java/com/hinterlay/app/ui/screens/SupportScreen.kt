package com.hinterlay.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hinterlay.app.billing.BillingManager
import com.hinterlay.app.data.AdFree
import com.hinterlay.app.ui.components.PrimaryButton
import com.hinterlay.app.ui.components.TonalButton
import com.hinterlay.app.ui.findActivity
import com.hinterlay.app.ui.safeStart

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val products by BillingManager.products.collectAsState()
    val message by BillingManager.message.collectAsState()
    val lifetime by AdFree.lifetime.collectAsState()

    LaunchedEffect(message) {
        message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); BillingManager.messageShown() }
    }
    fun price(id: String, fallback: String) =
        products[id]?.oneTimePurchaseOfferDetails?.formattedPrice ?: fallback
    fun buy(id: String) { context.findActivity()?.let { BillingManager.buy(it, id) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Support Hinterlay") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Hinterlay is free and made by one person. If it's useful to you, a small tip keeps it going. Tips are optional and don't unlock anything.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card(border = com.hinterlay.app.ui.components.hairline(), 
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        if (lifetime) Icons.Filled.CheckCircle else Icons.Filled.WorkspacePremium, null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp),
                    )
                    Text(
                        if (lifetime) "You're a lifetime supporter" else "Lifetime supporter",
                        style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        if (lifetime) "No ads, ever, on every device signed in to your Google account. Thank you!"
                        else "One payment of ${price(BillingManager.LIFETIME, "$100")}: Hinterlay is ad-free forever.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    if (!lifetime) {
                        Spacer(Modifier.height(4.dp))
                        PrimaryButton(
                            "Go ad-free for life - ${price(BillingManager.LIFETIME, "$100")}",
                            onClick = { buy(BillingManager.LIFETIME) },
                            modifier = Modifier.fillMaxWidth(),
                            icon = Icons.Filled.WorkspacePremium,
                        )
                    }
                }
            }

            Text("Or leave a tip", style = MaterialTheme.typography.titleMedium)
            if (com.hinterlay.app.BuildConfig.PAYPAL_DONATE_URL.isNotBlank()) {
                Card(border = com.hinterlay.app.ui.components.hairline(), 
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Donate with PayPal", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "A donation is a gift to support the developer. It does not unlock anything: the ad-free options above are only sold through Google Play.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TonalButton(
                            "Donate via PayPal",
                            onClick = { com.hinterlay.app.cloud.Track.event("paypal_donate_tapped"); context.safeStart(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(com.hinterlay.app.BuildConfig.PAYPAL_DONATE_URL))) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            listOf(
                Triple(BillingManager.TIP_SMALL, "Coffee", "$1"),
                Triple(BillingManager.TIP_MEDIUM, "Lunch", "$5"),
                Triple(BillingManager.TIP_LARGE, "Big thank you", "$20"),
            ).forEach { (id, label, fallback) ->
                Card(border = com.hinterlay.app.ui.components.hairline(), 
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Favorite, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TonalButton(price(id, fallback), onClick = { buy(id) })
                    }
                }
            }
            Text(
                "Payments are handled securely by Google Play.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
