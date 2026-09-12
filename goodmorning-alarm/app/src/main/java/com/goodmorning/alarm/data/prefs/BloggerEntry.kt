package com.goodmorning.alarm.data.prefs

import kotlinx.serialization.Serializable

/**
 * 博主历史条目：记录最近使用过的博主，供设置页快速来回切换。
 * [name] 为展示名（dyproxy 返回 root title / 校验回退名）。
 */
@Serializable
data class BloggerEntry(
    val secUid: String,
    val name: String,
    val lastUsedAt: Long
)
