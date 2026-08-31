package com.goodmorning.alarm.alarm

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import com.goodmorning.alarm.util.AppLogger
import com.goodmorning.alarm.util.Constants

/**
 * 到点去重守卫：保证同一场闹钟只响一次，解锁/重进应用后不会重复、延迟或叠加播放。
 *
 * 背景：闹钟被系统（Doze/App Standby/厂商省电策略）推迟投递时，可能出现
 * 「原始到点 + 推迟到点」两次投递；进程被杀后 PendingIntent 再次投递也会重复。
 * 判重依据：以「计划触发时刻」[Constants.EXTRA_TRIGGER_AT] 为身份，
 * 与上一次已处理的时刻差值在 [Constants.RING_DEDUPE_WINDOW_MS] 内即视为同一场，直接丢弃。
 *
 * 存储：使用设备保护存储（Device Protected Storage），
 * 保证「重启后首次解锁前」闹钟也能正常判重（普通 SharedPreferences 此时不可用）。
 * 存储不可用时一律放行（宁可响，不可哑火 —— 与四级兜底同原则）。
 */
object RingGuard {

    private const val PREFS_NAME = "ring_guard"
    private const val KEY_LAST_TRIGGER_AT = "last_trigger_at"

    /**
     * 本次到点是否应当起播。
     * @param triggerAt 计划触发时刻（手动触发传当前时刻，由调用方提前置 force=true 跳过判重）
     */
    fun shouldHandle(context: Context, triggerAt: Long): Boolean {
        val prefs = prefs(context) ?: return true
        val last = runCatching { prefs.getLong(KEY_LAST_TRIGGER_AT, Long.MIN_VALUE) }
            .getOrNull() ?: return true
        if (last == Long.MIN_VALUE) return true
        val isDuplicate = kotlin.math.abs(triggerAt - last) < Constants.RING_DEDUPE_WINDOW_MS
        if (isDuplicate) {
            AppLogger.w(
                TAG,
                "重复到点已忽略：本次=$triggerAt，上次=$last（差值 ${triggerAt - last}ms）"
            )
        }
        return !isDuplicate
    }

    /** 标记本次到点已被处理（在真正起播前调用） */
    fun markHandled(context: Context, triggerAt: Long) {
        val prefs = prefs(context) ?: return
        runCatching { prefs.edit().putLong(KEY_LAST_TRIGGER_AT, triggerAt).apply() }
            .onFailure { AppLogger.w(TAG, "记录到点时刻失败", it) }
    }

    /** 手动触发（主页测试键）后刷新水位，避免测试用的「当前时刻」干扰后续真实到点判重 */
    fun reset(context: Context) {
        val prefs = prefs(context) ?: return
        runCatching { prefs.edit().remove(KEY_LAST_TRIGGER_AT).apply() }
            .onFailure { AppLogger.w(TAG, "重置到点水位失败", it) }
    }

    private fun prefs(context: Context): SharedPreferences? =
        runCatching {
            val deviceProtected =
                ContextCompat.createDeviceProtectedStorageContext(context) ?: context
            deviceProtected.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }.onFailure { AppLogger.w(TAG, "去重存储不可用，按放行处理", it) }.getOrNull()

    private const val TAG = Constants.TAG_PREFIX + "RingGuard"
}
