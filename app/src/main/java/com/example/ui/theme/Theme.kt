package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val ShieldBoxColorScheme = darkColorScheme(
    primary = EmeraldAccent,
    onPrimary = ObsidianBg,
    primaryContainer = EmeraldDim,
    onPrimaryContainer = EmeraldAccent,
    secondary = CyanUpload,
    onSecondary = ObsidianBg,
    tertiary = AmberConnecting,
    background = ObsidianBg,
    onBackground = TextPrimary,
    surface = ObsidianSurface,
    onSurface = TextPrimary,
    surfaceVariant = ObsidianCard,
    onSurfaceVariant = TextSecondary,
    outline = ObsidianBorder,
    error = CoralAlert,
    onError = ObsidianBg,
)

@Composable
fun ShieldBoxTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = ShieldBoxColorScheme,
        typography = Typography,
        content = content,
    )
}
