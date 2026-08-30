package com.goodmorning.alarm.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.goodmorning.alarm.util.Constants
import com.goodmorning.alarm.util.TimeUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 顶层 DataStore 单例（同一文件只允许一个实例） */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * DataStore Preferences 封装：暴露 [Settings] Flow 与 suspend setter。
 * 写入即持久化，读取始终走 Flow 保证 UI 即时刷新。
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val ALARM_ENABLED = booleanPreferencesKey("alarm_enabled")
        val ALARM_HOUR = intPreferencesKey("alarm_hour")
        val ALARM_MINUTE = intPreferencesKey("alarm_minute")
        val RSSHUB_BASE_URL = stringPreferencesKey("rsshub_base_url")
        val SNOOZE_MINUTES = intPreferencesKey("snooze_minutes")
        val VOLUME_FADE_ENABLED = booleanPreferencesKey("volume_fade_enabled")
        val VOLUME_FADE_SECONDS = intPreferencesKey("volume_fade_seconds")
        val REPLAY_ENABLED = booleanPreferencesKey("replay_enabled")

        // ---- 副音频衬托 ----
        val AMBIENT_ENABLED = booleanPreferencesKey("ambient_enabled")
        val AMBIENT_URI = stringPreferencesKey("ambient_uri")
        val AMBIENT_NAME = stringPreferencesKey("ambient_name")
        val AMBIENT_VOLUME = intPreferencesKey("ambient_volume")
        val AMBIENT_DUCKED_VOLUME = intPreferencesKey("ambient_ducked_volume")
        val AMBIENT_LEAD_SECONDS = intPreferencesKey("ambient_lead_seconds")
        val AMBIENT_START_MS = longPreferencesKey("ambient_start_ms")
        val AMBIENT_END_MS = longPreferencesKey("ambient_end_ms")
        val AMBIENT_DURATION_MS = longPreferencesKey("ambient_duration_ms")
        val LAST_SYNC_AT = stringPreferencesKey("last_sync_at")
        val LAST_SYNC_OK = booleanPreferencesKey("last_sync_ok")
        val LAST_SYNC_MSG = stringPreferencesKey("last_sync_msg")

        // ---- V2 博主维度 ----
        /** 当前博主 sec_uid */
        val BLOGGER_SEC_UID = stringPreferencesKey("blogger_sec_uid")
        /** 当前博主展示名 */
        val BLOGGER_NAME = stringPreferencesKey("blogger_name")
        /** 上一次同步时使用的博主 sec_uid（SyncEngine 判断「换博主→清缓存」） */
        val LAST_BLOGGER_SEC_UID = stringPreferencesKey("last_blogger_sec_uid")
    }

    /** 当前设置（带默认值兜底，读取失败/缺省均回落到 [Settings] 默认） */
    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            alarmEnabled = prefs[Keys.ALARM_ENABLED] ?: false,
            alarmHour = prefs[Keys.ALARM_HOUR] ?: 7,
            alarmMinute = prefs[Keys.ALARM_MINUTE] ?: 0,
            rsshubBaseUrl = prefs[Keys.RSSHUB_BASE_URL] ?: Constants.DEFAULT_RSSHUB_BASE,
            bloggerSecUid = prefs[Keys.BLOGGER_SEC_UID] ?: Constants.SEC_UID,
            bloggerName = prefs[Keys.BLOGGER_NAME] ?: Settings.DEFAULT_BLOGGER_NAME,
            snoozeMinutes = prefs[Keys.SNOOZE_MINUTES] ?: Constants.SNOOZE_DEFAULT,
            volumeFadeEnabled = prefs[Keys.VOLUME_FADE_ENABLED] ?: true,
            volumeFadeSeconds = prefs[Keys.VOLUME_FADE_SECONDS] ?: Constants.FADE_DEFAULT_SECONDS,
            replayEnabled = prefs[Keys.REPLAY_ENABLED] ?: false,
            ambientEnabled = prefs[Keys.AMBIENT_ENABLED] ?: false,
            ambientUri = prefs[Keys.AMBIENT_URI] ?: "",
            ambientName = prefs[Keys.AMBIENT_NAME] ?: "",
            ambientVolume = prefs[Keys.AMBIENT_VOLUME] ?: 30,
            ambientDuckedVolume = prefs[Keys.AMBIENT_DUCKED_VOLUME] ?: 10,
            ambientLeadSeconds = prefs[Keys.AMBIENT_LEAD_SECONDS] ?: Constants.AMBIENT_LEAD_DEFAULT,
            ambientStartMs = prefs[Keys.AMBIENT_START_MS] ?: Constants.AMBIENT_CLIP_UNSET,
            ambientEndMs = prefs[Keys.AMBIENT_END_MS] ?: Constants.AMBIENT_CLIP_UNSET,
            ambientDurationMs = prefs[Keys.AMBIENT_DURATION_MS] ?: 0L,
            lastSyncAt = prefs[Keys.LAST_SYNC_AT] ?: "",
            lastSyncOk = prefs[Keys.LAST_SYNC_OK] ?: false,
            lastSyncMsg = prefs[Keys.LAST_SYNC_MSG] ?: ""
        )
    }

    /** 一次性读取当前设置（用于 Service/Receiver 等非 Flow 场景） */
    suspend fun current(): Settings = settings.first()

    suspend fun setAlarmEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ALARM_ENABLED] = enabled }
    }

    suspend fun setAlarmTime(hour: Int, minute: Int) {
        context.dataStore.edit {
            it[Keys.ALARM_HOUR] = hour.coerceIn(0, 23)
            it[Keys.ALARM_MINUTE] = minute.coerceIn(0, 59)
        }
    }

    /** 保存 RSSHub Base URL：去首尾空白与多余的结尾斜杠 */
    suspend fun setRssBaseUrl(url: String) {
        val normalized = url.trim().trimEnd('/')
        context.dataStore.edit {
            it[Keys.RSSHUB_BASE_URL] =
                if (normalized.isEmpty()) Constants.DEFAULT_RSSHUB_BASE else normalized
        }
    }

    suspend fun setSnoozeMinutes(minutes: Int) {
        context.dataStore.edit {
            it[Keys.SNOOZE_MINUTES] = minutes.coerceIn(Constants.SNOOZE_MIN, Constants.SNOOZE_MAX)
        }
    }

    suspend fun setVolumeFadeEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.VOLUME_FADE_ENABLED] = enabled }
    }

    suspend fun setVolumeFadeSeconds(seconds: Int) {
        context.dataStore.edit {
            it[Keys.VOLUME_FADE_SECONDS] = seconds.coerceIn(
                Constants.FADE_MIN_SECONDS, Constants.FADE_MAX_SECONDS
            )
        }
    }

    suspend fun setReplayEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.REPLAY_ENABLED] = enabled }
    }

    // ---- 副音频衬托 ----

    suspend fun setAmbientEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AMBIENT_ENABLED] = enabled }
    }

    /**
     * 保存副音频来源（uri + 展示名 + 探测到的文件时长，原子写入）。
     * 换文件后旧裁剪区间必然失效，一并重置为「从头播到尾」。
     */
    suspend fun setAmbientSource(uri: String, name: String, durationMs: Long = 0L) {
        context.dataStore.edit {
            it[Keys.AMBIENT_URI] = uri
            it[Keys.AMBIENT_NAME] = name
            it[Keys.AMBIENT_DURATION_MS] = durationMs.coerceAtLeast(0L)
            it[Keys.AMBIENT_START_MS] = Constants.AMBIENT_CLIP_UNSET
            it[Keys.AMBIENT_END_MS] = Constants.AMBIENT_CLIP_UNSET
        }
    }

    /** 保存裁剪起点：不越过终点（终点已设时至少留出最小间隔） */
    suspend fun setAmbientStartMs(startMs: Long) {
        context.dataStore.edit { prefs ->
            val end = prefs[Keys.AMBIENT_END_MS] ?: Constants.AMBIENT_CLIP_UNSET
            val maxStart = if (end > Constants.AMBIENT_CLIP_UNSET) {
                end - Constants.AMBIENT_CLIP_MIN_GAP_S * 1000L
            } else {
                Long.MAX_VALUE
            }
            prefs[Keys.AMBIENT_START_MS] =
                startMs.coerceIn(Constants.AMBIENT_CLIP_UNSET, maxStart)
        }
    }

    /** 保存裁剪终点：0 = 播到结尾；已设时不得越过起点 + 最小间隔 */
    suspend fun setAmbientEndMs(endMs: Long) {
        context.dataStore.edit { prefs ->
            val start = prefs[Keys.AMBIENT_START_MS] ?: Constants.AMBIENT_CLIP_UNSET
            val minEnd = start + Constants.AMBIENT_CLIP_MIN_GAP_S * 1000L
            prefs[Keys.AMBIENT_END_MS] =
                if (endMs <= Constants.AMBIENT_CLIP_UNSET) {
                    Constants.AMBIENT_CLIP_UNSET
                } else {
                    endMs.coerceAtLeast(minEnd)
                }
        }
    }

    suspend fun setAmbientVolume(volume: Int) {
        context.dataStore.edit { it[Keys.AMBIENT_VOLUME] = volume.coerceIn(0, 100) }
    }

    suspend fun setAmbientDuckedVolume(volume: Int) {
        context.dataStore.edit { it[Keys.AMBIENT_DUCKED_VOLUME] = volume.coerceIn(0, 100) }
    }

    suspend fun setAmbientLeadSeconds(seconds: Int) {
        context.dataStore.edit {
            it[Keys.AMBIENT_LEAD_SECONDS] = seconds.coerceIn(
                Constants.AMBIENT_LEAD_MIN, Constants.AMBIENT_LEAD_MAX
            )
        }
    }

    /** 写入最近一次同步结果（同时被“立即同步”与后台 Worker 调用） */
    suspend fun setSyncResult(ok: Boolean, message: String) {
        context.dataStore.edit {
            it[Keys.LAST_SYNC_AT] = TimeUtils.formatShort()
            it[Keys.LAST_SYNC_OK] = ok
            it[Keys.LAST_SYNC_MSG] = message
        }
    }

    // ---- V2 博主维度 ----

    /**
     * 更换博主：同时写入 sec_uid 与展示名（原子写入，供设置页博主对话框调用）。
     */
    suspend fun setBlogger(secUid: String, name: String) {
        context.dataStore.edit {
            it[Keys.BLOGGER_SEC_UID] = secUid
            it[Keys.BLOGGER_NAME] = name
        }
    }

    /** 读取上一次同步使用的博主 sec_uid（空串 = 从未同步过） */
    suspend fun lastBloggerSecUid(): String =
        context.dataStore.data.first()[Keys.LAST_BLOGGER_SEC_UID] ?: ""

    /** 写入上一次同步使用的博主 sec_uid（同步开始前由 SyncEngine 调用） */
    suspend fun setLastBloggerSecUid(value: String) {
        context.dataStore.edit { it[Keys.LAST_BLOGGER_SEC_UID] = value }
    }
}
