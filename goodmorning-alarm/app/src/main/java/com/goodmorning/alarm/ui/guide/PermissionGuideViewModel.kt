package com.goodmorning.alarm.ui.guide

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.goodmorning.alarm.util.Permissions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 权限引导状态（P1-3）：
 * 各步完成状态检测 + 系统设置页跳转意图；自启动无法编程检测，标记为“需手动确认”；
 * 全屏显示通知（Android 14+）仅在高版本系统上展示与检测。
 */
class PermissionGuideViewModel(application: Application) : AndroidViewModel(application) {

    /** 引导步骤完成状态（null = 无法自动检测，需手动确认） */
    data class GuideState(
        val notificationsGranted: Boolean = false,
        val exactAlarmGranted: Boolean = false,
        /** MIUI 自启动无公开检测 API：null = 需手动确认 */
        val autostartConfirmed: Boolean? = null,
        val batteryUnrestricted: Boolean = false,
        /** 全屏显示通知（Android 14+；低版本恒为 true 且对应步骤隐藏） */
        val fullScreenIntentGranted: Boolean = false
    )

    private val _state = MutableStateFlow(GuideState())
    val state: StateFlow<GuideState> = _state

    init {
        refresh()
    }

    /** 重新检测各权限状态（从系统设置页返回时调用） */
    fun refresh() {
        val app = getApplication<Application>()
        _state.value = GuideState(
            notificationsGranted = Permissions.areNotificationsEnabled(app),
            exactAlarmGranted = Permissions.canScheduleExactAlarms(app),
            autostartConfirmed = null,
            batteryUnrestricted = Permissions.isBatteryUnrestricted(app),
            fullScreenIntentGranted = Permissions.canUseFullScreenIntent(app)
        )
    }

    /** 通知设置页意图 */
    fun notificationSettingsIntent(): Intent =
        Permissions.notificationSettingsIntent(getApplication())

    /** 精确闹钟授权页意图 */
    fun exactAlarmSettingsIntent(): Intent =
        Permissions.exactAlarmSettingsIntent(getApplication())

    /**
     * HyperOS 自启动页意图（最佳努力直达），失败回退应用详情页。
     * @return 实际可用的意图
     */
    fun autostartIntent(): Intent {
        val app = getApplication<Application>()
        return Permissions.miuiAutoStartIntent(app) ?: Permissions.appDetailsIntent(app)
    }

    /** 是否成功跳转到 MIUI 自启动页（否则 UI 提示手动查找） */
    fun hasDirectAutostartPage(): Boolean =
        Permissions.miuiAutoStartIntent(getApplication()) != null

    /** 电池优化设置页意图 */
    fun batteryIntent(): Intent = Permissions.batteryOptimizationSettingsIntent()

    /** 全屏显示通知授权页意图（Android 14+；低版本返回 null，UI 隐藏该步） */
    fun fullScreenIntentSettingsIntent(): Intent? =
        Permissions.fullScreenIntentSettingsIntent(getApplication())

    /** 触发一次异步刷新（预留：未来接入 usagestats 等检测） */
    fun refreshAsync() {
        viewModelScope.launch { refresh() }
    }
}
