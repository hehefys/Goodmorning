# 「每日早安」V2 设计规格书 —— Material 3 Expressive「晨昏」主题

> 版本：V2.0（设计稿）
> 适用工程：`goodmorning-alarm`（Kotlin + Jetpack Compose + Material3，BOM **2024.09.03**）
> 读者：实现工程师。本文按"照做即可"粒度书写；所有色值、尺寸、动效参数均已定稿。
>
> **硬性约束**
> - 只使用 Compose 稳定版 API（BOM 2024.09.03 + material3 随 BOM 版本），**不升级任何依赖**。
>   - 因此不使用 `MaterialExpressiveTheme` / `MotionScheme` / `RoundedPolygon` 等 Expressive 专用 API（均未进稳定版）；
>   - "Expressive 感"通过：**spring 动效 token + 大圆角 + 渐变/光晕 + 大号等宽数字** 实现，全部基于 `androidx.compose.animation.*` 稳定 API。
> - 数据链路（选片规则、四级兜底、音量渐强算法、贪睡逻辑）**不动**，只改 UI 与新增博主维度字段。
> - 全部新增中文文案进 `strings.xml`（§4 给出 key 清单与文案定稿）。

---

## 1. 设计 Token

### 1.1 色彩

文件：`ui/theme/Color.kt`。在**保留现有 Sunrise/Night 命名**的基础上新增/调整下列色值（旧值删除处已标注）。

#### 浅色（清晨场景，主页/设置页/说明页/引导页）

| Token 名 | 色值 | 用途 |
|---|---|---|
| `Sunrise50` | `0xFFFFF8F0`（保留） | `background` / `surface`（暖奶油底） |
| `Sunrise100` | `0xFFFFECDC`（保留） | `surfaceVariant`、进度环轨道、卡片容器 |
| `Sunrise300` | `0xFFFFC28A`（保留） | 次级强调 |
| `Sunrise500` | `0xFFFB8C00`（保留） | `secondary` |
| `Sunrise700` | `0xFFE65100`（保留） | `primary`（日出橙，主按钮/进度环渐变端点） |
| `Dawn40` | `0xFF7A4B00`（保留） | `onPrimaryContainer` |
| `Dawn80` | `0xFFFFB95C`（保留） | 高亮琥珀（状态徽章/图标着色） |
| **新增** `SunriseSurface` | `0xFFFFFDF8` | 卡片 surface（比背景略亮一档，制造层次） |
| **新增** `Ink900` | `0xFF2B2115` | `onSurface`（比旧 Night900 `0xFF1C1B18` 更暖的黑，正文） |
| **新增** `Ink60` | `0xFF6E6257` | `onSurfaceVariant`（次要文字） |
| **新增** `WarnContainer` | `0xFFFFE5DC` | 权限警示卡容器（替代旧红 `0xFFB71C1C.copy(alpha=0.10f)`） |
| **新增** `OnWarnContainer` | `0xFF8C2B00` | 权限警示卡文字/图标 |
| **新增** `Success` | `0xFF2E7D32` | 同步成功徽章 |
| **新增** `ErrorBadge` | `0xFFB3402E` | 同步失败徽章（暖调红，非纯红） |

#### 深色（黎明场景，仅响铃页使用，强制 dark = true）

| Token 名 | 色值 | 用途 |
|---|---|---|
| **新增** `NightSkyTop` | `0xFF0D1B2A` | 响铃页背景渐变顶部（深蓝夜空） |
| **新增** `NightSkyBottom` | `0xFF1B263B` | 响铃页背景渐变底部 |
| **新增** `GlowAmber` | `0xFFFF8C42` | 底部日出光晕核心色 |
| **新增** `GlowAmberSoft` | `0xFFFFB95C` | 光晕外圈色 |
| **新增** `FrostWhite` | `0xFFFFFFFF` | 毛玻璃卡片基底（以 alpha 使用，见 1.4/2.2） |
| **新增** `MoonFrost` | `0xFFF2F5FA` | 深色 `onSurface`（冷白，区别于旧暖白） |
| **新增** `MoonMist` | `0xFFA9B4C2` | 深色次要文字 |
| **新增** `DawnAccent` | `0xFFFFB95C` | 响铃页强调（来源标签/渐变图标） |
| **新增** `DouyinBlue` | `0xFF7FB5E8` | 「打开抖音」按钮（替代旧 `0xFF64B5F6`，降饱和融入夜空） |
| **删除** | 旧响铃页硬编码 `0xFF1C1B18`、`0xFFF0E6D8`、`0xFFB8ADA0`、`0xFF3A3833` | 全部由上表 token 替代 |

#### MaterialTheme 色板映射（`Theme.kt`）

