package com.goodmorning.alarm.util

/**
 * 全局常量（ARCHITECTURE.md §7 共享约定的单一来源）。
 */
object Constants {

    // ===== 应用版本 =====
    /** 版本号（设置页「关于」展示；未启用 BuildConfig 时使用此常量） */
    const val APP_VERSION = "2.0.0"

    // ===== 数据源 =====
    /** 抖音用户「每日早安」的 sec_uid */
    const val SEC_UID =
        "MS4wLjABAAAAkme-Sn9GBLHkPFE6TSfhhmHbEfphTt7ZNL9BD14NWAneay8H7OxJQ05-CP9VgmSJ"

    /** RSSHub 默认公共实例 */
    const val DEFAULT_RSSHUB_BASE = "https://rsshub.app"

    /** RSSHub 抖音用户视频路由模板：embed=1 内嵌视频直链，format=json 返回 JSON Feed */
    const val RSSHUB_ROUTE_TEMPLATE = "%s/douyin/user/%s?embed=1&format=json"

    // ===== 同步时刻 =====
    const val SYNC_HOUR_MORNING = 5
    const val SYNC_MINUTE_MORNING = 30
    const val SYNC_HOUR_EVENING = 21
    const val SYNC_MINUTE_EVENING = 0

    // ===== 缓存与贪睡 =====
    /** 本地缓存保留条数 */
    const val CACHE_KEEP_COUNT = 3

    /** 贪睡默认间隔（分钟） */
    const val SNOOZE_DEFAULT = 10

    /** 贪睡可选项（分钟） */
    val SNOOZE_OPTIONS: List<Int> = listOf(5, 10, 15)

    // ===== 音量渐强 =====
    const val FADE_START = 0.3f
    const val FADE_DURATION_MS = 20_000L
    const val FADE_STEP_MS = 500L

    // ===== 第四级兜底：ToneGenerator 蜂鸣（无任何可用铃声 URI 时） =====
    /** ToneGenerator 音量（0-100） */
    const val TONE_VOLUME = 80
    /** 单次蜂鸣时长 */
    const val TONE_BEEP_DURATION_MS = 800
    /** 蜂鸣循环周期 */
    const val TONE_BEEP_PERIOD_MS = 1500L

    // ===== Service / 广播 Action =====
    const val ACTION_RING = "com.goodmorning.alarm.action.RING"
    const val ACTION_STOP = "com.goodmorning.alarm.action.STOP"
    const val ACTION_SNOOZE = "com.goodmorning.alarm.action.SNOOZE"
    const val ACTION_PLAY_PAUSE = "com.goodmorning.alarm.action.PLAY_PAUSE"

    // ===== 通知 =====
    const val CHANNEL_ALARM = "alarm_channel"
    const val CHANNEL_SYNC = "sync_channel"
    const val NOTIF_ID_RINGING = 2001

    // ===== PendingIntent requestCode =====
    const val REQUEST_CODE_DAILY = 1001
    const val REQUEST_CODE_SNOOZE = 1002

    // ===== 目录 =====
    const val VIDEO_DIR = "videos"
    const val LOG_DIR = "logs"

    // ===== 下载请求头（抖音防盗链，调研结论） =====
    const val DOUYIN_UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"
    const val DOUYIN_REFERER = "https://www.douyin.com/"

    // ===== 日志 =====
    /** 统一日志 Tag 前缀，如 GMA/Sync */
    const val TAG_PREFIX = "GMA/"
    /** 本地文件日志保留天数 */
    const val LOG_RETENTION_DAYS = 7

    // ===== 播放来源（PlaybackLog.source 取值） =====
    const val SOURCE_TODAY = "TODAY"
    const val SOURCE_CACHED = "CACHED"
    const val SOURCE_FALLBACK = "FALLBACK"
}
