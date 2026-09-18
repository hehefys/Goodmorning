# Goodmorning · 每日早安 —— 项目维护文档

> **适用范围**：仓库根目录下全部内容（Android App「每日早安」+ 数据代理 dyproxy + 实验工程 island-test）。
> **本文定位**：接手维护 / 故障排查 / 换机迁移时的**单一入口文档**。日常功能说明看
> [`goodmorning-alarm/docs/使用说明书.md`](goodmorning-alarm/docs/使用说明书.md)，版本明细看
> [`goodmorning-alarm/CHANGELOG.md`](goodmorning-alarm/CHANGELOG.md)。
> **更新时间**：2026-09-18 ｜ 对应版本 **2.2.1**（`3b4126e`）｜ 双远程：Gitee `hehefys/good-morning-every-day` + GitHub `hehefys/Goodmorning`

---

## 1. 系统全景

```
 Android App（手机）
   │  每日 05:30 / 12:00 / 21:00 三档同步 + 响铃现场轻量补拉
   ▼
 http://81.70.52.12:1200            ← App 设置页里配置的「数据源地址」，IP 从未变过
   │  腾讯云服务器（唯一公网入口）
   │  只跑 frps 容器，把 1200 端口流量转进隧道
   ▼ frp 隧道（frpc 主动连出，家宽无需公网 IP / 端口转发）
 家庭 VM（Ubuntu 24.04，/opt/dyproxy）
   │  dyproxy（Node + Playwright 无头 Chromium）+ frpc，均 systemd 托管
   ▼
 抖音网页版 API（住宅 IP 出口，免 a_bogus 签名风控）
```

**关键设计**：dyproxy 对 App 伪装成 RSSHub —— App 请求
`{数据源}/douyin/user/{sec_uid}?embed=1&format=json`，返回 JSON Feed（`items` +
`pubDate` ISO-8601 + `description` 内嵌 `<video src="直链">`）。App 端
`RssFeedParser` 兼容 `items`/`item`、pubDate 的秒/毫秒/ISO 三种形态。
**换数据源只需改 App 设置页一个 URL，App 代码无需变动。**

## 2. 技术栈清单

### 2.1 Android App（`goodmorning-alarm/`）

| 项 | 版本 | 用途 |
|---|---|---|
| Kotlin | 2.0.20 | 主语言 |
| AGP | 8.5.2 | Android 构建 |
| Gradle | 8.9（wrapper，走阿里云镜像） | 构建系统 |
| JDK | 17（`jvmTarget=17`） | 编译环境 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 29 | Android 10+ 可装，Android 15 实测 |
| Compose BOM | 2024.09.03（Material3 + material-icons-extended） | 全部 UI |
| Navigation Compose | 2.8.2 | 页面导航 |
| Room | 2.6.1（KSP 2.0.20-1.0.25 编译） | 视频缓存 / 博主历史 / 播放日志 |
| WorkManager | 2.9.1 | 后台任务（2 处引用） |
| DataStore Preferences | 1.1.1 | 设置持久化（⚠️ 曾因读盘挂起导致哑响铃，已有超时护栏） |
| Media3 / ExoPlayer | 1.4.1 | 主/副音频播放 |
| androidx.media | 1.7.0 | MediaSessionCompat + MediaStyle 媒体大卡（锁屏/超级岛） |
| OkHttp | 4.12.0 | 拉取（callTimeout 45s 总闸） |
| kotlinx-serialization-json | 1.7.2 | JSON Feed 解析 |
| kotlinx-coroutines | 1.8.1 | 播放状态机 / 同步调度 |
| JUnit4 | 4.13.2 | 单元测试 **86 例全绿**（2026-09-18 实测） |

规模：主源码 46 个 Kotlin 文件 / 约 7780 行；单测 867 行 / 86 例。
版本号规则：`versionCode = 主*10000 + 次*100 + 修订`（2.2.1 → 20201）；**小改升修订号、大改升次版本号**。
⚠️ 改版本须**三处同步**：`app/build.gradle.kts` + `Constants.APP_VERSION` + `docs/使用说明书.md`。

### 2.2 dyproxy 数据代理（`goodmorning-alarm/dyproxy/`）