```kotlin
LightColors = lightColorScheme(
    primary = Sunrise700, onPrimary = Sunrise50,
    primaryContainer = Sunrise100, onPrimaryContainer = Dawn40,
    secondary = Sunrise500, onSecondary = Sunrise50,
    background = Sunrise50, onBackground = Ink900,
    surface = SunriseSurface, onSurface = Ink900,
    surfaceVariant = Sunrise100, onSurfaceVariant = Ink60,
    error = ErrorBadge, errorContainer = WarnContainer, onErrorContainer = OnWarnContainer,
)
DarkColors = darkColorScheme(   // 仅供响铃页
    primary = DawnAccent, onPrimary = NightSkyTop,
    background = NightSkyBottom, onBackground = MoonFrost,
    surface = NightSkyBottom, onSurface = MoonFrost,
    onSurfaceVariant = MoonMist,
)
```

`AppTheme` 保持现有签名；`RingingActivity` 继续传 `darkTheme = true`。

### 1.2 字体（`ui/theme/Type.kt`）

在默认 `Typography()` 上覆写：

| Token | 定义 | 用途 |
|---|---|---|
| `countdownStyle` | `displayMedium` 基础 + `fontSize = 56.sp`、`fontWeight = FontWeight.W600`、`fontFeatureSettings = "tnum"` | 主页倒计时（进度环内），**必须 tabular-nums 防跳动** |
| `ringClockStyle` | `displayLarge` 基础 + `fontSize = 76.sp`、`fontWeight = FontWeight.W600`、`fontFeatureSettings = "tnum"` | 响铃页大字时钟 |
| `alarmTimeStyle` | `headlineMedium` + `fontFeatureSettings = "tnum"` | 主页闹钟时间 HH:mm |
| `sectionTitleStyle` | `titleMedium` + `fontWeight = FontWeight.W600` | 卡片组标题 |
| 其余 | `bodyMedium = 14.sp` / `labelLarge = 14.sp, W600` / `titleLarge = 22.sp` 按默认 | — |

> `fontFeatureSettings` 是 `TextStyle` 稳定参数，无需实验 API。

### 1.3 形状（新增 `ui/theme/Shape.kt`，加入 `MaterialTheme(shapes = …)`）

| Token | 圆角 | 用途 |
|---|---|---|
| `ShapeLarge` | `RoundedCornerShape(24.dp)` | 卡片（`Card` 容器统一改用） |
| `ShapeMedium` | `RoundedCornerShape(16.dp)` | 按钮、警示卡、毛玻璃卡 |
| `ShapeSmall` | `RoundedCornerShape(10.dp)` | 徽章、Chip 容器内嵌 |
| `ShapePill` | `RoundedCornerShape(percent = 50)` | SlideToStop 轨道/滑块、圆形播放键 |

### 1.4 动效（新增 `ui/theme/Motion.kt`，全部为稳定 API）

| Token | 定义 | 用途 |
|---|---|---|
| `Motion.springPosition` | `spring<Float>(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow /*400f*/)` | 位置/尺寸/滑块位移——**带轻微回弹** |
| `Motion.springSnap` | `spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy /*1f*/, stiffness = 800f)` | 进度环/开关等需要干脆到位的状态 |
| `Motion.tweenColor` | `tween<Color>(250, easing = FastOutSlowInEasing)` | 所有颜色变化（`animateColorAsState`） |
| `Motion.tweenFade` | `tween<Float>(200, easing = LinearOutSlowInEasing)` | 透明度淡入淡出（`animateFloatAsState`） |
| `Motion.glowSpec` | `Animatable(0f)` + `tween(400, easing = LinearEasing)` 步进跟随 | 响铃页日出光晕 alpha（见 2.2） |
| **页面转场**（`AppNavHost` 统一注入） | `enter = slideInHorizontally(tween(260, FastOutSlowInEasing)) { it / 8 } + fadeIn(tween(220))`；`exit = slideOutHorizontally(tween(220)) { -it / 12 } + fadeOut(tween(180))`；返回反向 | 前进从右轻推入 + 淡入；不要使用弹簧做转场（会来回晃） |

> Navigation Compose 2.8.2 的 `composable(enterTransition=…)` 参数为稳定 API，可直接用。

---

## 2. 逐屏规格

通用约定：页面左右安全边距 **20dp**；卡片间距 **16dp**；卡片内边距 **20dp**；卡片容器色 `SunriseSurface`，圆角 `ShapeLarge`，无阴影（elevation 0）。

### 2.1 主页 MainScreen（清晨场景）

自上而下（`Column` + `verticalScroll`，`Arrangement.spacedBy(16.dp)`）：

1. **TopAppBar（改弱化样式）**
   - 背景透明（`colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)`）；
   - 左：App 名 `titleLarge`；右：设置 IconButton（保留）。权限引导 IconButton **移除**（入口合并进警示卡与设置页）。

