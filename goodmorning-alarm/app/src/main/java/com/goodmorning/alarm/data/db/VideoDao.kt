package com.goodmorning.alarm.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 视频表 DAO（方法签名与 ARCHITECTURE.md §3.1 保持一致）。
 */
@Dao
interface VideoDao {

    @Upsert
    suspend fun upsertAll(videos: List<VideoEntity>)

    @Query("SELECT * FROM videos ORDER BY publishTimeMillis DESC")
    fun observeAll(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos WHERE localPath IS NOT NULL ORDER BY publishTimeMillis DESC LIMIT 1")
    suspend fun latestDownloaded(): VideoEntity?

    @Query(
        "SELECT * FROM videos WHERE localPath IS NOT NULL AND publishDate = :date " +
            "ORDER BY publishTimeMillis DESC LIMIT 1"
    )
    suspend fun latestDownloadedOnDate(date: String): VideoEntity?

    @Query("SELECT * FROM videos ORDER BY publishTimeMillis DESC")
    suspend fun getAll(): List<VideoEntity>

    @Query("DELETE FROM videos WHERE id NOT IN (:keepIds)")
    suspend fun deleteExcept(keepIds: List<String>)

    @Query("SELECT COALESCE(SUM(fileSize), 0) FROM videos WHERE localPath IS NOT NULL")
    suspend fun cacheBytes(): Long

    @Query("DELETE FROM videos")
    suspend fun deleteAll()
}
