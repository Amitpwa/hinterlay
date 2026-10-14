package com.hinterlay.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Minimal black-and-white palette. No accent colour: hierarchy comes from contrast, weight, spacing
 * and thin lines. Follows the system light/dark setting. Red is kept only for destructive/error states.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF111111),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE9E9E9),
    onPrimaryContainer = Color(0xFF111111),
    secondary = Color(0xFF333333),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEDEDED),
    onSecondaryContainer = Color(0xFF111111),
    tertiary = Color(0xFF555555),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEDEDED),
    onTertiaryContainer = Color(0xFF111111),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF111111),
    surface = Color(0xFFFAFAFA),
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFEDEDED),
    onSurfaceVariant = Color(0xFF5C5C5C),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F6F6),
    surfaceContainer = Color(0xFFF1F1F1),
    surfaceContainerHigh = Color(0xFFEBEBEB),
    surfaceContainerHighest = Color(0xFFE4E4E4),
    outline = Color(0xFF8C8C8C),
    outlineVariant = Color(0xFFD6D6D6),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF3DEDC),
    onErrorContainer = Color(0xFF410E0B),
    inverseSurface = Color(0xFF1C1C1C),
    inverseOnSurface = Color(0xFFF5F5F5),
    inversePrimary = Color(0xFFE8E8E8),
    scrim = Color(0xFF000000),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF2F2F2),
    onPrimary = Color(0xFF0D0D0D),
    primaryContainer = Color(0xFF2B2B2B),
    onPrimaryContainer = Color(0xFFF2F2F2),
    secondary = Color(0xFFD0D0D0),
    onSecondary = Color(0xFF0D0D0D),
    secondaryContainer = Color(0xFF262626),
    onSecondaryContainer = Color(0xFFF2F2F2),
    tertiary = Color(0xFFB5B5B5),
    onTertiary = Color(0xFF0D0D0D),
    tertiaryContainer = Color(0xFF262626),
    onTertiaryContainer = Color(0xFFF2F2F2),
    background = Color(0xFF0B0B0B),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF0B0B0B),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF242424),
    onSurfaceVariant = Color(0xFFA8A8A8),
    surfaceContainerLowest = Color(0xFF080808),
    surfaceContainerLow = Color(0xFF121212),
    surfaceContainer = Color(0xFF171717),
    surfaceContainerHigh = Color(0xFF1F1F1F),
    surfaceContainerHighest = Color(0xFF282828),
    outline = Color(0xFF707070),
    outlineVariant = Color(0xFF333333),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    inverseSurface = Color(0xFFF2F2F2),
    inverseOnSurface = Color(0xFF1C1C1C),
    inversePrimary = Color(0xFF2B2B2B),
    scrim = Color(0xFF000000),
)

// Rounded, calm shapes.
private val MinimalShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Cleaner, more technical type: light, large headlines with tight tracking; labels a touch wider and medium weight, so
 * buttons and chips read as controls. System sans, so it needs no download and stays sharp at every size.
 */
private val HinterlayTypography = Typography().let { t ->
    fun TextStyle.tune(weight: FontWeight, spacing: Double) = copy(fontWeight = weight, letterSpacing = spacing.sp)
    t.copy(
        displayLarge = t.displayLarge.tune(FontWeight.Light, -1.0),
        displayMedium = t.displayMedium.tune(FontWeight.Light, -0.8),
        headlineLarge = t.headlineLarge.tune(FontWeight.Light, -0.6),
        headlineMedium = t.headlineMedium.tune(FontWeight.Light, -0.5),
        headlineSmall = t.headlineSmall.tune(FontWeight.Normal, -0.3),
        titleLarge = t.titleLarge.tune(FontWeight.Normal, -0.2),
        titleMedium = t.titleMedium.tune(FontWeight.Medium, 0.0),
        titleSmall = t.titleSmall.tune(FontWeight.Medium, 0.1),
        labelLarge = t.labelLarge.tune(FontWeight.Medium, 0.3),
        labelMedium = t.labelMedium.tune(FontWeight.Medium, 0.4),
        labelSmall = t.labelSmall.tune(FontWeight.Medium, 0.5),
        bodyLarge = t.bodyLarge.copy(letterSpacing = 0.1.sp),
    )
}

@Composable
fun HinterlayTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = HinterlayTypography,
        shapes = MinimalShapes,
    ) {
        // Screens drawn without a Surface/Scaffold (onboarding, consent) would otherwise get black text in dark mode.
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface,
            content = content,
        )
    }
}
