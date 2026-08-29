package com.goodmorning.alarm.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.goodmorning.alarm.R
import com.goodmorning.alarm.util.AppLogger
import com.goodmorning.alarm.util.Constants

/**
 * WorkManager Worker：执行一次同步，结束后：
 * 1. 调 [SyncScheduler.scheduleNext] 续期下一次 05:30/21:00（自续期链）；
 * 2. 发送低打扰结果通知（sync_channel）。
 *
 * 无论同步成败都返回 success 并续期——失败静默等待下一周期兜底（P0-5），
 * 避免 WorkManager 的指数退避在固定时刻语义下造成调度漂移。
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val result = SyncEngine(applicationContext).sync()

        // 续期下一次定时同步（失败也要续期，形成每日两次的固定节奏）
        SyncScheduler.scheduleNext(applicationContext)

        // 结果通知（低打扰；未授予通知权限则静默跳过）
        notifyResult(result)
        return Result.success()
    }

    private fun notifyResult(result: SyncEngine.SyncResult) {
        val context = applicationContext
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    Constants.CHANNEL_SYNC,
                    context.getString(R.string.notif_channel_sync_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.notif_channel_sync_desc)
                }
            )
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        if (context.packageManager.checkPermission(
                android.Manifest.permission.POST_NOTIFICATIONS, context.packageName
            ) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return

        val title = if (result.ok) "视频同步完成" else "视频同步失败"
        val notification = NotificationCompat.Builder(context, Constants.CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(result.msg)
            .setAutoCancel(true)
            .setSilent(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_SYNC, notification)
        }.onFailure { AppLogger.w(TAG, "发送同步通知失败", it as? Exception) }
    }

    private companion object {
        const val TAG = Constants.TAG_PREFIX + "Worker"
        const val NOTIF_ID_SYNC = 2002
    }
}
