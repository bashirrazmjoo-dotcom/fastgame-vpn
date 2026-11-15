package com.fastgame.vpn.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Liquid glass" panel: a translucent body, a soft light inside the rim and bright highlights on the
 * top-left and bottom-right edges (the rim catches light like a thick glass plate).
 */
fun Modifier.liquidGlass(corner: Dp = 22.dp): Modifier = this
    .clip(RoundedCornerShape(corner))
    .drawBehind {
        val r = CornerRadius(corner.toPx())
        drawRoundRect(Brush.verticalGradient(listOf(Color(0x2EFFFFFF), Color(0x0FFFFFFF))), cornerRadius = r)
        // light that bleeds inward from the rim (the stroke is centred on the edge, so half is clipped away)
        drawRoundRect(Color(0x1AFFFFFF), cornerRadius = r, style = Stroke(10.dp.toPx()))
        drawRoundRect(Color(0x0FFFFFFF), cornerRadius = r, style = Stroke(4.dp.toPx()))
        drawRoundRect(
            Brush.linearGradient(
                listOf(Color(0xE6FFFFFF), Color(0x14FFFFFF), Color(0x14FFFFFF), Color(0xCCFFFFFF)),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            ),
            cornerRadius = r,
            style = Stroke(2.dp.toPx()),
        )
    }

/**
 * Flat "modern" card (not glass): a faint raised fill, a thin border and a hairline accent light
 * running along the top edge.
 */
fun Modifier.modernCard(corner: Dp = 16.dp): Modifier = this
    .clip(RoundedCornerShape(corner))
    .drawBehind {
        val r = CornerRadius(corner.toPx())
        drawRoundRect(Pal.cardFill, cornerRadius = r)
        drawRoundRect(Pal.cardBorder, cornerRadius = r, style = Stroke(1.dp.toPx()))
        val inset = corner.toPx()
        drawLine(
            Brush.horizontalGradient(listOf(Color.Transparent, Accent.copy(alpha = 0.6f), Color.Transparent), startX = inset, endX = size.width - inset),
            Offset(inset, 0.5.dp.toPx()), Offset(size.width - inset, 0.5.dp.toPx()), 1.dp.toPx(),
        )
    }

/** Button in the modern-card style for secondary actions; sinks a little when pressed. */
@Composable
fun GlassButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Sheen: a soft bright band sweeps across the button each time it is touched.
    val sheen = remember { androidx.compose.animation.core.Animatable(1f) }
    androidx.compose.runtime.LaunchedEffect(pressed) {
        if (pressed) {
            sheen.snapTo(0f)
            sheen.animateTo(1f, tween(650, easing = androidx.compose.animation.core.CubicBezierEasing(0.22f, 1f, 0.36f, 1f)))
        }
    }
    Box(
        modifier
            .height(52.dp)
            .graphicsLayer {
                val s = if (pressed) 0.97f else 1f
                scaleX = s
                scaleY = s
            }
            .modernCard(16.dp)
            .clip(RoundedCornerShape(16.dp))
            .drawWithContent {
                drawContent()
                val p = sheen.value
                if (p < 1f) {
                    val w = size.width
                    val band = w * 0.45f
                    val x = -band + p * (w + band * 2f)
                    val tint = if (Pal.light) Pal.accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.30f)
                    rotate(18f, Offset(size.width / 2, size.height / 2)) {
                        drawRect(
                            Brush.horizontalGradient(listOf(Color.Transparent, tint, Color.Transparent), startX = x, endX = x + band),
                            topLeft = Offset(x, -size.height), size = androidx.compose.ui.geometry.Size(band, size.height * 3f),
                        )
                    }
                }
            }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Pal.text, style = androidx.compose.material3.MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 1)
    }
}
