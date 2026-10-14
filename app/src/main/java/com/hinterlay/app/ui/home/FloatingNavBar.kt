package com.hinterlay.app.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.hinterlay.app.ui.components.halftone

class NavItem(val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

/**
 * A floating, rounded black-and-white bar with a raised camera button in the middle. No shadows:
 * the bar is defined by a hairline border and a faint halftone dot screen. A single pill slides
 * between the items. [items] are split around the centre button.
 */
@Composable
fun FloatingNavBar(
    items: List<NavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onCamera: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val half = items.size / 2
    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.surface
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Surface(
            shape = RoundedCornerShape(30.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().height(68.dp),
        ) {
            BoxWithConstraints(Modifier.halftone(ink.copy(alpha = 0.09f), spacing = 9.dp, maxRadius = 1.4.dp, fromTop = false)) {
                val slot = maxWidth / (items.size + 1)
                val slotIndex = if (selected < half) selected else selected + 1
                // The pill glides to the selected slot.
                val pillX by animateDpAsState(slot * slotIndex + (slot - PILL_W) / 2, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow), label = "pill")
                Box(
                    Modifier.offset(x = pillX, y = 10.dp).size(PILL_W, 30.dp)
                        .clip(CircleShape).background(ink),
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    items.forEachIndexed { i, item ->
                        if (i == half) Box(Modifier.weight(1f))
                        NavSlot(item, selected == i, Modifier.weight(1f)) { onSelect(i) }
                    }
                }
            }
        }

        // Camera button: solid ink disc on a paper ring, with a slow, quiet pulse. No shadow.
        val pulse = rememberInfiniteTransition(label = "cam")
        val ring by pulse.animateFloat(1f, 1.28f, infiniteRepeatable(tween(2200), RepeatMode.Restart), label = "ring")
        val ringAlpha by pulse.animateFloat(0.35f, 0f, infiniteRepeatable(tween(2200), RepeatMode.Restart), label = "ringA")
        Box(Modifier.align(Alignment.TopCenter).offset(y = (-24).dp).size(70.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(70.dp).scale(ring).alpha(ringAlpha).border(1.5.dp, ink, CircleShape))
            Box(
                Modifier.size(70.dp).clip(CircleShape).background(paper)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .padding(6.dp).clip(CircleShape).background(ink)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCamera),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.AddAPhoto, contentDescription = "Open camera", tint = paper, modifier = Modifier.size(27.dp))
            }
        }
    }
}

private val PILL_W = 54.dp

@Composable
private fun NavSlot(item: NavItem, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val iconColor by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant, tween(220), label = "navIcon",
    )
    val labelColor by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, tween(220), label = "navLabel",
    )
    Column(
        modifier.height(68.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.padding(top = 10.dp).width(PILL_W).height(30.dp), contentAlignment = Alignment.Center) {
            Icon(if (selected) item.selectedIcon else item.icon, null, tint = iconColor, modifier = Modifier.size(21.dp))
        }
        Text(
            item.label, style = MaterialTheme.typography.labelSmall, color = labelColor,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}
