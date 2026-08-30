package com.goodmorning.alarm.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.goodmorning.alarm.alarm.AlarmScheduler
import com.goodmorning.alarm.data.prefs.Settings
import com.goodmorning.alarm.data.prefs.SettingsRepository
import com.goodmorning.alarm.data.repo.VideoRepository
import com.goodmorning.alarm.util.Permissions
import com.goodmorning.alarm.util.TimeUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/**
 * 主页状态与动作：设置 Flow、当前时间 ticker、下次响铃倒计时、
 * 闹钟开关（权限检查 → 注册/取消）、最近同步状态、贪睡状态、
 * V2 新增：缓存条数与当前博主名（透出自 [Settings]）。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository(application)
    private val alarmScheduler = AlarmScheduler(application)
    private val repository = VideoRepository(application)

    /** 每秒跳动的当前时刻（epoch ms） */
    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1000)
        }
    }

    /** 本地缓存视频条数（Room Flow 驱动，同步/清理后自动刷新） */
    private val cacheCount: Flow<Int> = repository.cachedVideos().map { it.size }

    /** 主页展示状态 */
    data class MainUiState(
        val settings: Settings = Settings(),
        val nowMillis: Long = System.currentTimeMillis(),
        /** 下次响铃时刻（alarmEnabled 时有效） */
        val nextRingAtMillis: Long = 0L,
        /** 距下次响铃剩余毫秒 */
        val remainMillis: Long = 0L,
        /** 贪睡待触发时刻；null = 无 */
        val snoozeUntilMillis: Long? = null,
        /** 精确闹钟权限是否已授予 */
        val exactAlarmGranted: Boolean = true,
        /** 通知权限是否已授予（F5 常驻提醒） */
        val notificationsGranted: Boolean = true,
        /** V2：本地缓存视频条数（状态卡行③展示） */
        val cacheCount: Int = 0
    )

    /** F5：权限刷新信号（onResume / 引导页返回时 +1，强制立即重算权限标记） */
    private val permissionRefresh = MutableStateFlow(0)

    val uiState: StateFlow<MainUiState> = combine(
        settingsRepository.settings,
        ticker,
        AlarmScheduler.snoozeUntilMillis,
        permissionRefresh,
        cacheCount
    ) { settings, now, snoozeUntil, _, cached ->
        val nextAt = if (settings.alarmEnabled) {
            TimeUtils.nextDailyAt(settings.alarmHour, settings.alarmMinute, now)
        } else 0L
        val app = getApplication<Application>()
        MainUiState(
            settings = settings,
            nowMillis = now,
            nextRingAtMillis = nextAt,
            remainMillis = if (nextAt > 0) (nextAt - now).coerceAtLeast(0) else 0L,
            snoozeUntilMillis = snoozeUntil?.takeIf { it > now },
            exactAlarmGranted = Permissions.canScheduleExactAlarms(app),
            notificationsGranted = Permissions.areNotificationsEnabled(app),
            cacheCount = cached
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    /** 一次性事件：提醒用户去授权精确闹钟 */
    val needExactAlarmPermission = MutableStateFlow(false)

    /** 设置闹钟时间（若已启用则立即重新注册下一次） */
    fun setAlarmTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            settingsRepository.setAlarmTime(hour, minute)
            val settings = settingsRepository.current()
            if (settings.alarmEnabled) {
                alarmScheduler.scheduleNextDaily(hour, minute)
            }
        }
    }

    /** 开/关闹钟 */
    fun setAlarmEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                if (!Permissions.canScheduleExactAlarms(getApplication<Application>())) {
                    // 无精确权限：仍降级注册保证响铃，同时提示用户去授权
                    needExactAlarmPermission.value = true
                }
                val settings = settingsRepository.current()
                alarmScheduler.scheduleNextDaily(settings.alarmHour, settings.alarmMinute)
            } else {
                alarmScheduler.cancel()
            }
            settingsRepository.setAlarmEnabled(enabled)
        }
    }

    /** 用户从系统设置页/引导页返回后刷新权限标记（F5：onResume 调用） */
    fun refreshPermissionState() {
        permissionRefresh.value += 1
        needExactAlarmPermission.value = !Permissions.canScheduleExactAlarms(getApplication<Application>())
    }
}
