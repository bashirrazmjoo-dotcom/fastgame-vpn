package com.fastgame.vpn.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val FluidEase = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
private const val MENU_MS = 300
private val Cell = 48.dp
private val Step = 52.dp

/** One entry of the fluid menu: a short text (a script letter pair, a number...) in a circle. */
data class FluidItem(val label: String, val description: String, val onClick: () -> Unit, val icon: (@Composable () -> Unit)? = null)

/**
 * Circular menu that unfolds downward: the round head swaps between "menu" and "close", and each item
 * slides out of it one step lower while fading in. Styled with the app's glass cards. Add items to the list to grow it.
 */
@Composable
fun FluidMenu(items: List<FluidItem>, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val total by animateDpAsState(if (open) Cell + Step * items.size else Cell, tween(MENU_MS, easing = FluidEase), label = "total")
    val rot by animateFloatAsState(if (open) 1f else 0f, tween(MENU_MS, easing = FluidEase), label = "rot")

    Box(modifier.size(Cell, total)) {
        items.forEachIndexed { i, item ->
            val y by animateDpAsState(if (open) Step * (i + 1) else 0.dp, tween(MENU_MS, easing = FluidEase), label = "y$i")
            val a by animateFloatAsState(if (open) 1f else 0f, tween(if (open) MENU_MS else 350, easing = FluidEase), label = "a$i")
            Cell(
                Modifier.offset(y = y).alpha(a)
                    .semantics { contentDescription = item.description }
                    .clickable(
                        enabled = open, role = Role.Button,
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                    ) { open = false; item.onClick() },
            ) {
                if (item.icon != null) item.icon.invoke()
                else Text(item.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        // Head (drawn last so it stays on top of the items while they slide out of it).
        Cell(
            Modifier.clickable(
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() }, indication = null,
            ) { open = !open },
        ) {
            val line = MaterialTheme.colorScheme.onSurface
            Canvas(Modifier.size(22.dp).rotate(180f * rot).scale(1f)) {
                val w = size.width
                val cx = w / 2f
                val stroke = 2.dp.toPx()
                // Three bars that merge into a cross: outer bars move to the centre and rotate, middle fades.
                val gap = 6.dp.toPx() * (1f - rot)
                val half = w * 0.4f
                val ang = Math.toRadians((45f * rot).toDouble())
                val dx = (half * Math.cos(ang)).toFloat()
                val dy = (half * Math.sin(ang)).toFloat()
                drawLine(line, Offset(cx - dx, size.height / 2 - gap - dy), Offset(cx + dx, size.height / 2 - gap + dy), stroke, StrokeCap.Round)
                drawLine(line.copy(alpha = 1f - rot), Offset(cx - half, size.height / 2), Offset(cx + half, size.height / 2), stroke, StrokeCap.Round)
                drawLine(line, Offset(cx - dx, size.height / 2 + gap + dy), Offset(cx + dx, size.height / 2 + gap - dy), stroke, StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun Cell(modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .size(Cell)
            .clip(CircleShape)
            .drawBehind { drawCircle(Pal.cell) }
            .border(1.dp, Pal.cardBorder, CircleShape),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Sun (shown while dark: tap for light) or crescent moon (shown while light: tap for dark). */
@Composable
fun ThemeGlyph(sun: Boolean, color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        val stroke = 1.8.dp.toPx()
        if (sun) {
            drawCircle(color, radius = size.width * 0.2f, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            for (k in 0 until 8) {
                val a = Math.toRadians(k * 45.0)
                val dx = Math.cos(a).toFloat(); val dy = Math.sin(a).toFloat()
                drawLine(color, Offset(c.x + dx * size.width * 0.32f, c.y + dy * size.width * 0.32f), Offset(c.x + dx * size.width * 0.46f, c.y + dy * size.width * 0.46f), stroke, StrokeCap.Round)
            }
        } else {
            val outer = androidx.compose.ui.graphics.Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, size.width * 0.38f)) }
            val cut = androidx.compose.ui.graphics.Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(c.x + size.width * 0.2f, c.y - size.width * 0.12f), size.width * 0.32f)) }
            val moon = androidx.compose.ui.graphics.Path.combine(androidx.compose.ui.graphics.PathOperation.Difference, outer, cut)
            drawPath(moon, color)
        }
    }
}
