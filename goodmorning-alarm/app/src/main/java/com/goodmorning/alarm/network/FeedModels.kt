package com.goodmorning.alarm.network

/**
 * RSSHub JSON Feed 解析结果条目。
 *
 * @property id 抖音 aweme_id（唯一键）
 * @property title 视频文案首行
 * @property publishTimeMillis 发布时间 epoch ms；0 = pubDate 缺失/解析失败（无法判定“当天”）
 * @property pageUrl 视频页面地址
 * @property videoUrl 视频直链（302 → CDN mp4，数小时时效）
 */
data class VideoItem(
    val id: String,
    val title: String,
    val publishTimeMillis: Long,
    val pageUrl: String,
    val videoUrl: String?
)
