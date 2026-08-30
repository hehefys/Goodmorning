package com.goodmorning.alarm.network

import com.goodmorning.alarm.util.AppLogger
import com.goodmorning.alarm.util.Constants
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * RSSHub JSON Feed（format=json）解析器。
 *
 * 容错策略（ARCHITECTURE.md §1.4）：
 * 1. 条目数组键名兼容 "items"（JSON Feed 标准）与 "item"（部分 RSSHub 版本）；
 * 2. pubDate 兼容 epoch 毫秒/秒与 ISO-8601 字符串，全部失败记 0（选片时走规则②）；
 * 3. 视频直链优先从 description 的 `<video src="...">` 提取，
 *    失败则用正则兜底匹配任意 douyinvod.com / aweme/v1/play / .mp4 URL。
 */
class RssFeedParser {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 解析 RSSHub 返回的 JSON Feed 文本。
     * @throws RssParseException JSON 结构非法时抛出（SyncEngine 归入 PARSE:）
     * @throws EmptyFeedException 条目数组为空时抛出（SyncEngine 归入 EMPTY:）
     */
    fun parse(text: String): List<VideoItem> {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw RssParseException("根节点不是 JSON 对象: ${it.message}") }

        val items = root.itemsArray()
        if (items == null) throw RssParseException("缺少条目数组字段（items/item）")
        if (items.isEmpty()) throw EmptyFeedException("条目数组为空（可能被 WAF 拦截或用户未更新）")

        return items.mapNotNull(::parseItem)
    }

    // ---- 内部实现 ----

    private fun JsonObject.itemsArray(): List<JsonObject>? {
        val element: JsonElement = this["items"] ?: this["item"] ?: return null
        val array: JsonArray = when (element) {
            is JsonArray -> element
            else -> return null
        }
        return array.filterIsInstance<JsonObject>()
    }

    private fun parseItem(item: JsonObject): VideoItem? {
        val link = item.stringOf("link") ?: item.stringOf("url") ?: return null
        val id = extractAwemeId(link) ?: return null
        val title = item.stringOf("title")?.trim().orEmpty()
        val description = item.stringOf("description") ?: item.stringOf("content") ?: ""
        return VideoItem(
            id = id,
            title = if (title.isEmpty()) "早安 $id" else title,
            publishTimeMillis = parsePublishTime(item),
            pageUrl = "https://www.douyin.com/video/$id",
            videoUrl = extractVideoUrl(description)
        )
    }

    /** 从 https://www.douyin.com/video/{aweme_id} 提取纯数字 id */
    private fun extractAwemeId(link: String): String? {
        val match = Regex("""/video/(\d+)""").find(link) ?: return null
        return match.groupValues[1]
    }

    /** pubDate 兼容多种形态：数字（秒/毫秒）、ISO-8601（含/不含时区） */
    private fun parsePublishTime(item: JsonObject): Long {
        val raw = item["pubDate"] ?: item["date_published"] ?: item["datePublished"]
            ?: return UNKNOWN_TIME
        val text = runCatching { raw.jsonPrimitive.content }.getOrElse { return UNKNOWN_TIME }
        return parseTimeString(text.trim())
    }

    private fun parseTimeString(text: String): Long {
        if (text.isEmpty()) return UNKNOWN_TIME
        // 纯数字：区分秒（10 位）与毫秒（13 位）
        if (text.all { it.isDigit() }) {
            val value = text.toLongOrNull() ?: return UNKNOWN_TIME
            return when {
                text.length >= 13 -> value
                text.length >= 9 -> value * 1000
                else -> UNKNOWN_TIME
            }
        }
        // ISO-8601 带时区
        runCatching { Instant.parse(text).toEpochMilli() }
            .onSuccess { return it }
        // ISO-8601 带偏移（如 2026-08-27T06:00:00+08:00）
        runCatching { OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli() }
            .onSuccess { return it }
        // 本地日期时间（无时区，按设备时区理解）
        runCatching {
            java.time.LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.onSuccess { return it }
        AppLogger.w(TAG, "pubDate 解析失败：$text")
        return UNKNOWN_TIME
    }

    /** 直链提取：优先 <video src>，兜底任意 mp4/CDN URL 正则 */
    private fun extractVideoUrl(description: String): String? {
        if (description.isEmpty()) return null
        VIDEO_SRC_REGEX.find(description)?.let { return it.groupValues[1] }
        MP4_URL_REGEX.find(description)?.let { return it.groupValues[1] }
        return null
    }

    private fun JsonObject.stringOf(key: String): String? {
        val element = this[key] ?: return null
        return runCatching { element.jsonPrimitive.content }.getOrNull()
    }

    /** 解析失败异常（message 由 SyncEngine 归入 PARSE: 错误码） */
    open class RssParseException(message: String) : Exception(message)

    /**
     * 条目数组为空（可能被 WAF 拦截）——RssParseException 子类，
     * SyncEngine 据此类型化归入 EMPTY: 而非 PARSE:（替代按 message 文案匹配）。
     */
    class EmptyFeedException(message: String) : RssParseException(message)

    private companion object {
        const val TAG = Constants.TAG_PREFIX + "Parse"
        const val UNKNOWN_TIME = 0L
        val VIDEO_SRC_REGEX = Regex("""<video[^>]*\ssrc=["']([^"']+)["']""")
        val MP4_URL_REGEX = Regex(
            """(https?://[^"'\s<>]+?(?:douyinvod\.com[^"'\s<>]*|aweme/v1/play[^"'\s<>]*|\.mp4))""",
            RegexOption.IGNORE_CASE
        )
    }
}
