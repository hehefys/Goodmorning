package com.goodmorning.alarm.ui.theme

import androidx.compose.ui.graphics.Color

// ===== 浅色（清晨场景：主页 / 设置页 / 说明页 / 引导页）=====
// Sunrise 命名保留（DESIGN-V2 §1.1）
val Sunrise50 = Color(0xFFFFF8F0)
val Sunrise100 = Color(0xFFFFECDC)
val Sunrise300 = Color(0xFFFFC28A)
val Sunrise500 = Color(0xFFFB8C00)
val Sunrise700 = Color(0xFFE65100)
val Dawn40 = Color(0xFF7A4B00)
val Dawn80 = Color(0xFFFFB95C)

// V2 新增：浅色场景补充 token
/** 卡片 surface（比背景略亮一档，制造层次） */
val SunriseSurface = Color(0xFFFFFDF8)
/** onSurface：比旧 Night900 更暖的黑（正文） */
val Ink900 = Color(0xFF2B2115)
/** onSurfaceVariant：次要文字 */
val Ink60 = Color(0xFF6E6257)
/** 权限警示卡容器（替代旧红 0xFFB71C1C.copy(alpha=0.10f)） */
val WarnContainer = Color(0xFFFFE5DC)
/** 权限警示卡文字/图标 */
val OnWarnContainer = Color(0xFF8C2B00)
/** 同步成功徽章 */
val Success = Color(0xFF2E7D32)
/** 同步失败徽章（暖调红，非纯红） */
val ErrorBadge = Color(0xFFB3402E)

// ===== 深色（黎明场景，仅响铃页使用，强制 dark = true）=====
/** 响铃页背景渐变顶部（深蓝夜空） */
val NightSkyTop = Color(0xFF0D1B2A)
/** 响铃页背景渐变底部 */
val NightSkyBottom = Color(0xFF1B263B)
/** 底部日出光晕核心色 */
val GlowAmber = Color(0xFFFF8C42)
/** 光晕外圈色 */
val GlowAmberSoft = Color(0xFFFFB95C)
/** 毛玻璃卡片基底（以 alpha 使用） */
val FrostWhite = Color(0xFFFFFFFF)
/** 深色 onSurface（冷白，区别于旧暖白） */
val MoonFrost = Color(0xFFF2F5FA)
/** 深色次要文字 */
val MoonMist = Color(0xFFA9B4C2)
/** 响铃页强调（来源标签/渐变图标） */
val DawnAccent = Color(0xFFFFB95C)
/** 「打开抖音」按钮（降饱和融入夜空） */
val DouyinBlue = Color(0xFF7FB5E8)
