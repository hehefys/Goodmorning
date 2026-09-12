# 技术栈总览与学习路线：每日早安闹钟（GoodMorning Alarm）

> 依据 `app/build.gradle.kts`、`gradle/libs.versions.toml`、`AndroidManifest.xml` 与源码实测整理。
> 规模：主源码 6636 行（41 个 Kotlin 文件），单测 773 行（77 例）。
> 项目形态：**纯客户端 App，无自建服务端**，数据源为外部 RSSHub 实例。

---

## 1. 技术栈总览

### 1.1 UI 层（对应原表中「前端」）

| 技术 | 版本 | 项目中的用途 | 解决的问题 |
|---|---|---|---|
| Kotlin | 2.0.20 | 全量开发语言（无 Java 代码） | 空安全 + 协程，消除 NPE 与回调地狱 |
| Jetpack Compose | BOM 2024.09.03 | 全部 UI（MainScreen 642 行 / SettingsScreen 902 行 等 13 个文件） | 声明式 UI，去掉 XML 与 `findViewById`，状态驱动重组 |
| Material 3 | 随 BOM | 主题 `Theme.kt`、组件、Material You 动态配色 | 统一视觉规范与深色/浅色适配 |
| Navigation Compose | 2.8.2 | 4 条路由（Main / Settings / PermissionGuide / UsageGuide） | 页面跳转与返回栈管理，解耦页面间直接引用 |
| Lifecycle + ViewModel | 2.8.6 | `MainViewModel` / `SettingsViewModel` / `PermissionGuideViewModel` | 配置变更（旋转等）下状态存活，避免把逻辑塞进 Activity |
| Coroutines + Flow | 1.8.1 | `StateFlow` 驱动 UI（`combine` + `stateIn(WhileSubscribed(5000))`） | 异步编排 + 响应式数据流，替代 LiveData 与回调 |

### 1.2 后台与系统能力（对应原表中「后端」）

本项目**没有自建服务端**，这一层是「设备上的后台执行链路」。

| 技术 | 版本/API | 项目中的用途 | 解决的问题 |
|---|---|---|---|
| AlarmManager | `setAlarmClock` | `AlarmScheduler`（223 行）注册每日/贪睡闹钟 | 精确定时；`setAlarmClock` 官方保证系统不调整投递时间、必要时退出低功耗模式 |
| BroadcastReceiver | 系统 | `AlarmReceiver` 到点入口、`BootReceiver` 重启恢复 | 接收系统闹钟与开机广播 |
| Foreground Service | `mediaPlayback` 类型 | `AlarmService`（760 行）响铃宿主 | 后台长时间播放不被系统回收；通知即控制面板（无独立响铃页） |
| WorkManager | 2.9.1 | `SyncWorker` + `SyncScheduler` 视频预缓存 | 约束感知的后台任务；**刻意不用 PeriodicWork**——其 15 分钟最小间隔 + Doze 漂移无法精确落在 05:30/21:00 |
| Media3 ExoPlayer | 1.4.1 | `AlarmPlayer`（365 行）主音频/副音频播放、渐强渐弱、裁剪区间循环 | 统一的媒体播放与音量控制，替代 MediaPlayer |
| PowerManager.WakeLock | 系统 | `RingWakeLock` 覆盖「到点 → 出声」空档 | 防止设备在这段空档再次浅睡导致哑火 |
| 设备保护存储 | 系统 | `RingGuard` 到点去重水位 | 重启后首次解锁前也能判重（普通 SharedPreferences 此时不可用） |
| SAF 持久授权 | 系统 | 副音频文件选择与持久读取权限 | 跨重启保留用户所选音频的访问权限 |

### 1.3 数据层