2. **权限内联警示卡**（替换旧 `PermissionBanner`，仅当有缺失权限时出现）
   - 组件：`Card(ShapeMedium, containerColor = WarnContainer)`；
   - 每项一行：`Row(20dp 内边距, 8dp 竖间距)` = 前置 `Icon(Icons.Filled.Warning, 20dp, tint = OnWarnContainer)` + 文案 `bodyMedium, OnWarnContainer, weight(1f)` + 「去开启」`TextButton(colors.contentColor = OnWarnContainer)`；
   - 出现/消失动效：`AnimatedVisibility(tweenFade + expandVertically())`。

3. **日出进度环（页面视觉中心）**
   - 组件：`Box(240.dp × 240.dp, 内容居中)`，内层 `Canvas` 画环 + 中央 `Column`。
   - 环参数：直径 240dp；轨道 `Stroke(width = 12.dp, cap = Round)`，颜色 `Sunrise100`；
   - 进度弧：`Brush.sweepGradient(listOf(Sunrise500, Sunrise700))`，`Stroke(12.dp, cap = Round)`，起始角 -90°（顶部）；
   - 进度值：`progress = 1f - remainMillis / 86_400_000f`（以两次响铃的自然间隔 24h 为周期），`coerceIn(0f, 1f)`；
   - 动画：`animateFloatAsState(targetValue = progress, animationSpec = Motion.springSnap)`；闹钟关闭时 progress 固定 0；
   - 环中央（`Column`，居中，`padding(40.dp)`）：
     - 倒计时 `countdownStyle, Ink900`（如 `07:32:15`，`TimeUtils.formatCountdown` 现有格式）；
     - 上方 label「距下次响铃」`labelLarge, Ink60`；
     - 贪睡中：中央改显「稍后 HH:mm」`titleMedium, Sunrise500`，环动画暂停。

4. **闹钟时间行**（替换旧时间卡片，轻量化）
   - `Row(居中)`：`Icon(AccessTime, 20dp, tint = Sunrise700)` + 8dp + 时间 `alarmTimeStyle, Sunrise700` + 12dp + `Switch`（点击时间文字或图标弹出原有 `TimePicker` AlertDialog，逻辑不变）；
   - 开关关闭时：时间文字颜色降为 `Ink60`，进度环中央显示「闹钟已关闭」`bodyMedium, Ink60`。

5. **状态卡片区**（`Card(ShapeLarge)`，`Column(padding 20.dp, spacedBy(12.dp))`，单卡三行）
   - 行 ① 当前博主：`Icon(Person, 18dp, Dawn80)` + 「当前博主」`labelLarge, Ink60` + 博主名 `bodyMedium W600, Ink900`（来自 `settings.bloggerName`）；
   - 行 ② 同步状态：`labelLarge, Ink60`「同步状态」+ 结果徽章 —— 徽章 = `Surface(ShapeSmall, color = Success.copy(alpha = 0.12f))` 内 `Text(12.sp, Success)`，成功显「成功」、失败显「失败」（`ErrorBadge` 同构）；右侧时间文本 `bodyMedium, Ink60`（`lastSyncAt`）；
   - 行 ③ 缓存条数：`Icon(VideoLibrary, 18dp, Dawn80)` + 「已缓存」`labelLarge, Ink60` + 「N 条视频」`bodyMedium, Ink900`（N = `uiState.cacheCount`，见 §5 MainViewModel 改动）。
   - 行间距由 `12.dp` 竖向 `Row` 组成，行内 `Row(verticalAlignment = CenterVertically, horizontalArrangement = SpaceBetween)`。

6. **底部入口行**
   - `Row(SpaceBetween)`：`OutlinedButton(onNavigateToGuide)`「权限引导」+ `Button(onNavigateToSettings)`「设置」，各 `weight(1f)`，高 48dp，`ShapeMedium`；主按钮 `containerColor = Sunrise700`。

### 2.2 响铃页 RingingActivity（黎明场景）

**背景层**（最外层 `Box(fillMaxSize)`）：

- 层 ① 竖直渐变：`Brush.verticalGradient(listOf(NightSkyTop, NightSkyBottom))`；
- 层 ② 日出光晕：`Canvas(fillMaxSize)` 画 `drawCircle(Brush.radialGradient(colors = listOf(GlowAmber.copy(alpha = glow), GlowAmberSoft.copy(alpha = glow * 0.5f), Color.Transparent), center = Offset(size.width / 2f, size.height * 1.12f), radius = size.width * 0.9f))`；
- `glow` 值：`0.10f + 0.30f × volumeProgress`，其中 `volumeProgress ∈ [0,1]` 为音量渐强进度；
  - 数据来源：`AlarmService.RingingState` **新增字段 `volumeProgress: Float = 0f`**（`AlarmPlayer` 渐强循环内每步 `FADE_STEP_MS` 更新共享 StateFlow；音量渐强关闭时常量 0.5f）；
  - UI 侧 `Animatable` 平滑跟随：`LaunchedEffect(volumeProgress) { glowAnim.animateTo(volumeProgress, Motion.glowSpec) }`，Canvas 读 `glowAnim.value`；
  - 兜底：若 `volumeProgress` 未接通（联调前），以常量 `0.4f` 先行占位，接口不变。

