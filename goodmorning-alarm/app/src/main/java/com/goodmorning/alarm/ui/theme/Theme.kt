package com.goodmorning.alarm.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/** 浅色色板映射（DESIGN-V2 §1.1 清晨场景） */
private val LightColors = lightColorScheme(
    primary = Sunrise700,
    onPrimary = Sunrise50,
    primaryContainer = Sunrise100,
    onPrimaryContainer = Dawn40,
    secondary = Sunrise500,
    onSecondary = Sunrise50,
    background = Sunrise50,
    onBackground = Ink900,
    surface = SunriseSurface,
    onSurface = Ink900,
    surfaceVariant = Sunrise100,
    onSurfaceVariant = Ink60,
    error = ErrorBadge,
    errorContainer = WarnContainer,
    onErrorContainer = OnWarnContainer
)

/** 深色色板映射（仅供响铃页，黎明场景） */
private val DarkColors = darkColorScheme(
    primary = DawnAccent,
    onPrimary = NightSkyTop,
    primaryContainer = Sunrise700,
    onPrimaryContainer = Sunrise50,
    secondary = GlowAmber,
    onSecondary = NightSkyTop,
    background = NightSkyBottom,
    onBackground = MoonFrost,
    surface = NightSkyBottom,
    onSurface = MoonFrost,
    surfaceVariant = NightSkyBottom,
    onSurfaceVariant = MoonMist,
    error = ErrorBadge
)

/** 形状 token 注入 MaterialTheme（组件也可直接引用 ShapeLarge 等常量） */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = ShapeSmall,
    medium = ShapeMedium,
    large = ShapeLarge,
    extraLarge = ShapeLarge
)

/**
 * Material3 深浅色主题（晨曦暖色调，不使用动态取色以保证品牌一致性）。
 * 响铃页强制 darkTheme = true（黎明场景）。
 */
@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