| 技术 | 版本 | 项目中的用途 | 解决的问题 |
|---|---|---|---|
| Room | 2.6.1（KSP） | 2 张表：`videos`（缓存元数据）、`playback_logs`（播放记录）；`VideoDao` 返回 `Flow` / `suspend` | SQLite 的编译期 SQL 校验 + 响应式查询，免除手写 Cursor |
| DataStore Preferences | 1.1.1 | `Settings`（26 个字段）+ `SettingsRepository` | 异步、事务化的键值持久化，替代 `SharedPreferences` 的 ANR 风险 |
| OkHttp | 4.12.0 | 两套 client：RSSHub 拉取（15s/30s 超时）与抖音下载（120s 读超时 + UA/Referer 拦截器） | 超时控制、拦截器注入防盗链头、自动跟随 302 到 CDN |
| kotlinx.serialization | 1.7.2 | 解析 RSSHub 的 JSON Feed（`RssFeedParser` 141 行） | 无反射的 Kotlin 原生序列化，比 Gson/Moshi 更契合 Kotlin 类型 |
| 内部文件存储 | 系统 | `files/videos/*.mp4`，`CacheCleaner` 保留最近 3 条 | 离线可播，避免响铃时现拉网络 |

### 1.4 构建与部署

| 技术 | 版本 | 项目中的用途 | 解决的问题 |
|---|---|---|---|
| Gradle | 8.9（阿里云镜像分发） | 构建驱动 | 依赖下载加速（国内网络） |
| AGP | 8.5.2 | Android 构建插件 | — |
| Version Catalog | `libs.versions.toml` | 全部依赖版本的单一事实来源 | 多模块间版本统一，避免版本漂移 |
| KSP | 2.0.20-1.0.25 | Room 注解处理 | 比 KAPT 更快的 Kotlin 符号处理 |
| JDK | 17（`jvmTarget = 17`） | 编译目标 | 与 AGP 8.x 要求对齐 |
| 编译 SDK / 目标 / 最低 | 35 / 35 / **29** | `minSdk 29` = Android 10+ | 覆盖主流机型，同时可用较新的通知与前台服务 API |
| Foojay Toolchain Resolver | 0.10.0 | 自动下载匹配的 JDK | 免手工配置 JDK 路径 |
| 阿里云 Maven 镜像 | — | `settings.gradle.kts` 仓库优先顺序 | 解决 Google/MavenCentral 访问不稳定 |

### 1.5 测试与工具链

| 技术 | 版本 | 项目中的用途 | 解决的问题 |
|---|---|---|---|
| JUnit 4 | 4.13.2 | 77 个 JVM 单测：`SelectionPolicy`(13) / `BloggerValidator`(8) / `RssFeedParser`(31) / `Constants`(7) / `TimeUtils`(18) | 纯逻辑回归保护，秒级反馈 |
| `unitTests.isReturnDefaultValues` | — | 让 `android.util.Log` 在 JVM 下返回默认值而非抛 "not mocked" | 使依赖 Android 日志的纯逻辑类可被单测 |
| AppLogger（自研） | — | 运行日志落盘 + 设置页 FileProvider 导出 | **真机问题定位的关键**：本次「息屏不响铃」「到点哑火」全靠导出的日志定位 |
| 崩溃捕获 | — | `GoodMorningApp.installCrashLogger()` | 全局异常兜底记录 |

### 1.6 明确「没有用」的技术（避免误判）

| 未使用 | 说明 |
|---|---|
| DI 框架（Hilt / Koin） | 依赖通过构造函数手工注入（`SyncEngine(context)`、`SettingsRepository(context)`） |
| Retrofit | 直接用 OkHttp + 自写解析，请求面很小 |
| 图片加载库（Coil / Glide） | 无图片展示需求 |
| 插桩测试（`androidTest`） | 暂缺，UI 与系统组件无自动化覆盖 |
| CI/CD | 无 `.github` 等配置，构建与打包全在本机进行 |
| 签名配置 | `app/build.gradle.kts` 未配 `signingConfigs`，release 包需补 |

---

## 2. 关键依赖关系

### 2.1 分层协作

