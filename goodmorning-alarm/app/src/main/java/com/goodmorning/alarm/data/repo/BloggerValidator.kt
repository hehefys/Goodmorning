package com.goodmorning.alarm.data.repo

import android.content.Context
import com.goodmorning.alarm.network.Http
import com.goodmorning.alarm.sync.SyncError
import com.goodmorning.alarm.util.AppLogger
import com.goodmorning.alarm.util.Constants
import com.goodmorning.alarm.data.prefs.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import java.io.IOException

/**
 * 校验成功的博主信息。
 * @param secUid 抖音用户 sec_uid
 * @param name   展示名（取 feed 顶层 title；为空回退 secUid 前 8 位 + …）
 */
data class BloggerInfo(val secUid: String, val name: String)

/**
 * 博主合法性校验（DESIGN-V2 §3.1，V2 功能 A 数据层）：
 * 1. [parseInput] 本地解析输入（主页链接或纯 sec_uid），非法输入不发请求；
 * 2. [validate] 用当前 RSSHub 地址实际请求一次抖音路由，
 *    能解析出至少 1 条条目即视为有效博主，并回读 feed 顶层 title 作为展示名。
 *
 * 错误以 [SyncError.Network]/[Parse]/[Empty] 返回在 [Result.failure] 中，供 UI 文案化。
 */
class BloggerValidator(context: Context) {

    private val appContext = context.applicationContext
    private val settingsRepository = SettingsRepository(appContext)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 校验输入是否为有效博主。
     * @param input 抖音主页链接或纯 sec_uid
     * @return Success(BloggerInfo)；Failure(IllegalArgumentException=解析失败 / SyncError=网络/解析/空)
     */
    suspend fun validate(input: String): Result<BloggerInfo> = withContext(Dispatchers.IO) {
        val secUid = parseInput(input)
            ?: return@withContext Result.failure(
                IllegalArgumentException("无法识别，请粘贴抖音主页链接或 sec_uid")
            )
        val baseUrl = settingsRepository.current().rsshubBaseUrl.trimEnd('/')
        val feedUrl = Constants.RSSHUB_ROUTE_TEMPLATE.format(baseUrl, secUid)
        AppLogger.i(TAG, "校验博主：$feedUrl")
        try {
            val request = okhttp3.Request.Builder().url(feedUrl).get().build()
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw SyncError.Network("HTTP ${response.code}：${response.message}")
                }
                val body = response.body?.string() ?: throw SyncError.Network("响应体为空")
                val root = runCatching { json.parseToJsonElement(body).jsonObject }
                    .getOrElse { throw SyncError.Parse("根节点不是 JSON 对象") }
                // 条目数组非空即有效（兼容 items / item 两种键名）
                val items = root["items"] ?: root["item"]
                    ?: throw SyncError.Parse("缺少条目数组字段（items/item）")
                val itemCount = runCatching { items.jsonArray.size }.getOrDefault(0)
                if (itemCount < 1) {
                    throw SyncError.Empty("条目为空（可能被 WAF 拦截或博主无视频）")
                }
                // 展示名：feed 顶层 title，空则回退 secUid 前 8 位
                val title = runCatching {
                    root["title"]?.jsonPrimitive?.content?.trim().orEmpty()
                }.getOrDefault("")
                val name = title.ifEmpty { secUid.take(8) + "…" }
                Result.success(BloggerInfo(secUid, name))
            }
        } catch (e: SyncError) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(SyncError.Network(e.message ?: "网络错误"))
        } catch (e: Exception) {
            Result.failure(SyncError.Network("${e.javaClass.simpleName}: ${e.message}"))
        }
    }

    companion object {
        private const val TAG = Constants.TAG_PREFIX + "Blogger"

        /** 主页链接中提取 sec_uid：捕获 user/ 到下一个 / ? # 的完整段 */
        private val USER_LINK_REGEX = Regex("user/([^/?#]+)")

        /** 纯 sec_uid：长度 ≥ 20 的字母数字下划线连字符串 */
        private val PURE_SEC_UID_REGEX = Regex("^[A-Za-z0-9_-]{20,}$")

        /**
         * 本地解析输入（DESIGN-V2 §3.1.2，纯函数可单测）：
         * 1. 含 "douyin.com/user/" → 正则取 group(1)；
         * 2. 否则整体匹配纯 sec_uid 形态；
         * 3. 都不满足 → null（UI 内联报错，不发请求）。
         */
        fun parseInput(input: String): String? {
            val text = input.trim()
            if (text.isEmpty()) return null
            if (text.contains("douyin.com/user/")) {
                USER_LINK_REGEX.find(text)?.let { match ->
                    // 整段校验：截出的段必须是合法 sec_uid 形态，
                    // 防止 "user/not.valid.uid!!" 被截成 "not" 当博主
                    val candidate = match.groupValues[1]
                    if (PURE_SEC_UID_REGEX.matches(candidate)) return candidate
                }
            }
            return if (PURE_SEC_UID_REGEX.matches(text)) text else null
        }
    }
}
