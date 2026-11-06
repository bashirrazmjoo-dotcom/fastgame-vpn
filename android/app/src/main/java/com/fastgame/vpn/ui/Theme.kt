package com.fastgame.vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Web3 look: deep space background, violet/cyan neon accents, frosted-glass panels.
val Accent = Color(0xFF38E8C6)      // cyan-mint
val AccentViolet = Color(0xFF8B5CF6)
val Warning = Color(0xFFFFB454)
val Ink = Color(0xFF070A18)

val NeonBrush = Brush.linearGradient(listOf(AccentViolet, Accent))

private val Scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF031A16),
    background = Ink,
    onBackground = Color(0xFFEAF0FF),
    surface = Color(0x14FFFFFF),
    onSurface = Color(0xFFEAF0FF),
    surfaceVariant = Color(0x1FFFFFFF),
    onSurfaceVariant = Color(0xFF9FA9C9),
    error = Color(0xFFFF6B8B),
)

@Composable
fun FastGameTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}

/** Full-screen background: dark gradient with two soft glowing orbs. */
@Composable
fun Web3Background(content: @Composable BoxScope.() -> Unit) {
    // Surface provides the readable default text color (a bare Box would leave text black).
    androidx.compose.material3.Surface(
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0A0F25), Ink, Color(0xFF0B0720))))
            .drawBehind {
                drawCircle(
                    Brush.radialGradient(
                        listOf(AccentViolet.copy(alpha = 0.35f), Color.Transparent),
                        center = Offset(size.width * 0.1f, size.height * 0.08f),
                        radius = size.width * 0.9f,
                    ),
                    radius = size.width * 0.9f,
                    center = Offset(size.width * 0.1f, size.height * 0.08f),
                )
                drawCircle(
                    Brush.radialGradient(
                        listOf(Accent.copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(size.width * 0.95f, size.height * 0.85f),
                        radius = size.width * 0.8f,
                    ),
                    radius = size.width * 0.8f,
                    center = Offset(size.width * 0.95f, size.height * 0.85f),
                )
            },
        content = content,
    )
    }
}

/** Frosted-glass panel: translucent fill plus a faint gradient border. */
fun Modifier.glass(corner: Dp = 20.dp): Modifier = this
    .background(
        Brush.linearGradient(listOf(Color(0x33FFFFFF), Color(0x1AFFFFFF))),
        RoundedCornerShape(corner),
    )
    .border(
        1.dp,
        Brush.linearGradient(listOf(Color(0x55FFFFFF), Color(0x14FFFFFF))),
        RoundedCornerShape(corner),
    )
