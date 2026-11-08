package com.fastgame.vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.fastgame.vpn.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Web3 look: deep space background, violet/cyan neon accents, frosted-glass panels.
val Accent = Color(0xFF38E8C6)      // cyan-mint
val AccentViolet = Color(0xFF8B5CF6)
val Warning = Color(0xFFFFB454)
val Ink = Color(0xFF070A18)

val NeonBrush = Brush.linearGradient(listOf(AccentViolet, Accent))

private val Scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF031A16),
    background = Ink,
    onBackground = Color(0xFFEAF0FF),
    surface = Color(0x14FFFFFF),
    onSurface = Color(0xFFEAF0FF),
    surfaceVariant = Color(0x1FFFFFFF),
    onSurfaceVariant = Color(0xFF9FA9C9),
    error = Color(0xFFFF6B8B),
)

/**
 * One type scale for the whole app (sp, so it follows the user's font size setting):
 * 12 caption, 14 secondary, 16 body/buttons, 18 titles, 20 status, 22 screen title, 28+ numbers.
 * Line heights are about 1.4x so two-line texts (Persian especially) never touch.
 */
/** Vazirmatn (SIL OFL): one family for Persian and Latin so both scripts look the same weight and size. */
val AppFont = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_semibold, FontWeight.SemiBold),
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
)

val AppTypography = Typography(
    headlineSmall = TextStyle(fontFamily = AppFont, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontFamily = AppFont, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontFamily = AppFont, fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontFamily = AppFont, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = AppFont, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = AppFont, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontFamily = AppFont, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontFamily = AppFont, fontSize = 12.sp, lineHeight = 16.sp),
)

@Composable
fun FastGameTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, content = content)
}

/** Full-screen background: dark gradient with two soft glowing orbs. */
@Composable
fun Web3Background(content: @Composable BoxScope.() -> Unit) {
    // Surface provides the readable default text color (a bare Box would leave text black).
    androidx.compose.material3.Surface(
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0A0F25), Ink, Color(0xFF0B0720))))
            .drawBehind {
                drawCircle(
                    Brush.radialGradient(
                        listOf(AccentViolet.copy(alpha = 0.35f), Color.Transparent),
                        center = Offset(size.width * 0.1f, size.height * 0.08f),
                        radius = size.width * 0.9f,
                    ),
                    radius = size.width * 0.9f,
                    center = Offset(size.width * 0.1f, size.height * 0.08f),
                )
                drawCircle(
                    Brush.radialGradient(
                        listOf(Accent.copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(size.width * 0.95f, size.height * 0.85f),
                        radius = size.width * 0.8f,
                    ),
                    radius = size.width * 0.8f,
                    center = Offset(size.width * 0.95f, size.height * 0.85f),
                )
            },
        content = content,
    )
    }
}

/** Frosted-glass panel: translucent fill plus a faint gradient border. */
fun Modifier.glass(corner: Dp = 20.dp): Modifier = this
    .background(
        Brush.linearGradient(listOf(Color(0x33FFFFFF), Color(0x1AFFFFFF))),
        RoundedCornerShape(corner),
    )
    .border(
        1.dp,
        Brush.linearGradient(listOf(Color(0x55FFFFFF), Color(0x14FFFFFF))),
        RoundedCornerShape(corner),
    )
