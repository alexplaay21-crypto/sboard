package com.sboard.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sboard.app.core.settings.AppSettings

/** sBoard palette: graphite / charcoal surfaces with a muted gold accent. */
@Immutable
data class Palette(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val surfaceHighest: Color,
    val outline: Color,
    val text: Color,
    val textMuted: Color,
    val textFaint: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val danger: Color,
    val success: Color,
    val warning: Color,
    val navBar: Color,
)

val DarkPalette = Palette(
    isDark = true,
    background = Color(0xFF111214),
    surface = Color(0xFF1A1B1E),
    surfaceHigh = Color(0xFF222327),
    surfaceHighest = Color(0xFF2B2D31),
    outline = Color(0xFF34363B),
    text = Color(0xFFF3F2EE),
    textMuted = Color(0xFF9C9EA4),
    textFaint = Color(0xFF6C6E74),
    accent = Color(0xFFD5B94B),
    onAccent = Color(0xFF1B1708),
    accentSoft = Color(0xFF2E2A1A),
    danger = Color(0xFFFF7A70),
    success = Color(0xFF7FD19B),
    warning = Color(0xFFF2B65A),
    navBar = Color(0xFF1C1D20),
)

val LightPalette = Palette(
    isDark = false,
    background = Color(0xFFF3F3F1),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFF0EFEC),
    surfaceHighest = Color(0xFFE6E5E1),
    outline = Color(0xFFE0DFDA),
    text = Color(0xFF17181A),
    textMuted = Color(0xFF666870),
    textFaint = Color(0xFF9A9CA2),
    accent = Color(0xFFA58A27),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFF3EBD0),
    danger = Color(0xFFC8433A),
    success = Color(0xFF2E8B57),
    warning = Color(0xFFB7791F),
    navBar = Color(0xFFFFFFFF),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

object SBoardShapes {
    val small = RoundedCornerShape(12.dp)
    val medium = RoundedCornerShape(14.dp)
    val large = RoundedCornerShape(18.dp)
    val pill = RoundedCornerShape(50)
}

private fun scheme(p: Palette): ColorScheme = if (p.isDark) darkColorScheme(
    primary = p.accent, onPrimary = p.onAccent, primaryContainer = p.accentSoft, onPrimaryContainer = p.accent,
    secondary = p.accent, onSecondary = p.onAccent, secondaryContainer = p.surfaceHighest, onSecondaryContainer = p.text,
    background = p.background, onBackground = p.text, surface = p.surface, onSurface = p.text,
    surfaceVariant = p.surfaceHigh, onSurfaceVariant = p.textMuted, surfaceContainer = p.surface,
    surfaceContainerHigh = p.surfaceHigh, surfaceContainerHighest = p.surfaceHighest, surfaceContainerLow = p.surface,
    surfaceContainerLowest = p.background, outline = p.outline, outlineVariant = p.outline, error = p.danger,
    onError = Color.Black, inverseSurface = p.text, inverseOnSurface = p.background,
) else lightColorScheme(
    primary = p.accent, onPrimary = p.onAccent, primaryContainer = p.accentSoft, onPrimaryContainer = Color(0xFF5C4B10),
    secondary = p.accent, onSecondary = p.onAccent, secondaryContainer = p.surfaceHighest, onSecondaryContainer = p.text,
    background = p.background, onBackground = p.text, surface = p.surface, onSurface = p.text,
    surfaceVariant = p.surfaceHigh, onSurfaceVariant = p.textMuted, surfaceContainer = p.surface,
    surfaceContainerHigh = p.surfaceHigh, surfaceContainerHighest = p.surfaceHighest, surfaceContainerLow = p.surface,
    surfaceContainerLowest = p.background, outline = p.outline, outlineVariant = p.outline, error = p.danger,
    onError = Color.White, inverseSurface = p.text, inverseOnSurface = p.background,
)

private val base = Typography()
private val typography = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 21.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 19.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.Default, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.2.sp),
)

@Composable
fun isDarkTheme(): Boolean = when (AppSettings.themeMode.value) {
    AppSettings.THEME_DARK -> true
    AppSettings.THEME_LIGHT -> false
    else -> isSystemInDarkTheme()
}

@Composable
fun SBoardTheme(content: @Composable () -> Unit) {
    val dark = isDarkTheme()
    val palette = if (dark) DarkPalette else LightPalette
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(
            colorScheme = scheme(palette),
            typography = typography,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(10.dp), small = SBoardShapes.small,
                medium = SBoardShapes.medium, large = SBoardShapes.large, extraLarge = RoundedCornerShape(22.dp),
            ),
            content = content,
        )
    }
}

val palette: Palette @Composable get() = LocalPalette.current
