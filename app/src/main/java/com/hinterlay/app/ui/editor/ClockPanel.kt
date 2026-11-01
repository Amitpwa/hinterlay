package com.hinterlay.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hinterlay.app.editor.FontChoice
import com.hinterlay.app.ui.components.Chip
import com.hinterlay.app.ui.components.LabeledSlider
import com.hinterlay.app.ui.components.SwitchRow
import com.hinterlay.app.wallpaper.ClockEffect
import com.hinterlay.app.wallpaper.DepthClockConfig

/** Colours offered for the clock (content, not UI chrome — a small tasteful palette, no purple). */
private val CLOCK_COLORS = listOf(
    0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFEDEDED.toInt(), 0xFFFFD54F.toInt(),
    0xFFEF5350.toInt(), 0xFF42A5F5.toInt(), 0xFF66BB6A.toInt(), 0xFFFF7043.toInt(),
)

/**
 * Depth-clock styling, shown in the editor's bottom panel so the clock is edited with the same
 * photo tools (crop, adjust, effects) as everything else. Drives [DepthClockConfig] in the editor
 * state; the live preview is drawn on the canvas.
 */
@Composable
fun ClockPanel(
    config: DepthClockConfig,
    onConfig: (transform: (DepthClockConfig) -> DepthClockConfig) -> Unit,
    hasSubject: Boolean,
    contentHeight: Dp,
    onClose: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Depth clock", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            com.hinterlay.app.ui.components.SubtleButton("Done", onClick = onClose)
        }
        Column(
            Modifier.fillMaxWidth().height(contentHeight).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SwitchRow("Behind the subject", config.behind, { v -> onConfig { it.copy(behind = v) } }, enabled = hasSubject)
            SwitchRow("24-hour time", config.twentyFour, { v -> onConfig { it.copy(twentyFour = v) } })
            SwitchRow("Stack hour over minute", config.vertical, { v -> onConfig { it.copy(vertical = v) } })
            SwitchRow("Show separator", config.separator, { v -> onConfig { it.copy(separator = v) } }, enabled = !config.vertical)
            SwitchRow("Show date", config.showDate, { v -> onConfig { it.copy(showDate = v) } })

            Section("Font")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FontChoice.picker.forEach { f ->
                    Chip(selected = config.fontName == f.name, onClick = { onConfig { it.copy(fontName = f.name) } }, label = { Text(f.label) })
                }
            }

            Section("Colour")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CLOCK_COLORS.forEach { c ->
                    val selected = config.color == c
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).background(Color(c))
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                                shape = CircleShape,
                            )
                            .clickable { onConfig { it.copy(color = c) } },
                    )
                }
            }

            Section("Effect")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ClockEffect.entries.forEach { e ->
                    Chip(selected = config.clockEffect == e, onClick = { onConfig { it.copy(effect = e.name) } }, label = { Text(e.label) })
                }
            }

            Section("Position")
            LabeledSlider("Horizontal", config.x, 0f..1f, "${(config.x * 100).toInt()}%") { v -> onConfig { it.copy(x = v) } }
            LabeledSlider("Vertical", config.y, 0f..1f, "${(config.y * 100).toInt()}%") { v -> onConfig { it.copy(y = v) } }
            LabeledSlider("Size", config.sizeRel, 0.15f..0.7f, "${(config.sizeRel * 100).toInt()}") { v -> onConfig { it.copy(sizeRel = v) } }

            Section("Background")
            LabeledSlider("Wash", config.dim, 0f..0.85f, "${(config.dim * 100).toInt()}%") { v -> onConfig { it.copy(dim = v) } }
            LabeledSlider("Saturation", config.saturation, 0f..1.8f, "${(config.saturation * 100).toInt()}%") { v -> onConfig { it.copy(saturation = v) } }
            LabeledSlider("Vignette", config.vignette, 0f..1f, "${(config.vignette * 100).toInt()}%") { v -> onConfig { it.copy(vignette = v) } }

            com.hinterlay.app.ui.components.PrimaryButton(
                "Set as depth clock", onClick = onApply, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )
}
