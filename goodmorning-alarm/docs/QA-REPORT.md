# QA 测试报告：每日早安音频闹钟（goodmorning-alarm）

- **QA**：严过关（Yan，software-qa-engineer）
- **日期**：2026-08-27
- **轮次**：Round 1（静态审查 + 单元测试编写）
- **验证口径**：本机无 JDK / Android SDK，无法执行 Gradle 构建与设备测试。本轮以**独立静态逐行审查**为主，**单元测试代码已编写完毕**（共 67 个用例，需在 Android Studio 中执行，见 §5）。
- **结论速览**：核心选片/续期/解析/下载链路逻辑总体正确，未发现 P0 级阻断缺陷；发现 **2 个中等级别**、**6 个低级别/建议** 源码问题，均路由 Engineer；另有 1 项测试基础设施改动由 QA 完成。

---

## 1. 总体判定

| 维度 | 结论 |
|---|---|
| 选片规则（PRD §5） | ✅ 逻辑正确（13 个单测用例覆盖） |
| 每日闹钟自续期 | ✅ 不漏一天、不重复响（推演验证） |
| SyncEngine 合并逻辑 | ✅ mergeExisting 修复完备（静态推演） |
| RSS 解析容错 | ✅ 行为符合设计（30 个单测用例覆盖） |
| ExoPlayer 渐强/降级 | ⚠️ 时间轴数学正确，但存在 pause 后渐强丢失缺陷（E1） |
| Manifest / 权限 | ✅ 基本完备，2 项备注（E6/E7） |
| **路由决定** | **Engineer**（2 中 + 6 低/建议，无 P0 阻断） |

---

## 2. 六个高风险模块静态审查结论

### 2.1 SelectionPolicy.kt —— ✅ 通过

逐行推演结论：
- 规则①（当天）`publishDate == today && isNotEmpty()`：`today` 由 `TimeUtils.localDate()` 生成恒非空，空串 `publishDate` 永不等于 `today`，PRD §5.4「pubDate 缺失走规则②」实现正确。
- 规则①内部取 `todayVideos.first()`：候选集已按 `publishTimeMillis` 倒序，first 即当天最新，符合 PRD §5.3。
- 规则②（最近一期）取 `downloadable.first()`：正确。
- 规则③：候选集空（含 `localPath` 为 null/空白串被过滤）→ `FALLBACK`，`video=null`，正确。
- 跨日边界（23:59 发布 vs 次日响铃）：`publishDate` 由 `publishTimeMillis` 按本地时区换算，跨日归类正确。
- `localPath` 文件存在性双重校验（Repository 过滤 + Service 再查 `File.isFile`），脏记录不会选中后播放失败。
- 小瑕疵（不路由）：companion 中的 `TAG` 常量未被使用，属死代码。

### 2.2 AlarmScheduler.kt + AlarmService.kt —— ⚠️ 基本正确，2 个低级别问题

自续期推演（多场景）：
- 07:00 响铃 → 用户停止 → `handleStop` → `scheduleNextDaily(7,0)`：`nextDailyAt` 严格未来（等于当前时刻也推明天）→ 注册明天 07:00。**不漏、不重**。
- 贪睡链：07:00 响 → snooze 注册 1002（requestCode 独立于每日 1001，互不覆盖）→ 07:10 贪睡响（`isRinging=false` 重新选片，符合 PRD §5.5）→ 用户停止 → 注册明天 07:00。正确。
- 贪睡期间 `cancelSnoozeOnly()` 在起播后清除已触发的贪睡 PendingIntent，不误伤每日闹钟。正确。
- 降级路径：无精确权限 → `setAndAllowWhileIdle` 降级注册 + UI 引导，返回 false 提示。正确。
- 兜底链：候选空/文件坏 → `playFallback`（RingtoneManager 默认闹钟铃声 → 默认铃声 URI）；`fallbackPlayed` 防无限循环；播放器错误会删坏文件再降级。链路完整（E6 见问题列表）。

