package com.goodmorning.alarm.ui.guide

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.goodmorning.alarm.R
import com.goodmorning.alarm.ui.theme.Ink60
import com.goodmorning.alarm.ui.theme.Ink900
import com.goodmorning.alarm.ui.theme.ShapeLarge
import com.goodmorning.alarm.ui.theme.ShapeMedium
import com.goodmorning.alarm.ui.theme.Success
import com.goodmorning.alarm.ui.theme.SunriseSurface

/**
 * HyperOS 权限分步引导（P1-3）：通知 → 精确闹钟 → 自启动（MIUI 手动指引）→ 省电无限制
 * → 全屏显示通知（Android 14+，低版本隐藏）；
 * 每步附“为什么需要”说明；可跳过（顶部返回），可从设置页重新进入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionGuideScreen(
    onNavigateBack: () -> Unit,
    viewModel: PermissionGuideViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    // Android 14+ 才有全屏显示通知授权页；低版本为 null → 隐藏第 5 步
    val fullScreenIntent = viewModel.fullScreenIntentSettingsIntent()

    // 从系统设置页返回时自动刷新检测状态
    @Suppress("DEPRECATION")
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.guide_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.guide_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 步骤 1：通知权限
            GuideStepCard(
                title = stringResource(R.string.guide_step_notification),
                why = stringResource(R.string.guide_step_notification_why),
                status = if (state.notificationsGranted) {
                    StepStatus.DONE
                } else {
                    StepStatus.TODO
                },
                onAction = {
                    runCatching {
                        context.startActivity(viewModel.notificationSettingsIntent())
                    }
                }
            )

            // 步骤 2：精确闹钟
            GuideStepCard(
                title = stringResource(R.string.guide_step_exact_alarm),
                why = stringResource(R.string.guide_step_exact_alarm_why),
                status = if (state.exactAlarmGranted) {
                    StepStatus.DONE
                } else {
                    StepStatus.TODO
                },
                onAction = {
                    runCatching {
                        context.startActivity(viewModel.exactAlarmSettingsIntent())
                    }
                }
            )

            // 步骤 3：自启动（无法检测，需手动确认）
            GuideStepCard(
                title = stringResource(R.string.guide_step_autostart),
                why = stringResource(R.string.guide_step_autostart_why) +
                    if (!viewModel.hasDirectAutostartPage()) {
                        "\n" + stringResource(R.string.guide_jump_failed)
                    } else "",
                status = StepStatus.MANUAL,
                onAction = {
                    runCatching {
                        context.startActivity(viewModel.autostartIntent())
                    }
                }
            )

            // 步骤 4：省电策略无限制
            GuideStepCard(
                title = stringResource(R.string.guide_step_battery),
                why = stringResource(R.string.guide_step_battery_why),
                status = if (state.batteryUnrestricted) {
                    StepStatus.DONE
                } else {
                    StepStatus.TODO
                },
                onAction = {
                    runCatching {
                        context.startActivity(viewModel.batteryIntent())
                    }
                }
            )

            // 步骤 5：全屏显示通知（Android 14+；低版本无此权限页，隐藏）
            if (fullScreenIntent != null) {
                GuideStepCard(
                    title = stringResource(R.string.guide_step_full_screen),
                    why = stringResource(R.string.guide_step_full_screen_why),
                    status = if (state.fullScreenIntentGranted) {
                        StepStatus.DONE
                    } else {
                        StepStatus.TODO
                    },
                    onAction = {
                        runCatching {
                            context.startActivity(fullScreenIntent)
                        }
                    }
                )
            }

            Text(
                text = stringResource(R.string.guide_skip_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    viewModel.refresh()
                    onNavigateBack()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(R.string.guide_btn_finish))
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/** 步骤完成状态 */
private enum class StepStatus { DONE, TODO, MANUAL }

@Composable
private fun GuideStepCard(
    title: String,
    why: String,
    status: StepStatus,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = ShapeLarge,
        colors = CardDefaults.cardColors(
            containerColor = SunriseSurface
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = when (status) {
                        StepStatus.DONE -> Icons.Filled.CheckCircle
                        else -> Icons.Filled.RadioButtonUnchecked
                    },
                    contentDescription = null,
                    tint = when (status) {
                        StepStatus.DONE -> Success
                        else -> Ink60
                    },
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Ink900,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = when (status) {
                        StepStatus.DONE -> stringResource(R.string.guide_status_done)
                        StepStatus.MANUAL -> stringResource(R.string.guide_status_manual)
                        StepStatus.TODO -> stringResource(R.string.guide_status_todo)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = when (status) {
                        StepStatus.DONE -> Success
                        else -> Ink60
                    }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = why,
                style = MaterialTheme.typography.bodyMedium,
                color = Ink60
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = onAction, shape = ShapeMedium) {
                Text(text = stringResource(R.string.guide_btn_go_settings))
            }
        }
    }
}
