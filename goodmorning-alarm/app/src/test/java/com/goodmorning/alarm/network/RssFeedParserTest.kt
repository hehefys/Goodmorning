package com.goodmorning.alarm.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * RssFeedParser 容错解析单元测试（ARCHITECTURE.md §1.4）。
 *
 * 覆盖：items/item 双键、pubDate 三种形态（epoch 秒/毫秒、ISO-8601 各变体、非法值）、
 * 视频直链提取（<video src> 优先 + mp4/CDN 兜底正则）、脏数据（缺 link、非视频链接、
 * 空 items、非法 JSON、根节点非对象、items 非数组）。
 */
class RssFeedParserTest {

    private val parser = RssFeedParser()

    private val defaultLink = "https://www.douyin.com/video/7412345678901234567"
    private val defaultVideoUrl = "https://www.douyin.com/aweme/v1/play/?video_id=abc123&line=0"
    private val defaultDescription =
        """早安呀<video src=\"${defaultVideoUrl}\"></video>"""

    private fun itemJson(
        link: String? = defaultLink,
        title: String? = "8月27日早安",
        pubDate: String? = "1756252800000",
        description: String? = defaultDescription
    ): String {
        val fields = mutableListOf<String>()
        link?.let { fields += "\"link\":\"$it\"" }
        title?.let { fields += "\"title\":\"$it\"" }
        pubDate?.let { fields += "\"pubDate\":\"$it\"" }
        description?.let { fields += "\"description\":\"$it\"" }
        return "{${fields.joinToString(",")}}"
    }

    private fun feed(itemsJson: String, arrayKey: String = "items"): String =
        "{\"title\":\"每日早安\",\"$arrayKey\":[$itemsJson]}"

    // ---- 标准路径 ----

    @Test
    fun `标准JSON Feed解析出完整VideoItem`() {
        val items = parser.parse(feed(itemJson()))
        assertEquals(1, items.size)
        val item = items[0]
        assertEquals("7412345678901234567", item.id)
        assertEquals("8月27日早安", item.title)
        assertEquals(1_756_252_800_000L, item.publishTimeMillis)
        assertEquals(defaultVideoUrl, item.videoUrl)
        assertEquals("https://www.douyin.com/video/7412345678901234567", item.pageUrl)
    }

    @Test
    fun `兼容item键名`() {
        val items = parser.parse(feed(itemJson(), arrayKey = "item"))
        assertEquals(1, items.size)
        assertEquals("7412345678901234567", items[0].id)
    }

    @Test
    fun `多条目保持顺序`() {
        val json = feed(itemJson() + "," + itemJson(link = "https://www.douyin.com/video/111", pubDate = "1756166400000"))
        val items = parser.parse(json)
        assertEquals(2, items.size)
        assertEquals("7412345678901234567", items[0].id)
        assertEquals("111", items[1].id)
    }

    // ---- pubDate 三种形态 ----

    @Test
    fun `pubDate为13位毫秒原样返回`() {
        val items = parser.parse(feed(itemJson(pubDate = "1756252800000")))
        assertEquals(1_756_252_800_000L, items[0].publishTimeMillis)
    }

    @Test
    fun `pubDate为10位秒自动转毫秒`() {
        val items = parser.parse(feed(itemJson(pubDate = "1756252800")))
        assertEquals(1_756_252_800_000L, items[0].publishTimeMillis)
    }

    @Test
    fun `pubDate为未加引号的JSON数字也能解析`() {
        val json = "{\"items\":[{\"link\":\"$defaultLink\",\"pubDate\":1756252800000,\"description\":\"\"}]}"
        val items = parser.parse(json)
        assertEquals(1_756_252_800_000L, items[0].publishTimeMillis)
    }

    @Test
    fun `pubDate为ISO8601带Z后缀`() {
        val text = "2026-08-27T01:00:00.000Z"
        val expected = Instant.parse(text).toEpochMilli()
        val items = parser.parse(feed(itemJson(pubDate = text)))
        assertEquals(expected, items[0].publishTimeMillis)
    }

    @Test
    fun `pubDate为ISO8601带时区偏移`() {
        val text = "2026-08-27T09:00:00+08:00"
        val expected = Instant.parse("2026-08-27T01:00:00Z").toEpochMilli()
        val items = parser.parse(feed(itemJson(pubDate = text)))
        assertEquals(expected, items[0].publishTimeMillis)
    }

    @Test
    fun `pubDate为无时区本地时间按设备时区理解`() {
        val text = "2026-08-27T09:00:00"
        val expected = LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val items = parser.parse(feed(itemJson(pubDate = text)))
        assertEquals(expected, items[0].publishTimeMillis)
    }

    @Test
    fun `pubDate为date_published别名`() {
        val json = "{\"items\":[{\"link\":\"$defaultLink\",\"date_published\":\"2026-08-27T01:00:00.000Z\"}]}"
        val items = parser.parse(json)
        assertEquals(Instant.parse("2026-08-27T01:00:00.000Z").toEpochMilli(), items[0].publishTimeMillis)
    }

    @Test
    fun `pubDate非法字符串记0`() {
        val items = parser.parse(feed(itemJson(pubDate = "not-a-date")))
        assertEquals(0L, items[0].publishTimeMillis)
    }

    @Test
    fun `pubDate缺失记0`() {
        val items = parser.parse(feed(itemJson(pubDate = null)))
        assertEquals(0L, items[0].publishTimeMillis)
    }

    @Test
    fun `pubDate过短数字记0`() {
        val items = parser.parse(feed(itemJson(pubDate = "1234")))
        assertEquals(0L, items[0].publishTimeMillis)
    }

