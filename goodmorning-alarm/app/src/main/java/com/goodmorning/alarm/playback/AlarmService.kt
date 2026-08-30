package com.goodmorning.alarm.playback

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.goodmorning.alarm.R
import com.goodmorning.alarm.alarm.AlarmScheduler
import com.goodmorning.alarm.alarm.SelectionPolicy
import com.goodmorning.alarm.data.prefs.SettingsRepository
import com.goodmorning.alarm.data.repo.VideoRepository
import com.goodmorning.alarm.sync.SyncEngine
import com.goodmorning.alarm.sync.SyncScheduler
import com.goodmorning.alarm.util.AppLogger
import com.goodmorning.alarm.util.Constants
import com.goodmorning.alarm.util.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 响铃前台服务（mediaPlayback 类型，无响铃页形态——通知即控制面板）。
 *
 * 到点链路：选片（缓存为空时现场限时同步一次）→ AlarmPlayer 立即出声 →
 * 高优先级 heads-up 通知（视频标题 + 停止/贪睡按钮）。
 *
 * 参考成熟实现（ClockYou AlarmService）：
 * - PRIORITY_MAX + CATEGORY_ALARM + FOREGROUND_SERVICE_IMMEDIATE 保证通知秒显；
 * - 通知渠道不设铃声，音频只由服务经 USAGE_ALARM 播放，避免双音源。
 *
 * 四级兜底（绝不哑火）：本地 mp4 → TYPE_ALARM 铃声 → TYPE_RINGTONE → ToneGenerator 蜂鸣。
 */
