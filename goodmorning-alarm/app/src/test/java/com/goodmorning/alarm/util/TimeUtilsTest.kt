package com.goodmorning.alarm.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * TimeUtils 时间口径单元测试（PRD §5.1 本地时区自然日；ARCHITECTURE.md §1.5 自续期/同步时刻）。
 *
 * 所有期望值用 java.time 按系统默认时区推导，测试在任意时区环境下均可通过。
 */
class TimeUtilsTest {

    private fun at(
        year: Int, month: Int, day: Int,
        hour: Int, minute: Int, second: Int = 0, millis: Int = 0
    ): Long = ZonedDateTime.of(year, month, day, hour, minute, second, millis * 1_000_000, ZoneId.systemDefault())
        .toInstant().toEpochMilli()

    // ---- localDate（本地时区自然日 yyyy-MM-dd）----

    @Test
    fun `localDate返回本地时区yyyy-MM-dd格式`() {
        val t = at(2026, 8, 27, 12, 0)
        assertEquals("2026-08-27", TimeUtils.localDate(t))
    }

    @Test
    fun `localDate零点与2359属于同一天`() {
        val start = at(2026, 1, 5, 0, 0, 0, 0)
        val end = at(2026, 1, 5, 23, 59, 59, 999)
        assertEquals(TimeUtils.localDate(start), TimeUtils.localDate(end))
    }

    @Test
    fun `localDate对不同时间戳与java_time结果一致`() {
        // 避免测试对 UTC 的隐性依赖：与 LocalDate 按系统时区换算结果对比
        val samples = listOf(0L, 1_756_000_000_000L, 951_782_400_000L, 4_102_444_800_000L)
        for (t in samples) {
            val expected = java.time.LocalDate.ofInstant(Instant.ofEpochMilli(t), ZoneId.systemDefault()).toString()
            assertEquals("timestamp=$t", expected, TimeUtils.localDate(t))
        }
    }

    // ---- nextDailyAt（每日闹钟自续期核心：严格未来的最近一次）----

    @Test
    fun `时刻未到返回今天`() {
        val now = at(2026, 8, 27, 9, 0)
        assertEquals(at(2026, 8, 27, 10, 0), TimeUtils.nextDailyAt(10, 0, now))
    }

    @Test
    fun `差一毫秒未到仍返回今天`() {
        val now = at(2026, 8, 27, 9, 59, 59, 999)
        assertEquals(at(2026, 8, 27, 10, 0), TimeUtils.nextDailyAt(10, 0, now))
    }

    @Test
    fun `恰好到点视为已过返回明天`() {
        // 严格未来：等于当前时刻也要推到明天，防止响铃瞬间重注册导致当天二次响铃
        val now = at(2026, 8, 27, 10, 0)
        assertEquals(at(2026, 8, 28, 10, 0), TimeUtils.nextDailyAt(10, 0, now))
    }

    @Test
    fun `已过一毫秒返回明天`() {
        val now = at(2026, 8, 27, 10, 0, 0, 1)
        assertEquals(at(2026, 8, 28, 10, 0), TimeUtils.nextDailyAt(10, 0, now))
    }

    @Test
    fun `晚间时刻注册次日早间闹钟`() {
        val now = at(2026, 8, 27, 23, 30)
        assertEquals(at(2026, 8, 28, 7, 0), TimeUtils.nextDailyAt(7, 0, now))
    }

    @Test
    fun `跨年边界正确滚入下一年`() {
        val now = at(2026, 12, 31, 23, 59, 59, 999)
        assertEquals(at(2027, 1, 1, 0, 0), TimeUtils.nextDailyAt(0, 0, now))
    }

    // ---- nextSyncAt（05:30 / 21:00 中较近的未来时刻）----

    @Test
    fun `凌晨四点取当天0530`() {
        assertEquals(
            at(2026, 8, 27, 5, 30),
            TimeUtils.nextSyncAt(at(2026, 8, 27, 4, 0))
        )
    }

    @Test
    fun `恰在0530整点取当天2100`() {
        assertEquals(
            at(2026, 8, 27, 21, 0),
            TimeUtils.nextSyncAt(at(2026, 8, 27, 5, 30))
        )
    }

    @Test
    fun `上午九点取当天2100`() {
        assertEquals(
            at(2026, 8, 27, 21, 0),
            TimeUtils.nextSyncAt(at(2026, 8, 27, 9, 0))
        )
    }

    @Test
    fun `恰在2100整点取次日0530`() {
        assertEquals(
            at(2026, 8, 28, 5, 30),
            TimeUtils.nextSyncAt(at(2026, 8, 27, 21, 0))
        )
    }

    @Test
    fun `深夜二十三点取次日0530`() {
        assertEquals(
            at(2026, 8, 28, 5, 30),
            TimeUtils.nextSyncAt(at(2026, 8, 27, 23, 0))
        )
    }

    // ---- 倒计时格式化 ----

    @Test
    fun `formatCountdown零与负数返回000000`() {
        assertEquals("00:00:00", TimeUtils.formatCountdown(0L))
        assertEquals("00:00:00", TimeUtils.formatCountdown(-123L))
    }

    @Test
    fun `formatCountdown不满一天只显示时分秒`() {
        assertEquals("00:01:01", TimeUtils.formatCountdown(61_000L))
        assertEquals("01:01:01", TimeUtils.formatCountdown(3_661_000L))
    }

    @Test
    fun `formatCountdown超过一天带天数前缀`() {
        // 1天 + 1小时1分1秒
        assertEquals("1天 01:01:01", TimeUtils.formatCountdown(90_061_000L))
    }

    @Test
    fun `formatCountdownHm分钟级格式化`() {
        assertEquals("00:00", TimeUtils.formatCountdownHm(0L))
        assertEquals("00:00", TimeUtils.formatCountdownHm(59_000L))
        assertEquals("00:01", TimeUtils.formatCountdownHm(60_000L))
        assertEquals("01:00", TimeUtils.formatCountdownHm(3_600_000L))
        assertEquals("01:30", TimeUtils.formatCountdownHm(5_400_000L))
    }
}
