package com.fastgame.vpn.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fastgame.vpn.data.VpnServerInfo
import java.util.Locale

private val LiquidEase = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
// Same palette as the rest of the app: frosted card closed, deeper navy panel + mint accent when open.
private val MenuPanel = Color(0xFF0D1230)
private val MenuText = Color(0xFFEAF0FF)
private val MenuAccent = Accent

private const val BAR_DP = 60
private const val ITEM_DP = 40
private const val GAP_DP = 14
private const val MAX_VISIBLE = 5

/**
 * Server picker styled as a liquid-morphing pill: a frosted card that expands in place into a deeper
 * panel (a dark circle rises from the bottom) and reveals the server list one by one.
 * When [canPick] is false it stays a static pill showing the selected server.
 */
@Composable
internal fun LiquidServerMenu(
    selected: VpnServerInfo?,
    servers: List<VpnServerInfo>,
    canPick: Boolean,
    emptyLabel: String,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val isOpen = open && canPick
    if (isOpen) BackHandler { open = false }
    val fa = Locale.getDefault().language == "fa"

    val shown = servers.size.coerceIn(1, MAX_VISIBLE)
    val listDp = shown * ITEM_DP + (shown - 1) * GAP_DP + 28
    val height by animateDpAsState(
        if (isOpen) (BAR_DP + listDp).dp else BAR_DP.dp,
        tween(if (isOpen) 800 else 600, easing = LiquidEase), label = "h",
    )
    val radius by animateDpAsState(if (isOpen) 24.dp else 16.dp, tween(800, easing = LiquidEase), label = "r")
    val rise by animateFloatAsState(if (isOpen) 1f else 0f, tween(800, delayMillis = if (isOpen) 100 else 0, easing = LiquidEase), label = "rise")
    val scrollState = rememberScrollState()

    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(radius))
            .drawBehind {
                val corner = CornerRadius(radius.toPx())
                drawRoundRect(Color(0x0AFFFFFF), cornerRadius = corner)
                // Oval of 200% x 200%, centred horizontally; its bottom edge moves from 300% up to 120% of the height.
                val w = size.width
                val h = size.height
                val bottom = h * (3f - 1.8f * rise)
                drawOval(MenuPanel, topLeft = Offset(-w / 2f, bottom - 2f * h), size = Size(2f * w, 2f * h))
                drawRoundRect(Color(0x24FFFFFF), cornerRadius = corner, style = Stroke(1.dp.toPx()))
                val inset = radius.toPx()
                drawLine(
                    Brush.horizontalGradient(listOf(Color.Transparent, MenuAccent.copy(alpha = 0.6f), Color.Transparent), startX = inset, endX = w - inset),
                    Offset(inset, 0.5.dp.toPx()), Offset(w - inset, 0.5.dp.toPx()), 1.dp.toPx(),
                )
            },
    ) {
        Column(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .weight(1f, fill = true)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(top = 18.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(GAP_DP.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                servers.forEachIndexed { i, s ->
                    val a = remember { Animatable(0f) }
                    LaunchedEffect(isOpen) {
                        if (isOpen) a.animateTo(1f, tween(400, delayMillis = 400 + 80 * i, easing = LiquidEase)) else a.snapTo(0f)
                    }
                    val chosen = s.id == selected?.id
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(ITEM_DP.dp)
                            .alpha(a.value)
                            .graphicsLayer { translationY = (1f - a.value) * 12.dp.toPx() }
                            .clickable(
                                enabled = isOpen, role = Role.Button,
                                interactionSource = remember { MutableInteractionSource() }, indication = null,
                            ) { open = false; onSelect(s.id) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(flagEmoji(s.countryCode), fontSize = 22.sp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            serverLabel(s, fa),
                            color = if (chosen) MenuAccent else MenuText,
                            fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        )
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(BAR_DP.dp)
                    .clickable(
                        enabled = canPick, role = Role.Button,
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                    ) { open = !open }
                    .padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val ink = MenuText
                Text(flagEmoji(selected?.countryCode), fontSize = 26.sp)
                Spacer(Modifier.width(12.dp))
                Text(
                    selected?.let { serverLabel(it, fa) } ?: emptyLabel,
                    color = ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (canPick) Burger(isOpen, ink)
            }
        }
    }
}

@Composable
private fun Burger(open: Boolean, color: Color) {
    val rot by animateFloatAsState(if (open) 45f else 0f, tween(400, easing = LiquidEase), label = "rot")
    val off by animateDpAsState(if (open) 0.dp else 3.dp, tween(400, easing = LiquidEase), label = "off")
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.padding(bottom = off * 2).size(18.dp, 2.dp).rotate(rot).background(color, RoundedCornerShape(1.dp)))
        Box(Modifier.padding(top = off * 2).size(18.dp, 2.dp).rotate(-rot).background(color, RoundedCornerShape(1.dp)))
    }
}

private fun serverLabel(s: VpnServerInfo, fa: Boolean): String {
    val country = if (fa) s.countryNameFa else s.countryNameEn
    val number = Regex("(\\d+)\\s*$").find(s.name)?.value
    return listOfNotNull(country, number).joinToString(" ")
}
