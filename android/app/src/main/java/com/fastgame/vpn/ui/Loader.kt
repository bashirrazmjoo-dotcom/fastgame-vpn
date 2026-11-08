package com.fastgame.vpn.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * App loader: an isometric stack of three servers. Units bounce in a staggered wave, their
 * status LEDs strobe, and a ghost diamond pulses where the next unit will land.
 * Drawn in a 74 x 116 design space on a single 4 s clock.
 */
@Composable
fun FastLoader(modifier: Modifier = Modifier.height(130.dp)) {
    val clock = rememberInfiniteTransition(label = "loader").animateFloat(
        0f, 1f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "t",
    )
    Canvas(modifier.aspectRatio(74f / 124f)) {
        val k = size.width / 74f
        scale(k, k, pivot = Offset.Zero) {
            val t = clock.value
            fun diamond(cx: Float, cy: Float, hw: Float, hh: Float) = Path().apply {
                moveTo(cx, cy - hh); lineTo(cx + hw, cy); lineTo(cx, cy + hh); lineTo(cx - hw, cy); close()
            }
            val hw = 30f; val hh = 15f; val th = 14f; val cx = 37f
            // ghost diamond (platform) pulses under the stack
            val pulse = 0.5f + 0.5f * sin(t * 2 * PI.toFloat() * 2)
            val ghost = diamond(cx, 108f, hw, hh)
            drawPath(ghost, Accent.copy(alpha = 0.10f + 0.18f * pulse))
            drawPath(ghost, Accent.copy(alpha = 0.35f + 0.4f * pulse), style = Stroke(1.2f))
            // three units, bottom first so upper ones draw over
            val baseY = floatArrayOf(72f, 48f, 24f)
            val tops = listOf(Color(0xFF4B5563), Color(0xFF5B6575), Color(0xFF6B7687))
            for (i in 0..2) {
                val phase = (t * 2 - i * 0.22f)
                val bounce = -abs(sin(phase * PI.toFloat())) * 6f
                val y = baseY[i] + bounce
                translate(0f, 0f) {
                    drawPath(Path().apply {          // left face
                        moveTo(cx - hw, y); lineTo(cx, y + hh); lineTo(cx, y + hh + th); lineTo(cx - hw, y + th); close()
                    }, Color(0xFF1F2937))
                    drawPath(Path().apply {          // right face
                        moveTo(cx + hw, y); lineTo(cx, y + hh); lineTo(cx, y + hh + th); lineTo(cx + hw, y + th); close()
                    }, Color(0xFF111827))
                    drawPath(diamond(cx, y, hw, hh), tops[i])
                    drawPath(diamond(cx, y, hw, hh), Color.White.copy(alpha = 0.25f), style = Stroke(0.8f))
                    // LEDs on the left face, strobing out of phase
                    for (j in 0..2) {
                        val on = 0.5f + 0.5f * sin((t * 6 - i * 0.9f - j * 1.3f) * PI.toFloat())
                        val col = if (j == 0) Accent else if (j == 1) AccentViolet else Color(0xFFFFC857)
                        val lx = cx - hw + 7f + j * 6f
                        val ly = y + hh * (lx - (cx - hw)) / hw + th * 0.55f
                        drawCircle(col.copy(alpha = 0.25f * on), 3.6f, Offset(lx, ly))
                        drawCircle(col.copy(alpha = 0.35f + 0.65f * on), 1.7f, Offset(lx, ly))
                    }
                }
            }
        }
    }
}
