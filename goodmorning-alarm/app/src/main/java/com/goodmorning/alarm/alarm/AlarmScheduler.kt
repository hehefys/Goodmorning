package com.goodmorning.alarm.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.goodmorning.alarm.util.AppLogger
import com.goodmorning.alarm.util.Constants
import com.goodmorning.alarm.util.TimeUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 精确闹钟注册/取消。
 *
 * 「每日重复」采用响完自续期模式（不用 setRepeating——Doze 下不精确）：
 * 每次响铃结束/贪睡注册时，由 AlarmService 计算明天同一时刻再次 setExact。
 * 重启由 BootReceiver 重注册。
 */
class AlarmScheduler(private val context: Context) {

    private val alarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    /** 精确闹钟权限（Android 12 以下默认允许） */
    fun canScheduleExact(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager?.canScheduleExactAlarms() ?: false
        } else {
            true
        }
    }

    /**
     * 注册下一次每日闹钟（严格未来的最近一次 hour:minute）。
     * @return true = 已用精确闹钟注册；false = 无精确权限，降级为非精确注册（仍会大致准点触发）
     */
    fun scheduleNextDaily(hour: Int, minute: Int): Boolean {
        val triggerAt = TimeUtils.nextDailyAt(hour, minute)
        return scheduleAt(
            triggerAt = triggerAt,
            requestCode = Constants.REQUEST_CODE_DAILY,
            tag = "每日闹钟"
        )
    }

    /**
     * 注册贪睡闹钟（minutes 分钟后一次性触发，与每日闹钟同走 ACTION_RING 链路）。
     * @return true = 已用精确闹钟注册
     */
    fun scheduleSnooze(minutes: Int): Boolean {
        val triggerAt = System.currentTimeMillis() + minutes * 60_000L
        val scheduled = scheduleAt(
            triggerAt = triggerAt,
            requestCode = Constants.REQUEST_CODE_SNOOZE,
            tag = "贪睡闹钟"
        )
        // 供主页展示“稍后提醒中，HH:mm 再次响铃”
        _snoozeUntilMillis.value = triggerAt
        return scheduled
    }

    /** 取消全部闹钟（每日 + 贪睡） */
    fun cancel() {
        alarmManager?.cancel(pendingIntent(Constants.REQUEST_CODE_DAILY))
        alarmManager?.cancel(pendingIntent(Constants.REQUEST_CODE_SNOOZE))
        _snoozeUntilMillis.value = null
        AppLogger.i(TAG, "已取消全部闹钟")
    }

    /**
     * 仅取消贪睡（贪睡到点触发后调用，不影响每日闹钟）。
     * 无条件清除内存态：取消防贪睡闹钟即视为不再处于“稍后提醒”状态，
     * 避免提前取消（如每日闹钟先响、或用户手动取消）时主页残留“稍后提醒中 HH:mm”。
     */
    fun cancelSnoozeOnly() {
        alarmManager?.cancel(pendingIntent(Constants.REQUEST_CODE_SNOOZE))
        _snoozeUntilMillis.value = null
    }

    // ---- 内部实现 ----

    private fun scheduleAt(triggerAt: Long, requestCode: Int, tag: String): Boolean {
        val manager = alarmManager
            ?: run {
                AppLogger.e(TAG, "AlarmManager 不可用，注册失败")
                return false
            }
        val pendingIntent = pendingIntent(requestCode)
        val hasExact = canScheduleExact()
        runCatching {
            if (hasExact) {
                manager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent
                )
            } else {
                // 无精确权限时降级为窗口闹钟，尽量保住响铃（同时 UI 引导用户授权）
                manager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent
                )
            }
        }.onFailure { e ->
            AppLogger.e(TAG, "${tag}注册失败", e)
            return false
        }
        AppLogger.i(
            TAG,
            "${tag}已注册：${TimeUtils.formatShort(triggerAt)}（精确=$hasExact）"
        )
        return hasExact
    }

    private fun pendingIntent(requestCode: Int): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = Constants.ACTION_RING
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val TAG = Constants.TAG_PREFIX + "AlarmSched"

        /**
         * 贪睡到点时刻（epoch ms），null = 无待触发的贪睡。
         * 进程内存态即可满足主页展示需求；重启后贪睡视为放弃（BootReceiver 只恢复每日闹钟）。
         */
        private val _snoozeUntilMillis = MutableStateFlow<Long?>(null)
        val snoozeUntilMillis: StateFlow<Long?> = _snoozeUntilMillis
    }
}