**前景层**（`Column(fillMaxSize, horizontalPadding 24.dp, verticalPadding 40.dp, SpaceBetween)`，自上而下）：

1. **顶部提示区**（保留现有语义）
   - 来源标签（TODAY/CACHED/FALLBACK）：`Text(labelLarge, DawnAccent)`，置于 `Surface(ShapePill, color = FrostWhite.copy(alpha = 0.08f), border = BorderStroke(1.dp, FrostWhite.copy(alpha = 0.14f)), padding 12/6)` 胶囊内；
   - 未更新/兜底提示条：`Card(ShapeMedium, containerColor = GlowAmberSoft.copy(alpha = 0.14f))`，文字 `bodyMedium, 0xFFFFD699`（保留现色值）。
2. **中部：大字时钟 + 标题毛玻璃卡**
   - 时钟：`ringClockStyle`，`color = MoonFrost`（保留每秒刷新 ticker）；
   - 下方 24dp：标题卡 `Surface(ShapeMedium, color = FrostWhite.copy(alpha = 0.08f), border 1.dp FrostWhite.copy(alpha = 0.14f))`，`padding(20.dp)`：视频标题 `titleLarge, MoonFrost, 居中` + 8dp + 发布日期 `bodyMedium, MoonMist`；
   - 加载占位态**保留**：3 秒未就绪 → 卡片位置显示 `Text("闹钟加载中…", titleMedium, MoonMist)`（key 沿用 `R.string.ringing_loading`）。
3. **底部拇指区**（`Column(spacedBy(16.dp))`，全部控件落在屏幕下 1/3）
   - **SlideToStop 滑条**（新组件 `ui/ringing/SlideToStop.kt`，规格见 §3.3）；
   - 16dp；
   - 按钮行 `Row(fillMaxWidth, SpaceEvenly)`：
     - 「稍后提醒」`Button(shape = ShapePill, containerColor = FrostWhite.copy(alpha = 0.12f), contentColor = MoonFrost)`，前置 `Icon(Snooze, 20dp)`，高 48dp；
     - 「打开抖音」`OutlinedButton(shape = ShapePill, border 1.dp DouyinBlue, contentColor = DouyinBlue)`，前置 `Icon(OpenInNew, 20dp)`，高 48dp；
   - 播放/暂停圆形键：置于滑条上方 16dp，`Box(72.dp, ShapePill, containerColor = Sunrise700)` 中心 `Icon(Play/Pause, 36dp, White)`（比 V1 的 88dp 缩小，突出滑条）。

**行为约束（不变项）**：返回键不逃逸、3 秒最小展示、`showWhenLocked/turnScreenOn/KEEP_SCREEN_ON` 全部保留。

### 2.3 设置页 SettingsScreen（分组卡片重构）

`Scaffold` + `TopAppBar`（返回箭头保留）+ `Column(verticalScroll, spacedBy(16.dp))`。所有组卡统一：`Card(ShapeLarge, containerColor = SunriseSurface)`，组标题 = `Row`：`Icon(20dp, Sunrise700)` + 8dp + `sectionTitleStyle`。

自上而下共 4 组：

1. **组①「博主管理」**（新功能，图标 `Person`）
   - 条目行（`Row(padding 20.dp, CenterVertically)`，整体可点击，点击弹更换对话框）：
     - 左列：`labelLarge, Ink60`「当前博主」；下方 4dp：博主名 `bodyMedium W600, Ink900`；
     - 右侧：`TextButton`「更换博主」+ `Icon(ChevronRight, 18dp, Ink60)`；
   - 底部 4dp 辅助文案：`bodySmall→bodyMedium 12.sp, Ink60`「更换后将清空旧缓存并立即同步」。
2. **组②「数据源」**（图标 `CloudSync`）
   - 条目 1 RSSHub 地址：沿用 V1 的 `OutlinedTextField` + 保存/恢复默认按钮（样式不变，随新主题）；
   - 条目 2 同步状态行：`Row(SpaceBetween)`：左「最近同步」`labelLarge, Ink60`；右侧 = 结果徽章（同主页 §2.1-5 规格）+ `bodyMedium, Ink60` 时间；
   - 条目 3 立即同步按钮：`Button(ShapeMedium, 高 44dp)`，`syncing` 时文案变「同步中…」并禁用（现有逻辑）；
   - 条目 4 缓存占用：`Row(SpaceBetween)`：「缓存占用」`bodyMedium` + `%.1f MB` 右对齐 `bodyMedium W600`；下挂 `OutlinedButton`「清除缓存」。
