package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val GeminiVoiceDarkColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = DeepDarkBg,
    primaryContainer = GlowingIndigo,
    onPrimaryContainer = TextPrimaryDark,
    secondary = ElectricViolet,
    onSecondary = TextPrimaryDark,
    tertiary = VibrantMagenta,
    background = DeepDarkBg,
    onBackground = TextPrimaryDark,
    surface = DarkSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondaryDark,
    outline = DarkBorder
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = GeminiVoiceDarkColorScheme,
        typography = Typography,
        content = content
    )
}
