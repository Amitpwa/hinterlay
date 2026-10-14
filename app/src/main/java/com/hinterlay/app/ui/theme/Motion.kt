package com.hinterlay.app.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * One motion language for the whole app: things respond instantly to a touch (a small, springy press), settle with a
 * soft spring, and anything that appears or leaves takes about 200 ms with an ease-out. Nothing bounces for show.
 */
object Motion {
    /** Press feedback: quick and slightly under-damped, so it feels physical without wobbling. */
    fun <T> press() = spring<T>(dampingRatio = 0.62f, stiffness = Spring.StiffnessMedium)

    /** Layout and position changes: smooth, no overshoot. */
    fun <T> settle() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

    /** Appearing and leaving. */
    fun <T> enter() = tween<T>(durationMillis = 220, easing = androidx.compose.animation.core.FastOutSlowInEasing)
    fun <T> exit() = tween<T>(durationMillis = 140, easing = androidx.compose.animation.core.FastOutLinearInEasing)
}

/** The element shrinks a little while pressed and springs back, driven by [source] (pass the button's own interaction source). */
@Composable
fun Modifier.pressScale(source: InteractionSource, pressed: Float = 0.96f): Modifier {
    val down by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (down) pressed else 1f, Motion.press(), label = "press")
    return this.graphicsLayer { scaleX = s; scaleY = s }
}

/**
 * A panel of frosted dark or light glass: translucent fill and a hairline edge that is brighter along the top, like
 * light catching the rim. No shadow.
 */
@Composable
fun Modifier.glass(shape: Shape, fill: Float = 0.86f): Modifier {
    val surface = MaterialTheme.colorScheme.surface
    val ink = MaterialTheme.colorScheme.onSurface
    val rim = Brush.verticalGradient(listOf(ink.copy(alpha = 0.30f), ink.copy(alpha = 0.08f)))
    return this
        .clip(shape)
        .background(surface.copy(alpha = fill))
        .border(BorderStroke(1.dp, rim), shape)
}

/** A frosted-glass sheet for the editor's bottom controls: translucent tint, a faint light rim on top, rounded top corners. */
@Composable
fun Modifier.frostedPanel(): Modifier {
    val surface = MaterialTheme.colorScheme.surface
    val ink = MaterialTheme.colorScheme.onSurface
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    return this
        .clip(shape)
        .background(Brush.verticalGradient(listOf(surface.copy(alpha = 0.74f), surface.copy(alpha = 0.88f))))
        .border(BorderStroke(1.dp, Brush.verticalGradient(listOf(ink.copy(alpha = 0.28f), ink.copy(alpha = 0f)))), shape)
}