```
UI 层      Compose + ViewModel(StateFlow)  ── 只读 Flow，单向数据流
              │  调用 suspend setter / 触发调度
领域逻辑    SelectionPolicy（选片） · SyncEngine（同步编排） · CacheCleaner
              │
数据层      Room(Flow/suspend) · DataStore(Flow/suspend) · 文件存储
              │
外部 I/O    OkHttp ──> RSSHub ──> kotlinx.serialization
            ExoPlayer ──> 本地 mp4
系统服务    AlarmManager · WorkManager · NotificationManager · PowerManager
```

关键约定：**UI 只读 `Flow`，写入一律走 `suspend` 函数**，状态变更自动回流刷新界面，不存在手动 `refresh()`。

### 2.2 链路 A：一次闹钟响铃（入口 → 出声）

| # | 环节 | 组件 | 要点 |
|---|---|---|---|
| 1 | 用户设置 | `SettingsScreen` → `SettingsRepository`(DataStore) → `AlarmScheduler` | 写 DataStore 即持久化 |
| 2 | 注册闹钟 | `AlarmManager.setAlarmClock` | 优先精确；降级链 `setExactAndAllowWhileIdle → setWindow → setAndAllowWhileIdle` |
| 3 | 到点 | `AlarmReceiver.onReceive` | 持 `PARTIAL_WAKE_LOCK`；`RingGuard` 判重；补发兜底通知 |
| 4 | 启动服务 | `AlarmService.start(action, triggerAt)` | **必须带 `triggerAt` + `EXTRA_DEDUPE_PASSED`**，否则服务会二次判重自杀 |
| 5 | 服务就绪 | `onCreate` 持锁接管 → `startForeground` → 建 ExoPlayer | 先占前台再建播放器；进前台失败也不阻断出声 |
| 6 | 选片 | `SelectionPolicy.select(videos, today)` ← `VideoRepository` ← Room | 缓存为空且非 Doze 时现场限时同步（8s） |
| 7 | 出声 | `AlarmPlayer`：副音频衬托 → 主音频渐强 → 播完重播/收尾 | 四级兜底：本地 mp4 → 系统闹钟铃声 → 铃声 → ToneGenerator 蜂鸣 |

### 2.3 链路 B：一次视频同步（数据存储方向）

| # | 环节 | 组件 |
|---|---|---|
| 1 | 调度 | `SyncScheduler.scheduleNext` → `WorkManager.enqueueUniqueWork`（**OneTimeWork**） |
| 2 | 执行 | `SyncWorker.doWork()`（CoroutineWorker） → `SyncEngine.sync()`（`Dispatchers.IO`） |
| 3 | 抓取 | `Http.client` → RSSHub `/douyin/user/{sec_uid}?embed=1&format=json` |
| 4 | 解析 | `kotlinx.serialization` → `RssFeedParser` → `VideoItem` 列表 |
| 5 | 下载 | `VideoDownloader` 用 `downloadClient`（带 UA/Referer 防盗链头）落盘 mp4 |
| 6 | 落库 | `VideoDao.upsertAll`（按 aweme_id 主键去重） |
| 7 | 清理 | `CacheCleaner` 保留最近 3 条（`CACHE_KEEP_COUNT`） |
| 8 | 回写状态 | DataStore 写 `lastSyncAt` / `lastSyncOk` / `lastSyncMsg`（含 `NETWORK:` / `PARSE:` / `EMPTY:` / `DOWNLOAD:` 错误码前缀） |
| 9 | UI 刷新 | `Flow` 自动回流，主页同步状态行更新 |

### 2.4 两条链路的交汇点

`AlarmService` 在缓存为空时会**同步调用 `SyncEngine`**（限时 8 秒）——这是唯一一处「响铃链路」直接触发「同步链路」的地方，也是最容易在息屏/Doze 下出问题的接缝，因此加了 `isDeviceIdleMode()` 判断：Doze 下直接跳过同步走兜底铃声，不做无谓等待。

---

## 3. 学习路线

