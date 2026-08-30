package com.goodmorning.alarm.ui.main

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.goodmorning.alarm.R
import com.goodmorning.alarm.ui.theme.AlarmTimeStyle
import com.goodmorning.alarm.ui.theme.CountdownStyle
import com.goodmorning.alarm.ui.theme.Dawn80
import com.goodmorning.alarm.ui.theme.ErrorBadge
import com.goodmorning.alarm.ui.theme.Ink60
import com.goodmorning.alarm.ui.theme.Ink900
import com.goodmorning.alarm.ui.theme.Motion
import com.goodmorning.alarm.ui.theme.ShapeLarge
import com.goodmorning.alarm.ui.theme.ShapeMedium
import com.goodmorning.alarm.ui.theme.ShapeSmall
import com.goodmorning.alarm.ui.theme.Success
import com.goodmorning.alarm.ui.theme.Sunrise100
import com.goodmorning.alarm.ui.theme.Sunrise500
import com.goodmorning.alarm.ui.theme.Sunrise50
import com.goodmorning.alarm.ui.theme.Sunrise700
import com.goodmorning.alarm.ui.theme.SunriseSurface
import com.goodmorning.alarm.ui.theme.WarnContainer
import com.goodmorning.alarm.ui.theme.OnWarnContainer
import com.goodmorning.alarm.util.Permissions
import com.goodmorning.alarm.util.TimeUtils
import java.util.Locale

