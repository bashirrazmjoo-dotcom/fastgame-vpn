package com.fastgame.vpn.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import kotlinx.coroutines.delay
import kotlin.random.Random

private const val LATIN = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
private const val PERSIAN = "ابپتثجچحخدذرزژسشصضطظعغفقکگلمنوهی"

/**
 * Text that "scrambles" and then resolves into [text], left to right: unresolved letters are random
 * characters drawn in [scrambleColor]. The effect replays every [repeatEverySeconds] seconds while shown.
 */
@Composable
fun ScrambleText(
    text: String,
    modifier: Modifier = Modifier,
    repeatEverySeconds: Int = 10,
    scrambleColor: Color = Accent,
    persian: Boolean = false,
) {
    var revealed by remember(text) { mutableStateOf(text.length) }
    var noise by remember(text) { mutableStateOf(0) }
    LaunchedEffect(text) {
        while (true) {
            val steps = 28
            val hold = 6
            for (step in 0..steps + hold) {
                revealed = ((step - hold).coerceAtLeast(0) * text.length / steps).coerceAtMost(text.length)
                noise++
                delay(40)
            }
            revealed = text.length
            delay(repeatEverySeconds * 1000L)
        }
    }
    val chars = if (persian) PERSIAN else LATIN
    val shown = remember(text, revealed, noise) {
        buildAnnotatedString {
            text.forEachIndexed { i, c ->
                if (i < revealed || c == ' ') append(c)
                else withStyle(SpanStyle(color = scrambleColor)) { append(chars[Random.nextInt(chars.length)]) }
            }
        }
    }
    Text(
        shown, modifier = modifier, textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyLarge, minLines = 2, maxLines = 3,
    )
}
