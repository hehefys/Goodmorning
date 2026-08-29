package com.goodmorning.alarm.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.goodmorning.alarm.R
import com.goodmorning.alarm.data.prefs.Settings
import com.goodmorning.alarm.data.prefs.SettingsRepository
import com.goodmorning.alarm.data.repo.BloggerValidator
import com.goodmorning.alarm.data.repo.VideoRepository
import com.goodmorning.alarm.sync.SyncEngine
import com.goodmorning.alarm.sync.SyncError
import com.goodmorning.alarm.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页状态与动作（V2 §2.3 / §3.1）：
 * - RSSHub 地址编辑/恢复默认、贪睡时长、音量渐强、立即同步（结果回显）、缓存占用与清理；
 * - 博主管理：更换博主对话框状态机（Idle/Parsing/Validating/Done/Failed），
 *   调 [BloggerValidator] 校验 → 保存 → 自动重同步（内部换博主清缓存生效）。
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository(application)
    private val repository = VideoRepository(application)
    private val syncEngine = SyncEngine(application)
    private val bloggerValidator = BloggerValidator(application)

    /** 缓存占用字节数 */
    private val cacheBytes = MutableStateFlow(0L)

    /** 是否正在执行手动同步 */
    private val syncing = MutableStateFlow(false)

    /** 一次性提示消息（如“缓存已清理”），消费后置空 */
    val toastMessage = MutableStateFlow<String?>(null)

    /** 博主对话框输入（独立状态，关闭时清空） */
    private val bloggerInput = MutableStateFlow("")
    /** 博主对话框状态机 */
    private val bloggerState = MutableStateFlow<BloggerUiState>(BloggerUiState.Idle)

    /** 博主更换流程 UI 状态（DESIGN-V2 §3.1.3） */
    sealed class BloggerUiState {
        /** 待输入 */
        object Idle : BloggerUiState()
        /** 本地解析中（瞬时） */
        object Parsing : BloggerUiState()
        /** 网络校验中（按钮禁用 + 菊花） */
        object Validating : BloggerUiState()
        /** 成功（对话框已关闭） */
        data class Done(val name: String) : BloggerUiState()
        /** 失败（msg 为可展示原因，留在对话框内联红字） */
        data class Failed(val parseError: Boolean, val msg: String) : BloggerUiState()
    }

    /** 设置页展示状态 */
    data class SettingsUiState(
        val settings: Settings = Settings(),
        /** 地址输入框内容（独立于已保存值，避免每键入一字符就写库） */
        val urlInput: String = "",
        val cacheBytes: Long = 0L,
        val syncing: Boolean = false
    )

    private val urlInput = MutableStateFlow("")

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings, urlInput, cacheBytes, syncing
    ) { settings, input, bytes, isSyncing ->
        SettingsUiState(
            settings = settings,
            // 输入框尚未初始化（首次加载）时显示已保存值
            urlInput = input.ifEmpty { settings.rsshubBaseUrl },
            cacheBytes = bytes,
            syncing = isSyncing
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    /** 博主对话框对外状态（输入 + 流程状态） */
    val bloggerUiState: StateFlow<BloggerUiState> = bloggerState
    val bloggerInputValue: StateFlow<String> = bloggerInput

    init {
        refreshCacheBytes()
    }

    // ---- RSSHub 地址（逻辑不变） ----

    /** 地址输入框内容变更 */
    fun onUrlInputChange(value: String) {
        urlInput.value = value
    }

    /** 保存地址（规范化去空白/结尾斜杠） */
    fun saveRssBaseUrl() {
        viewModelScope.launch {
            settingsRepository.setRssBaseUrl(urlInput.value)
            // 保存后立即同步一次校验连通性（P1-2）
            syncNow()
        }
    }

    /** 恢复默认 RSSHub 地址并同步 */
    fun restoreDefaultUrl() {
        viewModelScope.launch {
            settingsRepository.setRssBaseUrl(Constants.DEFAULT_RSSHUB_BASE)
            urlInput.value = Constants.DEFAULT_RSSHUB_BASE
            syncNow()
        }
    }

    // ---- 播放（逻辑不变） ----

    /** 设置贪睡间隔（5/10/15） */
    fun setSnoozeMinutes(minutes: Int) {
        viewModelScope.launch {
            settingsRepository.setSnoozeMinutes(minutes)
        }
    }

    /** 设置音量渐强开关 */
    fun setVolumeFadeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setVolumeFadeEnabled(enabled)
        }
    }

    // ---- 立即同步 / 缓存（逻辑不变） ----

    /** 立即同步（直接跑 SyncEngine，结果写入 DataStore 并回显，P1-4） */
    fun syncNow() {
        if (syncing.value) return
        viewModelScope.launch {
            syncing.value = true
            try {
                withContext(Dispatchers.IO) { syncEngine.sync() }
                refreshCacheBytes()
            } finally {
                syncing.value = false
            }
        }
    }

    /** 清理缓存（文件 + 记录） */
    fun clearCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.clearCache() }
            refreshCacheBytes()
            toastMessage.value = getApplication<Application>()
                .getString(R.string.settings_cache_cleared)
        }
    }

    /** 消费一次性提示 */
    fun consumeToast() {
        toastMessage.value = null
    }

    // ---- 博主管理（V2 功能 A） ----

    /** 打开更换博主对话框（清空上次输入） */
    fun openBloggerDialog() {
        bloggerInput.value = ""
        bloggerState.value = BloggerUiState.Idle
    }

    /** 关闭对话框（校验中不允许关闭时由 UI 层禁用按钮） */
    fun dismissBloggerDialog() {
        bloggerState.value = BloggerUiState.Idle
        bloggerInput.value = ""
    }

    /** 对话框输入变更 */
    fun onBloggerInputChange(value: String) {
        bloggerInput.value = value
        // 重新输入时清除上次的内联错误
        if (bloggerState.value is BloggerUiState.Failed) {
            bloggerState.value = BloggerUiState.Idle
        }
    }

    /**
     * 确认更换：本地解析 → 网络校验 → 保存 → 关闭对话框 → Snackbar → 自动重同步。
     * 解析失败/校验失败均停留对话框并内联红字。
     */
    fun confirmChangeBlogger() {
        if (bloggerState.value is BloggerUiState.Validating) return
        val app = getApplication<Application>()
        viewModelScope.launch {
            bloggerState.value = BloggerUiState.Parsing
            val input = bloggerInput.value
            // 本地解析（不发请求的快速失败路径）
            if (BloggerValidator.parseInput(input) == null) {
                bloggerState.value = BloggerUiState.Failed(
                    parseError = true,
                    msg = app.getString(R.string.blogger_parse_error)
                )
                return@launch
            }
            // 网络校验
            bloggerState.value = BloggerUiState.Validating
            val result = bloggerValidator.validate(input)
            result.fold(
                onSuccess = { info ->
                    settingsRepository.setBlogger(info.secUid, info.name)
                    bloggerState.value = BloggerUiState.Done(info.name)
                    toastMessage.value = app.getString(R.string.blogger_switch_ok_fmt, info.name)
                    // 自动重同步（SyncEngine 内换博主清缓存逻辑生效）
                    syncNow()
                },
                onFailure = { e ->
                    bloggerState.value = BloggerUiState.Failed(
                        parseError = false,
                        msg = app.getString(R.string.blogger_validate_fail_fmt, describeError(e))
                    )
                }
            )
        }
    }

    /** 校验错误 → 展示原因（NETWORK:/EMPTY: 前缀保留供高级用户辨识） */
    private fun describeError(e: Throwable): String = when (e) {
        is SyncError.Network -> "NETWORK: ${e.msg}"
        is SyncError.Empty -> "EMPTY: ${e.msg}"
        is SyncError.Parse -> "PARSE: ${e.msg}"
        else -> e.message ?: Constants.TAG_PREFIX
    }

    private fun refreshCacheBytes() {
        viewModelScope.launch {
            cacheBytes.value = withContext(Dispatchers.IO) { repository.cacheBytes() }
        }
    }
}
