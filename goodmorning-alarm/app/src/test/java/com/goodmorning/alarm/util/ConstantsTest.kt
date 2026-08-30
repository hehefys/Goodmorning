package com.goodmorning.alarm.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Constants 契约测试：固化 PRD / ARCHITECTURE.md 中约定的关键参数，
 * 防止后续改动无意偏离产品口径（贪睡 1~30 默认 10、渐强 30%→100% 时长 5~60s 默认 20s、
 * 同步时刻 05:30/21:00、缓存保留 3 条、RSSHub 路由模板）。
 */
class ConstantsTest {

    @Test
    fun `RSSHub路由模板拼接结果符合设计`() {
        val url = Constants.RSSHUB_ROUTE_TEMPLATE.format(
            Constants.DEFAULT_RSSHUB_BASE, Constants.SEC_UID
        )
        assertEquals(
            "https://rsshub.app/douyin/user/${Constants.SEC_UID}?embed=1&format=json",
            url
        )
        // 自建实例：Base URL 带尾斜杠由 SettingsRepository 归一化，模板本身只做拼接
        val custom = Constants.RSSHUB_ROUTE_TEMPLATE.format("http://192.168.1.10:1200", Constants.SEC_UID)
        assertTrue(custom.startsWith("http://192.168.1.10:1200/douyin/user/"))
        assertTrue(custom.endsWith("?embed=1&format=json"))
    }

    @Test
    fun `同步时刻为0530与2100`() {
        assertEquals(5, Constants.SYNC_HOUR_MORNING)
        assertEquals(30, Constants.SYNC_MINUTE_MORNING)
        assertEquals(21, Constants.SYNC_HOUR_EVENING)
        assertEquals(0, Constants.SYNC_MINUTE_EVENING)
    }

    @Test
    fun `贪睡默认值必须在可调范围内`() {
        assertEquals(10, Constants.SNOOZE_DEFAULT)
        assertTrue(Constants.SNOOZE_DEFAULT in Constants.SNOOZE_MIN..Constants.SNOOZE_MAX)
        assertTrue(Constants.SNOOZE_MIN < Constants.SNOOZE_MAX)
    }

    @Test
    fun `音量渐强参数符合PRD_30percent起且时长默认在范围内`() {
        assertEquals(0.3f, Constants.FADE_START, 0.0001f)
        assertEquals(500L, Constants.FADE_STEP_MS)
        assertEquals(20, Constants.FADE_DEFAULT_SECONDS)
        assertTrue(Constants.FADE_DEFAULT_SECONDS in Constants.FADE_MIN_SECONDS..Constants.FADE_MAX_SECONDS)
    }

    @Test
    fun `副音频裁剪哨兵为0且最小间隔不小于5秒`() {
        assertEquals(0L, Constants.AMBIENT_CLIP_UNSET)
        assertTrue(Constants.AMBIENT_CLIP_MIN_GAP_S >= 5)
        // 衬托时长最小值为 0（0 = 不衬托，直接起播主音频）
        assertEquals(0, Constants.AMBIENT_LEAD_MIN)
        assertTrue(Constants.AMBIENT_LEAD_MIN < Constants.AMBIENT_LEAD_MAX)
        assertTrue(
            Constants.AMBIENT_LEAD_DEFAULT in Constants.AMBIENT_LEAD_MIN..Constants.AMBIENT_LEAD_MAX
        )
    }

    @Test
    fun `缓存保留条数为3`() {
        assertEquals(3, Constants.CACHE_KEEP_COUNT)
    }

    @Test
    fun `每日与贪睡闹钟PendingIntent请求码不冲突`() {
        assertTrue(Constants.REQUEST_CODE_DAILY != Constants.REQUEST_CODE_SNOOZE)
    }
}