发现问题：
- **E3（低）**`selectAndPlay` 竞态：`isRinging` 在协程内部（挂起的 DB 查询之后）才置位。若每日 1001 与贪睡 1002 几乎同时触发（或 START_STICKY 重启竞态），两个 `onStartCommand` 都可能通过 `!isRinging` 检查 → 双重选片、双份播放日志、播放器被二次起播。建议在 `onStartCommand` 同步置位卫兵标志。
- **E4（低）**`START_STICKY` + null intent：进程被杀后系统重启服务（intent 为 null）→ 走 else 分支 → 重新 `selectAndPlay()`，可能在用户已停止响铃之后**意外重响**（例如 handleStop 中 stopSelf 与系统重建竞态）。建议改 `START_NOT_STICKY`，或 null intent 时不重播仅保活。
- 备注（不路由）：贪睡注册后若进程被杀/重启，贪睡视为放弃（代码注释已声明为既定取舍）；每日闹钟兜底靠 BootReceiver + App 启动自检，可接受。

### 2.3 SyncEngine.kt —— ✅ 通过（mergeExisting 完备）

- **mergeExisting 完备性复核**（工程师自报修复点）：旧记录 localPath 存在且文件在 → 保留 `localPath/fileSize/downloadedAt`，其余字段（含新 videoUrl，直链有时效）刷新 ✅；旧记录文件丢失 → 用 fresh（localPath=null）→ 触发重下载 ✅；无旧记录 → fresh ✅。三种分支齐备，未发现遗漏字段。
- 下载半文件清理：`VideoDownloader` 在大小/魔数校验失败时删 `.part`；网络异常中断残留的 `.part` 由同一次同步第③步 `CacheCleaner.cleanOrphanParts()` 兜住；若同步在解析阶段就失败，残留 `.part` 会留到下一次成功同步清理，量级有界（≤3 条/次），可接受。
- 错误分类：NETWORK / PARSE / EMPTY / DOWNLOAD 四类齐全，`DOWNLOAD:` 不致命（有 1 条成功即部分成功）符合约定。
- 发现问题：
  - **E5（低）**EMPTY/PARSE 区分依赖 `e.message?.contains("为空")` 字符串匹配（SyncEngine.kt:61），与解析器异常文案强耦合，重构即断。建议改用异常子类型/错误码字段。另：解析失败路径 `finish(false, 0, ...)` 把 availableCount 硬编码为 0，即使本地已有可用缓存（仅影响 UI 展示数字，不影响兜底）。

### 2.4 RssFeedParser.kt —— ✅ 通过

- `items`/`item` 双键兼容 ✅；`items` 存在但非数组 → 按缺失处理抛异常 ✅。
- pubDate 三形态：epoch 秒（10位）/毫秒（13位）/ISO-8601（Z、+08:00 偏移、无时区本地时间）全覆盖，全失败记 0 → 选片走规则②，符合 PRD §5.4 ✅。
- 直链提取：`<video src>`（双引号/单引号/带属性）优先，`douyinvod.com`、`aweme/v1/play`、`.mp4` 兜底正则 ✅（正则逐个推演，lazy+alternation 捕获范围正确）。
- 脏数据：缺 link（回退 url）、链接非 `/video/{id}` 形态（丢弃条目）、根节点非对象、非法 JSON、空 items → 全部以 `RssParseException` 有序抛出 ✅。
- 备注：纯日期串（"2026-08-27" 无时间部分）解析失败记 0——RSSHub 实际输出均含时间，可接受。

### 2.5 AlarmPlayer.kt —— ⚠️ 时间轴正确，2 个问题

