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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.goodmorning.alarm.R
import com.goodmorning.alarm.alarm.AlarmScheduler
import com.goodmorning.alarm.alarm.RingGuard
import com.goodmorning.alarm.alarm.RingWakeLock
import com.goodmorning.alarm.alarm.SelectionPolicy
import com.goodmorning.alarm.data.db.VideoEntity
import com.goodmorning.alarm.data.prefs.Settings
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

    /** 当前正在播放的视频（重播时刷新通知标题用；停止/贪睡时清空） */
    private var currentVideo: VideoEntity? = null

    /** 本次选片的播放来源日志值（副音频早于衬托播完提前起播时补记用） */
    private var lastSourceLogValue: String = Constants.SOURCE_TODAY

    /** 主音频是否已起播（区分副音频结束发生在衬托期还是陪衬期） */
    @Volatile
    private var mainStarted = false

    /** 衬托期倒计时任务：副音频单轮提前播完时取消，立即起播主音频 */
    private var leadJob: Job? = null

    /** 会话代号：每次新响铃 +1；渐弱收尾回调据此判断是否已被新会话接管 */
    private var sessionSeq = 0

    override fun onCreate() {
        super.onCreate()
        // 息屏可靠性①：服务进程一启动就持锁（播放在 ExoPlayer 内另有一层 WAKE_MODE_LOCAL）
        RingWakeLock.acquire(this, "service")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_NOT_STICKY 下系统重建传入 null intent：仅保活不重播
        if (intent == null) {
            AppLogger.w(TAG, "服务重建（null intent），不重播，直接停止")
            stopSelf()
            return START_NOT_STICKY
        }

        // 息屏可靠性②：到点去重兜底（主判重在 AlarmReceiver）。
        // 控制命令不受影响；重复到点时先补一次前台调用（Android 8+ 要求
        // startForegroundService 后必须进前台，否则系统会判定启动超时），再安全退出。
        if (intent.action != Constants.ACTION_STOP && intent.action != Constants.ACTION_SNOOZE) {
            val triggerAt = intent.getLongExtra(
                Constants.EXTRA_TRIGGER_AT, System.currentTimeMillis()
            )
            // 主页测试键强制触发，不参与去重
            val force = intent.getBooleanExtra(Constants.EXTRA_FORCE, false)
            if (force) RingGuard.reset(this)
            if (!force && !RingGuard.shouldHandle(this, triggerAt)) {
                AppLogger.w(TAG, "重复到点已忽略（不再重复/叠加播放）：triggerAt=$triggerAt")
                startForegroundCompat()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            RingGuard.markHandled(this, triggerAt)
        }

        // 息屏可靠性③：先占前台（Doze 下必须在系统给的窗口内完成），再建播放器
        startForegroundCompat()
        ensurePlayer()

        when (intent.action) {
            Constants.ACTION_STOP -> handleStop()
            Constants.ACTION_SNOOZE -> handleSnooze()
            else -> {
                if (ringingGuard.compareAndSet(false, true)) {
                    sessionSeq++
                    selectAndPlay()
                } else {
                    AppLogger.i(TAG, "重复 ACTION_RING 到达，忽略（已在响铃）")
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * 惰性创建播放器：放在 startForeground 之后，避免 ExoPlayer 初始化耗时
     * 把「进入前台」挤出系统给 Doze 唤醒的短窗口。
     */
    private fun ensurePlayer() {
        if (::player.isInitialized) return
        player = AlarmPlayer(this).apply {
            onError = { throwable -> onPlayerError(throwable) }
            onEnded = { onMainEnded() }
            onAmbientEnded = { onAmbientEnded() }
        }
    }

    override fun onDestroy() {
        stopToneFallback()
        serviceScope.cancel()
        if (::player.isInitialized) runCatching { player.release() }
        RingWakeLock.release()
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
                // 息屏可靠性④：Doze 下网络被禁，现场同步必然超时（白等 RING_SYNC_TIMEOUT_MS），
                // 直接跳过并走兜底铃声，避免出现「到点后空等数秒才出声」。
                if (videos.isEmpty() && isDeviceIdleMode()) {
                    AppLogger.w(TAG, "缓存为空但设备处于 Doze，跳过现场同步，直接走兜底")
                } else if (videos.isEmpty()) {
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
                    mainStarted = false
                    currentPlayingPath = localPath
                    currentVideo = video
                    lastSourceLogValue = result.source.toLogValue()
                    ringtoneAttempted = false
                    if (settings.ambientEnabled && settings.ambientUri.isNotBlank() &&
                        settings.ambientLeadSeconds > 0
                    ) {
                        // 衬托开启且时长>0：重播开启 → 副音频单轮播完触发重播；
                        // 重播关闭 → 保持无限循环陪衬。时长=0 表示不衬托，直接走主音频。
                        player.startAmbient(
                            Uri.parse(settings.ambientUri),
                            settings.ambientVolume / 100f,
                            settings.ambientStartMs,
                            settings.ambientEndMs,
                            loop = !settings.replayEnabled
                        )
                        leadJob = serviceScope.launch {
                            delay(settings.ambientLeadSeconds * 1000L)
                            startMain(localPath, video, settings, result.source.toLogValue())
                        }
                    } else {
                        startMain(localPath, video, settings, result.source.toLogValue())
                    }
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

    /** 设备是否处于 Doze（息屏静置）模式：此时网络不可用，任何联网补救都是白等 */
    private fun isDeviceIdleMode(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isDeviceIdleMode == true
    }

    /**
     * 起播主音频（衬托到点 / 副音频单轮提前播完 / 无副音频三种路径共用）：
     * 渐强起播 → 副音频仍在播则压低陪衬 → 刷新通知 → 记录播放日志。
     * 衬托期间用户可能已停止/贪睡：卫兵已复位则本场作废。
     */
    private fun startMain(
        localPath: String,
        video: VideoEntity,
        settings: Settings,
        sourceLogValue: String
    ) {
        if (!ringingGuard.get()) return
        mainStarted = true
        player.playFile(
            File(localPath),
            settings.volumeFadeEnabled,
            settings.volumeFadeSeconds * 1000L
        )
        if (player.isAmbientPlaying) {
            player.duckAmbient(settings.ambientDuckedVolume / 100f)
        }
        updateNotificationContent(
            title = video.title.ifBlank { getString(R.string.notif_ring_title) },
            text = getString(R.string.ringing_publish_date_fmt, video.publishDate)
        )
        serviceScope.launch {
            runCatching { repository.logPlayback(video.id, sourceLogValue) }
                .onFailure { AppLogger.w(TAG, "记录播放日志失败", it) }
        }
        AppLogger.i(TAG, "选片命中「${video.title}」，主音频起播")
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
                val settings = settingsRepository.current()
                val fade = settings.volumeFadeEnabled
                player.playUri(ringtoneUri, fade, settings.volumeFadeSeconds * 1000L)
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

    /**
     * 主音频自然播完：
     * - 重播开启且副音频陪衬中 → 触发权交给副音频结束事件，此处静候；
     * - 重播开启但副音频不在播（未启用/加载失败/已耗尽）→ 退回「主音频播完即重播」，
     *   保证重播链路绝不哑火；
     * - 重播关闭 → 原行为：有副音频恢复音量续播收尾段（AMBIENT_WRAP_UP_MS）后停止，
     *   无副音频立即收场。
     */
    private fun onMainEnded() {
        if (!ringingGuard.get()) return
        serviceScope.launch {
            val settings = runCatching { settingsRepository.current() }.getOrNull()
            if (settings == null) {
                handleStop()
                return@launch
            }
            val path = currentPlayingPath
            if (settings.replayEnabled) {
                if (player.isAmbientPlaying) {
                    // 主音频已停，无音量需要压低：先还原副音频原始音量再等待重播触发点
                    AppLogger.i(TAG, "主音频播完，副音频陪衬中 → 还原其音量并等待结束后重播")
                    runCatching { player.restoreAmbient(settings.ambientVolume / 100f) }
                        .onFailure { AppLogger.w(TAG, "副音频音量还原失败", it) }
                    return@launch
                }
                if (path != null && File(path).isFile) {
                    AppLogger.i(TAG, "主音频播完且副音频不在播 → 直接重播主音频")
                    player.playFile(
                        File(path), settings.volumeFadeEnabled, settings.volumeFadeSeconds * 1000L
                    )
                    return@launch
                }
                AppLogger.w(TAG, "重播时主音频文件不可用 → 停止本场响铃")
                handleStop()
                return@launch
            }
            if (!player.isAmbientPlaying) {
                handleStop()
                return@launch
            }
            runCatching { player.restoreAmbient(settings.ambientVolume / 100f) }
            delay(Constants.AMBIENT_WRAP_UP_MS)
            handleStop()
        }
    }

    /**
     * 副音频一轮播完（重播触发点）：
     * - 主音频未起播（单轮副音频早于衬托时长播完）→ 取消衬托倒计时，立即起播主音频，
     *   并以压低音量重启一轮副音频陪衬；
     * - 主音频在播 → 重播主音频 + 重启一轮副音频（压低），循环往复直到用户手动关闭；
     * - 主音频文件不可用 → 停止本场响铃。
     * 副音频播放错误在 AlarmPlayer 内已降级为结束事件走到这里，链路不中断。
     */
    private fun onAmbientEnded() {
        if (!ringingGuard.get()) return
        serviceScope.launch {
            val settings = runCatching { settingsRepository.current() }.getOrNull()
            if (settings == null || !settings.replayEnabled) {
                // 循环模式不会自然结束；走到这里说明重播已被关闭，保持现状即可
                AppLogger.w(TAG, "副音频结束但重播未开启，忽略")
                return@launch
            }
            val path = currentPlayingPath
            val video = currentVideo
            if (path == null || video == null || !File(path).isFile) {
                AppLogger.w(TAG, "副音频结束后主音频不可用 → 停止本场响铃")
                handleStop()
                return@launch
            }
            if (!mainStarted) {
                AppLogger.i(TAG, "副音频一轮播完早于衬托时长 → 提前起播主音频")
                leadJob?.cancel()
                leadJob = null
                startMain(path, video, settings, lastSourceLogValue)
            } else {
                AppLogger.i(TAG, "副音频一轮播完 → 自动重播主音频")
                player.playFile(
                    File(path), settings.volumeFadeEnabled, settings.volumeFadeSeconds * 1000L
                )
            }
            if (settings.ambientEnabled && settings.ambientUri.isNotBlank()) {
                // 以压低音量重启一轮单轮副音频陪衬（主音频在播，无需再 duck）
                player.startAmbient(
                    Uri.parse(settings.ambientUri),
                    settings.ambientDuckedVolume / 100f,
                    settings.ambientStartMs,
                    settings.ambientEndMs,
                    loop = false
                )
            }
        }
    }

    // ---- 控制命令 ----

    /** 停止本次响铃：副音频即停，主音频 600ms 渐弱收尾后撤通知、注册明天闹钟、补调度同步 */
    private fun handleStop() {
        stopToneFallback()
        leadJob?.cancel()
        leadJob = null
        player.stopAmbient()
        ringingGuard.set(false)
        ringtoneAttempted = false
        currentPlayingPath = null
        currentVideo = null
        mainStarted = false
        val gen = sessionSeq
        serviceScope.launch {
            // 渐弱收尾完成后再做收尾登记，避免服务提前退出截断渐弱
            player.stopWithFadeOut {
                serviceScope.launch {
                    // 渐弱期间新响铃已接管 → 本场收尾登记全部让位，不得撤前台/杀服务
                    if (gen != sessionSeq) return@launch
                    val settings = settingsRepository.current()
                    if (settings.alarmEnabled) {
                        // 每日自续期：响完算明天同一时刻再 setExact
                        alarmScheduler.scheduleNextDaily(settings.alarmHour, settings.alarmMinute)
                    }
                    runCatching { SyncScheduler.scheduleNext(this@AlarmService) }
                    ServiceCompat.stopForeground(
                        this@AlarmService, ServiceCompat.STOP_FOREGROUND_REMOVE
                    )
                    stopSelf()
                }
            }
        }
    }

    /** 贪睡：副音频即停，主音频渐弱收尾后撤通知，N 分钟后一次性精确闹钟重跑完整流程 */
    private fun handleSnooze() {
        stopToneFallback()
        leadJob?.cancel()
        leadJob = null
        player.stopAmbient()
        ringingGuard.set(false)
        currentPlayingPath = null
        currentVideo = null
        mainStarted = false
        val gen = sessionSeq
        serviceScope.launch {
            val settings = settingsRepository.current()
            player.stopWithFadeOut {
                serviceScope.launch {
                    // 渐弱期间新响铃已接管 → 贪睡仍要注册，但不得撤前台/杀服务
                    val scheduled = alarmScheduler.scheduleSnooze(settings.snoozeMinutes)
                    if (!scheduled) {
                        AppLogger.w(TAG, "贪睡注册无精确闹钟权限，已降级注册")
                    }
                    if (gen != sessionSeq) return@launch
                    ServiceCompat.stopForeground(
                        this@AlarmService, ServiceCompat.STOP_FOREGROUND_REMOVE
                    )
                    stopSelf()
                }
            }
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

        /**
         * 启动响铃服务。
         * @param force 手动触发（主页测试键）时为 true：跳过到点去重，
         *              避免连续测试被判重逻辑拦掉（真实闹钟到点一律 false）
         */
        fun start(context: Context, action: String, force: Boolean = false) {
            val intent = Intent(context, AlarmService::class.java).apply {
                this.action = action
                if (force) putExtra(Constants.EXTRA_FORCE, true)
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