class AlarmService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val settingsRepository by lazy { SettingsRepository(this) }
    private val repository by lazy { VideoRepository(this) }
    private val alarmScheduler by lazy { AlarmScheduler(this) }
    private val selectionPolicy = SelectionPolicy()

    private lateinit var player: AlarmPlayer

    /** 响铃卫兵：双 ACTION_RING 连发（每日与贪睡同时到点）只选片一次 */
    private val ringingGuard = AtomicBoolean(false)

    /** ToneGenerator 第四级兜底（惰性创建，用完释放） */
    private var toneGenerator: ToneGenerator? = null
    private var toneJob: Job? = null

    /** 第三级铃声是否已尝试过：防"铃声失败→错误→再铃声"无限循环 */
    private var ringtoneAttempted = false

    /** 当前正在播放的本地文件路径（用于错误时清理坏文件） */
    private var currentPlayingPath: String? = null

    override fun onCreate() {
        super.onCreate()
        player = AlarmPlayer(this).apply {
            onError = { throwable -> onPlayerError(throwable) }
            onEnded = { handleStop() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()

        // START_NOT_STICKY 下系统重建传入 null intent：仅保活不重播
        if (intent == null) {
            AppLogger.w(TAG, "服务重建（null intent），不重播，直接停止")
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent.action) {
            Constants.ACTION_STOP -> handleStop()
            Constants.ACTION_SNOOZE -> handleSnooze()
            else -> {
                if (ringingGuard.compareAndSet(false, true)) {
                    selectAndPlay()
                } else {
                    AppLogger.i(TAG, "重复 ACTION_RING 到达，忽略（已在响铃）")
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopToneFallback()
        serviceScope.cancel()
        runCatching { player.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- 选片与起播 ----

    private fun selectAndPlay() {
        serviceScope.launch {
            try {
                val settings = settingsRepository.current()
                val today = TimeUtils.localDate()
                var videos = repository.playableVideos()

                // 缓存为空才现场补救（定时同步被系统杀掉的场景）：
                // 有缓存则零延迟直接播，同步交给既有的 05:30/21:00 链路更新明天。
                if (videos.isEmpty()) {
                    val synced = withTimeoutOrNull(RING_SYNC_TIMEOUT_MS) {
                        runCatching { SyncEngine(this@AlarmService).sync() }.getOrNull()
                    }
                    if (synced == null) {
                        AppLogger.w(TAG, "响铃现场同步未完成（≤${RING_SYNC_TIMEOUT_MS}ms），走兜底")
                    }
                    videos = repository.playableVideos()
                }

                val result = selectionPolicy.select(videos, today)
                val video = result.video
                val localPath = video?.localPath
                if (video != null && !localPath.isNullOrBlank() && File(localPath).isFile) {
                    currentPlayingPath = localPath
                    ringtoneAttempted = false
                    player.playFile(File(localPath), settings.volumeFadeEnabled)
                    updateNotificationContent(
                        title = video.title.ifBlank { getString(R.string.notif_ring_title) },
                        text = getString(R.string.ringing_publish_date_fmt, video.publishDate)
                    )
                    runCatching { repository.logPlayback(video.id, result.source.toLogValue()) }
                        .onFailure { AppLogger.w(TAG, "记录播放日志失败", it) }
                    AppLogger.i(TAG, "选片命中：${result.source} ${video.id}「${video.title}」")
                } else {
                    playFallback("无可用缓存视频（候选 ${videos.size} 条）")
                }
                alarmScheduler.cancelSnoozeOnly()
            } catch (e: Exception) {
                AppLogger.e(TAG, "选片/起播异常，进入兜底", e)
                playFallback("异常: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * 第三级兜底：系统默认闹钟铃声。
     * 已试过仍失败或无铃声 URI → 直接进第四级 ToneGenerator 蜂鸣。
     */
    private fun playFallback(reason: String) {
        val alarmUri: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val ringtoneUri = alarmUri ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        if (ringtoneUri == null || ringtoneAttempted) {
            playToneFallback(reason)
            return
        }
        ringtoneAttempted = true

        serviceScope.launch {
            try {
                val fade = settingsRepository.current().volumeFadeEnabled
                player.playUri(ringtoneUri, fade)
                runCatching { repository.logPlayback(null, Constants.SOURCE_FALLBACK) }
                    .onFailure { AppLogger.w(TAG, "记录兜底播放日志失败", it) }
                AppLogger.w(TAG, "兜底铃声已启用（$reason）→ $ringtoneUri")
            } catch (e: Exception) {
                AppLogger.e(TAG, "兜底铃声播放失败，进入蜂鸣兜底（$reason）", e)
                playToneFallback("兜底铃声异常: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** 第四级兜底：ToneGenerator 蜂鸣循环，绝不哑火的最后防线 */
    private fun playToneFallback(reason: String) {
        stopToneFallback()
        val generator = try {
            ToneGenerator(AudioManager.STREAM_ALARM, Constants.TONE_VOLUME)
        } catch (e: Exception) {
            AppLogger.e(TAG, "ToneGenerator 创建失败，响铃彻底失败（$reason）", e)
            handleStop()
            return
        }
        toneGenerator = generator
        toneJob = serviceScope.launch {
            while (isActive) {
                generator.startTone(ToneGenerator.TONE_PROP_BEEP, Constants.TONE_BEEP_DURATION_MS)
                delay(Constants.TONE_BEEP_PERIOD_MS)
            }
        }
        serviceScope.launch { runCatching { repository.logPlayback(null, Constants.SOURCE_FALLBACK) } }
        AppLogger.w(TAG, "第四级兜底 ToneGenerator 已启用（$reason）")
    }

    /** 播放器错误 → 兜底降级（文件损坏/解码失败等），坏文件顺手清掉 */
    private fun onPlayerError(throwable: Throwable) {
        if (!ringingGuard.get()) return
        val badPath = currentPlayingPath
        if (badPath != null) {
            serviceScope.launch { runCatching { File(badPath).delete() } }
        }
        playFallback("播放器错误：${throwable.message}")
    }

    // ---- 控制命令 ----

    /** 停止本次响铃：停播、撤通知、注册明天闹钟、补调度同步 */
    private fun handleStop() {
        stopToneFallback()
        player.stop()
        ringingGuard.set(false)
        ringtoneAttempted = false
        currentPlayingPath = null
        serviceScope.launch {
            val settings = settingsRepository.current()
            if (settings.alarmEnabled) {
                // 每日自续期：响完算明天同一时刻再 setExact
                alarmScheduler.scheduleNextDaily(settings.alarmHour, settings.alarmMinute)
            }
            runCatching { SyncScheduler.scheduleNext(this@AlarmService) }
            ServiceCompat.stopForeground(this@AlarmService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** 贪睡：停播、撤通知、N 分钟后一次性精确闹钟重跑完整流程 */
    private fun handleSnooze() {
        stopToneFallback()
        player.stop()
        ringingGuard.set(false)
        currentPlayingPath = null
        serviceScope.launch {
            val settings = settingsRepository.current()
            val scheduled = alarmScheduler.scheduleSnooze(settings.snoozeMinutes)
            if (!scheduled) {
                AppLogger.w(TAG, "贪睡注册无精确闹钟权限，已降级注册")
            }
            ServiceCompat.stopForeground(this@AlarmService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** 停止并释放 ToneGenerator 兜底 */
    private fun stopToneFallback() {
        toneJob?.cancel()
        toneJob = null
        toneGenerator?.let { generator ->
            runCatching {
                generator.stopTone()
                generator.release()
            }
        }
        toneGenerator = null
    }

    // ---- 前台通知（响铃期间的控制面板） ----

    private fun startForegroundCompat() {
        val notification = buildRingingNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, Constants.NOTIF_ID_RINGING, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(Constants.NOTIF_ID_RINGING, notification)
        }
    }

    /** 选片出结果后把视频标题/日期刷进通知（同 ID 覆盖） */
    private fun updateNotificationContent(title: String, text: String) {
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(Constants.NOTIF_ID_RINGING, buildRingingNotification(this, title, text))
        }.onFailure { AppLogger.w(TAG, "更新响铃通知失败", it) }
    }

    private fun SelectionPolicy.Source.toLogValue(): String = when (this) {
        SelectionPolicy.Source.TODAY -> Constants.SOURCE_TODAY
        SelectionPolicy.Source.CACHED -> Constants.SOURCE_CACHED
        SelectionPolicy.Source.FALLBACK -> Constants.SOURCE_FALLBACK
    }

    companion object {
        private const val TAG = Constants.TAG_PREFIX + "Service"
        private const val REQUEST_CODE_STOP = 3002
        private const val REQUEST_CODE_SNOOZE = 3003

        /** 缓存为空时的响铃现场同步上限：超时即放弃网络、走兜底铃声（响铃不能久等） */
        private const val RING_SYNC_TIMEOUT_MS = 8_000L

        fun start(context: Context, action: String) {
            val intent = Intent(context, AlarmService::class.java).apply {
                this.action = action
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * F1 双保险：到点兜底通知（AlarmReceiver 在服务起来之前先发）。
         * 服务正常启动后由 startForeground 同 ID 无缝接管。
         */
        fun notifyFallback(context: Context) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    AppLogger.w(TAG, "无通知权限，跳过兜底通知（主页有常驻提醒）")
                    return
                }
                NotificationManagerCompat.from(context)
                    .notify(Constants.NOTIF_ID_RINGING, buildRingingNotification(context))
                AppLogger.i(TAG, "已补发高优先级兜底通知（双保险）")
            } catch (e: Exception) {
                AppLogger.e(TAG, "补发兜底通知失败", e)
            }
        }

        private fun buildStopAction(context: Context): NotificationCompat.Action =
            NotificationCompat.Action.Builder(
                0,
                context.getString(R.string.notif_action_stop),
                PendingIntent.getService(
                    context, REQUEST_CODE_STOP,
                    Intent(context, AlarmService::class.java).apply { action = Constants.ACTION_STOP },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            ).build()

        private fun buildSnoozeAction(context: Context): NotificationCompat.Action =
            NotificationCompat.Action.Builder(
                0,
                context.getString(R.string.ringing_btn_snooze),
                PendingIntent.getService(
                    context, REQUEST_CODE_SNOOZE,
                    Intent(context, AlarmService::class.java).apply { action = Constants.ACTION_SNOOZE },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            ).build()

        private fun buildRingingNotification(
            context: Context,
            title: String = context.getString(R.string.notif_ring_title),
            text: String = context.getString(R.string.notif_ring_text)
        ): Notification =
            NotificationCompat.Builder(context, Constants.CHANNEL_ALARM)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setOngoing(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(buildStopAction(context))
                .addAction(buildSnoozeAction(context))
                .build()
    }
}
