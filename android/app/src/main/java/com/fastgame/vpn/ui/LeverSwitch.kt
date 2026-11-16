package com.fastgame.vpn.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Glossy lever switch: a light pill base with a slot (grey when off, green when connected),
 * a metal lever and a red ball. The lever sits at the left when off, at the right when connected,
 * and in the middle while connecting or disconnecting.
 */
@Composable
fun LeverSwitch(
    connected: Boolean,
    busy: Boolean,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    val target = when {
        busy -> 0.5f
        connected -> 1f
        else -> 0f
    }
    val p by animateFloatAsState(target, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow), label = "lever")
    val on = connected && !busy

    Box(
        Modifier
            .size(width = 168.dp, height = 96.dp)
            .semantics { contentDescription = description }
            .clickable(enabled = enabled, role = Role.Switch, onClick = onClick),
    ) {
        Canvas(Modifier.size(width = 168.dp, height = 96.dp)) {
            val w = size.width
            val baseH = 38.dp.toPx()
            val baseW = 150.dp.toPx()
            val baseL = (w - baseW) / 2
            val baseTop = size.height - baseH - 6.dp.toPx()
            val r = CornerRadius(baseH / 2)

            // soft shadow under the base
            for (i in 4 downTo 1) {
                drawRoundRect(
                    Color.Black.copy(alpha = 0.05f),
                    Offset(baseL - i * 1.5f, baseTop + i * 1.8f),
                    Size(baseW + i * 3f, baseH), CornerRadius(baseH / 2 + i),
                )
            }
            // base
            drawRoundRect(
                Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFE6E6E6)), baseTop, baseTop + baseH),
                Offset(baseL, baseTop), Size(baseW, baseH), r,
            )
            // slot
            val slotH = 14.dp.toPx()
            val slotTop = baseTop + (baseH - slotH) / 2
            val slotL = baseL + 10.dp.toPx()
            val slotW = baseW - 20.dp.toPx()
            val slotBrush = if (on) {
                Brush.verticalGradient(listOf(Color(0xFF6CC070), Color(0xFF3E9A44)), slotTop, slotTop + slotH)
            } else {
                Brush.verticalGradient(listOf(Color(0xFFCFCFCF), Color(0xFFA9A9A9)), slotTop, slotTop + slotH)
            }
            drawRoundRect(slotBrush, Offset(slotL, slotTop), Size(slotW, slotH), CornerRadius(slotH / 2))

            // lever: pivot slides along the slot, tilts away from the middle
            val pivot = Offset(baseL + baseW * (0.2f + 0.6f * p), baseTop + baseH / 2)
            val rad = Math.toRadians(((p - 0.5f) * 2 * 22f).toDouble())
            val len = 46.dp.toPx()
            val tip = Offset(pivot.x + (sin(rad) * len).toFloat(), pivot.y - (cos(rad) * len).toFloat())
            drawLine(
                Brush.linearGradient(listOf(Color(0xFFEDEDED), Color(0xFF8E8E8E)), pivot, tip),
                pivot, tip, strokeWidth = 7.dp.toPx(), cap = StrokeCap.Round,
            )
            // red ball
            val br = 16.dp.toPx()
            drawCircle(Color.Black.copy(alpha = 0.12f), br, tip + Offset(0f, 2.dp.toPx()))
            drawCircle(
                Brush.radialGradient(
                    listOf(Color(0xFFFF6B6B), Color(0xFFD01818), Color(0xFF9A0D0D)),
                    center = tip + Offset(-br * 0.3f, -br * 0.35f), radius = br * 1.5f,
                ),
                br, tip,
            )
            drawCircle(Color.White.copy(alpha = 0.75f), br * 0.22f, tip + Offset(-br * 0.38f, -br * 0.42f))
        }
    }
}
