package com.goodmorning.alarm.ui.ringing

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.goodmorning.alarm.R
import com.goodmorning.alarm.alarm.SelectionPolicy
import com.goodmorning.alarm.playback.AlarmService
import com.goodmorning.alarm.ui.theme.AppTheme
import com.goodmorning.alarm.ui.theme.DawnAccent
import com.goodmorning.alarm.ui.theme.DouyinBlue
import com.goodmorning.alarm.ui.theme.FrostWhite
import com.goodmorning.alarm.ui.theme.GlowAmber
import com.goodmorning.alarm.ui.theme.GlowAmberSoft
import com.goodmorning.alarm.ui.theme.MoonFrost
import com.goodmorning.alarm.ui.theme.MoonMist
import com.goodmorning.alarm.ui.theme.Motion
import com.goodmorning.alarm.ui.theme.NightSkyBottom
import com.goodmorning.alarm.ui.theme.NightSkyTop
import com.goodmorning.alarm.ui.theme.RingClockStyle
import com.goodmorning.alarm.ui.theme.ShapeMedium
import com.goodmorning.alarm.ui.theme.ShapePill
import com.goodmorning.alarm.ui.theme.Sunrise700
import com.goodmorning.alarm.util.TimeUtils
import kotlinx.coroutines.delay

/**
 * 锁屏全屏响铃页（P0-6，DESIGN-V2 §2.2 黎明场景重设计）：
 * 夜空竖直渐变背景 + 日出光晕（随音量渐强 0.10→0.40 alpha 增亮）+ 大字时钟 +
 * 标题毛玻璃卡 + SlideToStop 滑动停止 + 稍后提醒/打开抖音。
 *
 * 行为约束（不变项）：返回键不逃逸、3 秒最小展示、showWhenLocked/turnScreenOn/KEEP_SCREEN_ON。
 */
class RingingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 锁屏之上展示并点亮屏幕
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        // 响铃期间保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            AppTheme(darkTheme = true) {
                RingingScreen(
                    onFinished = { finish() }
                )
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 响铃页不允许返回键逃逸（必须滑动停止或贪睡）
    }
}

