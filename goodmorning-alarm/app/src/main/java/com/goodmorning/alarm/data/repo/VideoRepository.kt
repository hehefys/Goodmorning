package com.goodmorning.alarm.data.repo

import android.content.Context
import com.goodmorning.alarm.data.db.AppDatabase
import com.goodmorning.alarm.data.db.PlaybackLogEntity
import com.goodmorning.alarm.data.db.VideoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 数据层对上层的门面：统一封装 Room DAO 与缓存文件的访问。
 * 上层（Service / SyncEngine / ViewModel）只依赖本类，不直接触碰 DAO 与磁盘布局。
 */
class VideoRepository(context: Context) {

    private val appContext = context.applicationContext
    private val videoDao = AppDatabase.get(appContext).videoDao()
    private val playbackLogDao = AppDatabase.get(appContext).playbackLogDao()

    /** 视频缓存目录 filesDir/videos/ */
    fun videoDir(): File = File(appContext.filesDir, com.goodmorning.alarm.util.Constants.VIDEO_DIR)

    /** 全部缓存视频（按发布时间倒序） */
    fun cachedVideos(): Flow<List<VideoEntity>> = videoDao.observeAll()

    /** 最新一条已下载视频（规则②选片输入） */
    suspend fun latestDownloaded(): VideoEntity? = videoDao.latestDownloaded()

    /** 指定自然日最新一条已下载视频（规则①选片输入） */
    suspend fun latestDownloadedOn(date: String): VideoEntity? =
        videoDao.latestDownloadedOnDate(date)

    /**
     * 所有“本地文件确实存在”的已下载视频（选片实际候选集）。
 * 文件被系统清理但 DB 记录仍在的脏数据在此过滤，避免选片后播放失败。
     */
    suspend fun playableVideos(): List<VideoEntity> = withContext(Dispatchers.IO) {
        videoDao.getAll()
            .filter { !it.localPath.isNullOrBlank() && File(it.localPath!!).isFile }
            .sortedByDescending { it.publishTimeMillis }
    }

    /** 写一条播放历史 */
    suspend fun logPlayback(videoId: String?, source: String) = withContext(Dispatchers.IO) {
        playbackLogDao.insert(
            PlaybackLogEntity(
                date = com.goodmorning.alarm.util.TimeUtils.localDate(),
                videoId = videoId,
                source = source,
                playedAt = System.currentTimeMillis()
            )
        )
    }

    /** 播放历史（最近 30 条） */
    fun playbackHistory(): Flow<List<PlaybackLogEntity>> = playbackLogDao.observeRecent()

    /** 当前缓存占用字节数 */
    suspend fun cacheBytes(): Long = videoDao.cacheBytes()

    /** 清空缓存（DB 记录 + 本地文件，供设置页手动清理） */
    suspend fun clearCache(): Int = withContext(Dispatchers.IO) {
        val dir = videoDir()
        var deletedFiles = 0
        dir.listFiles()?.forEach { file ->
            if (file.delete()) deletedFiles++
        }
        videoDao.deleteAll()
        deletedFiles
    }

    /** 删除指定 id 的本地文件（用于坏文件清理） */
    suspend fun deleteLocalFile(entity: VideoEntity) = withContext(Dispatchers.IO) {
        entity.localPath?.let { path -> File(path).delete() }
    }
}
