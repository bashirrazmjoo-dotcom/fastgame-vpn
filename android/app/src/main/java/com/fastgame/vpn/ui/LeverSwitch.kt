package com.fastgame.vpn.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * A compact lever switch: a short base with a lever that throws left (off) or right (on).
 * Middle position while the connection is being set up or torn down.
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
        busy -> 0f
        connected -> 40f
        else -> -40f
    }
    val angle by animateFloatAsState(target, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow), label = "lever")
    val accent = Pal.Accent
    val violet = Pal.AccentViolet
    val ink = Pal.text
    val border = Pal.cardBorder
    val fill = Pal.discFill
    val on = connected && !busy

    Box(
        Modifier
            .size(width = 168.dp, height = 84.dp)
            .semantics { contentDescription = description }
            .clickable(enabled = enabled, role = Role.Switch, onClick = onClick),
    ) {
        Canvas(Modifier.size(width = 168.dp, height = 84.dp)) {
            val baseH = 30.dp.toPx()
            val baseTop = size.height - baseH
            // base
            drawRoundRect(fill, Offset(0f, baseTop), Size(size.width, baseH), CornerRadius(baseH / 2))
            drawRoundRect(border, Offset(0f, baseTop), Size(size.width, baseH), CornerRadius(baseH / 2), style = Stroke(1.5.dp.toPx()))
            // slot glowing when on
            val slotH = 8.dp.toPx()
            val slotTop = baseTop + (baseH - slotH) / 2
            val slotL = 18.dp.toPx()
            val slotW = size.width - slotL * 2
            drawRoundRect(
                if (on) Brush.horizontalGradient(listOf(accent, violet)) else Brush.horizontalGradient(listOf(border, border)),
                Offset(slotL, slotTop), Size(slotW, slotH), CornerRadius(slotH / 2),
            )
            // lever
            val pivot = Offset(size.width / 2, baseTop + baseH / 2)
            val len = 50.dp.toPx()
            val rad = Math.toRadians(angle.toDouble())
            val tip = Offset(pivot.x + (sin(rad) * len).toFloat(), pivot.y - (cos(rad) * len).toFloat())
            val tint = if (on) accent else ink.copy(alpha = 0.85f)
            if (on) drawCircle(accent.copy(alpha = 0.25f), 24.dp.toPx(), tip)
            drawLine(tint, pivot, tip, strokeWidth = 8.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(if (on) Brush.linearGradient(listOf(accent, violet)) else Brush.linearGradient(listOf(ink, ink)), 13.dp.toPx(), tip)
            drawCircle(fill, 5.dp.toPx(), tip)
            // pivot cap
            drawCircle(tint, 9.dp.toPx(), pivot)
            drawCircle(fill, 3.5.dp.toPx(), pivot)
        }
    }
}
