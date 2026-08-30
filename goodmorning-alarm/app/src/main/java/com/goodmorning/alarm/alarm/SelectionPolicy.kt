package com.goodmorning.alarm.alarm

import com.goodmorning.alarm.data.db.VideoEntity
import com.goodmorning.alarm.util.Constants

/**
 * 选片规则（PRD §5 / ARCHITECTURE.md §3.2）：
 * ① 发布日期 == 响铃日期（本地自然日）→ 取其中发布时间最新一条，标记 TODAY；
 * ② 否则取缓存中发布时间最新一条（最近一期），标记 CACHED；
 * ③ 无任何可用缓存 → FALLBACK（兜底铃声，video 为 null）。
 */
class SelectionPolicy {

    /** 播放来源 */
    enum class Source { TODAY, CACHED, FALLBACK }

    /** 选片结果 */
    data class SelectionResult(
        val video: VideoEntity?,
        val source: Source
    ) {
        val isFallback: Boolean get() = source == Source.FALLBACK
    }

    /**
     * 从“本地文件确实存在”的候选集中选片（调用方 [com.goodmorning.alarm.data.repo.VideoRepository.playableVideos]）。
     * @param today 本地时区自然日 yyyy-MM-dd
     */
    fun select(videos: List<VideoEntity>, today: String): SelectionResult {
        val downloadable = videos
            .filter { !it.localPath.isNullOrBlank() }
            .sortedByDescending { it.publishTimeMillis }
        if (downloadable.isEmpty()) {
            return SelectionResult(null, Source.FALLBACK)
        }

        // 规则①：当天视频（publishDate 为空串 = 无法判定，视为非当天，走规则②）
        val todayVideos = downloadable.filter { it.publishDate == today && it.publishDate.isNotEmpty() }
        if (todayVideos.isNotEmpty()) {
            return SelectionResult(todayVideos.first(), Source.TODAY)
        }

        // 规则②：最近一期
        return SelectionResult(downloadable.first(), Source.CACHED)
    }

    private companion object {
        const val TAG = Constants.TAG_PREFIX + "Select"
    }
}
