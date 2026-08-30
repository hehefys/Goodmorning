package com.goodmorning.alarm.data.repo

import android.content.Context
import com.goodmorning.alarm.data.db.AppDatabase
import com.goodmorning.alarm.util.AppLogger
import com.goodmorning.alarm.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 缓存清理：只保留最近 [Constants.CACHE_KEEP_COUNT] 条（按发布时间倒序），
 * 删除多余本地文件与对应 DB 记录，并统计缓存占用大小。
 */
class CacheCleaner(context: Context) {

    private val appContext = context.applicationContext
    private val videoDao = AppDatabase.get(appContext).videoDao()
    private val videoDir = File(appContext.filesDir, Constants.VIDEO_DIR)

    /**
     * 执行清理，返回删除的文件数。
     * keep 集合为发布时间最新的 N 条记录 id；超出者删除文件与记录。
     */
    suspend fun clean(keepCount: Int = Constants.CACHE_KEEP_COUNT): Int =
        withContext(Dispatchers.IO) {
            val all = videoDao.getAll().sortedByDescending { it.publishTimeMillis }
            if (all.isEmpty()) return@withContext 0

            val keepIds = all.take(keepCount).map { it.id }
            val outdated = all.drop(keepCount)

            var deleted = 0
            for (entity in outdated) {
                val path = entity.localPath
                if (!path.isNullOrBlank()) {
                    val file = File(path)
                    if (file.exists() && file.delete()) deleted++
                }
            }
            videoDao.deleteExcept(keepIds)

            // 额外清理目录中不再被任何记录引用的 *.part 残留文件
            cleanOrphanParts()

            AppLogger.i(
                TAG,
                "缓存清理完成：保留 ${keepIds.size} 条，删除 ${outdated.size} 条记录 / $deleted 个文件"
            )
            deleted
        }

    /** 清理下载中断遗留的 *.part 文件（part 文件不属于任何成功下载的记录） */
    private fun cleanOrphanParts() {
        val files = videoDir.listFiles() ?: return
        for (file in files) {
            if (file.name.endsWith(".part") && file.delete()) {
                AppLogger.w(TAG, "清理下载残留文件：${file.name}")
            }
        }
    }

    /** 当前缓存目录占用字节数 */
    suspend fun cacheBytes(): Long = withContext(Dispatchers.IO) {
        videoDao.cacheBytes()
    }

    /** 清空全部缓存（DB + 文件） */
    suspend fun clearAll(): Int = withContext(Dispatchers.IO) {
        var deleted = 0
        videoDir.listFiles()?.forEach { file ->
            if (file.delete()) deleted++
        }
        videoDao.deleteAll()
        AppLogger.i(TAG, "手动清空缓存：删除 $deleted 个文件")
        deleted
    }

    private companion object {
        const val TAG = Constants.TAG_PREFIX + "Cleaner"
    }
}
