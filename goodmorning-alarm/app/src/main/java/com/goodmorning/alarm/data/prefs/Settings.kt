package com.goodmorning.alarm.data.prefs

import com.goodmorning.alarm.util.Constants

/**
 * 不可变设置数据类（DataStore 持久化的内存映像）。
 * 响铃时长上限（P2-5）等后续字段在此扩展。
 */
data class Settings(
    /** 闹钟总开关 */
    val alarmEnabled: Boolean = false,
    /** 闹钟小时（24h 制，默认 07:00） */
    val alarmHour: Int = 7,
    /** 闹钟分钟 */
    val alarmMinute: Int = 0,
    /** RSSHub Base URL（可换自建实例） */
    val rsshubBaseUrl: String = Constants.DEFAULT_RSSHUB_BASE,
    /** 当前博主的抖音 sec_uid（V2 博主维度，默认内置博主） */
    val bloggerSecUid: String = Constants.SEC_UID,
    /** 当前博主展示名（V2 博主维度） */
    val bloggerName: String = DEFAULT_BLOGGER_NAME,
    /** 贪睡间隔（分钟，1~30 自由调节，默认 10） */
    val snoozeMinutes: Int = Constants.SNOOZE_DEFAULT,
    /** 音量渐强开关（默认开） */
    val volumeFadeEnabled: Boolean = true,
    /** 音量渐强时长（秒，5~60，默认 20） */
    val volumeFadeSeconds: Int = Constants.FADE_DEFAULT_SECONDS,
    /** 整段播完后若用户未手动关闭，自动再播一遍（默认关，关闭闹钟才停） */
    val replayEnabled: Boolean = false,
    /** 副音频衬托开关（默认关）：闹钟开始先循环副音频，衬托结束起播视频音频 */
    val ambientEnabled: Boolean = false,
    /** 副音频文件 URI（SAF 持久授权；空 = 未选择） */
    val ambientUri: String = "",
    /** 副音频文件名（仅展示用） */
    val ambientName: String = "",
    /** 副音频基础音量 0-100 */
    val ambientVolume: Int = 30,
    /** 视频音频播放期间副音频压低到的音量 0-100 */
    val ambientDuckedVolume: Int = 10,
    /** 视频音频起播前的衬托时长（秒，10~600） */
    val ambientLeadSeconds: Int = Constants.AMBIENT_LEAD_DEFAULT,
    /** 副音频裁剪起点（毫秒，0 = 从头播） */
    val ambientStartMs: Long = Constants.AMBIENT_CLIP_UNSET,
    /** 副音频裁剪终点（毫秒，0 = 播到文件结尾） */
    val ambientEndMs: Long = Constants.AMBIENT_CLIP_UNSET,
    /** 副音频文件总时长（毫秒，0 = 未知；供设置页滑条边界使用，选中文件时探测） */
    val ambientDurationMs: Long = 0L,
    /** 最近同步时间（展示文本 MM-dd HH:mm；空 = 从未同步） */
    val lastSyncAt: String = "",
    /** 最近同步是否成功（部分成功也算成功） */
    val lastSyncOk: Boolean = false,
    /** 最近同步结果信息（含错误码前缀 NETWORK:/PARSE:/EMPTY:/DOWNLOAD:） */
    val lastSyncMsg: String = ""
) {
    companion object {
        /** 默认博主展示名（数据层默认值，非 UI 文案） */
        const val DEFAULT_BLOGGER_NAME = "每日早安"
    }
}
