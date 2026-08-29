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
    /** 贪睡间隔（分钟，可选 5/10/15，默认 10） */
    val snoozeMinutes: Int = Constants.SNOOZE_DEFAULT,
    /** 音量渐强开关（默认开） */
    val volumeFadeEnabled: Boolean = true,
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
