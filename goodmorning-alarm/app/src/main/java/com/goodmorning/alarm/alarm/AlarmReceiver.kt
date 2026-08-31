package com.goodmorning.alarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.goodmorning.alarm.playback.AlarmService
import com.goodmorning.alarm.util.AppLogger
import com.goodmorning.alarm.util.Constants

/**
 * 到点接收器：精确闹钟触发后启动响铃前台服务。
 *
 * 息屏可靠性要点：
 * 1. 先持 PARTIAL_WAKE_LOCK —— 覆盖「到点 → 服务出声」这段空档，避免设备再次浅睡导致哑火；
 * 2. 先补发高优先级兜底通知（服务起来后由同一 ID 无缝接管）；
 * 3. 启动前台服务；精确闹钟不受 Android 12+ 后台启动前台服务限制，
 *    但厂商 ROM 可能在瞬时状态下拒绝，故失败后用 goAsync 保活并延迟重试一次。
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Constants.ACTION_RING) return
        val appContext = context.applicationContext
        AppLogger.i(TAG, "闹钟到点，启动响铃服务")

        // ① 到点去重（主判重点）：同一场闹钟的重复/延迟投递直接丢弃，
        //    根本不启动服务，自然也不会出现重复或叠加播放
        val triggerAt = intent.getLongExtra(Constants.EXTRA_TRIGGER_AT, Long.MIN_VALUE)
        if (triggerAt != Long.MIN_VALUE && !RingGuard.shouldHandle(appContext, triggerAt)) {
            AppLogger.w(TAG, "重复到点已忽略（不启动服务，避免重复/叠加播放）")
            return
        }
        if (triggerAt != Long.MIN_VALUE) RingGuard.markHandled(appContext, triggerAt)

        // ② 持锁：保证启动空档 CPU 不睡
        RingWakeLock.acquire(appContext, "receiver")

        // ③ 兜底通知：即使前台服务被拦截，用户仍可见可控（停止/贪睡）
        AlarmService.notifyFallback(appContext)

        // ③ 启动前台服务（失败则延迟重试一次）
        if (tryStartService(appContext)) {
            // 服务已在 onCreate/起播前自行持锁，交棒完成
            RingWakeLock.release()
            return
        }
        val pendingResult = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                if (!tryStartService(appContext)) {
                    AppLogger.e(TAG, "二次启动仍失败，已依赖兜底通知")
                }
            } finally {
                RingWakeLock.release()
                pendingResult.finish()
            }
        }, RETRY_DELAY_MS)
    }

    /** 尝试启动响铃服务；返回是否调用成功（不代表音频已出声） */
    private fun tryStartService(context: Context): Boolean =
        runCatching {
            AlarmService.start(context, Constants.ACTION_RING)
            true
        }.onFailure { AppLogger.e(TAG, "启动响铃前台服务失败", it) }.getOrDefault(false)

    private companion object {
        const val TAG = Constants.TAG_PREFIX + "AlarmRecv"

        /** 启动失败后的重试间隔：给系统一点时间解除瞬时限制 */
        const val RETRY_DELAY_MS = 800L
    }
}
