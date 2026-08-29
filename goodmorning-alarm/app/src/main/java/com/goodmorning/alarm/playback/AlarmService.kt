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
import com.goodmorning.alarm.sync.SyncScheduler
import com.goodmorning.alarm.ui.ringing.RingingActivity
import com.goodmorning.alarm.util.AppLogger
import com.goodmorning.alarm.util.Constants
import com.goodmorning.alarm.util.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 响铃前台服务（mediaPlayback 类型）。
 *
 * 职责链：选片（SelectionPolicy）→ 起播（AlarmPlayer）→ full-screen intent 拉起响铃页；
 * 处理 ACTION_STOP / ACTION_SNOOZE / ACTION_PLAY_PAUSE；
 * 停止时注册明天闹钟并补调度同步（自续期 + 冗余）。
 *
 * 四级兜底（P0-4 绝不哑火，主理人拍板不加 res/raw 素材）：
 * 本地 mp4 → RingtoneManager(TYPE_ALARM) → TYPE_RINGTONE → ToneGenerator 蜂鸣循环。
 *
 * 生命周期要点：
 * - START_NOT_STICKY：用户停止后服务被杀重建（null intent）不得意外重响（E4）；
 * - onStartCommand 入口用 AtomicBoolean 原子置位，双 ACTION_RING 连发
 *   （每日与贪睡毫秒级同时触发）只选片一次（E3）。
 */
class AlarmService : Service() {

    // ---- 响铃页共享状态（UI 层只读） ----

