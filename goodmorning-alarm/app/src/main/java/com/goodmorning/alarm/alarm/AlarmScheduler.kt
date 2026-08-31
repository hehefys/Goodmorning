package com.goodmorning.alarm.alarm

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.goodmorning.alarm.MainActivity
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

    /**
     * 精确闹钟能力（Android 12 以下默认允许）。
     *
     * 判定时同时认可 USE_EXACT_ALARM：Android 14 起 SCHEDULE_EXACT_ALARM 对
     * targetSdk>=33 的新装应用默认拒绝，而闹钟类应用声明的 USE_EXACT_ALARM 是安装即授予、
     * 用户不可撤销（官方明确「日历和闹钟应用应声明 USE_EXACT_ALARM」）。
     * 若只看 canScheduleExactAlarms()，在部分 ROM 上会误判为无权限而降级成非精确闹钟，
     * 非精确闹钟在 Doze 下会被推迟到维护窗口 → 表现为「息屏不响、解锁才响」。
     */
    fun canScheduleExact(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.USE_EXACT_ALARM
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        return alarmManager?.canScheduleExactAlarms() ?: false
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
        // PendingIntent 匹配不比较 extras，取消时用占位触发时刻即可命中
        alarmManager?.cancel(pendingIntent(Constants.REQUEST_CODE_DAILY, 0L))
        alarmManager?.cancel(pendingIntent(Constants.REQUEST_CODE_SNOOZE, 0L))
        _snoozeUntilMillis.value = null
        AppLogger.i(TAG, "已取消全部闹钟")
    }

    /**
     * 仅取消贪睡（贪睡到点触发后调用，不影响每日闹钟）。
     * 无条件清除内存态：取消防贪睡闹钟即视为不再处于“稍后提醒”状态，
     * 避免提前取消（如每日闹钟先响、或用户手动取消）时主页残留“稍后提醒中 HH:mm”。
     */
    fun cancelSnoozeOnly() {
        alarmManager?.cancel(pendingIntent(Constants.REQUEST_CODE_SNOOZE, 0L))
        _snoozeUntilMillis.value = null
    }

    // ---- 内部实现 ----

    /**
     * 注册闹钟：精确优先，逐级降级，任何一级成功即返回。
     *
     * 优先级（官方行为依据）：
     * 1. setAlarmClock —— 「系统永不调整其投递时间，并在必要时退出低功耗模式以送达」，
     *    是闹钟类应用的正解，同时豁免后台启动前台服务限制；
     * 2. setExactAndAllowWhileIdle —— 近似精确且可在 Doze 下触发；
     * 3. setWindow —— 无精确权限时的窗口闹钟（窗口下限 10 分钟，系统可微调）；
     * 4. setAndAllowWhileIdle —— 最后兜底，Doze 下会被推迟到维护窗口（可能延迟，但不丢）。
     */
    private fun scheduleAt(triggerAt: Long, requestCode: Int, tag: String): Boolean {
        val manager = alarmManager
            ?: run {
                AppLogger.e(TAG, "AlarmManager 不可用，注册失败")
                return false
            }
        val pendingIntent = pendingIntent(requestCode, triggerAt)
        val hasExact = canScheduleExact()

        if (hasExact) {
            // ① setAlarmClock（最可靠，息屏/Doze 下准时送达并允许后台起前台服务）
            runCatching {
                manager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(triggerAt, alarmInfoPendingIntent()),
                    pendingIntent
                )
            }.onSuccess {
                AppLogger.i(
                    TAG,
                    "${tag}已注册（setAlarmClock，精确）：${TimeUtils.formatShort(triggerAt)}"
                )
                return true
            }.onFailure { e ->
                AppLogger.w(TAG, "${tag} setAlarmClock 失败，降级 setExactAndAllowWhileIdle", e)
            }
            // ② setExactAndAllowWhileIdle
            runCatching {
                manager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent
                )
            }.onSuccess {
                AppLogger.i(
                    TAG,
                    "${tag}已注册（setExactAndAllowWhileIdle，精确）：" +
                        TimeUtils.formatShort(triggerAt)
                )
                return true
            }.onFailure { e ->
                AppLogger.w(TAG, "${tag} setExactAndAllowWhileIdle 失败，继续降级", e)
            }
        } else {
            AppLogger.w(TAG, "${tag}缺少精确闹钟权限，降级为窗口/非精确闹钟（可能延迟）")
        }

        // ③ setWindow（窗口下限 10 分钟）
        runCatching {
            manager.setWindow(
                AlarmManager.RTC_WAKEUP, triggerAt, WINDOW_FALLBACK_MS, pendingIntent
            )
        }.onSuccess {
            AppLogger.w(
                TAG,
                "${tag}已注册（setWindow 非精确）：${TimeUtils.formatShort(triggerAt)}"
            )
            return false
        }.onFailure { e ->
            AppLogger.w(TAG, "${tag} setWindow 失败，降级 setAndAllowWhileIdle", e)
        }

        // ④ 最后兜底
        return runCatching {
            manager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent
            )
            AppLogger.w(
                TAG,
                "${tag}已注册（setAndAllowWhileIdle 非精确）：${TimeUtils.formatShort(triggerAt)}"
            )
            false
        }.onFailure { e -> AppLogger.e(TAG, "${tag}注册失败", e) }.getOrDefault(false)
    }

    /** 到点广播意图：携带计划触发时刻，供 RingGuard 识别重复投递 */
    private fun pendingIntent(requestCode: Int, triggerAt: Long): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = Constants.ACTION_RING
            putExtra(Constants.EXTRA_TRIGGER_AT, triggerAt)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** setAlarmClock 的「展示闹钟」意图：状态栏闹钟图标被点击时打开主页 */
    private fun alarmInfoPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            Constants.REQUEST_CODE_ALARM_INFO,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val TAG = Constants.TAG_PREFIX + "AlarmSched"

        /** 无精确权限时 setWindow 的窗口长度（系统下限 10 分钟） */
        private const val WINDOW_FALLBACK_MS = 10 * 60_000L

        /**
         * 贪睡到点时刻（epoch ms），null = 无待触发的贪睡。
         * 进程内存态即可满足主页展示需求；重启后贪睡视为放弃（BootReceiver 只恢复每日闹钟）。
         */
        private val _snoozeUntilMillis = MutableStateFlow<Long?>(null)
        val snoozeUntilMillis: StateFlow<Long?> = _snoozeUntilMillis
    }
}