3. **组③「播放」**（图标 `VolumeUp`）
   - 条目 1 贪睡时长：`labelLarge` 标题 + `Row(spacedBy(8.dp))` 三个 `FilterChip`（5/10/15，现有逻辑）；
   - 条目 2 音量渐强：`Row(SpaceBetween)` 文案+`Switch`（现有逻辑）。
4. **组④「关于」**（图标 `Info`）
   - 条目 1 「使用说明」行（新功能 B）：`Row(padding 20.dp, 可点击)` = `Icon(MenuBook, 20dp, Sunrise700)` + 文案 `bodyMedium, Ink900` + 尾部 `ChevronRight`；点击 `onNavigateToUsageGuide`；
   - 条目 2 「权限引导」行：同构，图标 `VerifiedUser`；
   - 条目 3 版本行：`Row(SpaceBetween)`「版本」`bodyMedium, Ink60` + `bodyMedium, Ink60`（`BuildConfig.VERSION_NAME`，若未启用 BuildConfig 则硬编码常量 `Constants.APP_VERSION = "2.0.0"`）。

### 2.4 使用说明页 UsageGuideScreen（新页面）

- 路由：`Routes.USAGE_GUIDE = "usageGuide"`；`Scaffold` + `TopAppBar`（标题「使用说明」+ 返回）；
- 布局：`Column(verticalScroll, horizontalPadding 20.dp, spacedBy(12.dp))`，5 个章节卡（`Card(ShapeLarge)`，组标题 `sectionTitleStyle` 前置序号图标）：

| 章节 | 内容要点（文案定稿见 §4） |
|---|---|
| ① 快速上手 | 3 步：设时间开开关 → 完成权限引导 → 等待自动同步/点立即同步 |
| ② 权限说明 | 5 项权限（通知/精确闹钟/自启动/省电策略/全屏通知）逐条：作用 + 不开的后果 |
| ③ 同步与数据源 | RSSHub 地址含义、Cookie/实例有效期与 EMPTY/PARSE 现象、同步时机 05:30/21:00、立即同步用途 |
| ④ 响铃页操作 | 滑动停止、稍后提醒、播放暂停、打开抖音 |
| ⑤ 常见问题 | 不响 4 步排查、缓存占用（≤3 条）、更换博主说明 |

- 视觉：章节卡标题行 = `Icon(24dp, Sunrise700)` + 标题；条目 = `Row`（序号小圆 `Surface(20.dp, ShapePill, Sunrise100)` 内 `Text(12.sp, Dawn40)`）+ 文字 `bodyMedium`，条目间距 10dp；FAQ 条目 = 问题 `bodyMedium W600` + 答案 `bodyMedium, Ink60`。

### 2.5 权限引导页 PermissionGuideScreen（微调）

仅对齐新 token：卡片圆角换 `ShapeLarge`、主色沿用 `Sunrise700`、按钮 `ShapeMedium`、文字色改 `Ink900/Ink60`。**结构与流程不动。**

---

## 3. 新功能交互流程

### 3.1 功能 A：博主设置（换音频来源）

#### 3.1.1 数据层（先行实现）

- `Settings` 新增字段：
  - `bloggerSecUid: String = Constants.SEC_UID`（默认现有博主）
  - `bloggerName: String = "每日早安"`
- `SettingsRepository` 新增 Keys：`BLOGGER_SEC_UID` / `BLOGGER_NAME` / `LAST_BLOGGER_SEC_UID`（后者为同步引擎判断换博主用，默认空串）；新增 `suspend fun setBlogger(secUid: String, name: String)`（同时写入两个 key）与 `suspend fun lastBloggerSecUid(): String` / `suspend fun setLastBloggerSecUid(v: String)`。
- `SyncEngine.sync()` 改动（数据链路兼容，选片/兜底不动）：
  1. `feedUrl = template.format(baseUrl, settings.bloggerSecUid)`（替代 `Constants.SEC_UID`）；
  2. 同步开始前：`if (settings.lastBloggerSecUid != settings.bloggerSecUid)` → **清空旧缓存**：`videoDao.clearAll()` + 删除 `filesDir/videos/` 全部文件（`CacheCleaner` 新增 `clearAllFiles()`），随后 `setLastBloggerSecUid(settings.bloggerSecUid)`；
  3. 同步结束后写入 `lastSync*` 逻辑不变。
