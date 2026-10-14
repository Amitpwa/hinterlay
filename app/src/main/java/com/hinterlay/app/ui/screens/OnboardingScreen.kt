package com.hinterlay.app.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hinterlay.app.editor.FontChoice
import com.hinterlay.app.fx.Fx
import com.hinterlay.app.fx.rememberFeedback
import com.hinterlay.app.ui.components.PrimaryButton
import com.hinterlay.app.ui.components.SubtleButton
import kotlinx.coroutines.launch

private class Page(val title: String, val body: String)

private val Pages = listOf(
    Page("Pick any photo", "Hinterlay finds the subject right on your phone. Your photo never leaves the device."),
    Page("Put text behind it", "Words slide behind people, pets and objects for that magazine-cover depth."),
    Page("Make it yours", "Premium fonts, glossy bubble type, shadows and perspective that match your scene."),
    Page("Bring it to life", "Turn any creation into a 3D wallpaper that moves with your phone."),
)

private fun sceneFor(page: Int, t: Float): SceneState = when (page) {
    0 -> SceneState(beam = (t * 1.25f).coerceAtMost(1.05f).let { if (it > 1f) -1f else it }, glowSubject = if (t > 0.8f) 1f else 0f, zoom = 1f + 0.05f * t)
    1 -> {
        val k = (if (t < 0.55f) t / 0.55f else 1f).let { 1f - (1f - it) * (1f - it) * (1f - it) }
        SceneState(text = "PEAK", textAlpha = k, textX = 0.15f + 0.35f * k, zoom = 1f + 0.04f * t)
    }
    2 -> {
        val phase = (t * 3f).toInt().coerceIn(0, 2)
        val fonts = listOf(FontChoice.BANGERS, FontChoice.LILITA, FontChoice.PACIFICO)
        val words = listOf("Bold", "Pop", "Wild")
        SceneState(text = words[phase], font = fonts[phase], bubble = phase == 1, textAlpha = 1f, zoom = 1.02f + 0.03f * t)
    }
    else -> SceneState(text = "PEAK", font = FontChoice.ANTON, textAlpha = 1f, parallax = wave(t), zoom = 1.06f)
}

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val pager = rememberPagerState(pageCount = { Pages.size })
    val scope = rememberCoroutineScope()
    val fb = rememberFeedback()
    val last = pager.currentPage == Pages.lastIndex
    val loop = rememberInfiniteTransition(label = "onboarding")
    val t by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(4600, easing = LinearEasing), RepeatMode.Restart), label = "t")
    val rig = remember { SceneRig() }

    // A short sound when the page changes, and the scan sound on the first page.
    LaunchedEffect(Unit) {
        Fx.play(Fx.Sound.Scan, 0.5f)
        var first = true
        snapshotFlow { pager.currentPage }.collect { if (first) first = false else fb.whoosh() }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            SubtleButton(if (last) "" else "Skip", onClick = onDone)
        }
        HorizontalPager(pager, Modifier.weight(1f)) { index ->
            val page = Pages[index]
            val context = LocalContext.current
            // Drop real photos named onboard_1 ... onboard_4 into res/drawable-nodpi to replace the rendered scenes.
            val photo = remember(index) { context.resources.getIdentifier("onboard_${index + 1}", "drawable", context.packageName) }
            Column(
                Modifier.fillMaxSize().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(Modifier.fillMaxWidth().aspectRatio(0.92f).clip(MaterialTheme.shapes.extraLarge)) {
                    if (photo != 0) {
                        Image(
                            painterResource(photo), null, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().graphicsLayer { val z = 1f + 0.08f * t; scaleX = z; scaleY = z },
                        )
                    } else {
                        Canvas(Modifier.fillMaxSize()) { cinematic(rig, sceneFor(index, t)) }
                    }
                }
                Text(page.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 28.dp))
                Text(
                    page.body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(Pages.size) { i ->
                    Box(
                        Modifier.size(if (i == pager.currentPage) 24.dp else 8.dp, 8.dp).clip(CircleShape).background(
                            if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                        )
                    )
                }
            }
            PrimaryButton(
                if (last) "Get started" else "Next",
                onClick = {
                    fb.select()
                    if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                },
                modifier = Modifier.height(48.dp),
            )
        }
    }
}
