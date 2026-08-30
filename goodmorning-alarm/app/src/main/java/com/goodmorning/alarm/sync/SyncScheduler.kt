package com.goodmorning.alarm.sync

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.goodmorning.alarm.util.AppLogger
import com.goodmorning.alarm.util.Constants
import com.goodmorning.alarm.util.TimeUtils
import java.util.concurrent.TimeUnit

/**
 * 同步调度器：OneTimeWorkRequest 自续期链。
 *
 * 不用 PeriodicWork 的原因（ARCHITECTURE.md §1.5）：
 * PeriodicWork 受最小间隔 15 分钟与 Doze 漂移影响，无法精确落在 05:30/21:00；
 * 每次跑完（含失败）调度“下一个 05:30/21:00 中较近者”，对 HyperOS 更友好。
 * BootReceiver、每次闹钟结束时也会调用 [scheduleNext] 补调度，形成多重冗余。
 */
object SyncScheduler {

    private const val WORK_NAME = "daily_video_sync"

    /**
     * 调度下一次定时同步（05:30 / 21:00 中较近的严格未来时刻）。
     * REPLACE 策略保证任意时刻重复调用都收敛到唯一一个待执行任务。
     */
    fun scheduleNext(context: Context) {
        val now = System.currentTimeMillis()
        val nextAt = TimeUtils.nextSyncAt(now)
        val delay = (nextAt - now).coerceAtLeast(MIN_DELAY_MS)
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
        AppLogger.i(
            TAG,
            "已调度下一次同步：${TimeUtils.formatShort(nextAt)}（${TimeUtils.formatCountdownHm(delay)} 后）"
        )
    }

    /**
     * 立即触发一次同步（设置页“立即同步”/保存 RSSHub 地址后校验连通性）。
     * 复用同一 unique name 并 REPLACE，避免与定时链并存两个任务。
     */
    fun syncNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
        AppLogger.i(TAG, "手动触发立即同步")
    }

    private const val TAG = Constants.TAG_PREFIX + "SyncSched"
    private const val MIN_DELAY_MS = 60_000L
}
