package com.goodmorning.alarm.alarm

import com.goodmorning.alarm.data.db.VideoEntity
import com.goodmorning.alarm.util.TimeUtils

/**
 * 选片规则（PRD §5 / ARCHITECTURE.md §3.2）：
 * ① 发布日期 == 响铃日期（本地自然日）→ 取其中发布时间最新一条，标记 TODAY；
 * ①·补 前一晚 18:00 后发布的最新一条 → 也标记 TODAY（博主固定在前夜 ~23:16
 *    发布次日早安视频，publishDate 永远是「昨天」；此规则让日志/来源判定与
 *    响铃现场补同步的触发条件符合实际发布节奏）；
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
     * @param nowMillis 当前时刻（测试可注入固定值）
     */
    fun select(
        videos: List<VideoEntity>,
        today: String,
        nowMillis: Long = System.currentTimeMillis()
    ): SelectionResult {
        val downloadable = videos
            .filter { !it.localPath.isNullOrBlank() }
            .sortedByDescending { it.publishTimeMillis }
        if (downloadable.isEmpty()) {
            return SelectionResult(null, Source.FALLBACK)
        }

        // 规则①：当天视频（publishDate 为空串 = 无法判定，视为非当天，走后续规则）
        val todayVideos = downloadable.filter { it.publishDate == today && it.publishDate.isNotEmpty() }
        if (todayVideos.isNotEmpty()) {
            return SelectionResult(todayVideos.first(), Source.TODAY)
        }

        // 规则①·补：前一晚 18:00 后发布的最新视频 = 次日早安
        val newest = downloadable.first()
        if (isPreviousEveningPost(newest.publishTimeMillis, today, nowMillis)) {
            return SelectionResult(newest, Source.TODAY)
        }

        // 规则②：最近一期
        return SelectionResult(newest, Source.CACHED)
    }

    /**
     * 是否属于「前一晚发布、供今早播放」的视频：
     * 发布时间 ≥ 今天 00:00 − 6 小时（即昨晚 18:00 起），且不晚于当前时刻 + 1 小时
     * （容忍手机与服务器之间的少量时钟偏差；明显未来时间戳视为脏数据不认）。
     */
    private fun isPreviousEveningPost(
        publishTimeMillis: Long,
        today: String,
        nowMillis: Long
    ): Boolean {
        if (publishTimeMillis <= 0L) return false
        val dayStart = TimeUtils.startOfDayMillis(today)
        if (dayStart <= 0L) return false
        val eveningBoundary = dayStart - EVENING_POST_GRACE_MS
        return publishTimeMillis >= eveningBoundary &&
            publishTimeMillis <= nowMillis + CLOCK_SKEW_TOLERANCE_MS
    }

    private companion object {
        /** 「前一晚」宽限窗口：今天 00:00 往前推 6 小时（18:00 起算前夜发布） */
        const val EVENING_POST_GRACE_MS = 6 * 60 * 60_000L

        /** 时间戳未来偏差容忍（手机/服务器时钟差） */
        const val CLOCK_SKEW_TOLERANCE_MS = 60 * 60_000L
    }
}