| 项 | 版本 | 用途 |
|---|---|---|
| Node.js | ≥18（VM 实测 v24.19.0） | 运行 dyproxy.cjs（零编译，CommonJS 单文件） |
| playwright | ^1.63.0 + Chromium | 无头浏览器抓取抖音自有 API 响应，绕开签名风控（v4.0 核心方案） |
| frp | 0.71.0（frps + frpc） | 反向隧道：公网 1200 → 家里 VM 1200 |
| Docker | 任意近期版 | **仅服务器端跑 frps**（dyproxy 已不在容器里跑） |
| 系统 | 腾讯云 Ubuntu / 家庭 VM Ubuntu 24.04 | |

`FETCH_MODE`：`auto`（默认，先轻量 HTTP 失败再退浏览器）/ `browser` / `http`。
内存实测：node ~84MB + Chromium ~226MB；磁盘 ≥10GB。

## 3. 目录结构

```
.
├── README.md / LICENSE(AGPL-3.0) / MAINTENANCE.md（本文）
├── goodmorning-alarm/
│   ├── app/src/main/java/com/goodmorning/alarm/
│   │   ├── alarm/        # AlarmReceiver / BootReceiver / RingWakeLock / RingGuard / SelectionPolicy
│   │   ├── playback/     # AlarmService（响铃状态机 1300 行）/ AlarmPlayer（ExoPlayer 封装）
│   │   ├── sync/         # SyncEngine（拉取入库）/ SyncScheduler（三档调度）
│   │   ├── network/      # Http（OkHttp 封装）/ RssFeedParser（JSON Feed 兼容解析）
│   │   ├── data/         # prefs（Settings/DataStore）/ repo（Room）/ BloggerValidator
│   │   ├── ui/           # settings / guide（权限引导）/ 主界面
│   │   └── util/         # Constants（全局常量单一来源）/ TimeUtils / AppLogger / Permissions
│   ├── app/src/test/     # 86 例单测：SelectionPolicy / RssFeedParser / TimeUtils / Constants / BloggerValidator
│   ├── docs/             # PRD / DESIGN-V2 / ARCHITECTURE / QA 报告 / 使用说明书 / mermaid 图
│   ├── dyproxy/          # 数据代理（见 §4.3）
│   └── CHANGELOG.md      # 版本变更全记录（逐条附 commit hash）
└── island-test/          # 独立实验工程：HyperOS 超级岛渲染验证（392 行，媒体大卡的孵化地）
```

## 4. 部署架构与环境配置

### 4.1 腾讯云服务器 `81.70.52.12`（公网入口）

- 只跑 **frps**（Docker，`~/frp/docker-compose.yml`），配置 `frps.local.toml`。
- 端口：`7000`（frpc 连入，安全组放行）、`1200`（对外服务，只允许这一个 `allowPorts`）、
  `7500`（管理面板，**仅监听 127.0.0.1**，需 SSH 隧道访问）。
- 服务器上**已无 dyproxy 容器**（09-16 `docker compose down` 移除）。

### 4.2 家庭 VM（Ubuntu 24.04，宿主为 Windows）

- 目录 `/opt/dyproxy/`：`dyproxy.cjs`、`package.json`、`.env`、`frpc.local.toml`。
- 两个 systemd 服务（`frp/setup-linux-vm.sh` 一键创建，`Restart=always` + 开机自启）：
  `dyproxy.service`（本机 1200）、`frpc.service`。
- Node + frpc + npm 依赖 + Chromium 均由该脚本安装。

### 4.3 密钥与配置文件清单（⚠️ 均不入库，`.gitignore` 已排除）

| 文件 | 位置 | 内容 |
|---|---|---|
| `dyproxy/.env` | 本机 + VM `/opt/dyproxy/` | `DOUYIN_COOKIE`（抖音登录态，整串约 6500 字符，以 `ttwid=` 开头） |
| `dyproxy/frp/frpc.local.toml` | 本机 + VM | frps 地址、`auth.token` |
| `frps.local.toml` | 服务器 `~/frp/` | `auth.token`（与 frpc 一致）、面板密码 |

**备份现状**：2026-09-18 全量备份位于
`D:\AI Coding\Work_Buddy\backups\goodmorning-alarm-20260918-1515\`（262MB / 2387 文件，
含 `.git`、构建产物与上述密钥）——**含登录态凭据，严禁上传网盘 / 外发**。

### 4.4 App 端配置

- 数据源地址：设置页「数据源」→ `http://81.70.52.12:1200`（默认值是公共 RSSHub，需改）。
- 博主 sec_uid 硬编码在 `Constants.SEC_UID`（换博主 = 改常量 + 清缓存）。

