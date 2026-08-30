package com.goodmorning.alarm.util

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 权限检测与系统设置页跳转封装（含 HyperOS/MIUI 自启动页的最佳努力直达）。
 */
object Permissions {

    /** 精确闹钟权限（Android 12 以下系统默认允许） */
    fun canScheduleExactAlarms(context: Context): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    /** 通知权限（Android 13 以下系统默认允许） */
    fun areNotificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** 省电策略是否为“无限制”（未处于电池优化白名单则视为受限） */
    fun isBatteryUnrestricted(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 精确闹钟授权页（Android 12+），失败回退应用详情页 */
    fun exactAlarmSettingsIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:${context.packageName}")
            )
            if (intent.resolveActivity(context.packageManager) != null) return intent
        }
        return appDetailsIntent(context)
    }

    /** 应用通知设置页 */
    fun notificationSettingsIntent(context: Context): Intent {
        val intent = Intent()
        intent.action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        return intent
    }

    /** 电池优化设置列表页（无需额外权限） */
    fun batteryOptimizationSettingsIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /** 应用详情页（各类跳转的最终兜底） */
    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    /**
     * HyperOS/MIUI 自启动管理页最佳努力直达：
     * 无公开 API，使用已知的 MIUI 安全中心组件；失败返回 null 由调用方回退应用详情页。
     */
    fun miuiAutoStartIntent(context: Context): Intent? {
        val candidates = listOf(
            // MIUI/HyperOS 常见自启动管理页
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            ),
            // 部分 MIUI 版本的另一路径
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.powercenter.PowerSettings"
            )
        )
        for (component in candidates) {
            val intent = Intent().setComponent(component)
            if (intent.resolveActivity(context.packageManager) != null) return intent
        }
        return null
    }

    /** 运行时通知权限是否已授予（供 rememberLauncher 之外的状态判断） */
    fun hasNotificationRuntimePermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    /**
     * 全屏显示通知权限（Android 14+；锁屏响铃页自动弹出的前提）。
     * Android 14 以下默认允许；检测异常/未知状态时按“已授权”降级，避免误拦用户。
     *
     * 主路径使用官方 API [NotificationManager.canUseFullScreenIntent]（API 34 引入）；
     * 降级路径用 AppOpsManager 查询 OP_USE_FULL_SCREEN_INTENT。
     */
    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (notificationManager != null) {
            val granted =
                runCatching { notificationManager.canUseFullScreenIntent() }.getOrNull()
            if (granted != null) return granted
        }
        // 降级：AppOpsManager 查询 OP_USE_FULL_SCREEN_INTENT；未知状态按已授权处理
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return true
        return runCatching {
            val mode = appOps.unsafeCheckOpNoThrow(
                // OPSTR_USE_FULL_SCREEN_INTENT 为 @hide 常量，public SDK 不可见，直接使用系统 op 字符串
                "android:use_full_screen_intent",
                Process.myUid(),
                context.packageName
            )
            mode != AppOpsManager.MODE_IGNORED && mode != AppOpsManager.MODE_ERRORED
        }.getOrDefault(true)
    }

    /**
     * 全屏显示通知授权页（Android 14+）。
     * @return 可用的授权页意图；Android 14 以下或系统无此页面时返回 null（调用方隐藏该引导步骤）
     */
    fun fullScreenIntentSettingsIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
            Uri.parse("package:${context.packageName}")
        )
        return if (intent.resolveActivity(context.packageManager) != null) intent else null
    }
}
