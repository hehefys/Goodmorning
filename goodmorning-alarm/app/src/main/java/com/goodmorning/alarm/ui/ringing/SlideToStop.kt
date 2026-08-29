package com.goodmorning.alarm.ui.ringing

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.goodmorning.alarm.R
import com.goodmorning.alarm.ui.theme.FrostWhite
import com.goodmorning.alarm.ui.theme.MoonFrost
import com.goodmorning.alarm.ui.theme.Motion
import com.goodmorning.alarm.ui.theme.ShapePill
import com.goodmorning.alarm.ui.theme.Sunrise700
import kotlin.math.roundToInt

/**
 * SlideToStop 滑动停止条（DESIGN-V2 §3.3，V2 响铃页专用）。
 *
 * 规格：轨道 64dp 高毛玻璃胶囊；56dp 橙色圆滑块；
 * 拖动进度 ≥ 80% 松手触发停止（震动 + 一次触发保护），否则弹簧回弹；
 * 轨道文字 alpha 随拖动进度淡出；无障碍提供「停止响铃」自定义操作。
 * 全部基于稳定 API（pointerInput detectHorizontalDragGestures + animateFloatAsState）。
 */
@Composable
fun SlideToStop(
    modifier: Modifier = Modifier,
    onStopped: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val stopLabel = stringResource(R.string.ringing_slide_to_stop)

    var trackWidthPx by remember { mutableFloatStateOf(0f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    // 防重复触发：一次拖动手势内只允许触发一次
    var consumed by remember { mutableStateOf(false) }

    val sliderPx = with(density) { 56.dp.toPx() }
    val paddingPx = with(density) { 8.dp.toPx() }
    val maxOffset = (trackWidthPx - sliderPx - paddingPx).coerceAtLeast(0f)

    // 松手后回弹到 0（springPosition 带轻微回弹）；拖动中直接跟手
    val backOffset by animateFloatAsState(
        targetValue = if (isDragging) offsetX else 0f,
        animationSpec = Motion.springPosition,
        label = "slideToStopBack"
    )
    val displayOffset = if (isDragging) offsetX else backOffset
    val displayFraction = if (maxOffset > 0f) {
        (displayOffset / maxOffset).coerceIn(0f, 1f)
    } else 0f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .onSizeChanged { trackWidthPx = it.width.toFloat() }
            .background(FrostWhite.copy(alpha = 0.08f), ShapePill)
            .border(1.dp, FrostWhite.copy(alpha = 0.14f), ShapePill)
            .pointerInput(maxOffset) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        consumed = false
                        isDragging = true
                    },
                    onDragEnd = {
                        isDragging = false
                        val fraction = if (maxOffset > 0f) {
                            (offsetX / maxOffset).coerceIn(0f, 1f)
                        } else 0f
                        if (!consumed && fraction >= 0.8f) {
                            consumed = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onStopped()
                        }
                        // 无论触发与否，位移归零（触发后立即归 0 防重复触发）
                        offsetX = 0f
                    },
                    onDragCancel = {
                        isDragging = false
                        offsetX = 0f
                    }
                ) { change, dragAmount ->
                    change.consume()
                    offsetX = (offsetX + dragAmount).coerceIn(0f, maxOffset)
                }
            }
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(stopLabel) {
                        onStopped()
                        true
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // 轨道文字：随拖动进度淡出（拖满约 70% 完全消失）
        Text(
            text = stopLabel,
            style = MaterialTheme.typography.titleMedium,
            color = MoonFrost.copy(alpha = (1f - displayFraction * 1.4f).coerceIn(0f, 1f))
        )
        // 滑块：56dp 圆，橙色，中心箭头 → 达到触发阈值变 Stop
        Box(
            modifier = Modifier
                .offset { IntOffset(displayOffset.roundToInt(), 0) }
                .size(56.dp)
                .background(Sunrise700, ShapePill),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (displayFraction >= 0.8f) {
                    Icons.Filled.Stop
                } else {
                    Icons.AutoMirrored.Filled.KeyboardArrowRight
                },
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
