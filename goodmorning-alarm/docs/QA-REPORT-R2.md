# QA 测试报告 Round 2：每日早安音频闹钟（goodmorning-alarm）

- **QA**：严过关（Yan，software-qa-engineer）
- **日期**：2026-08-27
- **轮次**：Round 2（回归验证 E1–E8）
- **验证口径**：本机无 JDK / Android SDK，无法执行 Gradle 构建与设备测试。本轮以**代码级静态复核 + 逻辑推演 + 测试用例有效性复查**为口径（同第 1 轮）。E7 的编译阻断结论基于 Android 资源引用语义（`R.string.xxx` 引用不存在的资源必然编译失败）推演。
- **结论速览**：E1–E6、E8 共 **7 项修复真实落地，通过**；**E7 不通过**——工程师补了引导页第 5 步 UI 与 ViewModel/检测逻辑，但 **strings.xml 漏加 2 个字符串资源**（`guide_step_full_screen` / `guide_step_full_screen_why`），PermissionGuideScreen.kt 引用后**整个 app 编译失败（P0 阻断）**。回归抽查 4 个单测模块：3 个未改动、1 个（RssFeedParser）改动但测试兼容，**测试无需更新**。
- **交付判定**：**需返修**（仅 E7；返修内容极小，补 2 条字符串即可）。

---

## 1. E1–E8 逐项复核

### E1 AlarmPlayer 渐强 pause/resume —— ✅ 通过

复核点 | 证据（AlarmPlayer.kt）
---|---
渐强起始墙钟记录 | `FadeWindow(startedAtElapsed, totalMs)`（:93），`playMedia` 中 `FadeWindow(SystemClock.elapsedRealtime(), FADE_DURATION_MS)`（:143）
resume 从当前音量按剩余窗口重建 | `resume()` → `player.play()` + `resumeVolumeFadeIfNeeded()`（:115-118）；后者 `elapsed = elapsedRealtime() - startedAtElapsed`、`remainingMs = totalMs - elapsed`，`startVolumeFade(fromVolume = player.volume.coerceAtLeast(FADE_START), durationMs = remainingMs)`（:176-192）
暂停过久直接补齐 100% | `remainingMs <= 0` 分支 `player.volume = 1f` 并清 `fadeWindow`（:181-186）
协程取消无泄漏 | `fadeJob?.cancel()` 覆盖 pause(:111)/resume(:180)/stop(:121)/release(:128)/playMedia(:138) 全部路径；`fadeJob` 在 `startVolumeFade` 重新赋值，无悬挂引用
播放状态机与渐强生命周期一致 | `stop/release` 清 `fadeWindow`（:122/:129）；渐强自然完成清 `fadeWindow`（:167）；非渐强播放 `fadeWindow=null` → resume 直接 return 无副作用；协程在 `delay` 后检查 `isActive`（:163），取消不写音量

推演：渐强窗口内暂停（如音量 0.5）→ resume 以 0.5 为起点、剩余窗口时长续算 → 最终必然到达 100%；暂停超过 20s 窗口 → 直接补齐 100%。E1 修复真实落地。

### E2 ExoPlayer setWakeMode + release —— ✅ 通过

- `ExoPlayer.Builder(context).setWakeMode(C.WAKE_MODE_LOCAL)`（AlarmPlayer.kt:58）✅
- release 路径：`fadeJob.cancel()` → `scope.cancel()` → `player.removeListener` → `player.release()`（:127-133）；wakeLock 由 ExoPlayer 内部托管，`release()` 时自动释放，无自管 wakeLock 泄漏面 ✅

### E3 AlarmService 双响铃竞态 —— ✅ 通过（附 1 项低风险观察）

- `ringingGuard = AtomicBoolean(false)`（AlarmService.kt:84）✅
- `onStartCommand` 中 ACTION_RING 分支入口 `ringingGuard.compareAndSet(false, true)` **同步原子置位**（:125），重复 ACTION_RING 直接忽略（:128）✅
- 复位：`handleStop`（:263）/ `handleSnooze`（:284）`ringingGuard.set(false)`，均在 `serviceScope.launch` **之前同步执行**；复位先于下一轮响铃注册，无错序 ✅
- 观察项（低风险，不阻断）：`selectAndPlay` 协程体内若 Room 查询/播放器调用抛出未捕获异常，`ringingGuard` 将卡在 true 导致后续无法再响铃（无 try/finally 或 CoroutineExceptionHandler 兜底）。正常路径不会触发；建议工程师后续补一个协程级异常兜底（非本次返修项）。

### E4 START_NOT_STICKY + null intent —— ✅ 通过

