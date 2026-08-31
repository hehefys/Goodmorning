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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
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

    /**
     * 协程兜底（QA O1 加固）：任何逸出 try/catch 的未捕获异常都不得导致
     * 「服务卡在响铃态——既没声音、通知也停不掉」。捕获后统一降级到兜底铃声，
     * 保证用户至少能听见；[fallbackEngaged] 限制每场只降级一次，避免异常循环打转。
     */
    private val crashHandler = CoroutineExceptionHandler { _, throwable ->
        AppLogger.e(TAG, "响铃协程未捕获异常", throwable)
        if (fallbackEngaged.compareAndSet(false, true)) {
            runCatching { playFallback("协程异常: ${throwable.message ?: throwable.javaClass.simpleName}") }
                .onFailure { AppLogger.e(TAG, "异常后兜底再失败，本场只能停响", it) }
        }
    }

    /** 本场是否已因异常进入兜底（新的一场响铃时复位） */
    private val fallbackEngaged = AtomicBoolean(false)

    private val serviceScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + crashHandler)

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

        // 息屏可靠性②：到点去重（主判重在 AlarmReceiver，此处只兜底）。
        // 控制命令不受影响；确属重复到点时先补一次前台调用（Android 8+ 要求
        // startForegroundService 后必须进前台，否则系统会判定启动超时），再安全退出。
        if (intent.action != Constants.ACTION_STOP && intent.action != Constants.ACTION_SNOOZE) {
            // 主页测试键强制触发，不参与去重
            val force = intent.getBooleanExtra(Constants.EXTRA_FORCE, false)
            // Receiver 已判过重并带上触发时刻 → 本场身份明确，绝不能再判一次
            val dedupePassed = intent.getBooleanExtra(Constants.EXTRA_DEDUPE_PASSED, false)
            if (force) {
                // 手动测试：清掉水位且**不写入**，否则测试用的「当前时刻」会变成
                // 后续真实闹钟的判重基准，把紧随其后的真实到点误杀（实测误杀过 31s 后的闹钟）
                RingGuard.reset(this)
            } else if (!dedupePassed) {
                // 未经 Receiver 的异常路径（如 PendingIntent 直达）：兜底判一次
                val now = System.currentTimeMillis()
                if (!RingGuard.shouldHandle(this, now)) {
                    AppLogger.w(TAG, "重复到点已忽略（不再重复/叠加播放）：now=$now")
                    runCatching { startForegroundCompat() }
                        .onFailure { AppLogger.w(TAG, "重复到点分支进入前台失败", it) }
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                RingGuard.markHandled(this, now)
            } else {
                AppLogger.i(TAG, "到点已由 Receiver 完成去重，直接起播")
            }
        }

        // 息屏可靠性③：先占前台（Doze 下必须在系统给的窗口内完成），再建播放器。
        // O1 加固：进前台失败（通知权限被撤等）不能把整场响铃带崩——通知没了也要出声。
        runCatching { startForegroundCompat() }
            .onFailure { AppLogger.e(TAG, "进入前台失败（通知权限？），继续尝试出声", it) }
        // 播放器创建失败则连主音频都放不了，直接降到最后防线蜂鸣
        runCatching { ensurePlayer() }.onFailure {
            AppLogger.e(TAG, "播放器创建失败，直接走蜂鸣兜底", it)
            playToneFallback("播放器创建失败: ${it.message ?: it.javaClass.simpleName}")
            return START_NOT_STICKY
        }

        when (intent.action) {
            Constants.ACTION_STOP -> handleStop()
            Constants.ACTION_SNOOZE -> handleSnooze()
            else -> {
                if (ringingGuard.compareAndSet(false, true)) {
                    sessionSeq++
                    fallbackEngaged.set(false)
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
                        // O1 加固：副音频起播失败（文件被删/授权失效）不影响主音频——
                        // 直接跳过衬托立即起播，绝不因为陪衬没起来就整场哑火。
                        val ambientOk = runCatching {
                            player.startAmbient(
                                Uri.parse(settings.ambientUri),
                                settings.ambientVolume / 100f,
                                settings.ambientStartMs,
                                settings.ambientEndMs,
                                loop = !settings.replayEnabled
                            )
                            true
                        }.onFailure {
                            AppLogger.w(TAG, "副音频起播失败，跳过衬托直接播主音频", it)
                        }.getOrDefault(false)
                        if (ambientOk) {
                            leadJob = serviceScope.launch {
                                delay(settings.ambientLeadSeconds * 1000L)
                                startMain(localPath, video, settings, result.source.toLogValue())
                            }
                        } else {
                            startMain(localPath, video, settings, result.source.toLogValue())
                        }
                    } else {
                        startMain(localPath, video, settings, result.source.toLogValue())
                    }
                } else {
                    playFallback("无可用缓存视频（候选 ${videos.size} 条）")
                }
                alarmScheduler.cancelSnoozeOnly()
            } catch (e: CancellationException) {
                // 服务销毁导致的正常取消，不算故障，也不该再触发兜底铃声
                throw e
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
        // O1 加固：起播抛异常不得让本场卡在「有通知无声音」，直接降级到兜底铃声
        runCatching {
            player.playFile(
                File(localPath),
                settings.volumeFadeEnabled,
                settings.volumeFadeSeconds * 1000L
            )
        }.onFailure {
            AppLogger.e(TAG, "主音频起播失败，降级兜底", it)
            playFallback("主音频起播异常: ${it.message ?: it.javaClass.simpleName}")
            return
        }
        runCatching {
            if (player.isAmbientPlaying) {
                player.duckAmbient(settings.ambientDuckedVolume / 100f)
            }
        }.onFailure { AppLogger.w(TAG, "副音频压低失败（不阻断主音频）", it) }
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
        // 播放器都还没建起来（或已释放）→ 铃声兜底无从谈起，直奔蜂鸣
        if (!::player.isInitialized) {
            playToneFallback(reason)
            return
        }
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
            var failures = 0
            while (isActive && failures < TONE_MAX_FAILURES) {
                // O1 加固：蜂鸣循环里抛异常会让整个响铃链路崩掉（连停止都做不到），
                // 这里就地吞掉并计数，连续失败才放弃最后防线。
                val ok = runCatching {
                    generator.startTone(
                        ToneGenerator.TONE_PROP_BEEP, Constants.TONE_BEEP_DURATION_MS
                    )
                }.onFailure { AppLogger.w(TAG, "蜂鸣失败（第 ${failures + 1} 次）", it) }.isSuccess
                if (ok) failures = 0 else failures++
                if (failures >= TONE_MAX_FAILURES) {
                    AppLogger.e(TAG, "蜂鸣连续失败 $TONE_MAX_FAILURES 次，放弃最后防线")
                    handleStop()
                    return@launch
                }
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
                    runCatching {
                        player.playFile(
                            File(path),
                            settings.volumeFadeEnabled,
                            settings.volumeFadeSeconds * 1000L
                        )
                    }.onFailure {
                        AppLogger.e(TAG, "重播主音频失败，降级兜底", it)
                        playFallback("重播异常: ${it.message ?: it.javaClass.simpleName}")
                    }
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
                runCatching {
                    player.playFile(
                        File(path),
                        settings.volumeFadeEnabled,
                        settings.volumeFadeSeconds * 1000L
                    )
                }.onFailure {
                    AppLogger.e(TAG, "副音频结束后重播失败，降级兜底", it)
                    playFallback("重播异常: ${it.message ?: it.javaClass.simpleName}")
                }
            }
            if (settings.ambientEnabled && settings.ambientUri.isNotBlank()) {
                // 以压低音量重启一轮单轮副音频陪衬（主音频在播，无需再 duck）
                // O1 加固：副音频重启失败只影响陪衬，主音频照播，不降级、不中断
                runCatching {
                    player.startAmbient(
                        Uri.parse(settings.ambientUri),
                        settings.ambientDuckedVolume / 100f,
                        settings.ambientStartMs,
                        settings.ambientEndMs,
                        loop = false
                    )
                }.onFailure { AppLogger.w(TAG, "重启一轮副音频失败（主音频不受影响）", it) }
            }
        }
    }

    // ---- 控制命令 ----

    /** 停止本次响铃：副音频即停，主音频 600ms 渐弱收尾后撤通知、注册明天闹钟、补调度同步 */
    private fun handleStop() {
        stopToneFallback()
        leadJob?.cancel()
        leadJob = null
        runCatching { player.stopAmbient() }.onFailure { AppLogger.w(TAG, "停止副音频失败", it) }
        ringingGuard.set(false)
        ringtoneAttempted = false
        fallbackEngaged.set(false)
        currentPlayingPath = null
        currentVideo = null
        mainStarted = false
        val gen = sessionSeq
        serviceScope.launch {
            // 渐弱收尾完成后再做收尾登记，避免服务提前退出截断渐弱
            fadeOutThen {
                // O1 加固：读设置/续期调度任一失败也只记日志，
                // 用户按下的「停止」必须生效——通知要撤、服务要停。
                runCatching {
                    val settings = settingsRepository.current()
                    if (settings.alarmEnabled) {
                        // 每日自续期：响完算明天同一时刻再 setExact
                        alarmScheduler.scheduleNextDaily(settings.alarmHour, settings.alarmMinute)
                    }
                }.onFailure {
                    AppLogger.e(TAG, "停止后重新调度失败（下次可能不再响，请重开一次开关）", it)
                }
                runCatching { SyncScheduler.scheduleNext(this@AlarmService) }
                // 渐弱期间新响铃已接管 → 本场收尾登记全部让位，不得撤前台/杀服务
                finishForeground(gen)
            }
        }
    }

    /** 贪睡：副音频即停，主音频渐弱收尾后撤通知，N 分钟后一次性精确闹钟重跑完整流程 */
    private fun handleSnooze() {
        stopToneFallback()
        leadJob?.cancel()
        leadJob = null
        runCatching { player.stopAmbient() }.onFailure { AppLogger.w(TAG, "停止副音频失败", it) }
        ringingGuard.set(false)
        ringtoneAttempted = false
        fallbackEngaged.set(false)
        currentPlayingPath = null
        currentVideo = null
        mainStarted = false
        val gen = sessionSeq
        serviceScope.launch {
            fadeOutThen {
                // 贪睡注册优先级最高：即便设置读取失败，也要用默认间隔把贪睡排上，
                // 否则用户等于被静音丢弃。
                val minutes = runCatching { settingsRepository.current().snoozeMinutes }
                    .onFailure { AppLogger.e(TAG, "读取贪睡间隔失败，用默认值 ${Constants.SNOOZE_DEFAULT}", it) }
                    .getOrDefault(Constants.SNOOZE_DEFAULT)
                runCatching {
                    if (!alarmScheduler.scheduleSnooze(minutes)) {
                        AppLogger.w(TAG, "贪睡注册无精确闹钟权限，已降级注册")
                    }
                }.onFailure { AppLogger.e(TAG, "贪睡注册失败（本次贪睡可能不响）", it) }
                // 渐弱期间新响铃已接管 → 贪睡已注册，但不得撤前台/杀服务
                finishForeground(gen)
            }
        }
    }

    /**
     * 主音频渐弱收尾后执行 [block]（在 serviceScope 内运行，可调用挂起函数）。
     * 渐弱本身失败（播放器未初始化/已释放）时立即执行 [block]，
     * 绝不让服务卡在「前台还在、声音没了」的状态。
     * [block] 内逸出的异常只记日志，不会再冒泡到协程兜底去重新起铃。
     */
    private fun fadeOutThen(block: suspend () -> Unit) {
        val run: () -> Unit = {
            serviceScope.launch {
                runCatching { block() }
                    .onFailure { AppLogger.e(TAG, "响铃收尾流程异常，已强制收场", it) }
            }
        }
        val started = runCatching {
            player.stopWithFadeOut(onFinished = run)
            true
        }.onFailure { AppLogger.w(TAG, "渐弱收尾不可用，直接收尾", it) }.getOrDefault(false)
        if (!started) run()
    }

    /** 撤前台并停止服务；[gen] 与当前会话不符说明新响铃已接管，本场让位 */
    private fun finishForeground(gen: Int) {
        if (gen != sessionSeq) return
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }.onFailure { AppLogger.w(TAG, "撤前台失败", it) }
        stopSelf()
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

        /** 蜂鸣兜底连续失败上限：达到即放弃，避免无限报错循环 */
        private const val TONE_MAX_FAILURES = 3

        /**
         * 启动响铃服务。
         * @param force 手动触发（主页测试键）时为 true：跳过到点去重，
         *              避免连续测试被判重逻辑拦掉（真实闹钟到点一律 false）
         * @param triggerAt 闹钟的计划触发时刻；非 [Long.MIN_VALUE] 时一同带给服务，
         *                  并置上 [Constants.EXTRA_DEDUPE_PASSED]，
         *                  告诉服务「本场已判过重，别再判一次把自己杀掉」
         */
        fun start(
            context: Context,
            action: String,
            force: Boolean = false,
            triggerAt: Long = Long.MIN_VALUE
        ) {
            val intent = Intent(context, AlarmService::class.java).apply {
                this.action = action
                if (force) putExtra(Constants.EXTRA_FORCE, true)
                if (triggerAt != Long.MIN_VALUE) {
                    putExtra(Constants.EXTRA_TRIGGER_AT, triggerAt)
                    putExtra(Constants.EXTRA_DEDUPE_PASSED, true)
                }
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