/**
 * 主页（DESIGN-V2 §2.1 清晨场景）：
 * 弱化 TopAppBar + 权限内联警示卡 + 日出进度环（倒计时）+ 闹钟时间行 +
 * 状态卡片区（博主/同步/缓存）+ 底部入口行。
 * 业务逻辑（闹钟注册/贪睡/权限检查）全部沿用 V1。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToGuide: () -> Unit,
    viewModel: MainViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showTimePicker by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // F5：onResume 刷新权限状态（从系统设置页返回后立即生效，无需等下一秒 ticker）
    @Suppress("DEPRECATION")
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissionState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // 弱化样式：背景透明；权限引导 IconButton 移除（入口合并进警示卡与设置页）
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.btn_settings),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // 权限内联警示卡（仅当有缺失权限时出现）
            PermissionWarnCard(
                uiState = uiState,
                onOpenSettings = { intent ->
                    runCatching { context.startActivity(intent) }
                        .onFailure { /* 系统无对应页面时静默忽略，引导页有兜底文案 */ }
                }
            )

            // 日出进度环（页面视觉中心）
            SunriseRingSection(uiState)

            // 闹钟时间行（轻量化）
            AlarmTimeRow(
                enabled = uiState.settings.alarmEnabled,
                hour = uiState.settings.alarmHour,
                minute = uiState.settings.alarmMinute,
                onToggle = viewModel::setAlarmEnabled,
                onEditTime = { showTimePicker = true }
            )

            // 状态卡片区（当前博主 / 同步状态 / 缓存条数）
            StatusCard(uiState)

            // 底部入口行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onNavigateToGuide,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = ShapeMedium
                ) {
                    Text(text = stringResource(R.string.btn_permission_guide))
                }
                Button(
                    onClick = onNavigateToSettings,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = ShapeMedium,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Sunrise700,
                        contentColor = Sunrise50
                    )
                ) {
                    Text(text = stringResource(R.string.btn_settings))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // 时间选择对话框：表盘 ⇄ 键入双模式（键入直达，表盘直观）
    if (showTimePicker) {
        var inputMode by remember { mutableStateOf(false) }
        val timePickerState = rememberTimePickerState(
            initialHour = uiState.settings.alarmHour,
            initialMinute = uiState.settings.alarmMinute,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.alarm_time_label),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { inputMode = !inputMode }) {
                        Icon(
                            imageVector = if (inputMode) Icons.Filled.EditCalendar else Icons.Filled.Keyboard,
                            contentDescription = stringResource(
                                if (inputMode) R.string.time_picker_to_dial else R.string.time_picker_to_input
                            )
                        )
                    }
                }
            },
            text = {
                if (inputMode) {
                    TimeInput(state = timePickerState)
                } else {
                    TimePicker(state = timePickerState)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.setAlarmTime(timePickerState.hour, timePickerState.minute)
                        showTimePicker = false
                    }
                ) {
                    Text(text = stringResource(R.string.btn_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text(text = stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}

/**
 * 权限内联警示卡（替换旧 PermissionBanner，DESIGN-V2 §2.1-2）：
 * 每项一行 = Warning 图标 + 文案 + 「去开启」；出现/消失带竖向展开 + 淡入动效。
 */
@Composable
private fun PermissionWarnCard(
    uiState: MainViewModel.MainUiState,
    onOpenSettings: (Intent) -> Unit
) {
    val context = LocalContext.current
    val reminders: List<Pair<String, Intent>> = buildList {
        if (!uiState.notificationsGranted) {
            add(
                stringResource(R.string.main_perm_banner_notification) to
                    Permissions.notificationSettingsIntent(context)
            )
        }
        if (!uiState.exactAlarmGranted) {
            add(
                stringResource(R.string.main_perm_banner_exact_alarm) to
                    Permissions.exactAlarmSettingsIntent(context)
            )
        }
    }

    AnimatedVisibility(
        visible = reminders.isNotEmpty(),
        enter = expandVertically() + fadeIn(animationSpec = Motion.tweenFade),
        exit = shrinkVertically() + fadeOut(animationSpec = Motion.tweenFade)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = ShapeMedium,
            colors = CardDefaults.cardColors(containerColor = WarnContainer),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                reminders.forEach { (message, intent) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = OnWarnContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = OnWarnContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { onOpenSettings(intent) },
                            colors = ButtonDefaults.textButtonColors(contentColor = OnWarnContainer)
                        ) {
                            Text(text = stringResource(R.string.main_perm_go_open))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 日出进度环（DESIGN-V2 §2.1-3）：
 * 直径 240dp；轨道 Sunrise100；进度弧 sweepGradient(Sunrise500→Sunrise700)，起始 -90°；
 * progress = 1 - remain/24h；springSnap 动画；闹钟关闭归零；贪睡中动画暂停并显示「稍后 HH:mm」。
 */
@Composable
private fun SunriseRingSection(uiState: MainViewModel.MainUiState) {
    val snoozing = uiState.snoozeUntilMillis != null
    val enabled = uiState.settings.alarmEnabled

    // 贪睡中环动画暂停：记住贪睡发生前进度值
    var frozenProgress by remember { mutableStateOf(0f) }
    val targetProgress: Float = when {
        snoozing -> frozenProgress
        enabled -> (1f - uiState.remainMillis / 86_400_000f).coerceIn(0f, 1f)
        else -> 0f
    }
    LaunchedEffect(targetProgress, snoozing) {
        if (!snoozing) frozenProgress = targetProgress
    }

    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = Motion.springSnap,
        label = "sunriseProgress"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(modifier = Modifier.size(240.dp), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = 12.dp.toPx()
                val inset = stroke / 2f
                val arcSize = Size(size.minDimension - stroke, size.minDimension - stroke)
                val topLeft = Offset(inset, inset)
                // 轨道
                drawArc(
                    color = Sunrise100,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                // 进度弧：随 -90° 旋转从顶部起顺时针推进（渐变随画布一起旋转）
                rotate(-90f) {
                    drawArc(
                        brush = Brush.sweepGradient(listOf(Sunrise500, Sunrise700)),
                        startAngle = 0f,
                        sweepAngle = 360f * animatedProgress,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                }
            }
            // 环中央内容
            Column(
                modifier = Modifier.padding(40.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when {
                    // 贪睡进行中：环动画暂停，中央改显「稍后 HH:mm」
                    snoozing -> Text(
                        text = stringResource(
                            R.string.main_snooze_center_fmt,
                            TimeUtils.formatHm(uiState.snoozeUntilMillis ?: 0L)
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = Sunrise500,
                        textAlign = TextAlign.Center
                    )
                    // 闹钟开启：倒计时（tabular-nums 防跳动）
                    enabled -> {
                        Text(
                            text = stringResource(R.string.main_label_next_ring),
                            style = MaterialTheme.typography.labelLarge,
                            color = Ink60
                        )
                        Text(
                            text = TimeUtils.formatCountdown(uiState.remainMillis),
                            style = CountdownStyle,
                            color = Ink900,
                            textAlign = TextAlign.Center
                        )
                    }
                    // 闹钟关闭
                    else -> Text(
                        text = stringResource(R.string.main_alarm_off_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink60,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * 闹钟时间行（DESIGN-V2 §2.1-4）：图标 + HH:mm + Switch；
 * 点击时间/图标弹 TimePicker（逻辑不变）；关闭时时间文字降为 Ink60。
 */
@Composable
private fun AlarmTimeRow(
    enabled: Boolean,
    hour: Int,
    minute: Int,
    onToggle: (Boolean) -> Unit,
    onEditTime: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(onClick = onEditTime)
        ) {
            Icon(
                imageVector = Icons.Filled.AccessTime,
                contentDescription = stringResource(R.string.alarm_time_label),
                tint = if (enabled) Sunrise700 else Ink60,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = String.format(Locale.getDefault(), "%02d:%02d", hour, minute),
                style = AlarmTimeStyle,
                color = if (enabled) Sunrise700 else Ink60
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

/**
 * 状态卡片区（DESIGN-V2 §2.1-5）：单卡三行 —— 当前博主 / 同步状态徽章 / 缓存条数。
 */
@Composable
private fun StatusCard(uiState: MainViewModel.MainUiState) {
    val settings = uiState.settings
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = ShapeLarge,
        colors = CardDefaults.cardColors(containerColor = SunriseSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 行① 当前博主
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    tint = Dawn80,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.main_blogger_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = Ink60
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = settings.bloggerName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.W600,
                    color = Ink900
                )
            }
            // 行② 同步状态
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
                if (settings.lastSyncAt.isNotEmpty()) {
                    SyncBadge(ok = settings.lastSyncOk)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = settings.lastSyncAt,
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
            // 行③ 缓存条数
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.VideoLibrary,
                    contentDescription = null,
                    tint = Dawn80,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.main_cache_count_fmt, uiState.cacheCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink900
                )
            }
        }
    }
}

/** 同步结果徽章：Surface(ShapeSmall) 内 12sp 文字，成功 Success / 失败 ErrorBadge（暖调红） */
@Composable
private fun SyncBadge(ok: Boolean) {
    val color = if (ok) Success else ErrorBadge
    Surface(shape = ShapeSmall, color = color.copy(alpha = 0.12f)) {
        Text(
            text = stringResource(if (ok) R.string.badge_sync_ok else R.string.badge_sync_fail),
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.W600),
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}
