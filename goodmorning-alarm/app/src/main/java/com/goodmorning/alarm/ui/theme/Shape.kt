package com.goodmorning.alarm.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * 形状 token（DESIGN-V2 §1.3）：
 * 大圆角是「Expressive 感」的核心手段之一，全 App 统一从本文件取圆角。
 */

/** 卡片容器 */
val ShapeLarge = RoundedCornerShape(24.dp)

/** 按钮、警示卡、毛玻璃卡 */
val ShapeMedium = RoundedCornerShape(16.dp)

/** 徽章、Chip 容器内嵌 */
val ShapeSmall = RoundedCornerShape(10.dp)

/** SlideToStop 轨道/滑块、圆形播放键、胶囊 */
val ShapePill = RoundedCornerShape(percent = 50)