- `onStartCommand` 统一 `return START_NOT_STICKY`（AlarmService.kt:133）✅
- `intent == null` 分支：log + `stopSelf()` + `START_NOT_STICKY`，**仅保活不重播**（:112-116）；`startForegroundCompat()` 在任何分支前执行，满足前台服务契约 ✅

### E5 SyncEngine 强类型错误分类 —— ✅ 通过

- `sealed class SyncError`：`Network` / `Parse` / `Empty` 三子类（SyncEngine.kt:28-32）✅
- `sync()` 中 `when(e)` 分类 NETWORK/PARSE/EMPTY，**完全替换**旧 `contains("为空")` 文案匹配（:75-79）✅
- `fetchAndParse` 抛出点归类（:160-186）：HTTP 非成功/空响应体 → Network；`items.isEmpty()` → Empty（:169-171）；`EmptyFeedException` → Empty（:176-178）；`RssParseException` → Parse（:179-180）；`IOException` → Network（:181-182）；兜底 Exception → Network（:183-184）。归类合理 ✅
- catch 顺序正确：`EmptyFeedException`（子类）在 `RssParseException`（父类）之前，EMPTY 不会被 PARSE 吞掉 ✅
- `SyncResult(ok, availableCount, msg)` 结构未变；调用方 SyncWorker（:39-71 用 `result.ok/result.msg`）、SettingsViewModel（:109 仅触发 `sync()` 不消费返回字段）**兼容** ✅
- `availableCount` 语义未变：成功路径按 `localPath` 非空且文件存在统计（:141）；解析失败路径仍 `finish(false, 0, ...)`（:80）——第 1 轮已记录的 UI 展示级备注（本地有缓存但显示 0），非本项修复范围，不阻断。

### E6 四级兜底链 + ToneGenerator 生命周期 —— ✅ 通过

- 兜底链实现与注释一致：本地 mp4（AlarmService.kt:158）→ `RingtoneManager(TYPE_ALARM)`（:181）→ `TYPE_RINGTONE`（:182）→ `ToneGenerator` 蜂鸣（:183-185 进入）✅
- `ringtoneAttempted` 防“铃声失败→错误→再铃声”无限循环（:91/:183/:187）✅
- ToneGenerator 生命周期**成对**：创建（try/catch，:209-215）→ `startTone(TONE_PROP_BEEP, 800)` 循环（:217-222）→ `stopTone()` + `release()`（`stopToneFallback`，:234-244）；释放点覆盖 `onDestroy`(:137) / `handleStop`(:261) / `handleSnooze`(:282) / `playToneFallback` 开头(:208) ✅
- 循环参数合理：`TONE_BEEP_DURATION_MS=800 < TONE_BEEP_PERIOD_MS=1500`（Constants.kt:44-46），800ms 响 + 700ms 停，不重叠、可持续唤醒；ToneGenerator 创建失败记录并 handleStop（:211-214）✅

### E7 全屏显示通知（FSI）权限引导 —— ❌ 不通过（编译阻断 P0）

检测/引导逻辑本身全部到位：

复核点 | 证据
---|---
canUseFullScreenIntent 主路径（API 34） | `activityManager.canUseFullScreenIntent()`，runCatching 包裹（Permissions.kt:117-124）✅
AppOps 降级 | `unsafeCheckOpNoThrow(OPSTR_USE_FULL_SCREEN_INTENT, uid, pkg)`，MODE_IGNORED/MODE_ERRORED 判否（:125-135）✅
未知状态按已授权 | `getOrDefault(true)` / `?: return true`（:123/:127/:135）✅
API < 34 直接放行 | `SDK_INT < UPSIDE_DOWN_CAKE → true`（:118）✅
授权页跳转 | `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`，API<34 或不可解析返回 null（:142-149）✅
ViewModel 联动 | `GuideState.fullScreenIntentGranted`（:27）+ `refresh()` 检测（:45）✅
引导第 5 步 UI | PermissionGuideScreen.kt:162-177，`fullScreenIntent != null` 才显示，与 `state.fullScreenIntentGranted` 联动 ✅
ON_RESUME 刷新 | DisposableEffect + LifecycleEventObserver（:61-67）✅
步数/进度一致 | UI 无显式“x/5”步数指示器，无旧 4 步文案残留，无步数不一致问题 ✅

**阻断缺陷**：PermissionGuideScreen.kt:164-165 引用 `R.string.guide_step_full_screen` 与 `R.string.guide_step_full_screen_why`，但 **strings.xml 未定义这两个资源**（已全文 grep 确认，全工程仅此两处引用、无任何 values 变体定义）。Android 资源编译（AAPT2）不会生成对应 R 字段 → Kotlin 编译报 unresolved reference → **整个 app 无法构建**。这是 E7 修复引入的回归，属 P0 阻断，必须返修。