- `BloggerValidator`（新文件 `data/repo/BloggerValidator.kt`）：
  - `suspend fun validate(input: String): Result<BloggerInfo>`；内部用 `Http.client` 请求一次 `RSSHUB_ROUTE_TEMPLATE.format(baseUrl, secUid)`（超时沿用 Http 默认），解析出**至少 1 条条目**即成功；
  - `BloggerInfo(secUid, name)`：name 取 feed JSON 顶层 `title` 字段；若为空则回退 `secUid.take(8) + "…"`；
  - 失败抛 `SyncError.Network/Parse/Empty` 同构错误，供 UI 文案化。

#### 3.1.2 解析规则（写入 `BloggerValidator.parseInput()`，单元测试覆盖）

```text
输入 trim 后：
1. 含 "douyin.com/user/" → 正则 Regex("user/([A-Za-z0-9_-]+)") 取 group(1)
2. 否则整体匹配 Regex("^[A-Za-z0-9_-]{20,}$") → 视为纯 sec_uid
3. 都不满足 → 解析失败（UI 内联报错，不发请求）
```

#### 3.1.3 UI 流程（设置页组①）

1. 点击「更换博主」→ `AlertDialog(ShapeMedium)`：
   - 标题「更换博主」；正文说明「粘贴博主抖音主页链接，或直接粘贴 sec_uid」；
   - `OutlinedTextField`（placeholder「https://www.douyin.com/user/… 或 sec_uid」），`singleLine = false`（允许粘贴整链接换行）；
   - 按钮：取消 / 确认更换。
2. 确认 → 状态机（`SettingsViewModel` 内新增 `bloggerUiState: MutableStateFlow<BloggerUiState>`，四态：`Idle / Parsing / Validating(name) / Done / Failed(msg)`）：
   - `Parsing`（本地解析，瞬时）：输入非法 → 对话框内联红字 `OnWarnContainer`「无法识别，请粘贴抖音主页链接或 sec_uid」，停留在对话框；
   - `Validating`：对话框按钮显示 `CircularProgressIndicator(20dp)`，禁用；
   - 成功 → 保存 `setBlogger(secUid, name)` → 关闭对话框 → `Snackbar`「已切换为「{name}」，正在重新同步」→ 自动调 `syncNow()`（其内部换博主清缓存逻辑生效）；
   - 校验失败 → 对话框内联红字「校验失败：{NETWORK:/EMPTY: 原因}，未做更改」，停留在对话框可改。
3. **主页联动**：状态卡行①显示 `bloggerName`；**响铃页联动**：`RingingViewModel.openDouyin()` 改为 `Uri.parse("https://www.douyin.com/user/${settings.bloggerSecUid}")`（ViewModel 持 `SettingsRepository`，`init` 读一次 `current()` 存入成员变量）。

### 3.2 功能 B：使用说明页

交互极简：设置页组④「使用说明」行 → `navController.navigate(Routes.USAGE_GUIDE)` → 滚动阅读页 → 返回。无状态、无网络、无埋点。全部文案进 `strings.xml`（§4）。

### 3.3 SlideToStop 组件规格（`ui/ringing/SlideToStop.kt`，自定义实现）

> BOM 2024.09.03 无现成 SlideToStop 组件，需自实现（`Canvas`/`Box` + `pointerInput(detectHorizontalDragGestures)`，均为稳定 API）。

| 项 | 规格 |
|---|---|
| 轨道 | `Box(fillMaxWidth, height 64.dp, ShapePill)`，`color = FrostWhite.copy(alpha = 0.08f)`，`border 1.dp FrostWhite.copy(alpha = 0.14f)` |
| 轨道文字 | 居中「滑动停止响铃」`titleMedium, MoonFrost`，alpha = `1f - dragFraction * 1.4f`（`coerceIn(0f,1f)`） |
| 滑块 | `56.dp` 圆（`ShapePill`），`containerColor = Sunrise700`，中心 `Icon(Icons.Filled.KeyboardArrowRight → 停止时 Stop, 28.dp, White)`；x 偏移 `offsetX` 限 `[0, trackWidth - 56.dp - 8.dp]` |
| 手势 | `detectHorizontalDragGestures` 累加 `dragOffset`；`onDragEnd` 时若 `dragFraction >= 0.8f` 触发停止，否则回弹 |
| 触发 | `dragFraction = offsetX / maxOffset >= 0.8f` → 触发一次（`viewModel.stop()`），随后 `offsetX` 立即归 0（防重复触发用 `LaunchedEffect` 置 `consumed` 标记） |
| 回弹 | `animateFloatAsState(targetValue = 0f, Motion.springPosition)` —— 轻微回弹效果 |
| 触发瞬间 | `HapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)`（`LocalHapticFeedback`，稳定 API） |
| 无障碍 | 语义：轨道 `Modifier.semantics { customActions = listOf(CustomAccessibilityAction("停止响铃") { stop() }) }` |

