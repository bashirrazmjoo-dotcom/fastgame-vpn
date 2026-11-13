package com.fastgame.vpn.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Brand title whose letters are filled with moving liquid: the text is a mask over a violet-to-mint
 * gradient, with two waves and a few bubbles drifting through it.
 */
@Composable
fun LiquidTitle(text: String, modifier: Modifier = Modifier, titleSize: Int = 34) {
    val t = rememberInfiniteTransition(label = "liquid")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart), label = "p1")
    val phase2 by t.animateFloat(0f, 1f, infiniteRepeatable(tween(4800, easing = LinearEasing), RepeatMode.Restart), label = "p2")
    val bubble by t.animateFloat(0f, 1f, infiniteRepeatable(tween(5200, easing = LinearEasing), RepeatMode.Restart), label = "b")

    Text(
        text,
        style = TextStyle(fontFamily = AppFont, fontSize = titleSize.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
        color = Color.White,
        modifier = modifier
            .padding(vertical = 2.dp)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val w = size.width
                val h = size.height
                // Base fill: only where the glyphs are.
                drawRect(Brush.linearGradient(listOf(BrandViolet, BrandMint), Offset.Zero, Offset(w, h)), blendMode = BlendMode.SrcIn)
                fun wave(level: Float, amp: Float, ph: Float, color: Color) {
                    val p = Path()
                    val y0 = h * level
                    p.moveTo(0f, h)
                    p.lineTo(0f, y0)
                    var x = 0f
                    while (x <= w) {
                        p.lineTo(x, y0 + amp * sin((x / w * 2f + ph * 2f) * PI.toFloat()))
                        x += 4f
                    }
                    p.lineTo(w, h)
                    p.close()
                    drawPath(p, color, blendMode = BlendMode.SrcAtop)
                }
                wave(0.52f, h * 0.07f, phase, Color(0x40FFFFFF))
                wave(0.68f, h * 0.06f, -phase2, Color(0x2EFFFFFF))
                // Bubbles drifting upward.
                for (i in 0 until 5) {
                    val f = (bubble + i * 0.2f) % 1f
                    val bx = w * (0.1f + 0.8f * ((i * 37 % 100) / 100f)) + 6f * sin((f * 2f + i) * PI.toFloat())
                    val by = h * (1.05f - 1.1f * f)
                    drawCircle(Color(0x66FFFFFF), radius = h * (0.025f + 0.012f * (i % 3)), center = Offset(bx, by), blendMode = BlendMode.SrcAtop)
                }
            },
    )
}
