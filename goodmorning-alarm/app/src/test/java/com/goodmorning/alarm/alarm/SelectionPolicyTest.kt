package com.goodmorning.alarm.alarm

import com.goodmorning.alarm.data.db.VideoEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SelectionPolicy 选片规则单元测试（PRD §5）。

 * 覆盖：规则①当天命中、规则②最近一期、规则③空库兜底、
 * publishDate 空串（无法判定）、跨日边界、乱序输入、localPath 缺失过滤。
 */
class SelectionPolicyTest {

    private val policy = SelectionPolicy()
    private val today = "2026-08-27"

    private fun video(
        id: String,
        publishTimeMillis: Long,
        publishDate: String,
        localPath: String? = "/data/user/0/videos/$id.mp4"
    ): VideoEntity = VideoEntity(
        id = id,
        title = "标题$id",
        publishTimeMillis = publishTimeMillis,
        publishDate = publishDate,
        pageUrl = "https://www.douyin.com/video/$id",
        videoUrl = null,
        localPath = localPath,
        fileSize = 1024L,
        downloadedAt = 0L
    )

    // ---- 规则③：无可用缓存 ----

    @Test
    fun `空候选集返回兜底`() {
        val result = policy.select(emptyList(), today)
        assertNull(result.video)
        assertEquals(SelectionPolicy.Source.FALLBACK, result.source)
        assertTrue(result.isFallback)
    }

    @Test
    fun `全部 localPath 为 null 返回兜底`() {
        val videos = listOf(
            video("a", 1_756_000_000_000L, today, localPath = null),
            video("b", 1_755_000_000_000L, "2026-08-26", localPath = null)
        )
        val result = policy.select(videos, today)
        assertNull(result.video)
        assertEquals(SelectionPolicy.Source.FALLBACK, result.source)
    }

    @Test
    fun `localPath 为空白字符串视为不可播放返回兜底`() {
        val videos = listOf(video("a", 1_756_000_000_000L, today, localPath = "   "))
        val result = policy.select(videos, today)
        assertNull(result.video)
        assertEquals(SelectionPolicy.Source.FALLBACK, result.source)
    }

    // ---- 规则①：当天命中 ----

    @Test
    fun `当天存在视频返回TODAY`() {
        val videos = listOf(
            video("yesterday", 1_755_000_000_000L, "2026-08-26"),
            video("today", 1_756_000_000_000L, today)
        )
        val result = policy.select(videos, today)
        assertEquals("today", result.video?.id)
        assertEquals(SelectionPolicy.Source.TODAY, result.source)
        assertFalse(result.isFallback)
    }

    @Test
    fun `当天多条视频取发布时间最新一条`() {
        val videos = listOf(
            video("today_old", 1_756_000_000_000L, today),
            video("today_new", 1_756_050_000_000L, today)
        )
        val result = policy.select(videos, today)
        assertEquals("today_new", result.video?.id)
        assertEquals(SelectionPolicy.Source.TODAY, result.source)
    }

    @Test
    fun `规则①优先于时间更新近的往日视频`() {
        // 当天视频发布时间早于昨日视频，仍必须选当天那条（PRD §5.3 优先级）
        val videos = listOf(
            video("today_early", 1_756_000_000_000L, today),
            video("yesterday_late", 1_756_100_000_000L, "2026-08-26")
        )
        val result = policy.select(videos, today)
        assertEquals("today_early", result.video?.id)
        assertEquals(SelectionPolicy.Source.TODAY, result.source)
    }

    @Test
    fun `当天视频未下载本地文件时不参与规则①_降级到最近一期`() {
        // publishDate 命中但 localPath 为 null（文件被清理）：候选集过滤后走规则②
        val videos = listOf(
            video("today_nofile", 1_756_050_000_000L, today, localPath = null),
            video("yesterday_file", 1_755_000_000_000L, "2026-08-25")
        )
        val result = policy.select(videos, today)
        assertEquals("yesterday_file", result.video?.id)
        assertEquals(SelectionPolicy.Source.CACHED, result.source)
    }

    // ---- 规则②：最近一期 ----

    @Test
    fun `无当天视频返回缓存最新一条CACHED`() {
        val videos = listOf(
            video("old", 1_754_000_000_000L, "2026-08-24"),
            video("new", 1_755_000_000_000L, "2026-08-26")
        )
        val result = policy.select(videos, today)
        assertEquals("new", result.video?.id)
        assertEquals(SelectionPolicy.Source.CACHED, result.source)
    }

    @Test
    fun `publishDate为空串视为无法判定_不参与当天判定`() {
        // pubDate 解析失败（PRD §5.4）：publishDate = ""，即便 publishTime 是今天也不算当天
        val videos = listOf(
            video("unknown_date", 1_756_000_000_000L, publishDate = ""),
            video("dated", 1_755_000_000_000L, "2026-08-25")
        )
        val result = policy.select(videos, today)
        // unknown_date 的 publishTime 更新但无法判定日期；dated 可判定。
        // 规则②取“发布时间最新”，unknown_date 时间戳更大 → 取 unknown_date，来源为 CACHED
        assertEquals("unknown_date", result.video?.id)
        assertEquals(SelectionPolicy.Source.CACHED, result.source)
    }

    @Test
    fun `唯一候选且publishDate为空串时仍可播放_来源CACHED`() {
        val videos = listOf(video("only", 1_756_000_000_000L, publishDate = ""))
        val result = policy.select(videos, today)
        assertEquals("only", result.video?.id)
        assertEquals(SelectionPolicy.Source.CACHED, result.source)
    }

    @Test
    fun `跨日边界_昨日2359发布的视频次日零点后响铃不算当天`() {
        // 昨天 23:59 发布；响铃日为次日 → 规则②
        val yesterday = video("night", 1_756_000_000_000L, "2026-08-26")
        val result = policy.select(listOf(yesterday), "2026-08-27")
        assertEquals(SelectionPolicy.Source.CACHED, result.source)
    }

    // ---- 排序与输入形态 ----

    @Test
    fun `乱序输入仍按发布时间倒序选片`() {
        val videos = listOf(
            video("mid", 1_755_500_000_000L, "2026-08-25"),
            video("newest", 1_755_900_000_000L, "2026-08-26"),
            video("oldest", 1_755_100_000_000L, "2026-08-24")
        )
        val result = policy.select(videos, today)
        assertEquals("newest", result.video?.id)
        assertEquals(SelectionPolicy.Source.CACHED, result.source)
    }

    @Test
    fun `publishTimeMillis为0的条目排在最后`() {
        val videos = listOf(
            video("unknown", 0L, ""),
            video("known", 1_755_000_000_000L, "2026-08-25")
        )
        val result = policy.select(videos, today)
        assertEquals("known", result.video?.id)
    }
}