面向「有基础编程能力、缺 Android / Kotlin / 本项目经验」的学习者。建议顺序执行，每阶段达标再进下一阶段。

### 阶段一 · 入门（先把代码跑起来、读懂）

| 项 | 内容 |
|---|---|
| **核心知识点** | ① Kotlin 基础语法：`data class`、空安全 `?`、扩展函数、`when`、`sealed class`；② 协程入门：`launch` / `suspend` / `Dispatchers`；③ Android 四大组件与生命周期；④ Compose 基础：`@Composable`、`remember`、`Column/Row`、`Modifier`；⑤ Gradle 与依赖管理基础 |
| **优先资料方向** | Kotlin 官方「Get started with Kotlin」与「Coroutines Guide」；Android 官方「Android Basics with Compose」课程；Google 的「Guide to app architecture」前两章；Jetpack Compose 官方「Thinking in Compose」 |
| **可验证的完成标准** | ① 本机跑通 `assembleDebug` 与 `testDebugUnitTest`，77 例全绿；② 能说出 `MainActivity → AppNavHost → MainScreen → MainViewModel` 这条链的每一跳在做什么；③ 能独立改一处 UI 文案并在模拟器上看到效果；④ 能读懂 `Settings.kt` 全部 26 个字段的含义 |

### 阶段二 · 进阶（掌握本项目的技术骨架）

| 项 | 内容 |
|---|---|
| **核心知识点** | ① Compose 状态与副作用：`StateFlow` / `collectAsState` / `LaunchedEffect` / `DisposableEffect`；② Room：Entity / DAO / `Flow` 查询 / KSP 生成；③ DataStore 与 `preferencesDataStore` 单例约定；④ WorkManager：OneTime vs Periodic、约束、`enqueueUniqueWork`；⑤ AlarmManager 与后台限制：Doze、App Standby、`SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM`、厂商省电策略；⑥ ExoPlayer/Media3：`ExoPlayer` 生命周期、音量控制、`ClippingConfiguration`；⑦ OkHttp：拦截器、超时、`followRedirects`；⑧ kotlinx.serialization 自定义解析 |
| **优先资料方向** | Android 官方：Room / DataStore / WorkManager / AlarmManager 各自的 Developer Guide；Media3 ExoPlayer 官方文档；OkHttp Recipes；**重点精读 Android 官方「Schedule alarms」与「Optimize for Doze and App Standby」**——本项目绝大多数疑难都出自这里 |
| **可验证的完成标准** | ① 能画出第 2.2、2.3 两条链路的时序，并指出每处的失败兜底；② 能独立给 `Settings` 增加一个新字段，贯穿 DataStore → 设置页 UI → 响铃时读取；③ 能解释为什么本项目不用 `PeriodicWorkRequest`；④ 能在真机上用 adb 抓取并读懂 App 运行日志 |

### 阶段三 · 实战（可靠性工程与交付）

| 项 | 内容 |
|---|---|
| **核心知识点** | ① 息屏可靠性工程：WakeLock 生命周期与交棒、前台服务类型、通知渠道与免打扰穿透、OEM 差异；② 幂等与去重设计（本项目 `RingGuard` 的教训：任何二次判定都可能变成自杀开关）；③ 异常兜底分层：`runCatching` 单点兜底 + 协程级 `CoroutineExceptionHandler` + 多级降级链；④ 可观测性：结构化日志、错误码规范、导出与复盘；⑤ 发布工程：签名配置、release 构建、版本管理；⑥ 测试补齐：插桩测试与 CI |
| **优先资料方向** | Android 官方「Foreground services」「Wake locks」「Power management」；Google 的「Modern background execution」系列；Android 官方 Testing 指南（Instrumented tests）；GitHub Actions 官方 Android 构建文档 |
| **可验证的完成标准** | ① 能基于真机日志独立定位一次「该响没响」并给出根因；② 能为一个新功能设计完整的失败降级路径并写进文档；③ 能配好签名并产出可安装的 release 包；④ 能为现有链路补上第一条插桩测试 |

