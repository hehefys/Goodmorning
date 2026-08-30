package com.goodmorning.alarm.playback

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.goodmorning.alarm.util.AppLogger
import com.goodmorning.alarm.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * ExoPlayer 封装：纯音频播本地 mp4 / 系统铃声 URI。
 *
 * 关键行为（ARCHITECTURE.md §1.2 / §1.5）：
 * - AudioAttributes 走 USAGE_ALARM（系统闹钟音量通道）；
 * - 禁用视频轨（纯音频，不渲染画面）；
 * - setWakeMode(WAKE_MODE_LOCAL)：息屏 + Doze 下保持 CPU 唤醒，播放不因休眠停摆（E2）；
 * - 音量渐强：30% 起，每 500ms 步进，在设置时长内线性升至 100%（可在设置关闭/调节）；
 *   渐强窗口按“起播墙钟时刻”推进，暂停后 resume 会按剩余窗口重建渐强，
 *   保证任何暂停/恢复序列下最终音量必然达到 100%（E1）；
 * - 播放错误回调由 AlarmService 触发兜底降级（P0-4 绝不哑火）。
 */
class AlarmPlayer(private val context: Context) {

    /** 播放错误（文件损坏/解码失败等），由外部触发兜底降级 */
    var onError: ((Throwable) -> Unit)? = null

    /** 自然播完（用于闹钟结束并注册次日） */
    var onEnded: (() -> Unit)? = null

    /** 播放/暂停状态变化（用于响铃页按钮状态） */
    var onIsPlayingChanged: ((Boolean) -> Unit)? = null

    /**
     * 副音频一轮自然播完（单轮模式）或播放错误（降级为结束处理）时回调，
     * 由 AlarmService 绑定重播触发；循环模式（repeat=ONE）下不会触发。
     */
    var onAmbientEnded: (() -> Unit)? = null