    /** 响铃页展示状态；null = 当前未在响铃 */
    data class RingingState(
        /** 视频标题（兜底时为空） */
        val title: String,
        /** 发布日期 yyyy-MM-dd（兜底时为空） */
        val publishDate: String,
        /** 选片来源 */
        val source: SelectionPolicy.Source,
        /** 是否正在出声 */
        val isPlaying: Boolean,
        /** 音量渐强进度 0..1（V2 响铃页光晕跟随；渐强关闭时常量 0.5f） */
        val volumeProgress: Float = 0f
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val settingsRepository by lazy { SettingsRepository(this) }
    private val repository by lazy { VideoRepository(this) }
    private val alarmScheduler by lazy { AlarmScheduler(this) }
    private val selectionPolicy = SelectionPolicy()

    private lateinit var player: AlarmPlayer

    /**
     * 响铃卫兵：onStartCommand 入口 compareAndSet 原子置位（E3）。
     * 双 ACTION_RING 并发时仅第一个进入选片；handleStop / handleSnooze 复位。
     */
    private val ringingGuard = AtomicBoolean(false)

    /** ToneGenerator 第四级兜底（惰性创建，用完释放） */
    private var toneGenerator: ToneGenerator? = null
    private var toneJob: Job? = null

    /** 第三级铃声是否已尝试过：防止“铃声失败→错误→再铃声”无限循环，已试过则直接进第四级 */
    private var ringtoneAttempted = false

    /** 当前正在播放的本地文件路径（用于错误时清理坏文件） */
    private var currentPlayingPath: String? = null

    override fun onCreate() {
        super.onCreate()
        player = AlarmPlayer(this).apply {
            onError = { throwable -> onPlayerError(throwable) }
            onEnded = { handleStop() }
            onIsPlayingChanged = { playing ->
                _ringingState.value = _ringingState.value?.copy(isPlaying = playing)
            }
            // V2：音量渐强进度透传（响铃页日出光晕跟随增亮）
            onVolumeProgress = { progress ->
                _ringingState.value = _ringingState.value?.copy(volumeProgress = progress)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 无论何种 action 都必须尽快进入前台（startForegroundService 契约）
        startForegroundCompat()

        // START_NOT_STICKY 下系统重建传入 null intent：仅保活不重播（E4）
        if (intent == null) {
            AppLogger.w(TAG, "服务重建（null intent），不重播，直接停止")
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent.action) {
            Constants.ACTION_STOP -> handleStop()
            Constants.ACTION_SNOOZE -> handleSnooze()
            Constants.ACTION_PLAY_PAUSE -> handlePlayPause()
            else -> {
                // ACTION_RING：贪睡/每日闹钟共用入口。
                // 原子置位防双闹钟竞态：重复 intent 直接忽略（E3）
                if (ringingGuard.compareAndSet(false, true)) {
                    selectAndPlay()
                } else {
                    AppLogger.i(TAG, "重复 ACTION_RING 到达，忽略（已在响铃）")
                }
            }
        }
        // NOT_STICKY：用户停止后被杀不自动重启，避免意外重响（E4）
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopToneFallback()
        serviceScope.cancel()
        runCatching { player.release() }
        _ringingState.value = null
        // F4：复位显式拉起标记，允许下一次响铃（新服务生命周期）再次拉起响铃页
        activityLaunched = false
        super.onDestroy()
    }

    /** 非绑定式服务：不对外提供 Binder */
    override fun onBind(intent: Intent?): IBinder? = null

    // ---- 选片与起播 ----

    private fun selectAndPlay() {
        serviceScope.launch {
            try {
                val settings = settingsRepository.current()
                val today = TimeUtils.localDate()
                val videos = repository.playableVideos()
                val result = selectionPolicy.select(videos, today)

                val video = result.video
                val localPath = video?.localPath
                if (video != null && !localPath.isNullOrBlank() && File(localPath).isFile) {
                    currentPlayingPath = localPath
                    ringtoneAttempted = false
                    player.playFile(File(localPath), settings.volumeFadeEnabled)
                    publishRingingState(
                        RingingState(
                            title = video.title,
                            publishDate = video.publishDate,
                            source = result.source,
                            isPlaying = true
                        )
                    )
                    runCatching { repository.logPlayback(video.id, result.source.toLogValue()) }
                        .onFailure { AppLogger.w(TAG, "记录播放日志失败", it) }
                    AppLogger.i(TAG, "选片命中：${result.source} ${video.id}「${video.title}」")
                } else {
                    playFallback("无可用缓存视频（候选 ${videos.size} 条）")
                }
                // 贪睡到点触发的本次响铃：清除主页的贪睡提示
                alarmScheduler.cancelSnoozeOnly()
            } catch (e: Exception) {
                // F2：任何异常（读设置/选片/起播初始化）都进入兜底，保证 _ringingState 必被设置
                AppLogger.e(TAG, "选片/起播异常，进入兜底", e)
                playFallback("异常: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * 第三级兜底：系统默认闹钟铃声（主理人决定：不打包 res/raw 素材）。
     * 铃声已尝试过仍失败（播放器错误循环降级）或设备无铃声 URI 时，
     * 直接进入第四级 ToneGenerator 蜂鸣（E6）。
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
                publishRingingState(
                    RingingState(
                        title = "",
                        publishDate = "",
                        source = SelectionPolicy.Source.FALLBACK,
                        isPlaying = true
                    )
                )
                runCatching { repository.logPlayback(null, Constants.SOURCE_FALLBACK) }
                    .onFailure { AppLogger.w(TAG, "记录兜底播放日志失败", it) }
                AppLogger.w(TAG, "兜底铃声已启用（$reason）→ $ringtoneUri")
            } catch (e: Exception) {
                // F2：兜底铃声播放仍失败 → 直接进入第四级 ToneGenerator 蜂鸣，绝不哑火
                AppLogger.e(TAG, "兜底铃声播放失败，进入蜂鸣兜底（$reason）", e)
                playToneFallback("兜底铃声异常: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * 第四级兜底（E6，主理人拍板）：ToneGenerator 蜂鸣循环，绝不哑火的最后防线。
     * 走 STREAM_ALARM 音频流；创建失败（极端裸系统）才记录并放弃。
     */
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
        publishRingingState(
            RingingState(
                title = "",
                publishDate = "",
                source = SelectionPolicy.Source.FALLBACK,
                isPlaying = true
            )
        )
        serviceScope.launch { runCatching { repository.logPlayback(null, Constants.SOURCE_FALLBACK) } }
        AppLogger.w(TAG, "第四级兜底 ToneGenerator 已启用（$reason）")
    }

    // ---- F4：双路径拉起响铃页 ----

    /**
     * 发布响铃状态并（首次）显式拉起响铃页。
     * 不依赖 full-screen intent 也能弹出（屏幕点亮/解锁场景）；锁屏场景仍由 FSI 负责。
     */
    private fun publishRingingState(state: RingingState) {
        _ringingState.value = state
        launchRingingActivityIfNeeded()
    }

    /**
     * 显式启动响铃页（NEW_TASK + CLEAR_TOP 幂等，与通知 FSI 并发不冲突）。
     * companion 布尔位 [activityLaunched] 防重复启动；失败时复位以便后续状态发布重试，
     * 由 FSI 通知兜底。
     */
    private fun launchRingingActivityIfNeeded() {
        if (activityLaunched) return
        activityLaunched = true
        runCatching {
            val intent = Intent(this, RingingActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
            AppLogger.i(TAG, "已显式拉起响铃页（F4 双路径）")
        }.onFailure { e ->
            activityLaunched = false
            AppLogger.w(TAG, "显式拉起响铃页失败，依赖 FSI 通知兜底", e)
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

    /** 播放器错误 → 兜底降级（文件损坏/解码失败等） */
    private fun onPlayerError(throwable: Throwable) {
        if (!ringingGuard.get()) return
        // 清理触发错误的坏文件，避免下次继续命中同一条
        val badPath = currentPlayingPath
        if (badPath != null) {
            serviceScope.launch { runCatching { File(badPath).delete() } }
        }
        playFallback("播放器错误：${throwable.message}")
    }

    // ---- 控制命令 ----

    /** 停止本次响铃：停播、注销通知、注册明天闹钟、补调度同步 */
    private fun handleStop() {
        stopToneFallback()
        player.stop()
        ringingGuard.set(false)
        ringtoneAttempted = false
        _ringingState.value = null
        currentPlayingPath = null
        serviceScope.launch {
            val settings = settingsRepository.current()
            if (settings.alarmEnabled) {
                // 每日重复自续期：响完算明天同一时刻再 setExact
                alarmScheduler.scheduleNextDaily(settings.alarmHour, settings.alarmMinute)
            }
            // 冗余补调度同步（WorkManager 挂了不影响响铃，但顺手拉一把）
            runCatching { SyncScheduler.scheduleNext(this@AlarmService) }
            ServiceCompat.stopForeground(this@AlarmService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** 贪睡：停播、注销通知、注册 N 分钟后一次性精确闹钟（到点重跑完整流程） */
    private fun handleSnooze() {
        stopToneFallback()
        player.stop()
        ringingGuard.set(false)
        _ringingState.value = null
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

    /** 播放/暂停切换（响铃页控制按钮；对 ToneGenerator 兜底同样生效） */
    private fun handlePlayPause() {
        val state = _ringingState.value ?: return
        if (toneGenerator != null) {
            // 第四级兜底的播放/暂停：暂停取消循环，恢复重启循环
            if (state.isPlaying) {
                toneJob?.cancel()
                toneGenerator?.stopTone()
                _ringingState.value = state.copy(isPlaying = false)
            } else {
                val generator = toneGenerator ?: return
                toneJob = serviceScope.launch {
                    while (isActive) {
                        generator.startTone(
                            ToneGenerator.TONE_PROP_BEEP, Constants.TONE_BEEP_DURATION_MS
                        )
                        delay(Constants.TONE_BEEP_PERIOD_MS)
                    }
                }
                _ringingState.value = state.copy(isPlaying = true)
            }
            return
        }
        if (player.isPlaying) {
            player.pause()
        } else {
            player.resume()
        }
    }

    // ---- 前台通知（full-screen intent 直达响铃页） ----

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

    private fun SelectionPolicy.Source.toLogValue(): String = when (this) {
        SelectionPolicy.Source.TODAY -> Constants.SOURCE_TODAY
        SelectionPolicy.Source.CACHED -> Constants.SOURCE_CACHED
        SelectionPolicy.Source.FALLBACK -> Constants.SOURCE_FALLBACK
    }

    companion object {
        private const val TAG = Constants.TAG_PREFIX + "Service"
        private const val REQUEST_CODE_FULLSCREEN = 3001
        private const val REQUEST_CODE_STOP = 3002

        /** 响铃状态（Service 写、响铃页读；null = 未在响铃） */
        private val _ringingState = MutableStateFlow<RingingState?>(null)
        val ringingState: StateFlow<RingingState?> = _ringingState

        /** F4：响铃页是否已被显式拉起（本次服务生命周期内只拉一次，onDestroy 复位） */
        @Volatile
        private var activityLaunched = false

        /**
         * 以指定 action 启动响铃服务（前台服务）。
         */
        fun start(context: Context, action: String) {
            val intent = Intent(context, AlarmService::class.java).apply {
                this.action = action
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * F1：双保险兜底通知。
         * 复用响铃前台通知（fullScreenIntent 同构），由 AlarmReceiver 到点时补发，
         * 保证即使前台服务被系统拦截，用户也能看到/点进响铃页。
         * 与前台服务通知同 ID：服务正常启动后由 startForeground 无缝接管，不重复打扰。
         */
        fun notifyFallback(context: Context) {
            try {
                // Android 13+ 无通知权限时通知不会展示，直接记录（主页有常驻提醒引导）
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

        private fun buildRingingNotification(context: Context): Notification {
            // 全屏意图：锁屏时直接点亮并展示响铃页
            val fullScreenIntent = PendingIntent.getActivity(
                context, REQUEST_CODE_FULLSCREEN,
                Intent(context, RingingActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // 通知栏“停止”动作
            val stopAction = NotificationCompat.Action.Builder(
                0,
                context.getString(R.string.notif_action_stop),
                PendingIntent.getService(
                    context, REQUEST_CODE_STOP,
                    Intent(context, AlarmService::class.java).apply {
                        action = Constants.ACTION_STOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            ).build()

            return NotificationCompat.Builder(context, Constants.CHANNEL_ALARM)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notif_ring_title))
                .setContentText(context.getString(R.string.notif_ring_text))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setOngoing(true)
                .setFullScreenIntent(fullScreenIntent, /* highPriority = */ true)
                .setContentIntent(fullScreenIntent)
                .addAction(stopAction)
                .build()
        }
    }
}
