package com.goodmorning.alarm.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 播放历史 DAO（首版轻量：插入 + 查最近 30 条）。
 */
@Dao
interface PlaybackLogDao {

    @Insert
    suspend fun insert(entry: PlaybackLogEntity)

    @Query("SELECT * FROM playback_logs ORDER BY playedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 30): Flow<List<PlaybackLogEntity>>

    @Query("SELECT * FROM playback_logs ORDER BY playedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 30): List<PlaybackLogEntity>
}