@Composable
private fun RingingScreen(
    viewModel: RingingViewModel = viewModel(),
    onFinished: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    // F3 ②：启动 3 秒后状态仍为 null（服务协程未就绪/服务被拦截）时显示“闹钟加载中”占位
    var showLoading by remember { mutableStateOf(false) }

    // F3 ①：最小展示时长保护 —— 页面启动 3 秒内即使状态为 null 也绝不关闭，
    // 防止服务协程未就绪 / 短促结束（如坏文件立即 STATE_ENDED）时页面被误关（“闪一下回桌面”）。
    // 仅当“先见过非 null 状态、随后变 null”（服务真正停止）且已过 3 秒才关闭。
    LaunchedEffect(Unit) {
        val minDisplayMillis = 3_000L
        val startElapsed = SystemClock.elapsedRealtime()
        var seenRinging = false
        viewModel.state.collect { current ->
            if (current != null) {
                seenRinging = true
                showLoading = false
            } else if (seenRinging &&
                SystemClock.elapsedRealtime() - startElapsed >= minDisplayMillis
            ) {
                onFinished()
            }
        }
    }

    // F3 ②：启动 3 秒后仍未就绪 → 进入“闹钟加载中”占位（页面保持打开，非空白）
    LaunchedEffect(Unit) {
        delay(3_000L)
        if (viewModel.state.value == null) {
            showLoading = true
        }
    }

    // 日出光晕：0.10 + 0.30 × volumeProgress；状态未接通时以 0.4f 占位（DESIGN-V2 §2.2）
    val volumeProgress = state?.volumeProgress ?: 0.4f
    val glowAnim = remember { Animatable(0.10f) }
    LaunchedEffect(volumeProgress) {
        glowAnim.animateTo(0.10f + 0.30f * volumeProgress, Motion.glowSpec)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ---- 背景层 ①：夜空竖直渐变 ----
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(NightSkyTop, NightSkyBottom)))
        )
        // ---- 背景层 ②：底部日出光晕（随音量渐强增亮） ----
        Canvas(modifier = Modifier.fillMaxSize()) {
            val glow = glowAnim.value
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        GlowAmber.copy(alpha = glow),
                        GlowAmberSoft.copy(alpha = glow * 0.5f),
                        Color.Transparent
                    ),
                    center = Offset(size.width / 2f, size.height * 1.12f),
                    radius = size.width * 0.9f
                )
            )
        }

        // ---- 前景层 ----
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 顶部：来源标签胶囊 + 未更新/兜底提示条
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SourcePill(state?.source)
                when (state?.source) {
                    SelectionPolicy.Source.CACHED -> NotUpdatedTip()
                    SelectionPolicy.Source.FALLBACK -> FallbackTip()
                    else -> Unit
                }
            }

            // 中部：大字时钟 + 标题毛玻璃卡 / 加载占位
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ClockText()
                Spacer(modifier = Modifier.height(24.dp))
                if (state == null && showLoading) {
                    // F3 ②：服务状态长期未就绪 → 显示加载占位而非空白，页面保持打开
                    Text(
                        text = stringResource(R.string.ringing_loading),
                        style = MaterialTheme.typography.titleMedium,
                        color = MoonMist,
                        textAlign = TextAlign.Center
                    )
                } else {
                    val info = state
                    if (info != null && info.source != SelectionPolicy.Source.FALLBACK) {
                        FrostedCard {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = info.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MoonFrost,
                                    textAlign = TextAlign.Center
                                )
                                if (info.publishDate.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = stringResource(
                                            R.string.ringing_publish_date_fmt, info.publishDate
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MoonMist,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 底部拇指区：播放/暂停圆键 → SlideToStop → 稍后/打开抖音
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // 播放/暂停圆形键（72dp，滑条上方）
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .background(Sunrise700, ShapePill)
                        .clickable { viewModel.togglePlayPause() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (state?.isPlaying == true) {
                            Icons.Filled.Pause
                        } else {
                            Icons.Filled.PlayArrow
                        },
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }
                // 滑动停止（≥80% 松手触发，否则回弹）
                SlideToStop(onStopped = { viewModel.stop() })
                // 稍后提醒 + 打开抖音
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(
                        onClick = { viewModel.snooze() },
                        modifier = Modifier.height(48.dp),
                        shape = ShapePill,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = FrostWhite.copy(alpha = 0.12f),
                            contentColor = MoonFrost
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Snooze,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(text = stringResource(R.string.ringing_btn_snooze))
                    }
                    OutlinedButton(
                        onClick = { viewModel.openDouyin() },
                        modifier = Modifier.height(48.dp),
                        shape = ShapePill,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = DouyinBlue
                        ),
                        border = BorderStroke(1.dp, DouyinBlue)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(text = stringResource(R.string.ringing_open_douyin))
                    }
                }
            }
        }
    }
}

/** 毛玻璃标题卡：FrostWhite 8% 基底 + 14% 描边 */
@Composable
private fun FrostedCard(content: @Composable () -> Unit) {
    Surface(
        shape = ShapeMedium,
        color = FrostWhite.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, FrostWhite.copy(alpha = 0.14f))
    ) {
        Box(modifier = Modifier.padding(20.dp)) {
            content()
        }
    }
}

/** 来源标签（TODAY/CACHED/FALLBACK）：毛玻璃胶囊内 DawnAccent 小字 */
@Composable
private fun SourcePill(source: SelectionPolicy.Source?) {
    val label = when (source) {
        SelectionPolicy.Source.TODAY -> stringResource(R.string.ringing_today_source)
        SelectionPolicy.Source.CACHED -> stringResource(R.string.ringing_cached_source)
        SelectionPolicy.Source.FALLBACK -> stringResource(R.string.ringing_fallback_source)
        null -> ""
    }
    if (label.isEmpty()) return
    Surface(
        shape = ShapePill,
        color = FrostWhite.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, FrostWhite.copy(alpha = 0.14f))
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = DawnAccent,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

/** 大字时钟（每秒刷新，tabular-nums 防跳动） */
@Composable
private fun ClockText() {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    Text(
        text = TimeUtils.formatHm(now),
        style = RingClockStyle,
        color = MoonFrost
    )
}

/** 「今日尚未更新，为你播放最近一期」提示条（P0-7） */
@Composable
private fun NotUpdatedTip() {
    TipBanner(text = stringResource(R.string.ringing_not_updated_tip))
}

/** 兜底铃声提示条 */
@Composable
private fun FallbackTip() {
    TipBanner(text = stringResource(R.string.ringing_fallback_tip))
}

@Composable
private fun TipBanner(text: String) {
    Card(
        shape = ShapeMedium,
        colors = CardDefaults.cardColors(
            containerColor = GlowAmberSoft.copy(alpha = 0.14f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFFFD699),
            textAlign = TextAlign.Center
        )
    }
}
