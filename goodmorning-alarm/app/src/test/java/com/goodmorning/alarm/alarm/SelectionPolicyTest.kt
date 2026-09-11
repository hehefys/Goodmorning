package com.goodmorning.alarm.alarm

import com.goodmorning.alarm.data.db.VideoEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * SelectionPolicy 选片规则单元测试（PRD §5 + 前夜发布补丁）。

 * 覆盖：规则①当天命中、规则①·补前一晚 18:00 后发布（博主固定前夜 ~23:16 发次日早安）、
 * 规则②最近一期、规则③空库兜底、publishDate 空串（无法判定）、跨日边界、
 * 乱序输入、localPath 缺失过滤。
 *
 * 所有用例注入固定 now（2026-08-27 09:30 本地时区），不依赖真实时钟。
 */
class SelectionPolicyTest {

    private val policy = SelectionPolicy()
    private val today = "2026-08-27"

    /** 固定「当前时刻」= 2026-08-27 09:30 本地时区 */
    private val now = z(2026, 8, 27, 9, 30)

    private fun z(
        year: Int, month: Int, day: Int,
        hour: Int, minute: Int
    ): Long = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZoneId.systemDefault())
        .toInstant().toEpochMilli()

    private fun select(
        videos: List<VideoEntity>,
        date: String = today,
        at: Long = now
    ) = policy.select(videos, date, at)

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
        val result = select(emptyList())
        assertNull(result.video)
        assertEquals(SelectionPolicy.Source.FALLBACK, result.source)
        assertTrue(result.isFallback)
    }

    @Test
    fun `全部 localPath 为 null 返回兜底`() {
        val videos = listOf(
            video("a", z(2026, 8, 27, 6, 0), today, localPath = null),
            video("b", z(2026, 8, 26, 6, 0), "2026-08-26", localPath = null)
        )
        val result = select(videos)
        assertNull(result.video)
        assertEquals(SelectionPolicy.Source.FALLBACK, result.source)
    }

    @Test
    fun `localPath 为空白字符串视为不可播放返回兜底`() {
        val videos = listOf(video("a", z(2026, 8, 27, 6, 0), today, localPath = "   "))
        val result = select(videos)
        assertNull(result.video)
        assertEquals(SelectionPolicy.Source.FALLBACK, result.source)
    }

    // ---- 规则①：当天命中 ----

    @Test
    fun `当天存在视频返回TODAY`() {
        val videos = listOf(
            video("yesterday", z(2026, 8, 26, 8, 0), "2026-08-26"),
            video("today", z(2026, 8, 27, 6, 0), today)
        )
        val result = select(videos)
        assertEquals("today", result.video?.id)
        assertEquals(SelectionPolicy.Source.TODAY, result.source)
        assertFalse(result.isFallback)
    }

    @Test
    fun `当天多条视频取发布时间最新一条`() {
        val videos = listOf(
            video("today_old", z(2026, 8, 27, 5, 0), today),
            video("today_new", z(2026, 8, 27, 6, 30), today)
        )
        val result = select(videos)
        assertEquals("today_new", result.video?.id)
        assertEquals(SelectionPolicy.Source.TODAY, result.source)
    }

    @Test
    fun `规则①优先于时间更新近的往日视频`() {
        // 当天视频发布时间早于昨日视频，仍必须选当天那条（PRD §5.3 优先级）
        val videos = listOf(
            video("today_early", z(2026, 8, 27, 0, 30), today),
            video("yesterday_late", z(2026, 8, 26, 23, 16), "2026-08-26")
        )
        val result = select(videos)
        assertEquals("today_early", result.video?.id)
        assertEquals(SelectionPolicy.Source.TODAY, result.source)
    }

    @Test
    fun `当天视频未下载本地文件时不参与规则①_降级到最近一期`() {
        // publishDate 命中但 localPath 为 null（文件被清理）：候选集过滤后走规则②
        val videos = listOf(
            video("today_nofile", z(2026, 8, 27, 6, 0), today, localPath = null),
            video("yesterday_file", z(2026, 8, 25, 8, 0), "2026-08-25")
        )
        val result = select(videos)
        assertEquals("yesterday_file", result.video?.id)
        assertEquals(SelectionPolicy.Source.CACHED, result.source)
    }

    // ---- 规则①·补：前一晚 18:00 后发布 = 次日早安（博主实测 ~23:16 发布）----

    @Test
    fun `前一晚2316发布的视频次日早晨视为TODAY`() {
        // 真实场景复现：9/10 23:16 发布 → 9/11 11:00 响铃，publishDate 是"昨天"但就是当天内容
        val videos = listOf(video("night_post", z(2026, 8, 26, 23, 16), "2026-08-26"))
        val result = select(videos)
        assertEquals("night_post", result.video?.id)
        assertEquals(SelectionPolicy.Source.TODAY, result.source)
    }

    @Test
    fun `前一晚1800整发布的视频次日也算TODAY`() {
        val videos = listOf(video("evening", z(2026, 8, 26, 18, 0), "2026-08-26"))
        assertEquals(SelectionPolicy.Source.TODAY, select(videos).source)
    }

    @Test
    fun `前一晚1759发布的视频次日不算TODAY`() {
        // 18:00 之前发布 = 当天下午内容，次日早晨已过期 → CACHED（触发现场补同步）
        val videos = listOf(video("stale", z(2026, 8, 26, 17, 59), "2026-08-26"))
        val result = select(videos)
        assertEquals(SelectionPolicy.Source.CACHED, result.source)
    }

    @Test
    fun `前天夜晚发布的视频次日早晨已过期走CACHED`() {
        // 博主漏发一天：最新缓存是前天 23:16（36h+ 前）→ CACHED，允许响铃现场补同步
        val videos = listOf(video("two_days_ago", z(2026, 8, 25, 23, 16), "2026-08-25"))
        val result = select(videos)
        assertEquals(SelectionPolicy.Source.CACHED, result.source)
    }

    @Test
    fun `未来时间戳超过1小时容忍度不认前一晚规则`() {
        // 脏数据：日期串是昨天但时间戳在未来 2 小时 → 前夜规则的时钟偏差护栏拒绝 → CACHED
        val videos = listOf(video("future", z(2026, 8, 27, 11, 30), "2026-08-26"))
        val result = select(videos, at = z(2026, 8, 27, 9, 30))
        assertEquals(SelectionPolicy.Source.CACHED, result.source)
    }

    @Test
    fun `前夜规则不覆盖规则①_有当天日期视频时优先当天`() {
        // 昨晚 23:16 发布（日期=昨天）+ 今天凌晨 00:30 发布（日期=今天）：规则①按日期命中后者
        val videos = listOf(
            video("last_night", z(2026, 8, 26, 23, 16), "2026-08-26"),
            video("today_dated", z(2026, 8, 27, 0, 30), today)
        )
        val result = select(videos)
        assertEquals("today_dated", result.video?.id)
        assertEquals(SelectionPolicy.Source.TODAY, result.source)
    }

    // ---- 规则②：最近一期 ----

    @Test
    fun `无当天视频且最新已过期返回缓存最新一条CACHED`() {
        val videos = listOf(
            video("old", z(2026, 8, 24, 8, 0), "2026-08-24"),
            video("new", z(2026, 8, 26, 8, 0), "2026-08-26")
        )
        val result = select(videos)
        assertEquals("new", result.video?.id)
        assertEquals(SelectionPolicy.Source.CACHED, result.source)
    }

    @Test
    fun `publishDate为空串视为无法判定_不参与当天判定`() {
        // pubDate 解析失败（PRD §5.4）：publishDate = ""；但时间戳更新 → 规则②取它。
        // 两个时间戳都在 2025 年（远早于前夜边界）→ 不触发前夜规则 → CACHED
        val videos = listOf(
            video("unknown_date", 1_756_000_000_000L, publishDate = ""),
            video("dated", 1_755_000_000_000L, "2026-08-25")
        )
        val result = select(videos)
        assertEquals("unknown_date", result.video?.id)
        assertEquals(SelectionPolicy.Source.CACHED, result.source)
    }

    @Test
    fun `唯一候选且publishDate为空串时仍可播放_来源CACHED`() {
        val videos = listOf(video("only", 1_756_000_000_000L, publishDate = ""))
        val result = select(videos)
        assertEquals("only", result.video?.id)
        assertEquals(SelectionPolicy.Source.CACHED, result.source)
    }

    // ---- 排序与输入形态 ----

    @Test
    fun `乱序输入仍按发布时间倒序选片`() {
        val videos = listOf(
            video("mid", z(2026, 8, 25, 12, 0), "2026-08-25"),
            video("newest", z(2026, 8, 26, 12, 0), "2026-08-26"),
            video("oldest", z(2026, 8, 24, 12, 0), "2026-08-24")
        )
        val result = select(videos)
        assertEquals("newest", result.video?.id)
        assertEquals(SelectionPolicy.Source.CACHED, result.source)
    }

    @Test
    fun `publishTimeMillis为0的条目排在最后`() {
        val videos = listOf(
            video("unknown", 0L, ""),
            video("known", z(2026, 8, 25, 8, 0), "2026-08-25")
        )
        val result = select(videos)
        assertEquals("known", result.video?.id)
    }
}
