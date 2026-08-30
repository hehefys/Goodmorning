package com.goodmorning.alarm.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 时间工具：日期口径一律取设备本地时区自然日 yyyy-MM-dd（PRD §5.1，绝不用 UTC）。
 */
object TimeUtils {

    private val dateOnlyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val hourMinuteFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateTimeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    /** 本地时区自然日 yyyy-MM-dd */
    fun localDate(millis: Long = System.currentTimeMillis()): String =
        synchronized(dateOnlyFormat) { dateOnlyFormat.format(Date(millis)) }

    /** HH:mm */
    fun formatHm(millis: Long = System.currentTimeMillis()): String =
        synchronized(hourMinuteFormat) { hourMinuteFormat.format(Date(millis)) }

    /** MM-dd HH:mm（用于同步状态等简短展示） */
    fun formatShort(millis: Long = System.currentTimeMillis()): String =
        synchronized(dateTimeFormat) { dateTimeFormat.format(Date(millis)) }

    /**
     * 计算每日闹钟的下一次触发时刻（严格未来的最近一次）。
     * 若今天的 hour:minute 已过（含当前时刻），则返回明天同一时刻。
     */
    fun nextDailyAt(hour: Int, minute: Int, now: Long = System.currentTimeMillis()): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (calendar.timeInMillis <= now) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return calendar.timeInMillis
    }

    /**
     * 计算下一个同步时刻：05:30 与 21:00 中较近者（严格未来）。
     */
    fun nextSyncAt(now: Long = System.currentTimeMillis()): Long {
        val morning = nextDailyAt(Constants.SYNC_HOUR_MORNING, Constants.SYNC_MINUTE_MORNING, now)
        val evening = nextDailyAt(Constants.SYNC_HOUR_EVENING, Constants.SYNC_MINUTE_EVENING, now)
        return minOf(morning, evening)
    }

    /**
     * 倒计时格式化：超过一天显示“N天 HH:mm:ss”，否则“HH:mm:ss”。
     */
    fun formatCountdown(remainMillis: Long): String {
        if (remainMillis <= 0) return "00:00:00"
        val totalSeconds = remainMillis / 1000
        val days = totalSeconds / 86400
        val hours = (totalSeconds % 86400) / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val hms = String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        return if (days > 0) "${days}天 $hms" else hms
    }

    /**
     * 把剩余毫秒格式化为 HH:mm（用于“稍后提醒中，HH:mm 再次响铃”）。
     */
    fun formatCountdownHm(remainMillis: Long): String {
        if (remainMillis <= 0) return "00:00"
        val totalMinutes = remainMillis / 60000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return String.format(Locale.getDefault(), "%02d:%02d", hours, minutes)
    }
}
