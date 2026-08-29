package com.goodmorning.alarm.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.goodmorning.alarm.ui.guide.PermissionGuideScreen
import com.goodmorning.alarm.ui.guide.UsageGuideScreen
import com.goodmorning.alarm.ui.main.MainScreen
import com.goodmorning.alarm.ui.settings.SettingsScreen
import com.goodmorning.alarm.ui.theme.Motion

/** 路由常量（单一来源） */
object Routes {
    const val MAIN = "main"
    const val SETTINGS = "settings"
    const val PERMISSION_GUIDE = "permissionGuide"
    const val USAGE_GUIDE = "usageGuide"
}

/**
 * Compose 导航：主页 / 设置 / 权限引导 / 使用说明。
 * 统一页面转场（DESIGN-V2 §1.4）：前进从右轻推入 + 淡入，返回反向；
 * 使用 tween 而非弹簧（弹簧转场会来回晃）。
 */
@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = Routes.MAIN,
        enterTransition = {
            slideInHorizontally(
                animationSpec = tween(Motion.NAV_ENTER_SLIDE_MS, easing = FastOutSlowInEasing)
            ) { it / 8 } + fadeIn(tween(Motion.NAV_ENTER_FADE_MS))
        },
        exitTransition = {
            slideOutHorizontally(
                animationSpec = tween(Motion.NAV_EXIT_SLIDE_MS, easing = FastOutSlowInEasing)
            ) { -it / 12 } + fadeOut(tween(Motion.NAV_EXIT_FADE_MS))
        },
        popEnterTransition = {
            slideInHorizontally(
                animationSpec = tween(Motion.NAV_ENTER_SLIDE_MS, easing = FastOutSlowInEasing)
            ) { -it / 12 } + fadeIn(tween(Motion.NAV_ENTER_FADE_MS))
        },
        popExitTransition = {
            slideOutHorizontally(
                animationSpec = tween(Motion.NAV_EXIT_SLIDE_MS, easing = FastOutSlowInEasing)
            ) { it / 8 } + fadeOut(tween(Motion.NAV_EXIT_FADE_MS))
        }
    ) {
        composable(Routes.MAIN) {
            MainScreen(
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
                onNavigateToGuide = { navController.navigate(Routes.PERMISSION_GUIDE) }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToGuide = { navController.navigate(Routes.PERMISSION_GUIDE) },
                onNavigateToUsageGuide = { navController.navigate(Routes.USAGE_GUIDE) }
            )
        }
        composable(Routes.PERMISSION_GUIDE) {
            PermissionGuideScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(Routes.USAGE_GUIDE) {
            UsageGuideScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
