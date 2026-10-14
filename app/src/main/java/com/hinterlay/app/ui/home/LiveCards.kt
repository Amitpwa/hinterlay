package com.hinterlay.app.ui.home

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.hinterlay.app.cloud.LiveCard
import com.hinterlay.app.cloud.Track
import androidx.compose.foundation.background

private const val PREFS = "hinterlay_ui"

/** Server-driven cards from the owner. Dismissed cards stay dismissed (stored on device). */
@Composable
fun LiveCards(cards: List<LiveCard>, onAction: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var dismissed by remember { mutableStateOf(prefs.getStringSet("dismissed_cards", emptySet()).orEmpty()) }
    val visible = cards.filter { it.id !in dismissed }
    if (visible.isEmpty()) return

    val pager = rememberPagerState(pageCount = { visible.size })
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalPager(pager, pageSpacing = 12.dp) { page ->
            val card = visible[page]
            Card(border = com.hinterlay.app.ui.components.hairline(), 
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    card.imageUrl?.let {
                        AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(140.dp))
                    }
                    Row(Modifier.padding(start = 20.dp, top = 16.dp, end = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(card.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            Text(card.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        IconButton(onClick = {
                            val next = dismissed + card.id
                            dismissed = next
                            prefs.edit().putStringSet("dismissed_cards", next).apply()
                        }) { Icon(Icons.Filled.Close, "Dismiss", tint = MaterialTheme.colorScheme.onSecondaryContainer) }
                    }
                    if (!card.ctaLabel.isNullOrBlank() && !card.ctaAction.isNullOrBlank()) {
                        TextButton(
                            onClick = { Track.event("card_clicked", "card_id" to card.id); onAction(card.ctaAction) },
                            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                        ) { Text(card.ctaLabel) }
                    }
                }
            }
        }
        if (visible.size > 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                repeat(visible.size) { i ->
                    Box(
                        Modifier.padding(3.dp).size(if (i == pager.currentPage) 16.dp else 6.dp, 6.dp).clip(CircleShape).background(
                            if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                        )
                    )
                }
            }
        }
    }
}
