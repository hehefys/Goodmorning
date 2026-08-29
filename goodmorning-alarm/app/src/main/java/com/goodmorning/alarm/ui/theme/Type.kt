package com.goodmorning.alarm.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字体排印 token（DESIGN-V2 §1.2）：
 * - 大号数字一律 fontFeatureSettings = "tnum"（tabular-nums）防止跳动；
 * - [CountdownStyle] / [RingClockStyle] / [AlarmTimeStyle] / [SectionTitleStyle]
 *   为屏幕直接引用的具名 token，其余在 [AppTypography] 上覆写默认值。
 */

/** 主页倒计时（进度环内），tabular-nums 防跳动 */
val CountdownStyle = TextStyle(
    fontWeight = FontWeight.W600,
    fontSize = 56.sp,
    lineHeight = 64.sp,
    letterSpacing = (-1).sp,
    fontFeatureSettings = "tnum"
)

/** 响铃页大字时钟 */
val RingClockStyle = TextStyle(
    fontWeight = FontWeight.W600,
    fontSize = 76.sp,
    lineHeight = 84.sp,
    letterSpacing = (-2).sp,
    fontFeatureSettings = "tnum"
)

/** 主页闹钟时间 HH:mm */
val AlarmTimeStyle = TextStyle(
    fontWeight = FontWeight.SemiBold,
    fontSize = 26.sp,
    fontFeatureSettings = "tnum"
)

/** 卡片组标题 */
val SectionTitleStyle = TextStyle(
    fontWeight = FontWeight.W600,
    fontSize = 16.sp,
    lineHeight = 24.sp
)

/** Material3 默认 Typography 覆写（bodyMedium 14sp / labelLarge 14sp W600 / titleLarge 22sp） */
val AppTypography = Typography(
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.W600,
        fontSize = 14.sp,
        lineHeight = 20.sp
    )
)
