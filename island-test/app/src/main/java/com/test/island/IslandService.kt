package com.test.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * 上岛测试前台服务：MediaPlayer 循环播放 60s 测试音 + MediaSessionCompat + MediaStyle 通知。
 *
 * 超级岛/媒体卡片识别的是「活跃 MediaSession + 媒体样式通知」这套通用组合
 * （网易云等第三方音乐 App 上岛走的就是它，无需小米审核）。
 * 要点：
 * - session.isActive = true 且 playbackState 处于 PLAYING/PAUSED；
 * - setActions 带上 PLAY / PAUSE / PLAY_PAUSE / STOP / SEEK_TO（进度条可拖）；
 * - 每秒刷新一次 playbackState 的 position，保证岛/锁屏上的进度条前进；
 * - MediaMetadata 带 DURATION，媒体卡片才显示总时长。
 */
class IslandService : Service() {

    private var player: MediaPlayer? = null
    private var session: MediaSessionCompat? = null
    private var durationMs = 0L

    /** 进度基准：positionBase 为基准位置，baseElapsed 为其对应的单调时钟 */
    private var positionBase = 0L
    private var baseElapsed = 0L

    private var playing = false
    private val handler = Handler(Looper.getMainLooper())

    /** 每秒刷新播放状态（进度前进） */
    private val stateTicker = object : Runnable {
        override fun run() {
            if (playing) updatePlaybackState()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> toggle()
            ACTION_STOP -> stopEverything()
            else -> startEverything() // ACTION_START 及空 action 均视为开始
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(stateTicker)
        releasePlayer()
        releaseSession()
        status = "已停止"
        super.onDestroy()
    }

    // ---- 开始 / 暂停 / 停止 ----

    private fun startEverything() {
        createSessionIfNeeded()
        ensureForeground()
        startPlay()
    }

    private fun toggle() {
        if (playing) pause() else resumeOrStart()
    }

    private fun startPlay() {
        val p = player ?: createPlayer()
        if (durationMs > 0) updateMetadata()
        if (!p.isPlaying) {
            p.start()
            playing = true
            baseElapsed = SystemClock.elapsedRealtime()
            updatePlaybackState()
            rebuildNotification()
            status = "正在播放（看超级岛/锁屏有没有媒体卡）"
        }
        handler.removeCallbacks(stateTicker)
        handler.post(stateTicker)
    }

    private fun pause() {
        val p = player ?: return
        if (p.isPlaying) {
            positionBase = currentPosition()
            p.pause()
            playing = false
            updatePlaybackState()
            rebuildNotification()
            status = "已暂停"
        }
    }

    private fun resumeOrStart() {
        if (player == null) { startEverything(); return }
        player?.start()
        playing = true
        baseElapsed = SystemClock.elapsedRealtime()
        updatePlaybackState()
        rebuildNotification()
        status = "继续播放"
        handler.removeCallbacks(stateTicker)
        handler.post(stateTicker)
    }

    private fun stopEverything() {
        handler.removeCallbacks(stateTicker)
        releasePlayer()
        session?.isActive = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        status = "已停止"
    }

    // ---- MediaPlayer ----

    private fun createPlayer(): MediaPlayer {
        val p = MediaPlayer()
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        p.isLooping = true
        val afd = resources.openRawResourceFd(R.raw.test_tone)
        p.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        afd.close()
        p.prepare()
        p.setOnCompletionListener {
            // isLooping=true 理论不会触发；防御：结束后重播保持会话活跃
            positionBase = 0L
            baseElapsed = SystemClock.elapsedRealtime()
            p.start()
        }
        durationMs = p.duration.toLong()
        player = p
        return p
    }

    private fun releasePlayer() {
        player?.let { runCatching { it.stop(); it.release() } }
        player = null
        playing = false
    }

    /** 当前播放位置（循环取模） */
    private fun currentPosition(): Long {
        if (!playing) return positionBase
        val dur = if (durationMs > 0) durationMs else 60_000L
        val elapsed = SystemClock.elapsedRealtime() - baseElapsed
        return (positionBase + elapsed) % dur
    }

    // ---- MediaSession ----

    private fun createSessionIfNeeded() {
        if (session != null) return
        val s = MediaSessionCompat(this, "IslandTest")
        s.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() { resumeOrStart() }
            override fun onPause() { pause() }
            override fun onStop() { stopEverything() }
            override fun onSeekTo(pos: Long) {
                positionBase = pos.coerceAtLeast(0L)
                baseElapsed = SystemClock.elapsedRealtime()
                player?.seekTo((positionBase % (if (durationMs > 0) durationMs else 60_000L)).toInt())
                updatePlaybackState()
            }
        })
        s.isActive = true
        session = s
        updateMetadata()
    }

    /** 元数据（时长在 player 创建后才可得，故独立方法，可在 player 就绪后重复调用） */
    private fun updateMetadata() {
        session?.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "超级岛上岛测试")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "IslandTest")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
                .build()
        )
    }

    private fun releaseSession() {
        session?.release()
        session = null
    }

    private fun updatePlaybackState() {
        val s = session ?: return
        val state = if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        s.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(state, currentPosition(), if (playing) 1f else 0f)
                .build()
        )
    }

    // ---- 通知 ----

    private fun ensureForeground() {
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
    }

    private fun rebuildNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val toggleAction = NotificationCompat.Action.Builder(
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (playing) "暂停" else "继续",
            pendingAction(ACTION_TOGGLE, REQUEST_TOGGLE)
        ).build()
        val stopAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_delete, "停止", pendingAction(ACTION_STOP, REQUEST_STOP)
        ).build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("超级岛上岛测试")
            .setContentText(if (playing) "正在播放测试音 · 看能不能上岛" else "已暂停")
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .addAction(toggleAction)
            .addAction(stopAction)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session?.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .build()
    }

    private fun pendingAction(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, IslandService::class.java).apply { this.action = action },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "上岛测试", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        private const val CHANNEL_ID = "island_test"
        private const val NOTIF_ID = 1
        private const val REQUEST_TOGGLE = 11
        private const val REQUEST_STOP = 12

        const val ACTION_START = "com.test.island.START"
        const val ACTION_TOGGLE = "com.test.island.TOGGLE"
        const val ACTION_STOP = "com.test.island.STOP"

        /** 主页轮询展示的状态（主线程写，主线程读，无需同步） */
        @Volatile var status: String = "空闲"

        fun start(context: Context, action: String) {
            val intent = Intent(context, IslandService::class.java).apply { this.action = action }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
