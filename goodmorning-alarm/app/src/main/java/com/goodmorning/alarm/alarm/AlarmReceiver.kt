package com.goodmorning.alarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.goodmorning.alarm.playback.AlarmService
import com.goodmorning.alarm.util.AppLogger
import com.goodmorning.alarm.util.Constants

/**
 * 到点接收器：精确闹钟触发后启动响铃前台服务。
 *
 * Android 12+ 对后台启动前台服务有例外：由精确闹钟触发的 BroadcastReceiver
 * 允许 startForegroundService（alarm 触发属于豁免场景）。
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Constants.ACTION_RING) return
        AppLogger.i(TAG, "闹钟到点，启动响铃服务")
        // F1 双保险：先补发高优先级兜底通知（fullScreenIntent 同构，点按可进响铃页）。
        // 即使前台服务被系统拦截（Android 14+ 后台启动限制等），用户仍能看到/点进响铃页。
        AlarmService.notifyFallback(context)
        // 启动前台服务；失败仅记录（兜底通知仍在，服务启动成功则由其无缝接管）
        try {
            AlarmService.start(context, Constants.ACTION_RING)
        } catch (e: Exception) {
            AppLogger.e(TAG, "启动响铃前台服务失败，已依赖兜底通知", e)
        }
    }

    private companion object {
        const val TAG = Constants.TAG_PREFIX + "AlarmRecv"
    }
}
