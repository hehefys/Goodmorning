package com.goodmorning.alarm.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * 动效 token（DESIGN-V2 §1.4）。
 *
 * 硬性约束：BOM 2024.09.03 无 MaterialExpressiveTheme / MotionScheme 稳定 API，
 * 「Expressive 感」通过 spring 动效 token + 大圆角 + 渐变/光晕 + 大号等宽数字实现，
 * 全部基于 androidx.compose.animation.* 稳定 API。
 */
object Motion {

    /** 位置/尺寸/滑块位移：带轻微回弹 */
    val springPosition: SpringSpec<Float> = spring(
        dampingRatio = 0.72f,
        stiffness = Spring.StiffnessMediumLow
    )

    /** 进度环/开关等需要干脆到位的状态（无回弹） */
    val springSnap: SpringSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 800f
    )

    /** 所有颜色变化（animateColorAsState） */
    val tweenColor: TweenSpec<androidx.compose.ui.graphics.Color> =
        tween(durationMillis = 250, easing = FastOutSlowInEasing)

    /** 透明度淡入淡出（animateFloatAsState / AnimatedVisibility） */
    val tweenFade: TweenSpec<Float> =
        tween(durationMillis = 200, easing = LinearOutSlowInEasing)

    /** 响铃页日出光晕 alpha 平滑跟随（Animatable.animateTo 用） */
    val glowSpec: TweenSpec<Float> =
        tween(durationMillis = 400, easing = LinearEasing)

    // ---- 页面转场（AppNavHost 统一注入，不要使用弹簧做转场，会来回晃）----

    /** 前进：slideInHorizontally 位移时长 */
    const val NAV_ENTER_SLIDE_MS = 260

    /** 前进：fadeIn 时长 */
    const val NAV_ENTER_FADE_MS = 220

    /** 退出：slideOutHorizontally 位移时长 */
    const val NAV_EXIT_SLIDE_MS = 220

    /** 退出：fadeOut 时长 */
    const val NAV_EXIT_FADE_MS = 180
}
