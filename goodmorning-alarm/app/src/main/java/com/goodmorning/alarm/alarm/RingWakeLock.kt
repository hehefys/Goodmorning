package com.goodmorning.alarm.alarm

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import com.goodmorning.alarm.util.AppLogger
import com.goodmorning.alarm.util.Constants

/**
 * 响铃链路 CPU 保活锁（PARTIAL_WAKE_LOCK）。
 *
 * 为什么必须持有：
 * 闹钟在息屏/Doze 下触发时，系统只保证「投递」那一刻唤醒 CPU，
 * 从 BroadcastReceiver → startForegroundService → 服务 onCreate → 选片 → ExoPlayer 出声
 * 之间仍有数百毫秒到数秒的空档；若不持锁，设备可能在这段空档再次进入浅睡，
 * 表现为「闹钟到点但不出声，解锁后才响」。
 *
 * 生命周期：
 * - AlarmReceiver 到点即持锁（防止启动空档被睡）；
 * - AlarmService 起播后再持锁（覆盖播放全程，ExoPlayer 的 WAKE_MODE_LOCAL 仅覆盖播放态）；
 * - 交棒：服务持锁成功后，接收者释放自己的锁；
 * - 兜底：带超时获取（[TIMEOUT_MS]），任何路径遗漏释放都不会导致电量泄漏。
 */
object RingWakeLock {

    /** 兜底超时：即使某条路径忘了释放，10 分钟后系统自动回收 */
    private const val TIMEOUT_MS = 10 * 60 * 1000L

    @Volatile
    private var lock: PowerManager.WakeLock? = null

    /**
     * 获取（或续期）保活锁；非引用计数，重复调用只是续期，释放一次即放。
     * @param tag 持锁方标识，仅用于日志
     */
    @SuppressLint("WakelockTimeout") // 已通过 acquire(timeout) 提供兜底释放
    fun acquire(context: Context, tag: String) {
        val powerManager =
            context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val newLock = runCatching {
            powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "${Constants.TAG_PREFIX}Ring:$tag"
            )
        }.onFailure { AppLogger.w(TAG, "创建唤醒锁失败（$tag）", it) }.getOrNull() ?: return

        val acquired = runCatching {
            newLock.setReferenceCounted(false)
            newLock.acquire(TIMEOUT_MS)
        }.isSuccess
        if (!acquired) {
            AppLogger.w(TAG, "获取唤醒锁失败（$tag），继续依赖系统派发")
            return
        }
        // 交棒：同一时刻只保留最新一把锁
        lock?.let { previous -> runCatching { if (previous.isHeld) previous.release() } }
        lock = newLock
        AppLogger.i(TAG, "唤醒锁已持有（$tag）")
    }

    /** 释放保活锁（幂等，可重复调用） */
    fun release() {
        val current = lock ?: return
        lock = null
        runCatching { if (current.isHeld) current.release() }
            .onSuccess { AppLogger.i(TAG, "唤醒锁已释放") }
            .onFailure { AppLogger.w(TAG, "释放唤醒锁失败", it) }
    }

    private const val TAG = Constants.TAG_PREFIX + "WakeLock"
}
