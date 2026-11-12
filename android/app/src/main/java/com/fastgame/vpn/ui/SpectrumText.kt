package com.fastgame.vpn.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign

/** Colours of the wash, taken from the app palette. */
private val SpectrumColors = listOf(Accent, AccentViolet, Warning, Accent)

/**
 * Multi-colour wash that drifts through the glyphs (a gradient brush that slides back and forth).
 * One component for every message the app shows in its message area.
 */
@Composable
fun SpectrumText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    durationMillis: Int = 4000,
    colors: List<Color> = SpectrumColors,
) {
    val t = rememberInfiniteTransition(label = "spectrum")
    val shift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(durationMillis, easing = LinearEasing), RepeatMode.Reverse), label = "shift")
    val width = 700f
    val brush = remember(shift, colors) {
        Brush.linearGradient(
            colors = colors,
            start = Offset(-width * shift, 0f),
            end = Offset(width * (1f - shift) + width, 0f),
            tileMode = TileMode.Mirror,
        )
    }
    Text(text, modifier = modifier, textAlign = TextAlign.Center, style = style.copy(brush = brush))
}