    /**
     * 音量渐强进度回调（V2 响铃页光晕用）：0..1，渐强循环内每 FADE_STEP_MS 步进一次；
     * 渐强关闭时 [playMedia] 回调常量 0.5f。
     */
    var onVolumeProgress: ((Float) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 视频轨禁用的 TrackSelector */
    private val trackSelector = DefaultTrackSelector(context).apply {
        parameters = buildUponParameters()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .build()
    }

    /** 播放器监听：必须先于 [player] 声明（player 初始化时 addListener 引用它） */
    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            AppLogger.e(TAG, "播放器错误 errorCode=${error.errorCodeName}", error)
            onError?.invoke(error)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                AppLogger.i(TAG, "播放自然结束")
                onEnded?.invoke()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            onIsPlayingChanged?.invoke(isPlaying)
        }
    }

    /** 副音频播放器监听：单轮模式播完 → 重播触发；错误降级为结束，保证主链路继续 */
    private val ambientListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                AppLogger.i(TAG, "副音频一轮播放结束")
                onAmbientEnded?.invoke()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            AppLogger.e(TAG, "副音频播放错误，降级为结束处理", error)
            onAmbientEnded?.invoke()
        }
    }

    /** 闹钟音频属性（主/副播放器共用；USAGE_ALARM 通道）。
     *  handleAudioFocus 必须为 false：Media3 仅允许 MEDIA/GAME 自动管焦点，
     *  USAGE_ALARM + true 会抛 IllegalArgumentException 使服务创建即崩（真机日志实锤）。 */
    private val alarmAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_ALARM)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setTrackSelector(trackSelector)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .setAudioAttributes(alarmAttributes, /* handleAudioFocus = */ false)
        .build()
        .also { it.addListener(playerListener) }

    private var fadeJob: Job? = null

    // ---- 副音频轨（循环陪衬，主音频播放期间自动压低） ----

    private var ambientPlayer: ExoPlayer? = null
    private var ambientFadeJob: Job? = null

    /** 副音频是否正在循环播放 */
    val isAmbientPlaying: Boolean get() = ambientPlayer?.isPlaying == true

    /**
     * 启动副音频循环（重复调用会先释放旧实例）。
     * [startMs]/[endMs] 裁剪播放区间：0 = 从头播 / 播到结尾；循环时在裁剪区间内重复。
     * [loop] = false 时单轮播放：播到裁剪终点/文件结尾即触发 [onAmbientEnded]（重播链路用）。
     */
    fun startAmbient(
        uri: Uri,
        baseVolume: Float,
        startMs: Long = 0L,
        endMs: Long = 0L,
        loop: Boolean = true
    ) {
        stopAmbient()
        val p = ExoPlayer.Builder(context).build()
        p.setAudioAttributes(alarmAttributes, /* handleAudioFocus = */ false)
        p.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        p.volume = baseVolume.coerceIn(0f, 1f)
        p.setMediaItem(clipIfSet(uri, startMs, endMs))
        p.addListener(ambientListener)
        p.prepare()
        p.play()
        ambientPlayer = p
        AppLogger.i(
            TAG,
            "副音频已启动（音量 ${(baseVolume * 100).toInt()}%，裁剪 ${startMs}..${endMs}ms，" +
                if (loop) "循环" else "单轮"
        )
    }

    /** 裁剪区间任一端设置时套用 ClippingConfiguration（对本地渐进式媒体生效，循环在区间内重复） */
    private fun clipIfSet(uri: Uri, startMs: Long, endMs: Long): MediaItem {
        if (startMs <= 0L && endMs <= 0L) return MediaItem.fromUri(uri)
        val clip = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startMs.coerceAtLeast(0L))
        if (endMs > 0L) clip.setEndPositionMs(endMs)
        return MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(clip.build())
            .build()
    }

    /** 主音频起播：副音频渐变压低 */
    fun duckAmbient(duckedVolume: Float) {
        fadeAmbientTo(duckedVolume.coerceIn(0f, 1f))
        AppLogger.i(TAG, "副音频压低至 ${(duckedVolume * 100).toInt()}%")
    }

    /** 主音频播完：副音频渐变恢复 */
    fun restoreAmbient(baseVolume: Float) {
        fadeAmbientTo(baseVolume.coerceIn(0f, 1f))
        AppLogger.i(TAG, "副音频恢复至 ${(baseVolume * 100).toInt()}%")
    }

    /** 停止并释放副音频（先摘监听，避免 stop/release 触发结束回调造成误重播） */
    fun stopAmbient() {
        ambientFadeJob?.cancel()
        ambientFadeJob = null
        ambientPlayer?.let { p ->
            runCatching {
                p.removeListener(ambientListener)
                p.stop()
                p.release()
            }
        }
        ambientPlayer = null
    }

    /** 副音频音量线性渐变（AMBIENT_FADE_MS 内到位） */
    private fun fadeAmbientTo(target: Float) {
        val p = ambientPlayer ?: return
        ambientFadeJob?.cancel()
        ambientFadeJob = scope.launch {
            val from = p.volume
            val steps = (Constants.AMBIENT_FADE_MS / 100L).coerceAtLeast(1)
            val increment = (target - from) / steps
            repeat(steps.toInt()) {
                if (!isActive) return@launch
                p.volume = (p.volume + increment).coerceIn(0f, 1f)
                delay(100)
            }
            p.volume = target
        }
    }

    /**
     * 渐强窗口描述：从起播时刻（elapsedRealtime）起的 fadeDurationMs 时长。
     * 暂停不重置窗口——恢复时按墙钟剩余时间续算，暂停过久则直接补齐 100%。
     */
    private data class FadeWindow(val startedAtElapsed: Long, val totalMs: Long)

    private var fadeWindow: FadeWindow? = null

    val isPlaying: Boolean get() = player.isPlaying

    /** 播放本地 mp4 文件（纯音频模式） */
    fun playFile(file: File, fade: Boolean, fadeDurationMs: Long = DEFAULT_FADE_MS) {
        playMedia(Uri.fromFile(file), fade, fadeDurationMs)
    }

    /** 播放系统铃声 URI（第三级兜底：RingtoneManager 默认闹钟铃声） */
    fun playUri(uri: Uri, fade: Boolean, fadeDurationMs: Long = DEFAULT_FADE_MS) {
        playMedia(uri, fade, fadeDurationMs)
    }

    fun pause() {
        // 只取消协程、保留 fadeWindow：resume 时按剩余窗口重建渐强（E1）
        fadeJob?.cancel()
        player.pause()
    }

    fun resume() {
        player.play()
        resumeVolumeFadeIfNeeded()
    }

    fun stop() {
        fadeJob?.cancel()
        fadeWindow = null
        player.stop()
        player.clearMediaItems()
    }

    fun release() {
        fadeJob?.cancel()
        fadeWindow = null
        scope.cancel()
        stopAmbient()
        player.removeListener(playerListener)
        player.release()
    }

    // ---- 内部实现 ----

    private fun playMedia(uri: Uri, fade: Boolean, fadeDurationMs: Long) {
        fadeJob?.cancel()
        player.setMediaItem(MediaItem.fromUri(uri))
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.prepare()
        if (fade) {
            fadeWindow = FadeWindow(SystemClock.elapsedRealtime(), fadeDurationMs)
            onVolumeProgress?.invoke(0f)
            startVolumeFade(fromVolume = Constants.FADE_START, durationMs = fadeDurationMs)
        } else {
            fadeWindow = null
            player.volume = 1f
            // 渐强关闭：光晕固定中档亮度（DESIGN-V2 §2.2）
            onVolumeProgress?.invoke(0.5f)
        }
        player.play()
        AppLogger.i(TAG, "开始播放：$uri（渐强=$fade）")
    }

    /**
     * 从 [fromVolume] 出发，在 [durationMs] 内线性升至 1.0，每 FADE_STEP_MS 步进一次。
     */
    private fun startVolumeFade(fromVolume: Float, durationMs: Long) {
        player.volume = fromVolume
        fadeJob = scope.launch {
            val steps = (durationMs / Constants.FADE_STEP_MS).coerceAtLeast(1)
            val increment = (1f - fromVolume) / steps
            repeat(steps.toInt()) { index ->
                delay(Constants.FADE_STEP_MS)
                if (!isActive) return@launch
                player.volume = (player.volume + increment).coerceAtMost(1f)
                // 渐强进度同步透出（响铃页日出光晕跟随增亮）
                onVolumeProgress?.invoke(
                    ((index + 1) * Constants.FADE_STEP_MS / durationMs.toFloat()).coerceIn(0f, 1f)
                )
            }
            player.volume = 1f
            onVolumeProgress?.invoke(1f)
            fadeWindow = null
        }
    }

    /**
     * 暂停后恢复渐强（E1）：
     * - 墙钟已超出渐强窗口 → 音量直接补齐 100%；
     * - 仍有剩余窗口 → 从当前音量出发、在剩余时长内线性升至 100%。
     */
    private fun resumeVolumeFadeIfNeeded() {
        val window = fadeWindow ?: return
        val elapsed = SystemClock.elapsedRealtime() - window.startedAtElapsed
        val remainingMs = window.totalMs - elapsed
        fadeJob?.cancel()
        if (remainingMs <= 0) {
            player.volume = 1f
            fadeWindow = null
            AppLogger.i(TAG, "渐强窗口已过，恢复时音量补齐 100%")
            return
        }
        val currentVolume = player.volume.coerceAtLeast(Constants.FADE_START)
        AppLogger.i(
            TAG,
            "恢复渐强：当前音量 $currentVolume，剩余 ${remainingMs}ms 升至 100%"
        )
        startVolumeFade(fromVolume = currentVolume, durationMs = remainingMs)
    }

    private companion object {
        const val TAG = Constants.TAG_PREFIX + "Play"

        /** 未显式传入渐强时长时的兜底值（设置默认 20 秒） */
        val DEFAULT_FADE_MS = Constants.FADE_DEFAULT_SECONDS * 1000L
    }
}