---

## 4. strings.xml 新增 key 清单（文案定稿）

```xml
<!-- 主页 -->
<string name="main_label_next_ring">距下次响铃</string>
<string name="main_blogger_label">当前博主</string>
<string name="main_cache_count_fmt">已缓存 %1$d 条视频</string>
<string name="main_alarm_off_hint">闹钟已关闭</string>
<string name="main_snooze_center_fmt">稍后 %1$s</string>
<string name="badge_sync_ok">成功</string>
<string name="badge_sync_fail">失败</string>

<!-- 响铃页 -->
<string name="ringing_slide_to_stop">滑动停止响铃</string>

<!-- 设置页：博主 -->
<string name="settings_blogger_title">博主管理</string>
<string name="settings_blogger_current">当前博主</string>
<string name="settings_blogger_change">更换博主</string>
<string name="settings_blogger_help">更换后将清空旧缓存并立即同步</string>
<string name="blogger_dialog_title">更换博主</string>
<string name="blogger_dialog_desc">粘贴博主抖音主页链接，或直接粘贴 sec_uid</string>
<string name="blogger_dialog_hint">https://www.douyin.com/user/… 或 sec_uid</string>
<string name="blogger_dialog_confirm">确认更换</string>
<string name="blogger_parse_error">无法识别，请粘贴抖音主页链接或 sec_uid</string>
<string name="blogger_validate_fail_fmt">校验失败：%1$s，未做更改</string>
<string name="blogger_switch_ok_fmt">已切换为「%1$s」，正在重新同步</string>

<!-- 设置页：关于 -->
<string name="settings_group_source">数据源</string>
<string name="settings_group_playback">播放</string>
<string name="settings_group_about">关于</string>
<string name="settings_usage_guide">使用说明</string>
<string name="settings_version_label">版本</string>

<!-- 使用说明页（章节 + 条目，按 §2.4 表格顺序） -->
<string name="usage_title">使用说明</string>
<string name="usage_s1_title">快速上手</string>
<string name="usage_s1_step1">① 主页设置响铃时间并打开开关</string>
<string name="usage_s1_step2">② 按引导页完成全部 5 项权限</string>
<string name="usage_s1_step3">③ 点「立即同步」验证数据源，之后每天 05:30 / 21:00 自动同步</string>
<string name="usage_s2_title">权限说明</string>
<string name="usage_s2_p1">通知权限：展示响铃通知。不开则锁屏可能收不到响铃提醒。</string>
<string name="usage_s2_p2">精确闹钟：保证准点响铃。不开则可能延迟数分钟。</string>
<string name="usage_s2_p3">自启动：开机后恢复闹钟。不开则重启手机后闹钟丢失。</string>
<string name="usage_s2_p4">省电策略「无限制」：防止系统杀后台。不开则闹钟可能整晚被杀。</string>
<string name="usage_s2_p5">全屏显示通知：锁屏直接弹出响铃页。不开则只收到普通通知。</string>
<string name="usage_s3_title">同步与数据源</string>
<string name="usage_s3_p1">RSSHub 地址是视频数据源；公共实例对抖音路由不稳定，报 EMPTY/PARSE 属已知现象，不影响响铃（有缓存与铃声兜底）。</string>
<string name="usage_s3_p2">每天 05:30 与 21:00 自动同步并缓存最近视频；「立即同步」可手动触发验证。</string>
<string name="usage_s4_title">响铃页操作</string>
<string name="usage_s4_p1">向右滑到底停止响铃；「稍后提醒」按设定间隔再响；圆形键暂停/继续；「打开抖音」直达博主主页。</string>
<string name="usage_s5_title">常见问题</string>
<string name="usage_s5_q1">不响？依次查：①省电策略无限制 ②精确闹钟允许 ③自启动开启 ④最近任务上锁。</string>
<string name="usage_s5_q2">缓存最多保留 3 条视频（约 10–50MB/条），可在设置页清除。</string>
<string name="usage_s5_q3">更换博主会清空旧博主缓存并重新下载新博主最近视频。</string>
```

---

## 5. 改动文件清单预估

