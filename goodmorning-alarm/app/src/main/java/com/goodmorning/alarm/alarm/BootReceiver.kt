package com.goodmorning.alarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.goodmorning.alarm.data.prefs.SettingsRepository
import com.goodmorning.alarm.sync.SyncScheduler
import com.goodmorning.alarm.util.AppLogger
import com.goodmorning.alarm.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 重启/时间变化恢复（P0-1）：
 * BOOT_COMPLETED / QUICKBOOT_POWERON → 读设置，若闹钟开启则重注册下一次每日闹钟 + 补调度同步；
 * TIME_SET → 系统时间被修改，同样重算闹钟时刻。
 *
 * DataStore 读取为挂起操作，使用 goAsync + 协程在 10s 广播时限内完成。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val valid = action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_TIME_CHANGED
        if (!valid) return

        AppLogger.i(TAG, "收到 $action，开始恢复闹钟与同步调度")
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val settings = SettingsRepository(appContext).current()
                if (settings.alarmEnabled) {
                    val scheduler = AlarmScheduler(appContext)
                    val scheduled = scheduler.scheduleNextDaily(
                        settings.alarmHour, settings.alarmMinute
                    )
                    if (!scheduled) {
                        AppLogger.w(TAG, "恢复闹钟时无精确闹钟权限，已降级注册")
                    }
                } else {
                    AppLogger.i(TAG, "闹钟未开启，跳过重注册")
                }
                SyncScheduler.scheduleNext(appContext)
            } catch (e: Exception) {
                AppLogger.e(TAG, "重启恢复失败", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = Constants.TAG_PREFIX + "BootRecv"
    }
}
