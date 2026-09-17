package com.goodmorning.alarm.playback

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
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

    /**
     * 起播看门狗：卫兵已置位却迟迟没出声时强制兜底，杜绝「哑响铃」。
     * 事故说明见 [Constants.RING_WATCHDOG_MS]。
     */
    private var watchJob: Job? = null

    /**
     * 最近一次成功读取的设置。读设置超时/失败时退回它，避免整场响铃卡在读设置上。
     * 首次启动无缓存时为 null，调用方据此退回 [Settings] 默认值（宁可参数不完美，也不能没声音）。
     */
    @Volatile
    private var lastGoodSettings: Settings? = null

    /** ToneGenerator 第四级兜底（惰性创建，用完释放） */
    private var toneGenerator: ToneGenerator? = null
    private var toneJob: Job? = null

    /**
     * 系统铃声（第三级兜底）播放句柄。
     *
     * 用 [Ringtone] API 而非 ExoPlayer：Android 15+ 起不允许 ExoPlayer 直接读系统铃声 URI
     * （实测 ExoPlaybackException + FileNotFoundException:
     * "Direct file access no longer supported; ringtone playback is available through
     * android.media.Ringtone"），会让第三级兜底直接失效、一路降级到蜂鸣。
     */
    private var systemRingtone: Ringtone? = null

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

    /** 媒体会话：锁屏媒体大卡 / 系统媒体中心的数据源（响铃期间活跃，岛同款数据标准） */
    private var mediaSession: MediaSessionCompat? = null

    /** 主音频是否处于暂停（通知「暂停/继续」按钮文案与媒体状态同步用） */
    @Volatile
    private var mediaPaused = false

    /** 媒体状态刷新协程：每秒上报播放位置，锁屏进度条由此前进 */
    private var mediaTickerJob: Job? = null

    /** 最近一次通知标题/文案（暂停/继续重建通知时复用） */
    private var lastNotifTitle: String? = null
    private var lastNotifText: String? = null

    override fun onCreate() {
        super.onCreate()
        // 息屏可靠性①：服务进程一启动就持锁（播放在 ExoPlayer 内另有一层 WAKE_MODE_LOCAL）
        RingWakeLock.acquire(this, "service")
        // 媒体会话必须在首次 startForeground 前建好：MediaStyle 通知要拿 sessionToken
        createMediaSession()
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
        if (intent.action != Constants.ACTION_STOP && intent.action != Constants.ACTION_SNOOZE &&
            intent.action != Constants.ACTION_PLAY_PAUSE
        ) {
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
            Constants.ACTION_PLAY_PAUSE -> handlePlayPause()
            Constants.ACTION_NOTIF_DISMISSED -> handleNotifDismissed()
            else -> {
                if (ringingGuard.compareAndSet(false, true)) {
                    sessionSeq++
                    fallbackEngaged.set(false)
                    // 绝不允许「卫兵已置位、却迟迟没声音」——看门狗兜住任何静默挂起
                    startRingWatchdog()
                    selectAndPlay()
                } else if (intent.getBooleanExtra(Constants.EXTRA_FORCE, false)) {
                    // 测试键打断进行中的响铃：旧测试场直接杀掉，立即开新一场
                    // （陈年反馈：以前必须先手动关掉上一场才能测下一场）
                    forceRestartSession("测试键打断当前响铃")
                } else if (mediaPaused) {
                    // 暂停中的旧场**不得**吞掉新的到点（2026-09-17 事故）。
                    //
                    // 媒体卡「暂停」只静音、不结束响铃场：ringingGuard 仍为 true，
                    // 于是此后每一次真实到点都落进下面的「重复 ACTION_RING」分支被忽略。
                    // 实测：09-16 19:28 的一次暂停，把 09-16 19:24/19:25/19:28 与
                    // 09-17 10:00/11:07 共五次到点全部吞掉，且跨夜存活 ——
                    // 这正是 09-17 早上「闹钟没响」的真正原因（不是看门狗那次）。
                    //
                    // 语义澄清：用户按「暂停」= 先静音这一声，不等于放弃后续闹钟。
                    // 有新到点就当作新一场重新起播（同时把播放位置复位）。
                    AppLogger.i(TAG, "上一场处于暂停态 → 新到点重开一场（暂停不得吞掉闹钟）")
                    forceRestartSession("上一场暂停中，新到点不得被吞")
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

    /**
     * 读设置：带超时 + 上次成功值兜底。
     *
     * 为什么不直接 `settingsRepository.current()`：它是 DataStore 的 `flow.first()`，
     * 属挂起调用。2026-09-17 事故中该调用疑似长时间不返回，导致**主起播与兜底铃声
     * 两条链路一起哑掉**（兜底里也在读它），而卫兵已置位 → 后续 ACTION_RING 全被
     * 当作重复忽略，形成无法自愈的哑响铃。
     * 这里给单步加超时：超时就用上次成功值；没有缓存则返回 null，由调用方降级出声。
     */
    private suspend fun readSettingsOrLast(): Settings? =
        withTimeoutOrNull(Constants.RING_STEP_TIMEOUT_MS) {
            runCatching { settingsRepository.current() }.getOrNull()
        }.also { if (it != null) lastGoodSettings = it } ?: lastGoodSettings

    /**
     * 起播看门狗：卫兵已置位，但 [Constants.RING_WATCHDOG_MS] 内**既无主音频也无副音频**
     * → 判定为哑响铃，强制走兜底铃声。
     * 这是「绝不哑火」的最后一道保险，覆盖任何未被日志捕获的挂起或静默失败。
     *
     * ⚠️ 判据必须是「完全没声音」，不能只看 [mainStarted]：副音频衬托期（用户可设 10~600s）
     * 本来就不播主音频。2026-09-17 实测：衬托 5s 与看门狗 5s 正好撞点 → 看门狗误触发
     * → 兜底铃声抢先起播 → 主音频被误拦，整场只剩兜底（且兜底在该系统上还播不了）。
     */
    private fun startRingWatchdog() {
        val gen = sessionSeq
        watchJob?.cancel()
        watchJob = serviceScope.launch {
            delay(Constants.RING_WATCHDOG_MS)
            if (gen != sessionSeq) return@launch                            // 已被新一场接管
            if (!ringingGuard.get()) return@launch                          // 已停止/贪睡
            if (mainStarted || fallbackEngaged.get()) return@launch         // 主音频已出声
            // 副音频在播同样算「已经出声」：衬托期要么由 leadJob 到点起主音频，
            // 要么由 onAmbientEnded 接管，都不需要看门狗插手
            if (isAmbientPlaying()) return@launch
            AppLogger.w(
                TAG,
                "起播看门狗触发：${Constants.RING_WATCHDOG_MS}ms 内既无主音频也无副音频，强制兜底"
            )
            leadJob?.cancel()
            leadJob = null
            if (fallbackEngaged.compareAndSet(false, true)) {
                playFallback("起播超时（看门狗）")
            }
        }
    }

    /** 副音频是否正在播放（衬托期也算「已经在出声」，看门狗不得据此判哑） */
    private fun isAmbientPlaying(): Boolean =
        runCatching { ::player.isInitialized && player.isAmbientPlaying }.getOrDefault(false)

    /** 取消看门狗：停止/贪睡/接管新场/已出声时调用 */
    private fun cancelWatchdog() {
        watchJob?.cancel()
        watchJob = null
    }

    override fun onDestroy() {
        stopToneFallback()
        cancelWatchdog()
        mediaTickerJob?.cancel()
        serviceScope.cancel()
        if (::player.isInitialized) runCatching { player.release() }
        runCatching {
            mediaSession?.release()
            mediaSession = null
        }
        RingWakeLock.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- 选片与起播 ----

    private fun selectAndPlay() {
        serviceScope.launch {
            try {
                // 贪睡驻留通知清场：新一场响铃开始后不再需要
                NotificationManagerCompat.from(this@AlarmService)
                    .cancel(Constants.NOTIF_ID_SNOOZE_STAY)
                // 媒体会话重新激活（上一场停止时已置 STATE_NONE + 失活）
                runCatching {
                    mediaSession?.isActive = true
                    updateMediaState()
                }
                val t0 = System.currentTimeMillis()
                val settings = readSettingsOrLast()
                val today = TimeUtils.localDate()
                var videos = repository.playableVideos()
                AppLogger.i(
                    TAG,
                    "起播准备完成：读设置 ${System.currentTimeMillis() - t0}ms，" +
                        "settings ${if (settings == null) "超时→用默认" else "正常"}，" +
                        "候选 ${videos.size} 条"
                )

                // 缓存为空才现场补救（定时同步被系统杀掉的场景）：
                // 有缓存则零延迟直接播，同步交给既有的 05:30/12:00/21:00 链路更新明天。
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

                // 缓存里无「当天」视频（博主 00:00 发、RSSHub 抓取有延迟时易发生），
                // 现场再做一次轻量同步：只下载最新 1~2 条，不重下旧视频。
                // 同步失败/超时/无网不影响原选片结果——有缓存就播。
                val firstSource = selectionPolicy.select(videos, today).source
                if (firstSource != SelectionPolicy.Source.TODAY && !isDeviceIdleMode()) {
                    AppLogger.i(TAG, "缓存无当天视频（source=$firstSource），响铃现场轻量同步")
                    val syncedNew = withTimeoutOrNull(RING_RESYNC_TIMEOUT_MS) {
                        runCatching { SyncEngine(this@AlarmService).syncTopN(n = 2) }.getOrNull()
                    } ?: false
                    if (syncedNew) {
                        videos = repository.playableVideos()
                        val rerun = selectionPolicy.select(videos, today)
                        if (rerun.source == SelectionPolicy.Source.TODAY) {
                            AppLogger.i(TAG, "响铃现场同步拿到当天视频")
                        }
                    }
                }
                if (settings == null) {
                    AppLogger.w(TAG, "读设置超时且无缓存值，按默认参数起播（优先出声）")
                }
                startMainWith(videos, today, settings ?: Settings())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "选片/起播异常，进入兜底", e)
                playFallback("异常: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun startMainWith(
        videos: List<VideoEntity>,
        today: String,
        settings: Settings
    ) {
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
        // 注意：这里**不能**用 fallbackEngaged 拦截。
        // 兜底与主音频共用同一个 player，playFile 会自然接管（旧声音被替换），无需阻断；
        // 反倒是阻断会造成「看门狗误判后主音频永久不播」——2026-09-17 实测踩到：
        // 衬托 5s 与看门狗 5s 撞点，兜底先起，主音频被这条检查拦下，从此整场无声。
        mainStarted = true
        cancelWatchdog()        // 主音频已出声，看门狗使命完成
        // 兜底（由看门狗或异常触发过）与主音频走**不同**音频通道，必须显式停掉，
        // 否则会两路声音叠加；过去靠 fallbackEngaged 阻断主音频是错的（已回退）。
        stopToneFallback()
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
        // 媒体卡元数据：视频标题 + 时长；进度条从主音频起播开始每秒前进
        runCatching {
            mediaSession?.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(
                        MediaMetadataCompat.METADATA_KEY_TITLE,
                        video.title.ifBlank { getString(R.string.notif_ring_title) }
                    )
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, getString(R.string.app_name))
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, player.durationMs())
                    .build()
            )
            mediaPaused = false
            updateMediaState()
            startMediaTicker()
        }.onFailure { AppLogger.w(TAG, "媒体卡元数据更新失败（不影响播放）", it) }
        serviceScope.launch {
            runCatching { repository.logPlayback(video.id, sourceLogValue) }
                .onFailure { AppLogger.w(TAG, "记录播放日志失败", it) }
        }
        AppLogger.i(TAG, "选片命中「${video.title}」，主音频起播")
    }

    /**
     * 第三级兜底：系统默认闹钟铃声，用 [Ringtone] API 播放。
     *
     * 不用 ExoPlayer 的原因：Android 15+ 起禁止其直接读系统铃声 URI
     * （ExoPlaybackException + "Direct file access no longer supported;
     * ringtone playback is available through android.media.Ringtone"），
     * 会让这一级直接失效并一路降级到蜂鸣。
     *
     * 已试过仍失败 / 无铃声 URI / 播放异常 → 直接进第四级 ToneGenerator 蜂鸣。
     */
    private fun playFallback(reason: String) {
        val alarmUri: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val ringtoneUri = alarmUri ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        if (ringtoneUri == null || ringtoneAttempted) {
            playToneFallback(reason)
            return
        }
        ringtoneAttempted = true

        val ringtone = runCatching { RingtoneManager.getRingtone(this, ringtoneUri) }
            .onFailure { AppLogger.w(TAG, "获取系统铃声失败（$reason）", it) }
            .getOrNull()
        if (ringtone == null) {
            playToneFallback("系统铃声不可用: $reason")
            return
        }

        val played = runCatching {
            ringtone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            ringtone.play()
            systemRingtone = ringtone
        }.onFailure {
            AppLogger.e(TAG, "系统铃声播放失败，进入蜂鸣兜底（$reason）", it)
        }.isSuccess

        if (!played) {
            playToneFallback("系统铃声异常: $reason")
            return
        }

        serviceScope.launch {
            runCatching { repository.logPlayback(null, Constants.SOURCE_FALLBACK) }
                .onFailure { AppLogger.w(TAG, "记录兜底播放日志失败", it) }
        }
        AppLogger.w(TAG, "兜底铃声已启用（Ringtone API，$reason）→ $ringtoneUri")
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

    // ---- 媒体会话（锁屏媒体大卡 / 系统媒体中心数据源） ----

    /**
     * 建媒体会话：onCreate 一次性创建，先于首次 startForeground——
     * MediaStyle 通知需要 sessionToken。回调把锁屏/耳机的播放控制接到本场响铃。
     */
    private fun createMediaSession() {
        if (mediaSession != null) return
        mediaSession = MediaSessionCompat(this, "GoodMorningAlarm").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { handlePlayPause() }
                override fun onPause() { handlePlayPause() }
                override fun onStop() { handleStop() }
                override fun onSeekTo(pos: Long) {
                    if (!::player.isInitialized) return
                    runCatching { player.seekTo(pos) }
                    updateMediaState()
                }
            })
            setPlaybackState(playbackState())
            isActive = true
        }
    }

    /** 当前媒体播放状态（主播放器位置；衬托期/起播前位置为 0，进度条不定长显示） */
    private fun playbackState(): PlaybackStateCompat {
        val paused = mediaPaused
        return PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(
                if (paused) PlaybackStateCompat.STATE_PAUSED else PlaybackStateCompat.STATE_PLAYING,
                if (::player.isInitialized) player.currentPosition() else 0L,
                if (paused) 0f else 1f
            )
            .build()
    }

    /** 刷新媒体会话状态（位置/播放态）——锁屏进度条由此前进 */
    private fun updateMediaState() {
        runCatching { mediaSession?.setPlaybackState(playbackState()) }
    }

    /** 每秒刷新一次媒体状态；时长解析完成后补写元数据（修复进度条 00:00/00:00） */
    private fun startMediaTicker() {
        mediaTickerJob?.cancel()
        mediaTickerJob = serviceScope.launch {
            var writtenDuration = 0L
            while (isActive && ringingGuard.get()) {
                val dur = if (::player.isInitialized) player.durationMs() else 0L
                if (dur > 0 && dur != writtenDuration) {
                    writtenDuration = dur
                    runCatching {
                        mediaSession?.setMetadata(
                            MediaMetadataCompat.Builder()
                                .putString(
                                    MediaMetadataCompat.METADATA_KEY_TITLE,
                                    lastNotifTitle ?: getString(R.string.notif_ring_title)
                                )
                                .putString(
                                    MediaMetadataCompat.METADATA_KEY_ARTIST,
                                    getString(R.string.app_name)
                                )
                                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, dur)
                                .build()
                        )
                    }
                }
                updateMediaState()
                delay(1_000L)
            }
        }
    }

    private fun stopMediaTicker() {
        mediaTickerJob?.cancel()
        mediaTickerJob = null
        mediaPaused = false
        // 会话置停止态并失活：否则系统媒体中心/锁屏会持续驻留上一场的媒体卡
        // （用户反馈：打开 App 后媒体卡常驻通知栏）
        runCatching {
            mediaSession?.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(PlaybackStateCompat.ACTION_STOP)
                    .setState(PlaybackStateCompat.STATE_NONE, 0L, 0f)
                    .build()
            )
            mediaSession?.isActive = false
        }
    }

    /**
     * 暂停/继续（通知按钮、锁屏媒体卡、耳机媒体键的统一入口）。
     * 仅在主音频已起播后有实际意义；衬托期忽略（副音频陪衬不宜单独暂停）。
     */
    private fun handlePlayPause() {
        if (!ringingGuard.get() || !::player.isInitialized || !mainStarted) return
        if (mediaPaused) {
            player.resume()
            mediaPaused = false
            AppLogger.i(TAG, "媒体卡操作：继续播放")
        } else {
            player.pause()
            mediaPaused = true
            AppLogger.i(TAG, "媒体卡操作：暂停播放")
        }
        updateMediaState()
        rebuildRingingNotification()
    }

    // ---- 控制命令 ----

    /**
     * 终止当前响铃场并立即开新一场（不打收尾登记：不注册明天闹钟、不排同步）。
     *
     * 两条触发路径：
     * - 测试键打断进行中的响铃（陈年反馈：以前必须先手动关掉上一场才能测下一场）
     * - **上一场处于暂停态时又来了新的到点**（暂停只静音、不结束场，若仍按「重复到点」
     *   忽略，后续每一次闹钟都会被吞掉 —— 见 2026-09-17 事故）
     *
     * 收尾登记交给新一场结束时的 [handleStop] 统一处理。
     */
    private fun forceRestartSession(reason: String) {
        AppLogger.i(TAG, "重开新一场（$reason）")
        stopToneFallback()
        leadJob?.cancel()
        leadJob = null
        stopMediaTicker()
        if (::player.isInitialized) {
            runCatching { player.stopAmbient() }
            runCatching { player.stop() }
        }
        mainStarted = false
        mediaPaused = false
        currentPlayingPath = null
        currentVideo = null
        ringtoneAttempted = false
        fallbackEngaged.set(false)
        sessionSeq++            // 旧场任何残留回调全部让位
        cancelWatchdog()
        ringingGuard.set(true)
        startRingWatchdog()     // 新一场同样需要看门狗兜底
        selectAndPlay()
    }

    /**
     * 响铃通知被划掉（暂停态下系统允许清除 MediaStyle）→ **停止本次响铃**。
     *
     * 历史：v2 起此处是「立即重建控制面板」，目的是不让用户误划后失去停止入口
     * （当时反馈：划掉后只能杀应用才能关闹钟）。
     * 2026-09-17 用户明确改选「划掉即停止」—— 语义更符合直觉（划掉 = 我不听了），
     * 通知内仍保留显式的「停止 / 贪睡」按钮作为常规操作路径。
     */
    private fun handleNotifDismissed() {
        if (!ringingGuard.get()) return
        AppLogger.i(TAG, "通知被划掉 → 停止本次响铃")
        handleStop()
    }

    /** 停止本次响铃：副音频即停，主音频 600ms 渐弱收尾后撤通知、注册明天闹钟、补调度同步 */
    private fun handleStop() {
        stopToneFallback()
        leadJob?.cancel()
        leadJob = null
        stopMediaTicker()
        runCatching { player.stopAmbient() }.onFailure { AppLogger.w(TAG, "停止副音频失败", it) }
        ringingGuard.set(false)
        ringtoneAttempted = false
        fallbackEngaged.set(false)
        currentPlayingPath = null
        currentVideo = null
        mainStarted = false
        cancelWatchdog()
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
        stopMediaTicker()
        runCatching { player.stopAmbient() }.onFailure { AppLogger.w(TAG, "停止副音频失败", it) }
        ringingGuard.set(false)
        ringtoneAttempted = false
        fallbackEngaged.set(false)
        currentPlayingPath = null
        currentVideo = null
        mainStarted = false
        cancelWatchdog()
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
                // 贪睡驻留通知：锁屏/通知栏可见下次响铃时刻（对齐小米原生「闹钟再响」驻留）
                showSnoozeStay(System.currentTimeMillis() + minutes * 60_000L)
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

    /**
     * 贪睡驻留通知：显示下次响铃时刻，回笼觉时锁屏可见（对齐小米原生「闹钟再响」驻留）。
     * 非 ongoing 可滑动清除（不影响真正的贪睡闹钟）；贪睡到点的新一场开始时自动撤掉。
     */
    private fun showSnoozeStay(wakeAt: Long) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val notif = NotificationCompat.Builder(this, Constants.CHANNEL_ALARM)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(TimeUtils.formatHm(wakeAt))
                .setContentText(getString(R.string.notif_snooze_stay_text))
                .setOngoing(false)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setOnlyAlertOnce(true)
                .build()
            NotificationManagerCompat.from(this).notify(Constants.NOTIF_ID_SNOOZE_STAY, notif)
        }.onFailure { AppLogger.w(TAG, "贪睡驻留通知发送失败", it) }
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
        // 第三级兜底（系统铃声）一并停掉，避免停止后铃声还在响
        stopSystemRingtone()
    }

    /** 停止系统铃声兜底（[Ringtone] API 播放，与 ToneGenerator 分开管理） */
    private fun stopSystemRingtone() {
        systemRingtone?.let { rt ->
            runCatching { if (rt.isPlaying) rt.stop() }
        }
        systemRingtone = null
    }

    // ---- 前台通知（响铃期间的控制面板） ----

    private fun startForegroundCompat() {
        val notification = buildRingingNotification()
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
        lastNotifTitle = title
        lastNotifText = text
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(Constants.NOTIF_ID_RINGING, buildRingingNotification(title, text))
        }.onFailure { AppLogger.w(TAG, "更新响铃通知失败", it) }
    }

    /** 暂停/继续后重建通知：按钮文案（暂停⇄继续）与媒体样式同步刷新 */
    private fun rebuildRingingNotification() {
        val title = lastNotifTitle ?: getString(R.string.notif_ring_title)
        val text = lastNotifText ?: getString(R.string.notif_ring_text)
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(Constants.NOTIF_ID_RINGING, buildRingingNotification(title, text))
        }.onFailure { AppLogger.w(TAG, "重建响铃通知失败", it) }
    }

    /**
     * 响铃通知（MediaStyle 媒体样式）：锁屏媒体大卡 + 系统媒体中心由此渲染，
     * 操作区 = [暂停/继续]（主音频起播后）+ 停止 + 贪睡。
     * 会话不可用（如 Receiver 抢先补发的兜底通知）时退回大文本样式。
     */
    private fun buildRingingNotification(
        title: String = getString(R.string.notif_ring_title),
        text: String = getString(R.string.notif_ring_text)
    ): Notification {
        val builder = NotificationCompat.Builder(this, Constants.CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            // 暂停态下系统允许用户划掉媒体通知：划掉 = 停止本次响铃（见 handleNotifDismissed）
            .setDeleteIntent(
                PendingIntent.getService(
                    this, REQUEST_CODE_NOTIF_DISMISS,
                    Intent(this, AlarmService::class.java)
                        .apply { action = Constants.ACTION_NOTIF_DISMISSED },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        if (mainStarted) builder.addAction(buildPauseAction())
        builder.addAction(buildStopAction(this))
        builder.addAction(buildSnoozeAction(this))
        val session = mediaSession
        if (session != null) {
            builder.setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(text))
        }
        return builder.build()
    }

    /** 「暂停/继续」按钮：文案与图标随当前播放态切换，统一走 ACTION_PLAY_PAUSE 切换 */
    private fun buildPauseAction(): NotificationCompat.Action =
        NotificationCompat.Action.Builder(
            if (mediaPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
            getString(if (mediaPaused) R.string.ringing_btn_resume else R.string.ringing_btn_pause),
            PendingIntent.getService(
                this, REQUEST_CODE_PLAY_PAUSE,
                Intent(this, AlarmService::class.java).apply { action = Constants.ACTION_PLAY_PAUSE },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        ).build()

    private fun SelectionPolicy.Source.toLogValue(): String = when (this) {
        SelectionPolicy.Source.TODAY -> Constants.SOURCE_TODAY
        SelectionPolicy.Source.CACHED -> Constants.SOURCE_CACHED
        SelectionPolicy.Source.FALLBACK -> Constants.SOURCE_FALLBACK
    }

    companion object {
        private const val TAG = Constants.TAG_PREFIX + "Service"
        private const val REQUEST_CODE_STOP = 3002
        private const val REQUEST_CODE_SNOOZE = 3003
        private const val REQUEST_CODE_PLAY_PAUSE = 3004
        private const val REQUEST_CODE_NOTIF_DISMISS = 3005

        /** 缓存为空时的响铃现场同步上限：超时即放弃网络、走兜底铃声（响铃不能久等） */
        private const val RING_SYNC_TIMEOUT_MS = 8_000L

        /** 缓存非空但无当天视频时的现场轻量同步上限：只补 1~2 条，比空缓存补齐快很多 */
        private const val RING_RESYNC_TIMEOUT_MS = 12_000L

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
                    .notify(Constants.NOTIF_ID_RINGING, buildFallbackNotification(context))
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

        /**
         * 兜底通知（Receiver 抢先补发用，大文本样式）：
         * 服务 onCreate 建好媒体会话后，startForeground 会以 MediaStyle 同 ID 无缝接管。
         */
        private fun buildFallbackNotification(
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
                // 与正式前台通知保持同一语义：划掉 = 停止本次响铃
                .setDeleteIntent(
                    PendingIntent.getService(
                        context, REQUEST_CODE_NOTIF_DISMISS,
                        Intent(context, AlarmService::class.java)
                            .apply { action = Constants.ACTION_NOTIF_DISMISSED },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .addAction(buildStopAction(context))
                .addAction(buildSnoozeAction(context))
                .build()
    }
}