- 渐强数学：40 步 × 500ms = 20s，步长 (1−0.3)/40=0.0175，从 0.3 线性收敛 1.0 ✅（与 Constants 契约一致，单测固化）。
- USAGE_ALARM 音频属性 ✅、视频轨禁用（DefaultTrackSelector）✅、`REPEAT_MODE_OFF` 自然播完回调 `onEnded` ✅、release 顺序（取消 fade 协程 → 移除监听 → 释放播放器）✅、重播前取消旧 fadeJob ✅。
- 发现问题：
  - **E1（中）**`pause()` 取消 `fadeJob`，但 `resume()` 只调 `player.play()` **不重启渐强**：用户在 20s 渐强窗口内暂停（如音量 0.5 时）再继续，音量将**永久停留在暂停时刻**，无法到达 100%，偏离 PRD 渐强口径。修复建议：resume 时基于已播放音量续算剩余步进，或至少把音量直接补齐到当前应达值。
  - **E2（中）**ExoPlayer 未配置 `setWakeMode(C.WAKE_MODE_LOCAL)`，Manifest 声明的 `WAKE_LOCK` 实际未使用：若 full-screen intent 被 Android 14+ 拒绝或 HyperOS 息屏策略拦截、响铃页未拉起，息屏 + Doze 下**音频播放可能停摆**，威胁 P0-2「5 秒内出声/绝不哑火」。建议 Builder 上加 `.setWakeMode(C.WAKE_MODE_LOCAL)`。

### 2.6 AndroidManifest.xml + 权限/组件 —— ✅ 基本完备

核对项：
- 权限：SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM / RECEIVE_BOOT_COMPLETED / POST_NOTIFICATIONS / USE_FULL_SCREEN_INTENT / INTERNET / WAKE_LOCK / FOREGROUND_SERVICE / FOREGROUND_SERVICE_MEDIA_PLAYBACK —— **全部到位** ✅（Android 14 FSI 显式声明、FGS 类型权限齐备）。
- 组件：MainActivity（exported=true+LAUNCHER）✅；RingingActivity（exported=false、singleTop、showOnLockScreen/turnScreenOn + 代码 setShowWhenLocked/setTurnScreenOn 双保险、excludeFromRecents）✅；AlarmReceiver（exported=false，精确闹钟广播豁免后台 FGS 启动）✅；BootReceiver（BOOT_COMPLETED/QUICKBOOT_POWERON/TIME_SET，exported=true 必需）✅；AlarmService（foregroundServiceType="mediaPlayback"，startForegroundCompat 用 ServiceCompat 指定类型，Android 14 合规）✅。
- 发现问题：
  - **E6（低）**架构文档规定第三级兜底为内置资产 `res/raw/fallback_ringtone.mp3`，实现改为 RingtoneManager 系统默认铃声（代码注释称主理人决定）。功能上满足 PRD「本地铃声」，但若设备无默认闹钟/铃声 URI（少数裸系统或无铃声音库设备），`playFallback` 直接 `handleStop` → **哑火**，触碰 P0-4 的绝对下限。建议：保留一个极小的内置 raw 兜底音，或在默认 URI 为 null 时用 ToneGenerator 蜂鸣作第四级。
  - **E7（低/建议）**全工程未出现 `canUseFullScreenIntent()` 检测（grep 验证）：Android 14+ FSI 权限可能被用户/系统拒绝，权限引导页无该步骤、无降级提示，届时锁屏只剩通知栏条目。建议引导页增加检测与跳转（`ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`）。
  - 备注：BootReceiver 未监听 `ACTION_TIMEZONE_CHANGED`——闹钟按 wall-clock(hh:mm) 存储与重算，实际影响极小，不路由。

---

## 3. 问题清单与路由判定

