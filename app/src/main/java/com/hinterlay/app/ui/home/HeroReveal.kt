package com.hinterlay.app.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.hinterlay.app.R

private fun smooth(x: Float) = (x * x * (3f - 2f * x)).coerceIn(0f, 1f)

/**
 * Real before/after demo: a plain photo, a scan beam sweeps down it, and the same photo appears with
 * text sitting behind the person. Loops quietly with a slow push-in.
 */
@Composable
fun HeroReveal(modifier: Modifier = Modifier) {
    // The demo plays your own photo first, then a few drawn scenes with different text styles (3D gold, chrome, ice...).
    val ctx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val demos by androidx.compose.runtime.produceState<List<Triple<android.graphics.Bitmap, android.graphics.Bitmap, String>>>(emptyList()) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val all = com.hinterlay.app.cloud.ContentRepo.inspirations.value
            listOf("p7", "p11", "p1", "p3", "p13", "p5", "p9", "p8").mapNotNull { id -> all.firstOrNull { it.id == id } }.map { item ->
                Triple(
                    com.hinterlay.app.ui.screens.TemplateArt.before(ctx, item.preset, 540, 660),
                    com.hinterlay.app.ui.screens.TemplateArt.preview(ctx, item.preset, 540, 660),
                    item.title,
                )
            }
        }
    }
    var scene by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var t by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            androidx.compose.animation.core.animate(0f, 1f, animationSpec = tween(9000, easing = LinearEasing)) { v, _ -> t = v }
            scene++
        }
    }
    val slots = 1 + demos.size
    val current = scene % slots

    // Timeline: hold the photo, sweep, hold the result, fade back.
    val sweep = smooth(((t - 0.18f) / 0.30f).coerceIn(0f, 1f))   // 0.18 .. 0.48
    val fadeBack = smooth(((t - 0.90f) / 0.10f).coerceIn(0f, 1f)) // 0.90 .. 1.0
    val revealed = sweep * (1f - fadeBack)
    val scanning = t in 0.18f..0.5f
    val zoom = 1f + 0.06f * t

    Box(modifier.clip(MaterialTheme.shapes.extraLarge).background(Color.Black)) {
        val align = BiasAlignment(0f, -0.15f)
        val demo = if (current == 0) null else demos.getOrNull(current - 1)
        if (demo == null) {
            Image(
                painterResource(R.drawable.hero_before), null, contentScale = ContentScale.Crop, alignment = align,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom },
            )
        } else {
            Image(
                demo.first.asImageBitmap(), null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom },
            )
        }
        // The result, revealed from the top down by the beam.
        val revealMod = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom }
            .drawWithContent {
                val h = size.height * revealed
                clipRect(0f, 0f, size.width, h) { this@drawWithContent.drawContent() }
            }
        if (demo == null) {
            Image(painterResource(R.drawable.hero_after), null, contentScale = ContentScale.Crop, alignment = align, modifier = revealMod)
        } else {
            Image(demo.second.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = revealMod)
        }
        // Scan beam and corner marks while it works.
        Canvas(Modifier.fillMaxSize().alpha(if (scanning) 1f else 0f)) {
            val y = size.height * sweep
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.35f), Color.Transparent), startY = y - 80f, endY = y + 80f),
                topLeft = Offset(0f, y - 80f), size = androidx.compose.ui.geometry.Size(size.width, 160f),
            )
            drawLine(Color.White, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.5f)
        }
        // Caption chip that swaps with the state.
        val after = revealed > 0.6f
        Text(
            if (after) (demo?.third ?: "Text behind you") else if (scanning) "Finding the subject" else "Your photo",
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)
                .clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}