| 文件 | 改动 | 批次 |
|---|---|---|
| `ui/theme/Color.kt` | 新增 §1.1 色 token，删响铃页旧硬编码 | 1 |
| `ui/theme/Type.kt` | 覆写 §1.2 字体 token | 1 |
| `ui/theme/Shape.kt` | **新建** §1.3 形状 token，接入 Theme | 1 |
| `ui/theme/Motion.kt` | **新建** §1.4 动效 token | 1 |
| `ui/theme/Theme.kt` | 色板映射更新 + `shapes` 注入 | 1 |
| `data/prefs/Settings.kt` | + `bloggerSecUid` / `bloggerName` | 2 |
| `data/prefs/SettingsRepository.kt` | + 对应 Keys / `setBlogger` / `lastBloggerSecUid` | 2 |
| `sync/SyncEngine.kt` | feedUrl 用设置的 secUid；换博主清缓存 | 2 |
| `data/repo/CacheCleaner.kt` | + `clearAllFiles()` | 2 |
| `data/repo/BloggerValidator.kt` | **新建**（解析 + 校验） | 2 |
| `util/Constants.kt` | + `APP_VERSION`（如需） | 2 |
| `ui/ringing/RingingViewModel.kt` | openDouyin 用设置的 secUid | 2 |
| `ui/main/MainScreen.kt` | §2.1 全部重写 UI | 3 |
| `ui/main/MainViewModel.kt` | UiState + `cacheCount` + `bloggerName` 透出 | 3 |
| `ui/ringing/RingingActivity.kt` | §2.2 背景/光晕/毛玻璃/布局重排 | 4 |
| `ui/ringing/SlideToStop.kt` | **新建**（§3.3） | 4 |
| `playback/AlarmService.kt` + `AlarmPlayer.kt` | `RingingState.volumeProgress` 字段透传 | 4 |
| `ui/settings/SettingsScreen.kt` | §2.3 分组重构 + 博主对话框 | 5 |
| `ui/settings/SettingsViewModel.kt` | 博主状态机 + 调 Validator | 5 |
| `ui/guide/UsageGuideScreen.kt` | **新建**（§2.4） | 6 |
| `ui/navigation/AppNavHost.kt` | + `Routes.USAGE_GUIDE` + 转场参数 | 6 |
| `ui/guide/PermissionGuideScreen.kt` | token 对齐微调 | 6 |
| `res/values/strings.xml` | §4 全部新增 | 各批次随用随加 |
| 测试 | `BloggerValidatorTest`（解析规则 5+ 用例）、`SyncEngine` 换博主清缓存用例 | 2 |

---

## 6. 实现顺序建议（6 批，每批可独立编译运行）

| 批次 | 内容 | 独立编译验证方式 |
|---|---|---|
| **1. 主题基座** | Color/Type/Shape/Motion/Theme 全部落位；`Shape.kt`、`Motion.kt` 新建；旧页面不改也能编译（新 token 仅追加，旧色名保留） | 编译 + 全 App 过一眼（视觉暂无变化或轻微变化） |
| **2. 数据层 + 博主打通（无 UI）** | Settings 字段、Repository、SyncEngine 换源+清缓存、BloggerValidator、openDouyin 改设置源、单元测试 | 编译 + 单测通过；现有 UI 行为不变 |
| **3. 主页重设计** | §2.1：进度环、状态卡、警示卡、时间行 | 编译 + 真机看主页；验证倒计时/开关/贪睡态 |
| **4. 响铃页重设计** | §2.2 + SlideToStop + volumeProgress 透传 | 编译 + 触发响铃验证：渐变背景、光晕随渐强增亮、滑停防误触、加载占位 |
| **5. 设置页重设计 + 博主 UI** | §2.3 + 更换博主对话框状态机 | 编译 + 真机走通换博主全流程（合法链接/纯 secUid/非法输入/校验失败） |
| **6. 说明页 + 收尾** | UsageGuideScreen、NavHost 转场、引导页微调、strings 补齐 | 编译 + 全页面走查 |

依赖关系：2 依赖 1（无）；3/4 依赖 1；5 依赖 2；6 依赖 1。3 与 4 可并行。

---

## 7. 验收清单（QA 用）

- [ ] 主页倒计时数字跳动时宽度不抖（tnum 生效）
- [ ] 进度环随倒计时平滑推进，闹钟关闭时归零且显示「闹钟已关闭」
- [ ] 权限缺失时警示卡出现，去开启跳转正确，授权后动画收起
- [ ] 响铃页背景为深蓝渐变，光晕随音量渐强（约 20s）从微弱到明显增亮
- [ ] 滑动未达 80% 松手 → 滑块回弹且不停止；达到 80% → 触发停止 + 震动，且仅触发一次
- [ ] 响铃页加载占位 3 秒逻辑与 3 秒最小展示不回归
- [ ] 更换博主：粘贴主页链接可解析；纯 secUid 可解析；乱码报错不发请求；校验成功后主页博主名即时更新、旧缓存被清空并自动重新同步
- [ ] 换回原博主同样清缓存重下
- [ ] 响铃页「打开抖音」直达**新博主**主页
- [ ] 使用说明页 5 章节完整、纯 Compose 渲染、无网页
- [ ] 所有页面转场为统一轻推入 + 淡入；无依赖版本变更（`libs.versions.toml` diff 为空）
- [ ] 新增文案全部来自 strings.xml，代码内无硬编码中文
