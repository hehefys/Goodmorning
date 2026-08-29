package com.goodmorning.alarm.ui.ringing

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.goodmorning.alarm.data.prefs.SettingsRepository
import com.goodmorning.alarm.playback.AlarmService
import com.goodmorning.alarm.util.AppLogger
import com.goodmorning.alarm.util.Constants
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 响铃页状态：读取 AlarmService 共享的 [AlarmService.ringingState]，
 * 播放控制通过向服务发送 intent 指令转发。
 * V2：「打开抖音」直达当前设置的博主主页（secUid 从设置读取，不再用 Constants 硬编码）。
 */
class RingingViewModel(application: Application) : AndroidViewModel(application) {

    /** 当前响铃状态（null = 已停止，页面应自行关闭） */
    val state: StateFlow<AlarmService.RingingState?> = AlarmService.ringingState

    /** 当前博主 sec_uid（init 读一次设置；默认内置博主兜底） */
    private var bloggerSecUid: String = Constants.SEC_UID

    init {
        viewModelScope.launch {
            bloggerSecUid = SettingsRepository(getApplication()).current().bloggerSecUid
        }
    }

    /** 停止本次响铃（服务会注册明天闹钟） */
    fun stop() = sendCommand(Constants.ACTION_STOP)

    /** 贪睡（按设置间隔 N 分钟后重响） */
    fun snooze() = sendCommand(Constants.ACTION_SNOOZE)

    /** 播放/暂停切换 */
    fun togglePlayPause() = sendCommand(Constants.ACTION_PLAY_PAUSE)

    /**
     * 打开抖音 App 直达博主主页（方案 A：数据源不可靠时的兜底入口）。
     * 使用 ACTION_VIEW 将 https://www.douyin.com/user/<sec_uid> 交给系统解析：
     * 已安装抖音则直接拉起，否则退回系统浏览器；任何解析失败仅记日志，不崩溃。
     */
    fun openDouyin() {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.douyin.com/user/$bloggerSecUid")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            getApplication<Application>().startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            AppLogger.w(TAG, "打开抖音失败：未找到可处理该链接的应用", e)
        } catch (e: SecurityException) {
            AppLogger.w(TAG, "打开抖音失败：被系统安全策略拦截", e)
        }
    }

    private fun sendCommand(action: String) {
        AlarmService.start(getApplication<Application>(), action)
    }

    private companion object {
        const val TAG = Constants.TAG_PREFIX + "Ringing"
    }
}