## 5. 构建与测试（本机 Windows）

```powershell
# ⚠️ 用 PowerShell，不要 Git Bash（wrapper 会误报 GradleWrapperMain 缺失）
cd D:\AI Coding\Work_Buddy\2026-08-27-18-01-08\goodmorning-alarm
.\gradlew.bat assembleDebug          # APK → app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat testDebugUnitTest      # 86 例单测
```

- **构建卡死/文件锁**：`gradlew --stop` → 等 4s → 删 `app\build\intermediates\` 下
  `desugar_graph` 与 `project_dex_archive` → 重跑。`graph.bin 拒绝访问` 与
  `Unable to delete project_dex_archive` 是同一根因的两种表象。
- **改动是否真进 APK**：`unzip -p app-debug.apk "classes*.dex" | grep -qa "新字符串"`，
  别只看时间戳。

## 6. 使用说明（速览，详见使用说明书.md）

1. 首次安装 → 权限引导（精确闹钟 / 通知 / 全屏显示通知）→ 设置页填数据源地址。
2. 每天自动三档同步缓存最新视频；闹钟到点：副音频衬托（可选渐强）→ 主视频音频 →
   锁屏媒体卡可暂停/拖动/停止/稍后提醒。
3. **暂停语义**：只静音这一声，不结束后续闹钟；10 分钟无操作自动收尾。
4. **划掉通知**：直接停止本次响铃（通知内仍有显式停止/贪睡按钮）。
5. 断网兜底：同步失败时播本地缓存里最近一期；连缓存都没有则走四级音频兜底出声。

## 7. 历史 Bug 与修复记录（按时间序，详见 CHANGELOG）

| 日期 | 版本/提交 | 现象 | 根因 | 修复 |
|---|---|---|---|---|
| 08-31 | 2.1.1 `bd0238b` | 响铃异常后整场卡死 | 单点失败拖垮整场 | 协程级兜底降级铃声；停止/贪睡必定撤前台停服务 |
| 08-31 | 2.1.2 `06ab3a8` | 息屏 100% 哑火 | Receiver 未传 `triggerAt` → 服务自判重复；手动测试污染判重基准 | 窗口 60s→15s、判重水位与测试隔离 |
| 09-11 | 2.1.3 `fc6ba0d` | 同步无限拖延（12:01 发起无「同步结束」） | 代理半开连接滴字节，readTimeout 管不住 | OkHttp `callTimeout` 45s 总闸；调度 REPLACE→KEEP |
| 09-12 | 2.2.0 `0893d11` | 早上播的是别的博主旧视频 | dyproxy 对无效账号兜底返回**另一账号**的旧缓存 | App 端 24h 脏数据护栏：上游时间倒退超 24h 拒写 |
| 09-12 | 2.2.0 `e695773` | 响铃时没当天视频 | 缓存未及时同步 | 响铃现场轻量补拉 1~2 条，最多等 12s，不等就出声 |
| 09-17 | `e8890ce` | **哑响铃**：卫兵已置位却全程无声且不自愈 | `ringingGuard` 置位后起播协程在 DataStore/Room 挂起点静默挂死；兜底链路调同一个函数一起哑 | 起播看门狗 + `readSettingsOrLast()` 单步超时退上次成功值 |
| 09-17 | `c7e99a7` | 划掉通知不停播 | 原设计是「划掉重建通知」 | 用户拍板改「划掉即停止」；补兜底通知缺失的 deleteIntent |
| 09-17 | `6b76854` | 主音频永不播放 | ①看门狗只认 mainStarted，衬托期误判抢跑；②`fallbackEngaged` 拦截把主音频永久拦下；③Android 15+ 禁 ExoPlayer 读系统铃声 URI | 判据改「完全没声音」+8s；删拦截改显式 `stopToneFallback()`；系统铃声改 `android.media.Ringtone` |
| 09-17 | `35d3112` | **暂停吞掉后续所有到点**（09-17「闹钟没响」真因） | 暂停只静音不复位 `ringingGuard`，后续到点全落「重复忽略」分支 | 新到点且 `mediaPaused` → `forceRestartSession`；暂停=静音≠放弃 |
| 09-18 | `3b4126e` | 暂停场通知/媒体卡无限期驻留 | 暂停不结束响铃场 | 暂停 10 分钟无操作自动按「停止」收尾 |

### dyproxy / 基建侧

| 日期 | 事件 | 结论 |
|---|---|---|
| 09-16 | 服务器直连抖音 403 `Signature Not Found` | `ArgusSecurityPlugin` 按出口 IP 画像：机房/校园网/蜂窝一律要 `a_bogus` 签名，**住宅 IP 免检** |
| 09-16 | 逆向 `a_bogus` 失败 | 与抖音 JS 混淆版本强绑定，公开实现产出的签名不被接受 → **放弃逆向** |
| 09-16 | frp 住宅出口方案落地 | 腾讯云只跑 frps，家宽 VM 跑 dyproxy+frpc；`DEPLOY.md` 为迁移手册 |
| 09-17 | HTTP 直连仍被签名拦截（换出口后实测） | **v4.0 改 playwright 无头浏览器抓抖音自有 API 响应**，彻底绕开签名 |
| 09-16 | Cookie 静默截断 | 终端粘贴上限 1024 字符且不报错 → **必须 scp/编辑器传文件**，`restart.sh` 有长度拦截 |

### 教训三则（写代码前先读）

1. **看门狗/护栏的判据必须等价于「用户耳朵能听到」**，不能是内部标志位；
2. **加保护性拦截前必须确认它不会把正常路径一并拦掉**（`fallbackEngaged` 回归）；
3. **状态机里任何「只静音不结束」的分支都要检查卫兵/状态位是否需要复位**。

## 8. 服务器到期迁移方案（`81.70.52.12` 失效时）

### 8.1 影响面

- **App 拉取全部失败**（连不上 1200）→ 自动走本地缓存兜底，**响铃不受影响**，
  但视频会停在缓存那期。
- 需要恢复的事：新 VPS 上重建 frps → 家里 frpc 改指向 → App 数据源 URL（若公网 IP 变了）。

### 8.2 迁移步骤（新 VPS，约 30 分钟）

1. **备新 VPS**（任意国内云，1C1G 足够——只跑 frps）：装 Docker，
   `git clone` 本仓库取 `goodmorning-alarm/dyproxy/frp/{docker-compose.yml,frps.example.toml}`。
2. `cp frps.example.toml frps.local.toml`，**token 填旧值**（在本地
   `dyproxy/frp/frpc.local.toml` 里，两边一致即可，不必换）。
3. 安全组放行 `7000/TCP + 1200/TCP`。
4. `docker compose up -d`，日志确认 `new proxy [dyproxy] type [tcp] success`。
5. 家里 VM 改 `/opt/dyproxy/frpc.local.toml` 的 `serverAddr` → `sudo systemctl restart frpc`。
6. **若新公网 IP ≠ 旧 IP**：App 设置页把数据源地址改成 `http://<新IP>:1200`。
7. 四层验证（顺序不能乱，详见 `DEPLOY.md` §五.第 3 步）：
   VM 服务活着 → VM 本地真实拉取 → 隧道注册 → 公网路径 `curl /health` + 真实拉取。

