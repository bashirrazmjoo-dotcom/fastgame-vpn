package com.fastgame.vpn.ui

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A pill button with a light that runs around its border, a faint dot texture and a glow at the
 * bottom edge (Compose version of the "shiny" call-to-action look). The light widens while pressed.
 */
@Composable
fun ShinyButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlight: Color = AccentViolet,
    shine: Color = Color.White,
    enabled: Boolean = true,
    contentDescription: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "shiny")
    val angle = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing)),
        label = "angle",
    )
    val breathe = transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val spread = if (pressed) 0.2f else 0.05f
    val shape = RoundedCornerShape(50)

    Row(
        modifier
            .graphicsLayer { translationY = if (pressed) 1.dp.toPx() else 0f }
            .drawBehind {
                // soft outer glow that breathes
                drawCircle(
                    Brush.radialGradient(
                        listOf(highlight.copy(alpha = 0.30f * breathe.value), Color.Transparent),
                        center = Offset(size.width / 2, size.height / 2),
                        radius = size.width * 0.62f,
                    ),
                    radius = size.width * 0.62f,
                    center = Offset(size.width / 2, size.height / 2),
                )
            }
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF0B1022), Color(0xFF05070F))))
            .drawWithCache {
                val step = 4.dp.toPx()
                val dots = buildList {
                    var y = step
                    while (y < size.height) {
                        var x = step
                        while (x < size.width) {
                            add(Offset(x, y))
                            x += step
                        }
                        y += step
                    }
                }
                val shimmer = Brush.radialGradient(
                    listOf(highlight.copy(alpha = 0.50f), Color.Transparent),
                    center = Offset(size.width / 2, size.height),
                    radius = size.width * 0.45f,
                )
                val border = 2.dp.toPx()
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = border
                }
                val rect = RectF(border / 2, border / 2, size.width - border / 2, size.height - border / 2)
                val matrix = Matrix()
                onDrawWithContent {
                    drawRect(shimmer)
                    drawPoints(dots, PointMode.Points, Color.White.copy(alpha = 0.10f), 1.2.dp.toPx(), StrokeCap.Round)
                    drawContent()
                    val p = spread
                    val shader = SweepGradient(
                        size.width / 2, size.height / 2,
                        intArrayOf(
                            Color.Transparent.toArgb(), highlight.toArgb(), shine.toArgb(),
                            highlight.toArgb(), Color.Transparent.toArgb(), Color.Transparent.toArgb(),
                        ),
                        floatArrayOf(0f, p, p * 2, p * 3, p * 4, 1f),
                    )
                    matrix.setRotate(angle.value, size.width / 2, size.height / 2)
                    shader.setLocalMatrix(matrix)
                    paint.shader = shader
                    drawIntoCanvas { it.nativeCanvas.drawRoundRect(rect, size.height / 2, size.height / 2, paint) }
                }
            }
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 32.dp, vertical = 20.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
