package com.fastgame.vpn.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

// A looping ribbon (drawn in a 200 x 200 box) with a dashed trail that runs along it.
private const val RIBBON =
    "m 164,100 c 0,-35.346224 -28.65378,-64 -64,-64 -35.346224,0 -64,28.653776 -64,64 0,35.34622 " +
        "28.653776,64 64,64 35.34622,0 64,-26.21502 64,-64 0,-37.784981 -26.92058,-64 -64,-64 " +
        "-37.079421,0 -65.267479,26.922736 -64,64 1.267479,37.07726 26.703171,65.05317 64,64 " +
        "37.29683,-1.05317 64,-64 64,-64"

/** Loading animation: a gradient ribbon with a fast dashed ring on it and a soft shadow. */
@Composable
fun FastLoader(modifier: Modifier = Modifier) {
    val ribbon = remember { PathParser().parsePathString(RIBBON).toPath() }
    val transition = rememberInfiniteTransition(label = "loader")
    val slow = transition.animateFloat(0f, 403f, infiniteRepeatable(tween(10_000, easing = LinearEasing)), label = "slow")
    val fast = transition.animateFloat(0f, 403f, infiniteRepeatable(tween(3_000, easing = LinearEasing)), label = "fast")
    Box(modifier.aspectRatio(1f)) {
        LoaderLayer(ribbon, slow, fast, Modifier.matchParentSize().offset(3.dp, 3.dp).blur(5.dp).alpha(0.3f))
        LoaderLayer(ribbon, slow, fast, Modifier.matchParentSize())
    }
}

@Composable
private fun LoaderLayer(ribbon: Path, slow: State<Float>, fast: State<Float>, modifier: Modifier) {
    Canvas(modifier) {
        scale(size.minDimension / 200f, size.minDimension / 200f, pivot = Offset.Zero) {
            val brush = Brush.linearGradient(listOf(AccentViolet, Accent), start = Offset(40f, 40f), end = Offset(160f, 160f))
            drawPath(
                ribbon, brush,
                style = Stroke(23f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(180f, 800f), 403f - slow.value)),
            )
            drawCircle(
                brush, radius = 64f, center = Offset(100f, 100f),
                style = Stroke(23f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(26f, 54f), 403f - fast.value)),
            )
        }
    }
}