| # | 严重度 | 模块 / 文件:行 | 问题 | 复现条件 | 路由 |
|---|---|---|---|---|---|
| E1 | 中 | playback/AlarmPlayer.kt:96-103, 135-148 | pause 取消渐强协程后 resume 不恢复，音量卡死在暂停时刻，永远达不到 100% | 渐强 20s 窗口内点暂停→继续 | **Engineer** |
| E2 | 中 | playback/AlarmPlayer.kt:52-61 | ExoPlayer 未 setWakeMode(WAKE_MODE_LOCAL)，WAKE_LOCK 权限未生效；息屏+Doze 下播放可能停摆 | FSI 拉起失败/HyperOS 息屏拦截时响铃 | **Engineer** |
| E3 | 低 | playback/AlarmService.kt:95, 112-140 | isRinging 异步置位，双 ACTION_RING 连发存在双重选片竞态 | 每日与贪睡闹钟毫秒级同时触发、或 START_STICKY 重启竞态 | **Engineer** |
| E4 | 低 | playback/AlarmService.kt:85-101 | START_STICKY 重启（null intent）会重新选片响铃，可能在用户停止后意外重响 | 响铃服务被杀后系统重建 | **Engineer** |
| E5 | 低 | sync/SyncEngine.kt:60-62 | EMPTY/PARSE 靠异常文案 `contains("为空")` 区分，脆弱；解析失败路径 availableCount 恒 0 | 解析器异常文案改动 / 解析失败但本地有缓存 | **Engineer** |
| E6 | 低 | playback/AlarmService.kt:142-159 | 无 raw 兜底铃声，设备无默认铃声 URI 时彻底哑火（P0-4 边界）；与 ARCHITECTURE.md §1.5 偏差 | 无铃声音库的设备/ROM | **Engineer**（需主理人确认口径） |
| E7 | 低/建议 | ui/guide/*（缺失） | 未检测 Android 14+ `canUseFullScreenIntent()`，FSI 被拒无引导 | Android 14+ 用户拒绝 FSI | **Engineer**（建议） |
| E8 | 低/建议 | alarm/AlarmScheduler.kt:73-80 | `cancelSnoozeOnly` 在贪睡时刻未到时被调用（每日闹钟早于贪睡触发场景）会取消闹钟但不清 StateFlow，主页显示残留 | 贪睡期间每日闹钟提前触发 | **Engineer**（建议） |

**QA 自理（非源码）**：
- 新增 JUnit 依赖与 `testOptions.unitTests.isReturnDefaultValues = true`（`app/build.gradle.kts`、`gradle/libs.versions.toml`）——原工程**没有任何单测依赖**，RssFeedParser 内部调用 `android.util.Log`，不放宽会在 JVM 测试中抛 "not mocked"。
- 可测性建议（记录，不阻塞）：`SyncEngine.mergeExisting` 为 private 且构造依赖 Android Context，JVM 单测不可达。建议工程师将其抽出为顶层纯函数（入参两个 VideoEntity），即可纳入单测。

---

## 4. 单元测试清单（本轮交付，共 67 个用例）

> ⚠️ 本机无 JDK/Android SDK，无法执行。**请在 Android Studio 中打开工程后运行 `app` 模块的 `test`（Unit Tests）任务验证**。所有测试均为纯 JVM 单测，不依赖 Android 框架（`isReturnDefaultValues` 已配置）。

### 4.1 SelectionPolicyTest（13 例）— app/src/test/java/com/goodmorning/alarm/alarm/
| # | 用例 | 断言要点 |
|---|---|---|
| 1 | 空候选集返回兜底 | FALLBACK、video=null、isFallback=true |
| 2 | 全部 localPath 为 null 返回兜底 | FALLBACK |
| 3 | localPath 空白串视为不可播放 | FALLBACK |
| 4 | 当天存在视频返回 TODAY | 命中当天条目 |
| 5 | 当天多条取发布时间最新 | today_new |
| 6 | 规则①优先于时间更新近的往日视频 | PRD §5.3 优先级 |
| 7 | 当天视频无本地文件不参与规则① | 降级 CACHED |
| 8 | 无当天视频返回缓存最新 CACHED | newest |
| 9 | publishDate 空串不参与当天判定 | PRD §5.4 |
| 10 | 唯一候选 publishDate 空串仍可播 | CACHED |
| 11 | 跨日边界（昨日 23:59 → 次日响铃） | CACHED |
| 12 | 乱序输入按发布时间倒序 | newest |
| 13 | publishTime=0 排最后 | known |

### 4.2 TimeUtilsTest（18 例）— app/src/test/java/com/goodmorning/alarm/util/
| # | 用例 | 断言要点 |
|---|---|---|
| 1-3 | localDate 本地自然日 | 格式、同日边界、与 java.time 一致（任意时区可移植） |
| 4-9 | nextDailyAt 严格未来 | 未到→今天；差 1ms→今天；恰好到点→明天（防当天二次响铃）；已过 1ms→明天；晚间注册次日；跨年滚动 |
| 10-14 | nextSyncAt 双时刻取近 | 04:00→05:30；恰 05:30→21:00；09:00→21:00；恰 21:00→次日 05:30；23:00→次日 05:30 |
| 15-17 | formatCountdown | 0/负数、<1 天、≥1 天带「N天」前缀 |
| 18 | formatCountdownHm | 分钟级格式化 |

### 4.3 RssFeedParserTest（30 例）— app/src/test/java/com/goodmorning/alarm/network/
- 标准解析 3 例：完整字段、`item` 键兼容、多条目顺序。
- pubDate 形态 10 例：13 位毫秒、10 位秒、未加引号 JSON 数字、ISO Z、ISO +08:00、无时区本地时间、`date_published` 别名、非法串记 0、缺失记 0、过短数字记 0。
- 直链提取 8 例：`<video src>`、带其他属性、单引号、douyinvod 兜底、aweme/v1/play 兜底、video 标签优先于兜底正则、description 空→null、无任何链接→null。
- 脏数据 9 例：非视频链接丢弃、link 缺失回退 url、link/url 均缺丢弃、title 缺省默认文案、非法 JSON、根节点为数组、items 空数组（含「为空」契约断言，固化 E5 耦合点）、缺 items/item 字段、items 非数组。

### 4.4 ConstantsTest（6 例）— app/src/test/java/com/goodmorning/alarm/util/
RSSHub 路由模板拼接（默认/自建实例）、同步时刻 05:30/21:00、贪睡 5/10/15 默认 10、渐强 30%/20s/500ms/40 步线性、缓存保留 3 条、每日与贪睡 requestCode 不冲突。

### 4.5 已写但无法在本机运行的说明
- **执行方式**：Android Studio → 项目打开后 `./gradlew test`（或右键 `app/src/test` → Run Tests）。
- **预期结果**：67/67 通过（所有断言均按源码现行正确行为与 PRD 口径编写；若失败，优先怀疑 E5 一类文案耦合或测试环境配置）。
- AlarmService/AlarmPlayer/AlarmScheduler/SyncEngine 因强依赖 Android 框架（Context/AlarmManager/ExoPlayer/Room），属 instrumentation/Robolectric 范畴，本轮以静态审查覆盖，未强行写伪测试。

---

## 5. 测试 Round 记录

- **Round 1（本轮）**：静态审查 6 大高风险模块 + 41 个 Kotlin 文件全量扫描 + 编写 67 个单元测试 + 测试基础设施补齐。发现 8 项源码问题（2 中 6 低/建议），全部路由 Engineer；测试代码自身问题 0（首轮交付，无自修复项）。
- **Round 2（待办）**：工程师修复 E1–E8 后，回归方式：① Android Studio 执行 67 个单测；② 针对 E1/E2 复查 AlarmPlayer 修改；③ 针对 E3/E4 复查 AlarmService 生命周期改动。按团队规则最多 2 轮，Round 2 仍失败的问题将以 Known Issues 形式归档。

---

## 6. 附：审查方法说明

- 逐行读源码并与 PRD §5（选片规则）、ARCHITECTURE.md §1.4/§1.5/§7（数据链路/关键机制/共享约定）交叉比对。
- 关键时序做桌面推演：设闹钟→同步→下载→响铃→贪睡→停止→续期；正常路径 / 断网 / 空库 / 坏文件 / 重启 / 双闹钟并发。
- 正则（VIDEO_SRC_REGEX / MP4_URL_REGEX）逐分支推演 + 单测固化典型输入。
- Manifest 逐权限、逐组件核对 Android 12/13/14 分版本要求。
- 未采纳工程师自查结论作为输入，全部独立复核（选片/续期/合并等核心结论与自查一致，但额外发现 E1–E8 为自查未覆盖项）。
