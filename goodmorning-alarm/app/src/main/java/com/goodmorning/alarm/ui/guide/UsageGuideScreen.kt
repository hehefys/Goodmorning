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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goodmorning.alarm.R
import com.goodmorning.alarm.ui.theme.Dawn40
import com.goodmorning.alarm.ui.theme.Ink60
import com.goodmorning.alarm.ui.theme.Ink900
import com.goodmorning.alarm.ui.theme.SectionTitleStyle
import com.goodmorning.alarm.ui.theme.ShapeLarge
import com.goodmorning.alarm.ui.theme.ShapePill
import com.goodmorning.alarm.ui.theme.Sunrise100
import com.goodmorning.alarm.ui.theme.Sunrise700
import com.goodmorning.alarm.ui.theme.SunriseSurface

/**
 * 使用说明页（DESIGN-V2 §2.4，V2 功能 B）：
 * 纯 Compose 渲染的 5 章节滚动阅读页 —— 快速上手 / 权限说明 / 同步与数据源 /
 * 响铃页操作 / 常见问题。无状态、无网络，全部文案来自 strings.xml。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageGuideScreen(
    onNavigateBack: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.usage_title)) },
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
            Spacer(modifier = Modifier.height(4.dp))

            // ① 快速上手（3 步）
            ChapterCard(
                icon = Icons.Filled.RocketLaunch,
                title = stringResource(R.string.usage_s1_title)
            ) {
                StepRow(1, stringResource(R.string.usage_s1_step1))
                StepRow(2, stringResource(R.string.usage_s1_step2))
                StepRow(3, stringResource(R.string.usage_s1_step3))
            }

            // ② 权限说明（5 项）
            ChapterCard(
                icon = Icons.Filled.VerifiedUser,
                title = stringResource(R.string.usage_s2_title)
            ) {
                Paragraph(stringResource(R.string.usage_s2_p1))
                Paragraph(stringResource(R.string.usage_s2_p2))
                Paragraph(stringResource(R.string.usage_s2_p3))
                Paragraph(stringResource(R.string.usage_s2_p4))
                Paragraph(stringResource(R.string.usage_s2_p5))
            }

            // ③ 同步与数据源
            ChapterCard(
                icon = Icons.Filled.CloudSync,
                title = stringResource(R.string.usage_s3_title)
            ) {
                Paragraph(stringResource(R.string.usage_s3_p1))
                Paragraph(stringResource(R.string.usage_s3_p2))
            }

            // ④ 响铃页操作
            ChapterCard(
                icon = Icons.AutoMirrored.Filled.MenuBook,
                title = stringResource(R.string.usage_s4_title)
            ) {
                Paragraph(stringResource(R.string.usage_s4_p1))
            }

            // ⑤ 常见问题（Q/A 形态）
            ChapterCard(
                icon = Icons.AutoMirrored.Filled.HelpOutline,
                title = stringResource(R.string.usage_s5_title)
            ) {
                FaqRow(
                    question = stringResource(R.string.usage_s5_q1),
                    answer = null
                )
                FaqRow(
                    question = stringResource(R.string.usage_s5_q2),
                    answer = null
                )
                FaqRow(
                    question = stringResource(R.string.usage_s5_q3),
                    answer = null
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/** 章节卡：ShapeLarge + SunriseSurface，标题行 = 24dp 图标（Sunrise700）+ sectionTitleStyle */
@Composable
private fun ChapterCard(
    icon: ImageVector,
    title: String,
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
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = title, style = SectionTitleStyle, color = Ink900)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                content()
            }
        }
    }
}

/** 步骤条目：序号小圆（Sunrise100 底 + Dawn40 字）+ 文字 */
@Composable
private fun StepRow(index: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(
            modifier = Modifier.size(20.dp),
            shape = ShapePill,
            color = Sunrise100
        ) {
            Text(
                text = index.toString(),
                style = TextStyle(fontSize = 12.sp),
                fontWeight = FontWeight.W600,
                color = Dawn40,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = Ink900
        )
    }
}

/** 普通段落条目 */
@Composable
private fun Paragraph(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = Ink900
    )
}

/** FAQ 条目：问题（W600）+ 答案（Ink60）；Q1 自带答案故 answer 可空 */
@Composable
private fun FaqRow(question: String, answer: String?) {
    Column {
        Text(
            text = question,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.W600,
            color = Ink900
        )
        if (!answer.isNullOrEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = answer,
                style = MaterialTheme.typography.bodyMedium,
                color = Ink60
            )
        }
    }
}
