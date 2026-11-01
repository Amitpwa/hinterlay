package com.hinterlay.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hinterlay.app.fx.Fx

/** One spoke of the radial Create menu. [enabled] false renders it dimmed and unclickable ("soon"). */
class RadialAction(
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * A pie / radial menu that fans up from the centre Create button. Tapping the button opens it; the
 * spokes animate out on an arc, each a paper disc with a hairline border and an ink icon (black and
 * white, no hard shadows). Tapping a spoke runs it and closes; tapping the scrim or the centre X
 * closes. Positions come from [arcOffset].
 */
@Composable
fun RadialCreateMenu(actions: List<RadialAction>, onDismiss: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true; Fx.play(Fx.Sound.Tap) }
    val p by animateFloatAsState(
        if (shown) 1f else 0f,
        spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMediumLow),
        label = "radial",
    )

    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.surface
    val radius = 132.dp

    Box(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f * p))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
    ) {
        val baseUp = 74.dp // raise the arc centre up to where the camera disc sits
        actions.forEachIndexed { i, a ->
            val (dx, dy) = arcOffset(i, actions.size, radius)
            Column(
                Modifier.align(Alignment.BottomCenter)
                    .offset(x = dx * p, y = -baseUp + dy * p)
                    .alpha((if (a.enabled) 1f else 0.4f) * p)
                    .scale(p),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(58.dp).clip(CircleShape).background(paper)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        .clickable(
                            enabled = a.enabled,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { Fx.play(Fx.Sound.Tap); onDismiss(); a.onClick() },
                    contentAlignment = Alignment.Center,
                ) { Icon(a.icon, a.label, tint = ink, modifier = Modifier.size(24.dp)) }
                Text(
                    a.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = ink,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(76.dp).padding(top = 4.dp),
                )
            }
        }
        // Centre close button, where the camera disc normally sits.
        Box(
            Modifier.align(Alignment.BottomCenter)
                .offset(y = -baseUp)
                .scale(p)
                .size(64.dp).clip(CircleShape).background(ink)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Close, "Close", tint = paper, modifier = Modifier.size(26.dp)) }
    }
}

/**
 * Where spoke [index] of [count] sits relative to the centre, [radius] away, fanned across an
 * upward arc. Returns (x, y) in dp; y is negative to rise above the button.
 */
private fun arcOffset(index: Int, count: Int, radius: Dp): Pair<Dp, Dp> {
    if (count <= 1) return 0.dp to -radius
    val spanDeg = 150.0                 // fan width, centred on straight up (90°)
    val startDeg = 90.0 + spanDeg / 2.0 // first spoke on the upper-left
    val deg = startDeg - index * (spanDeg / (count - 1))
    val rad = Math.toRadians(deg)
    // 0° = right, 90° = up; negate sin so the arc rises above the button (screen y grows downward).
    return radius * kotlin.math.cos(rad).toFloat() to radius * (-kotlin.math.sin(rad)).toFloat()
}
