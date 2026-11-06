package com.fastgame.vpn.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF3DDC97)
val Warning = Color(0xFFFFB454)

private val Scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF04210F),
    background = Color(0xFF0B0F1A),
    onBackground = Color(0xFFE8ECF4),
    surface = Color(0xFF141A2A),
    onSurface = Color(0xFFE8ECF4),
    surfaceVariant = Color(0xFF1C2438),
    onSurfaceVariant = Color(0xFF9AA6BF),
    error = Color(0xFFFF6B6B),
)

@Composable
fun FastGameTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
