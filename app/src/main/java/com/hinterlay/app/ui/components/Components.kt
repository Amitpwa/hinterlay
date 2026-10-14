package com.hinterlay.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.hinterlay.app.ui.theme.pressScale

private val Pill = CircleShape

/** Solid black-on-white (or white-on-black) pill. The one strong action on a screen. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val src = remember { MutableInteractionSource() }
    val fb = com.hinterlay.app.fx.rememberFeedback()
    Button(
        onClick = { onClick(); fb.select() }, modifier = modifier.pressScale(src), enabled = enabled, interactionSource = src, shape = Pill,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.surface,
            disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        ),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Hairline-outlined pill. [contentColor] lets destructive actions use the error colour. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val src = remember { MutableInteractionSource() }
    val fb = com.hinterlay.app.fx.rememberFeedback()
    OutlinedButton(
        onClick = { onClick(); fb.select() }, modifier = modifier.pressScale(src), enabled = enabled, interactionSource = src, shape = Pill,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = contentColor)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = contentColor, style = MaterialTheme.typography.labelLarge)
    }
}

/** A soft grey pill for secondary-but-prominent actions. */
@Composable
fun TonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val src = remember { MutableInteractionSource() }
    val fb = com.hinterlay.app.fx.rememberFeedback()
    FilledTonalButton(
        onClick = { onClick(); fb.select() }, modifier = modifier.pressScale(src), enabled = enabled, interactionSource = src, shape = Pill,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SubtleButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val src = remember { MutableInteractionSource() }
    val fb = com.hinterlay.app.fx.rememberFeedback()
    TextButton(onClick = { onClick(); fb.select() }, modifier = modifier.pressScale(src), interactionSource = src) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** A text button with a row of content, same call shape as Material's TextButton, with the shared press and tick. */
@Composable
fun ActionText(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val fb = com.hinterlay.app.fx.rememberFeedback()
    TextButton(onClick = { onClick(); fb.select() }, modifier = modifier.pressScale(src), enabled = enabled, interactionSource = src, content = content)
}

/** The thin edge used on cards and dialogs, brighter along the top like the glass panels. */
@Composable
fun hairline(): BorderStroke {
    val ink = MaterialTheme.colorScheme.onSurface
    return BorderStroke(1.dp, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(ink.copy(alpha = 0.26f), ink.copy(alpha = 0.08f))))
}

/** Same call shape as Material's AlertDialog, drawn as a dark or light glass card with a hairline edge and no shadow. */
@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") shape: androidx.compose.ui.graphics.Shape? = null,
) {
    val shape28 = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
    val fb = com.hinterlay.app.fx.rememberFeedback()
    androidx.compose.runtime.LaunchedEffect(Unit) { fb.open() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { fb.close() } }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismissRequest, confirmButton = confirmButton, dismissButton = dismissButton,
        icon = icon, title = title, text = text,
        modifier = modifier.androidx_border(shape28),
        shape = shape28, containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp,
        iconContentColor = MaterialTheme.colorScheme.onSurface, titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Modifier.androidx_border(shape: androidx.compose.ui.graphics.Shape): Modifier =
    this.then(Modifier.border(hairline(), shape))

/** Put at the top of a bottom sheet's content: plays the arrival cue once. */
@Composable
fun SheetSound() {
    val fb = com.hinterlay.app.fx.rememberFeedback()
    androidx.compose.runtime.LaunchedEffect(Unit) { fb.open() }
}

/** Small "Ad" label so every ad entry point is clearly disclosed. */
@Composable
fun AdBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Text("Ad", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/** A pill chip: filled ink when chosen, a hairline outline when not. Same call shape as Material's FilterChip. */
@Composable
fun Chip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") shape: androidx.compose.ui.graphics.Shape? = null,
) {
    val src = remember { MutableInteractionSource() }
    val fb = com.hinterlay.app.fx.rememberFeedback()
    val ink = MaterialTheme.colorScheme.onSurface
    FilterChip(
        selected = selected, onClick = { onClick(); fb.select() }, label = label, enabled = enabled, interactionSource = src, leadingIcon = leadingIcon,
        modifier = modifier.pressScale(src, 0.95f), shape = Pill,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent, labelColor = ink,
            selectedContainerColor = ink, selectedLabelColor = MaterialTheme.colorScheme.surface,
            disabledLabelColor = ink.copy(alpha = 0.38f),
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled, selected = selected,
            borderColor = MaterialTheme.colorScheme.outlineVariant, selectedBorderColor = ink,
            borderWidth = 1.dp, selectedBorderWidth = 1.dp,
        ),
    )
}

/** A thin line with a small round handle, instead of Material's thick track. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HairlineSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val track = MaterialTheme.colorScheme.outlineVariant
    val src = remember { MutableInteractionSource() }
    val fb = com.hinterlay.app.fx.rememberFeedback()
    val span = range.endInclusive - range.start
    // Ranges that run through zero (brightness, contrast...) get a firmer notch at the neutral middle.
    val notch = if (range.start < 0f && range.endInclusive > 0f) -range.start / span else null
    val down by src.collectIsPressedAsState()
    androidx.compose.runtime.LaunchedEffect(down) { if (down) fb.slideStart() }
    Slider(
        value = value.coerceIn(range.start, range.endInclusive),
        onValueChange = { v -> fb.slide((v - range.start) / span, notch); onValueChange(v) }, valueRange = range,
        modifier = modifier, interactionSource = src,
        colors = SliderDefaults.colors(thumbColor = ink, activeTrackColor = ink, inactiveTrackColor = track),
        thumb = { Box(Modifier.size(18.dp).pressScale(src, 1.25f).clip(CircleShape).background(ink)) },
        track = { state ->
            val frac = ((state.value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(2.dp).clip(CircleShape).background(track)) {
                Box(Modifier.fillMaxWidth(frac).height(2.dp).background(ink))
            }
        },
    )
}

/** A thin monochrome determinate progress bar, matching the hairline slider's track (no accent colour). */
@Composable
fun HairlineProgress(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val track = MaterialTheme.colorScheme.outlineVariant
    val p = progress.coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(track)) {
        Box(Modifier.fillMaxWidth(p).height(3.dp).clip(CircleShape).background(ink))
    }
}

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(display, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HairlineSlider(value, onValueChange, range)
    }
}

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ThemedSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/**
 * The shared black-and-white switch: a filled ink track when on, a hairline-outlined empty track
 * when off, with the toggle haptic. Use this instead of Material's Switch wherever a switch sits
 * on its own (e.g. in a ListItem's trailing slot); use [SwitchRow] when it has its own title row.
 */
@Composable
fun ThemedSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val fb = com.hinterlay.app.fx.rememberFeedback()
    val ink = MaterialTheme.colorScheme.onSurface
    Switch(
        checked = checked, onCheckedChange = { fb.toggle(it); onCheckedChange(it) }, enabled = enabled, modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.surface, checkedTrackColor = ink, checkedBorderColor = ink,
            uncheckedThumbColor = ink, uncheckedTrackColor = Color.Transparent, uncheckedBorderColor = MaterialTheme.colorScheme.outline,
        ),
    )
}
