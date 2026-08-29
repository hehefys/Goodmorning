package com.goodmorning.alarm.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.goodmorning.alarm.R
import com.goodmorning.alarm.ui.theme.ErrorBadge
import com.goodmorning.alarm.ui.theme.Ink60
import com.goodmorning.alarm.ui.theme.Ink900
import com.goodmorning.alarm.ui.theme.OnWarnContainer
import com.goodmorning.alarm.ui.theme.SectionTitleStyle
import com.goodmorning.alarm.ui.theme.ShapeLarge
import com.goodmorning.alarm.ui.theme.ShapeMedium
import com.goodmorning.alarm.ui.theme.ShapeSmall
import com.goodmorning.alarm.ui.theme.Success
import com.goodmorning.alarm.ui.theme.Sunrise100
import com.goodmorning.alarm.ui.theme.Sunrise700
import com.goodmorning.alarm.ui.theme.SunriseSurface
import java.util.Locale

/**
 * 设置页（DESIGN-V2 §2.3 分组卡片重构）：
 * 组① 博主管理（V2 功能 A）→ 组② 数据源 → 组③ 播放 → 组④ 关于（使用说明/权限引导/版本）。
 * 所有组卡统一 ShapeLarge + SunriseSurface，组标题 = 图标 + sectionTitleStyle。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToGuide: () -> Unit,
    onNavigateToUsageGuide: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showBloggerDialog by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }

    LaunchedEffect(toastMessage) {
        toastMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeToast()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // ---- 组① 博主管理（V2 功能 A） ----
            GroupCard(title = stringResource(R.string.settings_blogger_title), icon = Icons.Filled.Person) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.openBloggerDialog()
                            showBloggerDialog = true
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_blogger_current),
                            style = MaterialTheme.typography.labelLarge,
                            color = Ink60
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = uiState.settings.bloggerName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.W600,
                            color = Ink900
                        )
                    }
                    TextButton(onClick = {
                        viewModel.openBloggerDialog()
                        showBloggerDialog = true
                    }) {
                        Text(text = stringResource(R.string.settings_blogger_change))
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = Ink60,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_blogger_help),
                    style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
                    color = Ink60
                )
            }

            // ---- 组② 数据源 ----
            GroupCard(title = stringResource(R.string.settings_group_source), icon = Icons.Filled.CloudSync) {
                // 条目 1：RSSHub 地址（沿用 V1 逻辑，随新主题）
                Text(
                    text = stringResource(R.string.settings_rsshub_url_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = Ink60
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = uiState.urlInput,
                    onValueChange = { viewModel.onUrlInputChange(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(text = stringResource(R.string.settings_rsshub_url_hint))
                    },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_rsshub_url_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink60
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { viewModel.saveRssBaseUrl() },
                        shape = ShapeMedium
                    ) {
                        Text(text = stringResource(R.string.settings_btn_save_url))
                    }
                    OutlinedButton(
                        onClick = { viewModel.restoreDefaultUrl() },
                        shape = ShapeMedium
                    ) {
                        Text(text = stringResource(R.string.settings_btn_restore_default))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 条目 2：最近同步（徽章 + 时间）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.sync_status_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = Ink60,
                        modifier = Modifier.weight(1f)
                    )
                    if (uiState.settings.lastSyncAt.isNotEmpty()) {
                        SyncBadge(ok = uiState.settings.lastSyncOk)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = uiState.settings.lastSyncAt,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Ink60
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.sync_status_never),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Ink60
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 条目 3：立即同步（syncing 时禁用并变文案，逻辑不变）
                Button(
                    onClick = { viewModel.syncNow() },
                    enabled = !uiState.syncing,
                    modifier = Modifier.height(44.dp),
                    shape = ShapeMedium
                ) {
                    Text(
                        text = if (uiState.syncing) {
                            stringResource(R.string.settings_syncing)
                        } else {
                            stringResource(R.string.settings_btn_sync_now)
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 条目 4：缓存占用 + 清除
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings_cache_label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink900
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = String.format(
                            Locale.getDefault(), "%.1f MB",
                            uiState.cacheBytes / 1024f / 1024f
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.W600,
                        color = Ink900
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { viewModel.clearCache() },
                    shape = ShapeMedium
                ) {
                    Text(text = stringResource(R.string.settings_btn_clear_cache))
                }
            }

            // ---- 组③ 播放 ----
            GroupCard(title = stringResource(R.string.settings_group_playback), icon = Icons.AutoMirrored.Filled.VolumeUp) {
                // 条目 1：贪睡时长（5/10/15，逻辑不变）
                Text(
                    text = stringResource(R.string.settings_snooze_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = Ink60
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.goodmorning.alarm.util.Constants.SNOOZE_OPTIONS.forEach { minutes ->
                        FilterChip(
                            selected = uiState.settings.snoozeMinutes == minutes,
                            onClick = { viewModel.setSnoozeMinutes(minutes) },
                            label = {
                                Text(
                                    text = stringResource(
                                        R.string.settings_snooze_minutes_fmt, minutes
                                    )
                                )
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 条目 2：音量渐强（逻辑不变）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_fade_label),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Ink900
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.settings_fade_help),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Ink60
                        )
                    }
                    Switch(
                        checked = uiState.settings.volumeFadeEnabled,
                        onCheckedChange = { viewModel.setVolumeFadeEnabled(it) }
                    )
                }
            }

            // ---- 组④ 关于 ----
            GroupCard(title = stringResource(R.string.settings_group_about), icon = Icons.Filled.Info) {
                // 条目 1：使用说明（V2 功能 B）
                AboutRow(
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    label = stringResource(R.string.settings_usage_guide),
                    onClick = onNavigateToUsageGuide
                )
                // 条目 2：权限引导
                AboutRow(
                    icon = Icons.Filled.VerifiedUser,
                    label = stringResource(R.string.settings_btn_guide),
                    onClick = onNavigateToGuide
                )
                // 条目 3：版本
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings_version_label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink60
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = com.goodmorning.alarm.util.Constants.APP_VERSION,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink60
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // ---- 更换博主对话框（DESIGN-V2 §3.1.3） ----
    if (showBloggerDialog) {
        val bloggerState by viewModel.bloggerUiState.collectAsState()
        val bloggerInput by viewModel.bloggerInputValue.collectAsState()
        val validating = bloggerState is SettingsViewModel.BloggerUiState.Validating

        AlertDialog(
            onDismissRequest = { if (!validating) viewModel.dismissBloggerDialog().also { showBloggerDialog = false } },
            shape = ShapeMedium,
            title = { Text(text = stringResource(R.string.blogger_dialog_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.blogger_dialog_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink60
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = bloggerInput,
                        onValueChange = { viewModel.onBloggerInputChange(it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(text = stringResource(R.string.blogger_dialog_hint))
                        },
                        enabled = !validating,
                        singleLine = false
                    )
                    // 内联错误（解析失败 / 校验失败）
                    val failure = bloggerState as? SettingsViewModel.BloggerUiState.Failed
                    if (failure != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = failure.msg,
                            style = MaterialTheme.typography.bodyMedium,
                            color = OnWarnContainer
                        )
                    }
                }
            },
            confirmButton = {
                when {
                    validating -> {
                        // 校验中：菊花 + 禁用
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Sunrise700
                        )
                    }
                    else -> {
                        TextButton(
                            onClick = {
                                viewModel.confirmChangeBlogger()
                            }
                        ) {
                            Text(text = stringResource(R.string.blogger_dialog_confirm))
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.dismissBloggerDialog()
                        showBloggerDialog = false
                    },
                    enabled = !validating
                ) {
                    Text(text = stringResource(R.string.btn_cancel))
                }
            }
        )

        // 校验成功：关闭对话框（Snackbar 由 toastMessage 通路展示）
        LaunchedEffect(bloggerState) {
            if (bloggerState is SettingsViewModel.BloggerUiState.Done) {
                showBloggerDialog = false
                viewModel.dismissBloggerDialog()
            }
        }
    }
}

/** 统一组卡：ShapeLarge + SunriseSurface + 组标题（图标 20dp Sunrise700 + sectionTitleStyle） */
@Composable
private fun GroupCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = ShapeLarge,
        colors = CardDefaults.cardColors(containerColor = SunriseSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Sunrise700,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = title, style = SectionTitleStyle, color = Ink900)
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

/** 「关于」组条目行：图标 + 文案 + 尾部箭头，整行可点击 */
@Composable
private fun AboutRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Sunrise700,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Ink900,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = Ink60,
            modifier = Modifier.size(18.dp)
        )
    }
}

/** 同步结果徽章（与主页同构，DESIGN-V2 §2.1-5） */
@Composable
private fun SyncBadge(ok: Boolean) {
    val color = if (ok) Success else ErrorBadge
    Surface(shape = ShapeSmall, color = color.copy(alpha = 0.12f)) {
        Text(
            text = stringResource(if (ok) R.string.badge_sync_ok else R.string.badge_sync_fail),
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.W600),
            color = color,
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}
