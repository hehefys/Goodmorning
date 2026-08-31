package com.goodmorning.alarm.util

/**
 * 全局常量（ARCHITECTURE.md §7 共享约定的单一来源）。
 */
object Constants {

    // ===== 应用版本 =====
    /** 版本号（设置页「关于」展示；未启用 BuildConfig 时使用此常量） */
    const val APP_VERSION = "2.1.1"

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

    /** 贪睡间隔可调范围（分钟） */
    const val SNOOZE_MIN = 1
    const val SNOOZE_MAX = 30

    // ===== 音量渐强 =====
    const val FADE_START = 0.3f
    /** 渐强时长默认值（秒） */
    const val FADE_DEFAULT_SECONDS = 20
    /** 渐强时长可调范围（秒） */
    const val FADE_MIN_SECONDS = 5
    const val FADE_MAX_SECONDS = 60
    const val FADE_STEP_MS = 500L

    // ===== 副音频衬托 =====
    /** 衬托时长可调范围下限（秒）；0 = 不衬托，直接起播主音频 */
    const val AMBIENT_LEAD_MIN = 0
    /** 衬托时长可调范围上限（秒） */
    const val AMBIENT_LEAD_MAX = 600
    /** 默认衬托时长（秒） */
    const val AMBIENT_LEAD_DEFAULT = 120
    /** 副音频压低/恢复渐变时长 */
    const val AMBIENT_FADE_MS = 3_000L
    /** 视频音频播完后，副音频恢复续播的收尾时长 */
    const val AMBIENT_WRAP_UP_MS = 30_000L
    /** 副音频裁剪未设置的哨兵值（起点 0 = 从头播，终点 0 = 播到结尾） */
    const val AMBIENT_CLIP_UNSET = 0L
    /** 副音频裁剪起点与终点最小间隔（秒） */
    const val AMBIENT_CLIP_MIN_GAP_S = 5

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
    // v2：渠道创建后声音设置不可变；响铃音频由服务经 USAGE_ALARM 播放，
    // 渠道必须静音避免通知音混入闹钟音，故启用新 ID。
    const val CHANNEL_ALARM = "alarm_channel_v2"
    const val CHANNEL_SYNC = "sync_channel"
    const val NOTIF_ID_RINGING = 2001

    // ===== PendingIntent requestCode =====
    const val REQUEST_CODE_DAILY = 1001
    const val REQUEST_CODE_SNOOZE = 1002
    /** setAlarmClock 的「展示/编辑闹钟」意图（状态栏闹钟图标点击用） */
    const val REQUEST_CODE_ALARM_INFO = 1003

    // ===== 到点去重（防重复/叠加/延迟重播） =====
    /** 到点意图携带的「计划触发时刻」（epoch ms），用于识别同一场闹钟的重复投递 */
    const val EXTRA_TRIGGER_AT = "trigger_at"
    /** 手动触发（主页测试键）：跳过去重 */
    const val EXTRA_FORCE = "force"
    /** 计划触发时刻差值小于此值即视为同一场闹钟（ms） */
    const val RING_DEDUPE_WINDOW_MS = 60_000L

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
