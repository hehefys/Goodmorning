package com.goodmorning.alarm.sync

import android.content.Context
import com.goodmorning.alarm.data.db.AppDatabase
import com.goodmorning.alarm.data.db.VideoEntity
import com.goodmorning.alarm.data.prefs.SettingsRepository
import com.goodmorning.alarm.data.repo.CacheCleaner
import com.goodmorning.alarm.network.Http
import com.goodmorning.alarm.network.RssFeedParser
import com.goodmorning.alarm.network.VideoDownloader
import com.goodmorning.alarm.network.VideoItem
import com.goodmorning.alarm.util.AppLogger
import com.goodmorning.alarm.util.Constants
import com.goodmorning.alarm.util.TimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 同步错误分类（ARCHITECTURE.md §7 错误码的强类型化，替代“文案 contains”的脆弱判断）：
 * - [Network]：IO/超时 → 结果前缀 NETWORK:
 * - [Parse]：JSON 结构/字段缺失 → 结果前缀 PARSE:
 * - [Empty]：条目为空（可能被 WAF 拦截）→ 结果前缀 EMPTY:
 *
 * 继承 [Exception] 以复用异常控制流：由 [SyncEngine.fetchAndParse] 抛出、[SyncEngine.sync] 统一捕获归类。
 */
sealed class SyncError(message: String) : Exception(message) {
    data class Network(val msg: String) : SyncError(msg)
    data class Parse(val msg: String) : SyncError(msg)
    data class Empty(val msg: String) : SyncError(msg)
}

/**
 * 核心同步编排（ARCHITECTURE.md §1.4 数据流）：
 * 拉取 RSSHub → 解析 → upsert Room → 下载最新 N 条 → 清缓存 → 写同步状态。
 *
 * 错误分类（SyncResult.msg 前缀，共享约定 §7）：
 * - NETWORK: IO/超时
 * - PARSE:   JSON 结构/字段缺失
 * - EMPTY:   条目为空（可能被 WAF 拦截）
 * - DOWNLOAD: 单条下载失败（不致命，有 1 条成功即算部分成功）
 */
class SyncEngine(context: Context) {

    private val appContext = context.applicationContext
    private val settingsRepository = SettingsRepository(appContext)
    private val videoDao = AppDatabase.get(appContext).videoDao()
    private val parser = RssFeedParser()
    private val downloader = VideoDownloader()
    private val cacheCleaner = CacheCleaner(appContext)

    /** 同步结果（写入 DataStore 供主页/设置页展示） */
    data class SyncResult(
        /** 是否成功（部分成功也算成功） */
        val ok: Boolean,
        /** 同步后本地可播放条数 */
        val availableCount: Int,
        /** 结果描述（成功为“同步成功”或降级说明；失败为“错误码: 详情”） */
        val msg: String
    )

    /**
     * 执行一次完整同步。任何异常都在内部消化并转化为 [SyncResult]，绝不向上抛。
     */
    suspend fun sync(): SyncResult = withContext(Dispatchers.IO) {
        val settings = settingsRepository.current()
        val baseUrl = settings.rsshubBaseUrl.trimEnd('/')

        // 换博主检测（V2 功能 A）：上一次同步的博主与当前不同 → 先清空旧缓存（DB + 文件），
        // 防止旧博主视频被继续播放/选中；首次同步（last 为空）不清。
        val lastSecUid = settingsRepository.lastBloggerSecUid()
        if (lastSecUid.isNotEmpty() && lastSecUid != settings.bloggerSecUid) {
            val deleted = runCatching { cacheCleaner.clearAll() }
                .onFailure { AppLogger.e(TAG, "换博主清缓存失败", it as? Exception) }
                .getOrDefault(0)
            AppLogger.i(TAG, "检测到更换博主（$lastSecUid → ${settings.bloggerSecUid}），已清空旧缓存 $deleted 个文件")
        }
        settingsRepository.setLastBloggerSecUid(settings.bloggerSecUid)

        // feedUrl 使用当前设置的博主 secUid（V2：替代旧 Constants.SEC_UID 硬编码）
        val feedUrl = Constants.RSSHUB_ROUTE_TEMPLATE.format(baseUrl, settings.bloggerSecUid)
        AppLogger.i(TAG, "开始同步：$feedUrl")

        val items: List<VideoItem> = try {
            fetchAndParse(feedUrl)
        } catch (e: SyncError) {
            val code = when (e) {
                is SyncError.Network -> "NETWORK"
                is SyncError.Parse -> "PARSE"
                is SyncError.Empty -> "EMPTY"
            }
            return@withContext finish(false, 0, "$code:${e.message}")
        }

        if (items.none { !it.videoUrl.isNullOrBlank() }) {
            return@withContext finish(
                false, 0,
                "PARSE:条目缺少视频直链（请确认 RSSHub 实例支持 embed=1 或更换自建实例）"
            )
        }

        // ① 元数据 upsert（按 aweme_id 去重；保留已下载文件的 localPath 等字段）
        val existingById = videoDao.getAll().associateBy { it.id }
        val entities = items.map { item -> mergeExisting(toEntity(item), existingById[item.id]) }
        videoDao.upsertAll(entities)
        AppLogger.i(TAG, "解析到 ${entities.size} 条视频元数据")

        // ② 下载最新的 N 条（直链有时效，同步后立即下载）
        val targets = entities
            .sortedByDescending { it.publishTimeMillis }
            .take(Constants.CACHE_KEEP_COUNT)
        var successCount = 0
        val failures = mutableListOf<String>()
        for (entity in targets) {
            val url = entity.videoUrl
            if (url.isNullOrBlank()) {
                failures.add("${entity.id}:无直链")
                continue
            }
            // 已有可用本地文件则跳过重复下载
            val existing = entity.localPath
            if (!existing.isNullOrBlank() && File(existing).isFile) {
                successCount++
                continue
            }
            try {
                val dest = File(
                    File(appContext.filesDir, Constants.VIDEO_DIR),
                    "${entity.id}.mp4"
                )
                val file = downloader.download(url, dest)
                videoDao.upsertAll(
                    listOf(
                        entity.copy(
                            localPath = file.absolutePath,
                            fileSize = file.length(),
                            downloadedAt = System.currentTimeMillis()
                        )
                    )
                )
                successCount++
            } catch (e: Exception) {
                AppLogger.e(TAG, "下载失败 ${entity.id}", e)
                failures.add("${entity.id}:${e.message}")
            }
        }

        // ③ 清理超出保留数的旧缓存
        runCatching { cacheCleaner.clean() }
            .onFailure { AppLogger.e(TAG, "缓存清理失败", it as? Exception) }

        // ④ 统计可用条数并落结果
        val available = videoDao.getAll().count { !it.localPath.isNullOrBlank() && File(it.localPath!!).isFile }
        val result = when {
            successCount == 0 && targets.isNotEmpty() ->
                SyncResult(false, available, "DOWNLOAD:全部下载失败（${failures.joinToString("；")}）")
            failures.isNotEmpty() ->
                SyncResult(true, available, "同步成功，${successCount}/${targets.size} 条下载完成")
            else ->
                SyncResult(true, available, "同步成功，新增/保有 $successCount 条缓存")
        }
        return@withContext finish(result.ok, result.availableCount, result.msg)
    }

