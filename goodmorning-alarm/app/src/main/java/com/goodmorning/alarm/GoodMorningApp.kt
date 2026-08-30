package com.goodmorning.alarm

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.goodmorning.alarm.alarm.AlarmScheduler
import com.goodmorning.alarm.data.prefs.SettingsRepository
import com.goodmorning.alarm.sync.SyncScheduler
import com.goodmorning.alarm.util.AppLogger
import com.goodmorning.alarm.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口：
 * 1. 初始化本地文件日志；
 * 2. 创建通知渠道（alarm_channel 高优先级 / sync_channel 低打扰）；
 * 3. 启动自检：闹钟开启则重注册下一次闹钟（覆盖进程被杀后 PendingIntent 丢失的场景），
 *    并补调度同步 Worker（多重冗余的一环）。
 */
class GoodMorningApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        installCrashLogger()
        createNotificationChannels()
        startupSelfCheck()
    }

    /**
     * 全局崩溃落盘：任何未捕获异常先同步写入文件日志再交给系统默认处理。
     * 「进程还在但界面反复闪退」类问题（前台服务保活进程、Activity 层崩溃循环）
     * 此前无堆栈可查，落盘后从 filesDir/logs/ 即可定位。
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                AppLogger.eSync(
                    Constants.TAG_PREFIX + "Crash",
                    "未捕获异常 thread=${thread.name}",
                    throwable
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** 创建通知渠道（Android 8+ 必须；低于该版本直接忽略） */
    private fun createNotificationChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                Constants.CHANNEL_ALARM,
                getString(com.goodmorning.alarm.R.string.notif_channel_alarm_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(com.goodmorning.alarm.R.string.notif_channel_alarm_desc)
                // 响铃音频由 AlarmService 经 USAGE_ALARM 播放，渠道本身必须静音，
                // 否则通知音会叠加在闹钟音频之上（参考 ClockYou 的做法）
                setSound(null, null)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                Constants.CHANNEL_SYNC,
                getString(com.goodmorning.alarm.R.string.notif_channel_sync_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(com.goodmorning.alarm.R.string.notif_channel_sync_desc)
            }
        )
    }

    /**
     * 启动自检（T10 集成联调要求）：
     * - 每次进程启动都重算并注册下一次每日闹钟（AlarmManager 的 PendingIntent
     *   在进程被杀后依然有效，但重注册可修正因系统时间调整导致的漂移）；
     * - 补调度同步（REPLACE 策略幂等，不会重复堆积任务）。
     */
    private fun startupSelfCheck() {
        appScope.launch {
            try {
                val settings = SettingsRepository(this@GoodMorningApp).current()
                if (settings.alarmEnabled) {
                    val scheduler = AlarmScheduler(this@GoodMorningApp)
                    val exact = scheduler.scheduleNextDaily(
                        settings.alarmHour, settings.alarmMinute
                    )
                    if (!exact) {
                        AppLogger.w(TAG, "启动自检：无精确闹钟权限，已降级注册")
                    }
                }
                SyncScheduler.scheduleNext(this@GoodMorningApp)
                AppLogger.i(TAG, "启动自检完成")
            } catch (e: Exception) {
                AppLogger.e(TAG, "启动自检失败", e)
            }
        }
    }

    private companion object {
        const val TAG = Constants.TAG_PREFIX + "App"
    }
}
