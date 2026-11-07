package com.fastgame.vpn.ui

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SweepGradient
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val RainbowColors = listOf(0f, 90f, 210f, 195f, 270f, 0f).map { Color.hsl(it, 1f, 0.63f) }

/**
 * The round connect button.
 *  * not connected (including while connecting): quiet glass circle, no motion;
 *  * connected: a dark disc ringed by a flowing rainbow light with a soft bloom around it.
 */
@Composable
fun PowerButton(
    connected: Boolean,
    busy: Boolean,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    val tap = Modifier
        .semantics { contentDescription = description }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    if (connected && !busy) RainbowPower(tap) else QuietPower(busy, tap)
}

@Composable
private fun QuietPower(busy: Boolean, tap: Modifier) {
    Box(
        Modifier
            .size(176.dp)
            .background(Brush.radialGradient(listOf(AccentViolet.copy(alpha = 0.35f), Color.Transparent)), CircleShape)
            .padding(8.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(Color(0x33FFFFFF), Color(0x0FFFFFFF))))
            .border(2.dp, NeonBrush, CircleShape)
            .then(tap),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(color = Accent, modifier = Modifier.size(56.dp)) else PowerGlyph(Accent, 64.dp)
    }
}

@Composable
private fun RainbowPower(tap: Modifier) {
    val transition = rememberInfiniteTransition(label = "rainbow")
    val angle = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "angle",
    )
    val colors = remember { RainbowColors.map { it.toArgb() }.toIntArray() }
    Box(
        Modifier
            .size(176.dp)
            .drawBehind {
                val cx = size.width / 2
                val cy = size.height / 2
                val ring = size.minDimension / 2 - 22.dp.toPx()
                val matrix = Matrix().apply { setRotate(angle.value, cx, cy) }
                val shader = SweepGradient(cx, cy, colors, null).also { it.setLocalMatrix(matrix) }
                // bloom: wide, faint strokes stacked outside the ring
                drawIntoCanvas { canvas ->
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        this.shader = shader
                    }
                    for ((width, alpha) in listOf(30f to 18, 22f to 28, 14f to 44, 8f to 70)) {
                        paint.strokeWidth = width.dp.toPx()
                        paint.alpha = alpha
                        canvas.nativeCanvas.drawCircle(cx, cy, ring, paint)
                    }
                    paint.alpha = 255
                    paint.strokeWidth = 4.dp.toPx()
                    canvas.nativeCanvas.drawCircle(cx, cy, ring, paint)
                }
                drawCircle(Color(0xFF121213), radius = ring - 2.dp.toPx())
            }
            .then(tap),
        contentAlignment = Alignment.Center,
    ) {
        PowerGlyph(Color.White, 64.dp)
    }
}

@Composable
fun PowerGlyph(color: Color, size: androidx.compose.ui.unit.Dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val w = this.size.width
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.11f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        val pad = w * 0.12f
        drawArc(
            color, startAngle = -60f, sweepAngle = 300f, useCenter = false, style = stroke,
            topLeft = androidx.compose.ui.geometry.Offset(pad, pad + w * 0.06f),
            size = androidx.compose.ui.geometry.Size(w - 2 * pad, w - 2 * pad - w * 0.06f),
        )
        drawLine(
            color, androidx.compose.ui.geometry.Offset(w / 2, w * 0.06f), androidx.compose.ui.geometry.Offset(w / 2, w * 0.5f),
            w * 0.11f, cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}
