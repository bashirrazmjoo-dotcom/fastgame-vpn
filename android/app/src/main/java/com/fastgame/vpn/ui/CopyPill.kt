package com.fastgame.vpn.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.fastgame.vpn.R

/**
 * Glass "copy" pill. Tap: the label blurs and scales out, a check draws in with "Copied",
 * a fill sweeps across the pill for ~4 s, then everything returns to the idle label.
 */
@Composable
fun CopyPill(onCopy: () -> Unit, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    val swap = remember { Animatable(0f) }   // 0 = idle label, 1 = copied
    val check = remember { Animatable(0f) }
    var run by remember { mutableIntStateOf(0) }

    LaunchedEffect(run) {
        if (run == 0) return@LaunchedEffect
        progress.snapTo(0f); check.snapTo(0f)
        swap.animateTo(1f, tween(280))
        check.animateTo(1f, tween(320))
        progress.animateTo(1f, tween(3400, easing = LinearEasing))
        swap.animateTo(0f, tween(280))
        progress.snapTo(0f); check.snapTo(0f)
    }

    val fill = Accent.copy(alpha = 0.28f)
    Box(
        modifier
            .width(104.dp)
            .size(width = 104.dp, height = 40.dp)
            .clip(CircleShape)
            .modernCard(20.dp)
            .drawBehind { drawRect(fill, size = Size(size.width * progress.value, size.height)) }
            .clickable(role = Role.Button, enabled = swap.value == 0f) { onCopy(); run += 1 },
        contentAlignment = Alignment.Center,
    ) {
        val idle = 1f - swap.value
        Text(
            stringResource(R.string.copy_id),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .alpha(idle)
                .scale(0.8f + 0.2f * idle)
                .blur((8f * swap.value).dp),
        )
        Row(
            Modifier.alpha(swap.value).scale(0.8f + 0.2f * swap.value),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(Modifier.size(18.dp)) {
                val w = size.width
                val path = Path().apply {
                    moveTo(w * 0.18f, w * 0.52f); lineTo(w * 0.42f, w * 0.74f); lineTo(w * 0.82f, w * 0.28f)
                }
                val seg = Path()
                PathMeasure().apply { setPath(path, false) }.let { m ->
                    m.getSegment(0f, m.length * check.value, seg, true)
                }
                drawPath(seg, Accent, style = Stroke(width = w * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            Text(
                stringResource(R.string.copied),
                modifier = Modifier.padding(start = 6.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