    // ---- 内部实现 ----

    /**
     * 拉取并解析 RSS Feed。
     * 失败以 [SyncError] 抛出：网络/IO → [SyncError.Network]；结构非法 → [SyncError.Parse]；
     * 条目为空（数组为空或全部过滤掉）→ [SyncError.Empty]。
     */
    private fun fetchAndParse(feedUrl: String): List<VideoItem> {
        val request = okhttp3.Request.Builder().url(feedUrl).get().build()
        try {
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw SyncError.Network("HTTP ${response.code}：${response.message}")
                }
                val body = response.body?.string() ?: throw SyncError.Network("响应体为空")
                val items = parser.parse(body)
                if (items.isEmpty()) {
                    throw SyncError.Empty("条目为空（可能被 WAF 拦截或用户未更新）")
                }
                return items
            }
        } catch (e: SyncError) {
            throw e
        } catch (e: RssFeedParser.EmptyFeedException) {
            // 条目数组为空：类型化归入 EMPTY（不再依赖 message 文案）
            throw SyncError.Empty(e.message ?: "条目为空")
        } catch (e: RssFeedParser.RssParseException) {
            throw SyncError.Parse(e.message ?: "解析失败")
        } catch (e: IOException) {
            throw SyncError.Network(e.message ?: "网络错误")
        } catch (e: Exception) {
            throw SyncError.Network("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun toEntity(item: VideoItem): VideoEntity = VideoEntity(
        id = item.id,
        title = item.title,
        publishTimeMillis = item.publishTimeMillis,
        // pubDate 无法解析（0）时置空串，选片时按规则②处理
        publishDate = if (item.publishTimeMillis > 0) TimeUtils.localDate(item.publishTimeMillis) else "",
        pageUrl = item.pageUrl,
        videoUrl = item.videoUrl,
        localPath = null,
        fileSize = 0,
        downloadedAt = null
    )

    /** 合并旧记录：同一条视频已成功下载过则保留 localPath/fileSize/downloadedAt，避免 upsert 抹掉 */
    private fun mergeExisting(
        fresh: VideoEntity,
        existing: VideoEntity?
    ): VideoEntity {
        if (existing == null) return fresh
        val path = existing.localPath
        return if (!path.isNullOrBlank() && File(path).isFile) {
            fresh.copy(
                localPath = path,
                fileSize = existing.fileSize,
                downloadedAt = existing.downloadedAt
            )
        } else {
            fresh
        }
    }

    /** 写入 DataStore 同步状态并返回结果 */
    private suspend fun finish(ok: Boolean, availableCount: Int, msg: String): SyncResult {
        settingsRepository.setSyncResult(ok, msg)
        AppLogger.i(TAG, "同步结束 ok=$ok 可用=$availableCount msg=$msg")
        return SyncResult(ok, availableCount, msg)
    }

    private companion object {
        const val TAG = Constants.TAG_PREFIX + "Sync"
    }
}