---

## 4. 实践建议（基于本项目代码）

### 练习 1 · 阶段一巩固：给「关于」页加一个真实版本来源

**任务**：设置页「关于」当前读的是 `Constants.APP_VERSION`（现为 `2.1.2`），而 `app/build.gradle.kts` 里的 `versionName` 还是 `1.0.0`——**两者已经不一致**。请把它改成从 `BuildConfig.VERSION_NAME` 读取，并让两处对齐。

**涉及文件**：`SettingsScreen.kt`、`app/build.gradle.kts`、`Constants.kt`

**验收**：设置页显示的版本号与 Gradle 配置一致；`ConstantsTest` 仍全绿。

> 这个练习会让你走完「Gradle → BuildConfig → UI」的完整链路，同时修掉一个真实存在的不一致。

### 练习 2 · 阶段二巩固：新增「贪睡次数上限」设置项

**任务**：仿照现有 `snoozeMinutes`（贪睡间隔）的实现，新增一个 `snoozeMaxCount`（贪睡次数上限，1~5），贯穿 DataStore → 设置页滑条 → `AlarmService.handleSnooze()` 生效（超过上限后贪睡按钮消失或不再注册）。

**涉及文件**：`Settings.kt`、`SettingsRepository.kt`、`SettingsViewModel.kt`、`SettingsScreen.kt`、`AlarmService.kt`

**验收**：设置可调；连续贪睡达到上限后不再响；重启 App 后设置保持。

> 这个练习覆盖「数据层 → ViewModel → UI → 后台服务消费」的完整改法，是本项目最典型的增量开发模式。

### 练习 3 · 阶段三巩固：为 `RingGuard` 补单元测试

**任务**：`RingGuard` 是本次「到点哑火」事故的根源，目前**没有任何测试覆盖**。请为它补上 JVM 单测，至少覆盖：首次到点放行、窗口内重复到点拦截、窗口外新闹钟放行、`reset()` 后放行、以及**「手动测试后紧接真实闹钟不应被误杀」**这条回归用例。

**涉及文件**：新建 `app/src/test/.../alarm/RingGuardTest.kt`；可能需要为 `RingGuard` 抽出可注入的存储接口（当前直接依赖 `Context` 与 SharedPreferences）。

**验收**：5 条以上用例全绿；把 2026-08-31 那次事故的两个场景（服务二次判重、手动测试污染水位）写成回归用例钉死。

> 这个练习会逼你面对「为什么这段逻辑测不了」，进而理解可测试性设计——比单纯写测试更有价值。

---

## 5. 需要你补充的信息

以下信息在当前代码库中**无法确认**，若需要我据此调整路线或补齐文档，请补充：

| # | 缺失项 | 为什么重要 |
|---|---|---|
| 1 | **目标机型与厂商** | 息屏可靠性高度依赖 OEM（小米/华为/OPPO/vivo 的省电策略差异极大），决定阶段三的实操侧重点 |
| 2 | **是否已上架或计划上架** | 决定是否需要补齐签名配置、隐私政策、targetSdk 合规项 |
| 3 | **RSSHub 是公共实例还是自建** | `Constants.DEFAULT_RSSHUB_BASE = https://rsshub.app` 公共实例限流严重；若自建，学习路线需加一节 RSSHub 运维 |
| 4 | **版本号的唯一来源** | `versionName = "1.0.0"` 与 `Constants.APP_VERSION = "2.1.2"` 冲突，请确认以哪个为准（建议以 Gradle 为准，见练习 1） |
| 5 | **是否需要多人协作 / CI** | 决定阶段三是否要加入 Git 工作流、Code Review 与流水线配置 |
| 6 | **是否接受引入 DI 框架** | 当前手工注入在 41 个文件规模下尚可，继续增长会变痛；是否引入 Hilt 属于路线分歧点 |
