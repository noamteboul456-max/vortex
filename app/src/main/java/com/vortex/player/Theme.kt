package com.vortex.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Obsidian = Color(0xFF000000)
val Panel = Color(0xFF0C0C0F)
val PanelHigh = Color(0xFF16161B)
val Accent = Color(0xFFB794FF)
val AccentAlt = Color(0xFF4DE1FF)
val Muted = Color(0xFF8A8A94)
val TextPrimary = Color(0xFFEDEDF2)

private val VortexColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.Black,
    secondary = AccentAlt,
    onSecondary = Color.Black,
    background = Obsidian,
    onBackground = TextPrimary,
    surface = Panel,
    onSurface = TextPrimary,
    surfaceVariant = PanelHigh,
    onSurfaceVariant = Muted,
    outline = Color(0xFF2A2A31)
)

@Composable
fun VortexTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VortexColors,
        typography = Typography(),
        content = content
    )
}
