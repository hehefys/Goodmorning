package com.goodmorning.alarm.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 视频缓存元数据（ARCHITECTURE.md §3.1）。
 * 主键为抖音 aweme_id，同步时按 id 去重 upsert。
 */
@Entity(tableName = "videos")
data class VideoEntity(
    /** 抖音 aweme_id（从 item.link 提取） */
    @PrimaryKey
    val id: String,
    /** 视频标题（desc 首行） */
    val title: String,
    /** 发布时间 epoch ms（pubDate 解析；0 表示无法判定） */
    val publishTimeMillis: Long,
    /** 本地 yyyy-MM-dd（选片“当天”判定用；空串表示无法判定，走规则②） */
    val publishDate: String,
    /** 页面地址 https://www.douyin.com/video/{id} */
    val pageUrl: String,
    /** RSSHub 给的直链（有时效，仅下载期使用） */
    val videoUrl: String?,
    /** 本地 mp4 路径；null = 未下载成功 */
    val localPath: String?,
    /** 字节数 */
    val fileSize: Long,
    /** 下载完成时间 */
    val downloadedAt: Long?
)