### 8.3 注意事项

- 迁移期间 App **无需停用**：缓存兜底 + 响铃现场补拉失败也照常出声。
- dyproxy 本身**不在服务器上**，服务器只是转发器——重装成本极低，真正的状态在家里 VM
  和 Cookie。
- 若家庭宽带出口 IP 变化导致 403（罕见，住宅 IP 一般免检）：重启 dyproxy 即可重开浏览器。

### 8.4 备选方案（服务器彻底不想续了）

| 方案 | 代价 | 说明 |
|---|---|---|
| 家宽 DDNS 直连 | 需家庭宽带有公网 IP（多数运营商默认不给），且 80/443 被封但 1200 通常可用 | 免掉服务器，但 IP 变动需 DDNS + App 手动改地址 |
| Cloudflare Tunnel | 免费额度内零成本，但国内直连延迟高、视频 CDN 链接与出口无关不受影响 | 折中，配置复杂度中等 |

## 9. 日常运维铁律

1. **宿主机不能睡**：家庭 VM 宿主（Windows）睡眠 = 隧道断 = App 拉不到新视频
   （缓存仍能响）。电源计划设「永不睡眠」。
2. **换 Cookie**：scp 传文件或编辑器改 `/opt/dyproxy/.env`（⚠️ 终端粘贴会静默截断到
   1024 字符）；改完 `sudo systemctl restart dyproxy`，再 curl 本机真实拉取验证。
