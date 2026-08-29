package com.goodmorning.alarm.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 播放历史（P2-4 轻量版：记录每天实际播放了哪条音频及其来源）。
 */
@Entity(tableName = "playback_logs")
data class PlaybackLogEntity(
    /** 自增主键 */
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 响铃自然日 yyyy-MM-dd */
    val date: String,
    /** 实际播放的视频 id；null = 兜底铃声 */
    val videoId: String?,
    /** TODAY / CACHED / FALLBACK */
    val source: String,
    /** 播放时刻 epoch ms */
    val playedAt: Long
)