- 位置：`app/src/main/res/values/strings.xml`（缺两条 `<string name="guide_step_full_screen">` / `<string name="guide_step_full_screen_why">`）
- 复现条件：任何一次 Gradle 构建（`./gradlew assembleDebug` / `./gradlew test` 前的编译阶段）

### E8 cancelSnoozeOnly 无条件清除 —— ✅ 通过

- `cancelSnoozeOnly()` 无条件 `snoozeUntilMillis.value = null`（AlarmScheduler.kt:77-80），注释明确“无条件清除避免主页残留”✅
- 主页消费安全：MainViewModel `combine` 中 `snoozeUntil?.takeIf { it > now }`（MainViewModel.kt:64），即使值残留也会被时间过滤；无条件清除后主页立即不再显示“稍后提醒中”✅
- 响铃页不消费该值：RingingActivity/RingingViewModel 仅提供“稍后提醒”按钮，不读取 `snoozeUntilMillis`（已 grep 确认）→ 无条件清除**不引入新问题** ✅

---

## 2. 回归抽查（4 个单测模块）

| 模块 | 是否被 E1–E8 修复改动 | 单测影响 | 结论 |
|---|---|---|---|
| SelectionPolicy.kt | 否（逻辑与第 1 轮一致） | SelectionPolicyTest（13 例）断言全部仍成立 | ✅ 无需更新 |
| TimeUtils.kt | 否 | TimeUtilsTest（18 例）断言全部仍成立 | ✅ 无需更新 |
| Constants.kt | 是（仅**新增** TONE_VOLUME/TONE_BEEP_DURATION_MS/TONE_BEEP_PERIOD_MS，E6 相关） | ConstantsTest（6 例）只断言旧常量，未受影响 | ✅ 无需更新 |
| RssFeedParser.kt | 是（新增 `EmptyFeedException : RssParseException` 子类，E5 相关） | 旧用例 `catch (RssParseException)` 捕获子类仍通过；测试文件已含新增类型化用例 `items空数组抛EmptyFeedException_类型化归入EMPTY`（RssFeedParserTest.kt:262-272），共 31 例全部兼容 | ✅ 无需更新 |

补充说明：
- RssFeedParserTest 中 `items空数组抛含为空的RssParseException_对应EMPTY错误码`（:250-259）的**注释**已过时（称 SyncEngine 按 message 区分 EMPTY/PARSE），但断言本身（catch 父类 + message 含“为空”）仍通过，不影响执行。建议后续清理注释，不阻塞。
- 单测总数：13 + 18 + 31 + 6 = **68 例**（较第 1 轮 67 例 +1，因 RssFeedParserTest 新增 1 例类型化断言）。

---

## 3. 残留问题 / 回归清单

| # | 严重度 | 文件:行 | 问题 | 复现条件 | 路由 |
|---|---|---|---|---|---|
| R1（E7 残留） | **P0 阻断** | res/values/strings.xml（缺 2 条）<br>PermissionGuideScreen.kt:164-165 | `R.string.guide_step_full_screen` / `R.string.guide_step_full_screen_why` 未定义却被引用 → 编译失败 | 任何 Gradle 构建 | **Engineer（返修）** |
| O1（观察） | 低 | AlarmService.kt:146-172 | `selectAndPlay` 协程无异常兜底，若 DB/播放器调用抛未捕获异常，`ringingGuard` 卡 true 无法再响铃 | 极端异常路径 | Engineer（后续加固，非本次返修） |

**未发现其他 E1–E6/E8 修复引入的回归。**

---

## 4. Round 记录

- **Round 1**：静态审查 + 67 个单测，发现 E1–E8（2 中 6 低/建议），路由 Engineer。
- **Round 2（本轮）**：工程师声称已修复，独立代码级复核 E1–E8 + 回归抽查。结论：**7 项通过（E1–E6、E8），1 项不通过（E7，编译阻断 P0）**；回归抽查 4 模块测试无需更新。
- **Round 2 后**：E7 返修内容极小（补 2 条字符串资源），修复后无需第 3 轮全量回归——QA 建议工程师补完字符串后，由主理人确认构建通过即可合入（或按团队规则以本报告为最终 Known Issue 归档）。

---

## 5. 附：验证口径声明

本报告结论基于**代码级静态复核 + 逻辑推演 + 测试用例有效性复查**（本机无 JDK/Android SDK，无法实际执行 Gradle 构建与 68 个单测）。E7 编译阻断为 Android 资源引用语义的确定性推演（引用不存在的 `R.string` 必然编译失败）；若构建环境允许，建议在 Android Studio 中执行 `./gradlew test` 与 `assembleDebug` 做最终确认。