3. **token 与面板密码另存一份**（密码管理器/离线纸条）——只存在两台机器的 local.toml 里，
   机器一挂就找不回。
4. **改版本号三处同步**（§2.1 末）；发版后 CHANGELOG 逐条补 commit hash。
5. 双远程推送前：**先 fetch 两端**（Gitee 网页端改动会造成分叉）、公开仓库按提交区间
   做密钥审计、逐条推并立刻比对哈希。

## 10. 已知技术债与清理候选（2026-09-18 审计）

> 以下均为**建议**，删除前请二次确认；标记 ✅ 的无风险，⚠️ 的有取舍。

| 项 | 位置 | 说明 | 风险 |
|---|---|---|---|
| ✅ ~~`.scratch/` 临时笔记~~ **已清理** | `goodmorning-alarm/.scratch/`（2 个 md） | 2026-09-18 已删除；git 历史可回看（`git log -- goodmorning-alarm/.scratch`） | 无 |
| ✅ ~~21 条未引用字符串~~ **已清理** | `strings.xml`（V1/V2 旧响铃界面遗留） | 2026-09-18 已删除；删除前全仓 64 个源文件复核零引用、无 `getIdentifier` 动态取串 | 无 |
| ⚠️ Docker 部署路线（旧架构） | `dyproxy/{Dockerfile,docker-compose.yml,restart.sh}` | 服务端已改「裸 Node + systemd」（setup-linux-vm.sh），容器方案仅剩文档价值；README 已标注「服务器上现已无 dyproxy 容器」 | 若日后想回容器部署则有用；删则建议连 README §对应行一起改 |
| ⚠️ `island-test/` 实验工程 | 仓库根 | 超级岛渲染验证（392 行），媒体大卡孵化自它；现功能已并入主 App，且**无任何文档引用** | 留作渲染行为参照（HyperOS 版本升级时可复测）；移出仓库则丢这段历史 |
| ⚠️ 设计文档整体过时 | `docs/{ARCHITECTURE,PRD,DESIGN-V2,QA-REPORT,QA-REPORT-R2,TECH-STACK-AND-LEARNING-PATH}.md` + 2 个 mermaid 图 | 均为 08-27（v1.0 时代）产物：ARCHITECTURE **0 处**提及 dyproxy/frp/看门狗/暂停语义；TECH-STACK 数字停在 6636 行/41 文件/77 例（实际 7780/46/86），未覆盖 playwright、androidx.media、AGPL；QA 报告停留在首轮 | 若定位是「历史决策记录」可原样保留；若当「现状说明」会误导 → 建议在各文件头部加「⚠️ 快照文档，现状见 MAINTENANCE.md」横幅，或重写 ARCHITECTURE |
| ✅ 依赖零冗余 | `gradle/libs.versions.toml` 22 个别名、`package.json` 仅 playwright | 逐个核对均有引用，无未使用依赖 | — |
| ✅ 无死函数/无 TODO 残留 | `app/src/main` | 全量 private fun 引用扫描，无孤儿；TODO 命中均为 `StepStatus.TODO` 枚举值 | — |
| ✅ 测试告警 1 处 | `RssFeedParserTest.kt:269` | `Check for instance is always 'true'`（编译警告，非失败） | 无 |

## 11. 文档索引

| 文档 | 内容 | 现状 |
|---|---|---|
| `MAINTENANCE.md`（本文） | 技术栈 / 部署 / 历史 bug / 迁移 / 运维 | ✅ 2026-09-18 |
| `goodmorning-alarm/CHANGELOG.md` | 版本变更全记录 | ✅ 随版本更新 |
| `goodmorning-alarm/docs/使用说明书.md` | 面向使用者的功能说明 | ✅ 2.2.1 |
| `goodmorning-alarm/dyproxy/README.md` | dyproxy 架构 / 排查顺序 / 运维 | ✅ v4.0 |
| `goodmorning-alarm/dyproxy/DEPLOY.md` | 换机迁移手册 + 最小配置 | ✅ |
| `goodmorning-alarm/dyproxy/frp/README.md` | frp 隧道部署 | ✅ |
| `goodmorning-alarm/docs/ARCHITECTURE.md` 等 | v1.0 时代设计快照 | ⚠️ 过时，见 §10 |
