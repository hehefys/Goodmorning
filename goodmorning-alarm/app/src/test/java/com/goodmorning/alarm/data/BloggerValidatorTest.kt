package com.goodmorning.alarm.data

import com.goodmorning.alarm.data.repo.BloggerValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 博主输入解析规则单测（DESIGN-V2 §3.1.2）：
 * 1. 含 "douyin.com/user/" → 正则取 group(1)；
 * 2. 整体匹配纯 sec_uid（≥20 位字母数字_-）；
 * 3. 其余 → null（不发请求）。
 */
class BloggerValidatorTest {

    private val secUid =
        "MS4wLjABAAAAkme-Sn9GBLHkPFE6TSfhhmHbEfphTt7ZNL9BD14NWAneay8H7OxJQ05-CP9VgmSJ"

    @Test
    fun `parse douyin user link`() {
        assertEquals(
            secUid,
            BloggerValidator.parseInput("https://www.douyin.com/user/$secUid")
        )
    }

    @Test
    fun `parse douyin user link with query params`() {
        assertEquals(
            secUid,
            BloggerValidator.parseInput("https://www.douyin.com/user/${secUid}?sec_uid=xxx")
        )
    }

    @Test
    fun `parse plain sec uid`() {
        assertEquals(secUid, BloggerValidator.parseInput("  $secUid  "))
    }

    @Test
    fun `parse sec uid without scheme`() {
        assertEquals(
            secUid,
            BloggerValidator.parseInput("www.douyin.com/user/$secUid?from=share")
        )
    }

    @Test
    fun `reject short garbage`() {
        assertNull(BloggerValidator.parseInput("abc123"))
    }

    @Test
    fun `reject arbitrary url without user path`() {
        assertNull(BloggerValidator.parseInput("https://www.douyin.com/video/7412345678901234567"))
    }

    @Test
    fun `reject empty input`() {
        assertNull(BloggerValidator.parseInput(""))
        assertNull(BloggerValidator.parseInput("   "))
    }

    @Test
    fun `reject douyin user link with malformed sec uid`() {
        // user/ 后跟非法字符（点号）→ 无法提取 group，且整体非纯 sec_uid → null
        assertNull(BloggerValidator.parseInput("https://www.douyin.com/user/not.valid.uid!!"))
    }
}
