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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
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
 * Brushed-metal button: a dark base with slow diagonal bands of light, a faint red/blue colour
 * fringe on the rim and a bright top edge. The sheen drifts slowly and the button sinks a little
 * when pressed.
 */
@Composable
fun MetallicButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    baseColor: Color = Color(0xFF222417),
) {
    val transition = rememberInfiniteTransition(label = "metal")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(7000, easing = LinearEasing)),
        label = "phase",
    )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(14.dp)
    val mid = Color(0xFF494B3A)
    val bands = listOf(baseColor, mid, Color(0xFFF2F2EA), mid, baseColor, Color(0xFF3A3C2D), baseColor)

    Box(
        modifier
            .height(52.dp)
            .graphicsLayer {
                val s = if (pressed) 0.97f else 1f
                scaleX = s
                scaleY = s
            }
            .clip(shape)
            .drawBehind {
                val tile = size.width * 1.1f
                val x0 = phase.value * tile
                drawRect(
                    Brush.linearGradient(
                        colors = bands,
                        start = Offset(x0, 0f),
                        end = Offset(x0 + tile, tile),
                        tileMode = TileMode.Repeated,
                    ),
                )
                val r = CornerRadius(14.dp.toPx())
                val fringe = 1.dp.toPx()
                drawRoundRect(Color(0x4DFF3B3B), Offset(-fringe, 0f), cornerRadius = r, style = Stroke(fringe))
                drawRoundRect(Color(0x4D3B6BFF), Offset(fringe, 0f), cornerRadius = r, style = Stroke(fringe))
                drawRoundRect(
                    Brush.verticalGradient(listOf(Color(0x99FFFFFF), Color(0x00FFFFFF), Color(0x33000000))),
                    cornerRadius = r,
                    style = Stroke(1.5.dp.toPx()),
                )
            }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = Color(0xFFFAFAF5),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            style = TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color(0xCC000000), Offset(0f, -1f), 1f)),
        )
    }
}