    // ---- 视频直链提取 ----

    @Test
    fun `video标签src属性提取直链`() {
        val items = parser.parse(feed(itemJson()))
        assertEquals(defaultVideoUrl, items[0].videoUrl)
    }

    @Test
    fun `video标签带其他属性时仍能提取src`() {
        val desc = """<video controls preload=\"auto\" src=\"$defaultVideoUrl\"></video>"""
        val items = parser.parse(feed(itemJson(description = desc)))
        assertEquals(defaultVideoUrl, items[0].videoUrl)
    }

    @Test
    fun `video标签单引号src也能提取`() {
        val desc = """<video src='$defaultVideoUrl'></video>"""
        val items = parser.parse(feed(itemJson(description = desc)))
        assertEquals(defaultVideoUrl, items[0].videoUrl)
    }

    @Test
    fun `无video标签时用douyinvod域名兜底正则`() {
        val desc = "好看 https://v26-web.douyinvod.com/tos/cn-ve-15/abc123.mp4?x=1 推荐"
        val items = parser.parse(feed(itemJson(description = desc)))
        assertEquals("https://v26-web.douyinvod.com/tos/cn-ve-15/abc123.mp4?x=1", items[0].videoUrl)
    }

    @Test
    fun `无video标签时用aweme播放地址兜底正则`() {
        val desc = "链接 https://www.douyin.com/aweme/v1/play/?video_id=xyz&line=0 在这"
        val items = parser.parse(feed(itemJson(description = desc)))
        assertEquals("https://www.douyin.com/aweme/v1/play/?video_id=xyz&line=0", items[0].videoUrl)
    }

    @Test
    fun `video标签优先级高于兜底正则`() {
        val desc = """<video src=\"$defaultVideoUrl\"></video> 备用 https://v26-web.douyinvod.com/other.mp4"""
        val items = parser.parse(feed(itemJson(description = desc)))
        assertEquals(defaultVideoUrl, items[0].videoUrl)
    }

    @Test
    fun `description为空时videoUrl为null`() {
        val items = parser.parse(feed(itemJson(description = null)))
        assertNull(items[0].videoUrl)
    }

    @Test
    fun `description无任何视频链接时videoUrl为null`() {
        val items = parser.parse(feed(itemJson(description = "纯文字描述，没有链接")))
        assertNull(items[0].videoUrl)
    }

    // ---- 脏数据与异常 ----

    @Test
    fun `link非视频页面地址条目被丢弃`() {
        val items = parser.parse(feed(itemJson(link = "https://v.douyin.com/shortvideo/")))
        assertTrue(items.isEmpty())
    }

    @Test
    fun `link缺失时回退url字段`() {
        val json = "{\"items\":[{\"url\":\"https://www.douyin.com/video/999888777\",\"description\":\"\"}]}"
        val items = parser.parse(json)
        assertEquals(1, items.size)
        assertEquals("999888777", items[0].id)
    }

    @Test
    fun `link与url均缺失条目被丢弃`() {
        val json = "{\"items\":[{\"title\":\"没链接\",\"description\":\"\"}]}"
        val items = parser.parse(json)
        assertTrue(items.isEmpty())
    }

    @Test
    fun `title缺失使用默认标题`() {
        val items = parser.parse(feed(itemJson(title = null)))
        assertEquals("早安 7412345678901234567", items[0].title)
    }

    @Test
    fun `非法JSON抛RssParseException`() {
        try {
            parser.parse("这不是JSON{{{")
            fail("应当抛出 RssParseException")
        } catch (e: RssFeedParser.RssParseException) {
            assertTrue(e.message!!.contains("根节点"))
        }
    }

    @Test
    fun `根节点为数组抛RssParseException`() {
        try {
            parser.parse("[1,2,3]")
            fail("应当抛出 RssParseException")
        } catch (_: RssFeedParser.RssParseException) {
            // 预期行为
        }
    }

    @Test
    fun `items空数组抛含为空的RssParseException_对应EMPTY错误码`() {
        // SyncEngine 依据 message 是否含“为空”区分 EMPTY / PARSE，
        // 该断言固化此契约（脆弱点已记录在 QA 报告 E5）
        try {
            parser.parse(feed(""))
            fail("应当抛出 RssParseException")
        } catch (e: RssFeedParser.RssParseException) {
            assertTrue(e.message!!.contains("为空"))
        }
    }

    @Test
    fun `items空数组抛EmptyFeedException_类型化归入EMPTY`() {
        // E5 修复后 SyncEngine 按异常类型（EmptyFeedException）而非文案区分 EMPTY：
        // EmptyFeedException 是 RssParseException 子类，兼容旧断言，同时允许类型化分类。
        try {
            parser.parse(feed(""))
            fail("应当抛出 EmptyFeedException")
        } catch (e: RssFeedParser.EmptyFeedException) {
            assertTrue(e is RssFeedParser.RssParseException)
            assertTrue(e.message!!.contains("为空"))
        }
    }

    @Test
    fun `缺少items与item字段抛RssParseException`() {
        try {
            parser.parse("{\"title\":\"每日早安\"}")
            fail("应当抛出 RssParseException")
        } catch (e: RssFeedParser.RssParseException) {
            assertTrue(e.message!!.contains("缺少"))
        }
    }

    @Test
    fun `items不是数组抛RssParseException`() {
        try {
            parser.parse("{\"items\":{\"a\":1}}")
            fail("应当抛出 RssParseException")
        } catch (_: RssFeedParser.RssParseException) {
            // 预期行为
        }
    }
}
