package com.weige.music.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 一套完整的界面配色。夜间是暖黑 + 琥珀，白天是米白 + 深琥珀。 */
data class WeigeColors(
    val bg: Color,
    val panel: Color,
    val panel2: Color,
    val line: Color,
    val accent: Color,
    val accentDim: Color,
    val onAccent: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val ok: Color,
)

private val DarkColors = WeigeColors(
    bg = Color(0xFF0A0908),
    panel = Color(0xFF14110F),
    panel2 = Color(0xFF1C1815),
    line = Color(0xFF2E2419),
    accent = Color(0xFFFF7A18),
    accentDim = Color(0xFFB35410),
    onAccent = Color(0xFF1A0E05),
    text = Color(0xFFF4ECDD),
    text2 = Color(0xFFA89B88),
    text3 = Color(0xFF6B6154),
    ok = Color(0xFF5CE2A8),
)

private val LightColors = WeigeColors(
    bg = Color(0xFFF5F0E6),
    panel = Color(0xFFFFFDF9),
    panel2 = Color(0xFFEAE1D2),
    line = Color(0xFFD6C9B4),
    // 白底上用亮琥珀会发飘，压深一档保证文字对比度
    accent = Color(0xFFB45309),
    accentDim = Color(0xFFC98A4B),
    onAccent = Color(0xFFFFFFFF),
    text = Color(0xFF201A14),
    text2 = Color(0xFF6F6355),
    text3 = Color(0xFF9C9082),
    ok = Color(0xFF15803D),
)

val LocalWeigeColors = staticCompositionLocalOf { LightColors }

/**
 * 兼容层：界面里一直写的是 `Palette.Accent` 这种形式，
 * 这里把它们改成从 CompositionLocal 取值，调用点不需要改。
 *
 * 注意：@Composable getter 只能在 composable 作用域调用。
 * Canvas 的 draw lambda 不是 composable 上下文，要在外面先取成局部变量。
 */
object Palette {
    val Bg: Color @Composable get() = LocalWeigeColors.current.bg
    val Panel: Color @Composable get() = LocalWeigeColors.current.panel
    val Panel2: Color @Composable get() = LocalWeigeColors.current.panel2
    val Line: Color @Composable get() = LocalWeigeColors.current.line
    val Accent: Color @Composable get() = LocalWeigeColors.current.accent
    val AccentDim: Color @Composable get() = LocalWeigeColors.current.accentDim
    val OnAccent: Color @Composable get() = LocalWeigeColors.current.onAccent
    val Text: Color @Composable get() = LocalWeigeColors.current.text
    val Text2: Color @Composable get() = LocalWeigeColors.current.text2
    val Text3: Color @Composable get() = LocalWeigeColors.current.text3
    val Ok: Color @Composable get() = LocalWeigeColors.current.ok
}

private val AppTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        letterSpacing = 0.5.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 11.sp,
        letterSpacing = 1.2.sp,
    ),
)

@Composable
fun WeigeTheme(dark: Boolean = false, content: @Composable () -> Unit) {
    val c = if (dark) DarkColors else LightColors

    val scheme = if (dark) {
        darkColorScheme(
            primary = c.accent,
            onPrimary = c.onAccent,
            secondary = c.accent,
            background = c.bg,
            onBackground = c.text,
            surface = c.panel,
            onSurface = c.text,
            surfaceVariant = c.panel2,
            onSurfaceVariant = c.text2,
            outline = c.line,
            error = Color(0xFFE05C5C),
        )
    } else {
        lightColorScheme(
            primary = c.accent,
            onPrimary = c.onAccent,
            secondary = c.accent,
            background = c.bg,
            onBackground = c.text,
            surface = c.panel,
            onSurface = c.text,
            surfaceVariant = c.panel2,
            onSurfaceVariant = c.text2,
            outline = c.line,
            error = Color(0xFFC0392B),
        )
    }

    CompositionLocalProvider(LocalWeigeColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}
