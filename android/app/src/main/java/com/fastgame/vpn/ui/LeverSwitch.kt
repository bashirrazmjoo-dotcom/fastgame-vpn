package com.fastgame.vpn.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

private val Rainbow = listOf(0f, 90f, 210f, 195f, 270f, 0f).map { Color.hsl(it, 1f, 0.63f) }

/**
 * Glossy 3D lever switch. A short, thick metal lever rises out of the slot of a soft pill base;
 * the slot is grey when off and green when connected, and a flowing rainbow light rings the base
 * while connected. The lever sits left when off, right when on and in the middle while busy.
 */
@Composable
fun LeverSwitch(
    connected: Boolean,
    busy: Boolean,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    val on = connected && !busy
    val target = when {
        busy -> 0.5f
        connected -> 1f
        else -> 0f
    }
    val p by animateFloatAsState(target, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow), label = "lever")
    val glow by animateFloatAsState(if (on) 1f else 0f, tween(600), label = "glow")
    val spin by rememberInfiniteTransition(label = "ring").animateFloat(
        0f, 360f, infiniteRepeatable(tween(4500, easing = LinearEasing)), label = "spin",
    )

    Box(
        Modifier
            .size(width = 184.dp, height = 112.dp)
            .semantics { contentDescription = description }
            .clickable(enabled = enabled, role = Role.Switch, onClick = onClick),
    ) {
        Canvas(Modifier.size(width = 184.dp, height = 112.dp)) {
            val dp = 1.dp.toPx()
            val baseW = 118 * dp
            val baseH = 40 * dp
            val baseL = (size.width - baseW) / 2
            val baseTop = size.height - baseH - 12 * dp
            val rr = CornerRadius(baseH / 2)

            // light ring + bloom while connected
            if (glow > 0.01f) {
                for (i in 6 downTo 1) {
                    drawRoundRect(
                        Rainbow[(i * 2) % 5].copy(alpha = 0.05f * glow),
                        Offset(baseL - i * 3 * dp, baseTop - i * 3 * dp),
                        Size(baseW + i * 6 * dp, baseH + i * 6 * dp), CornerRadius(baseH / 2 + i * 3 * dp),
                    )
                }
                val pad = 3 * dp
                val outer = RoundRect(baseL - pad, baseTop - pad, baseL + baseW + pad, baseTop + baseH + pad, CornerRadius(baseH / 2 + pad))
                val inner = RoundRect(baseL - 0.5f * dp, baseTop - 0.5f * dp, baseL + baseW + 0.5f * dp, baseTop + baseH + 0.5f * dp, CornerRadius(baseH / 2 + 0.5f * dp))
                val ring = Path().apply { fillType = PathFillType.EvenOdd; addRoundRect(outer); addRoundRect(inner) }
                val c = Offset(baseL + baseW / 2, baseTop + baseH / 2)
                clipPath(ring) {
                    rotate(spin, c) {
                        drawCircle(Brush.sweepGradient(Rainbow, c), baseW, c, alpha = glow)
                    }
                }
            }

            // soft depth shadow under the base
            for (i in 7 downTo 1) {
                drawRoundRect(
                    Color.Black.copy(alpha = 0.04f),
                    Offset(baseL - i * 1.2f * dp, baseTop + i * 1.6f * dp),
                    Size(baseW + i * 2.4f * dp, baseH), CornerRadius(baseH / 2 + i * dp),
                )
            }
            // base: soft grey body with gentle top light and a darker lower edge
            drawRoundRect(
                Brush.verticalGradient(listOf(Color(0xFFF1F1F1), Color(0xFFDADADA)), baseTop, baseTop + baseH),
                Offset(baseL, baseTop), Size(baseW, baseH), rr,
            )

            // slot (inset)
            val slotH = 18 * dp
            val slotTop = baseTop + (baseH - slotH) / 2 + 1 * dp
            val slotL = baseL + 9 * dp
            val slotW = baseW - 18 * dp
            val slotR = CornerRadius(slotH / 2)
            drawRoundRect(Color.Black.copy(alpha = 0.18f), Offset(slotL, slotTop - 1.5f * dp), Size(slotW, slotH + 1.5f * dp), slotR)
            val slotBrush = if (on) {
                Brush.verticalGradient(listOf(Color(0xFF66BB6A), Color(0xFF3B9440)), slotTop, slotTop + slotH)
            } else {
                Brush.verticalGradient(listOf(Color(0xFFD2D2D2), Color(0xFFA6A6A6)), slotTop, slotTop + slotH)
            }
            drawRoundRect(slotBrush, Offset(slotL, slotTop), Size(slotW, slotH), slotR)

            // lever: starts inside the slot, cut off at the slot's lower edge
            val slotBottom = slotTop + slotH
            val pivot = Offset(baseL + baseW * (0.22f + 0.56f * p), slotBottom)
            val rad = Math.toRadians(((p - 0.5f) * 2 * 18f).toDouble())
            val len = 40 * dp
            val dx = sin(rad).toFloat()
            val dy = cos(rad).toFloat()
            val tip = Offset(pivot.x + dx * len, pivot.y - dy * len)
            val ballR = 17 * dp

            // ball shadow on the base
            for (i in 4 downTo 1) {
                drawCircle(Color.Black.copy(alpha = 0.035f), ballR + i * 1.5f * dp, Offset(tip.x + 5 * dp, slotBottom + 3 * dp))
            }
            clipRect(top = 0f, bottom = slotBottom) {
                val sh = Offset(3 * dp, 1.5f * dp)
                drawLine(Color.Black.copy(alpha = 0.22f), pivot + sh, tip + sh, strokeWidth = 12 * dp, cap = StrokeCap.Butt)
                drawLine(Color(0xFF6E6E6E), pivot, tip, strokeWidth = 12 * dp, cap = StrokeCap.Butt)
                drawLine(Color(0xFFB9B9B9), pivot, tip, strokeWidth = 8.5f * dp, cap = StrokeCap.Butt)
                val off = Offset(-1.2f * dp, 0f)
                drawLine(Color(0xFFF4F4F4), pivot + off, tip + off, strokeWidth = 4 * dp, cap = StrokeCap.Butt)
            }
            // red ball
            drawCircle(
                Brush.radialGradient(
                    listOf(Color(0xFFE85050), Color(0xFFC62828), Color(0xFF8E1010)),
                    center = tip + Offset(-ballR * 0.3f, -ballR * 0.35f), radius = ballR * 1.6f,
                ),
                ballR, tip,
            )
            drawCircle(Color.White.copy(alpha = 0.8f), ballR * 0.2f, tip + Offset(-ballR * 0.4f, -ballR * 0.45f))
        }
    }
}
