# Goodmorning · 每日早安

一个 Android 闹钟 App：**每天早上到点自动播放抖音博主「每日早安」的最新一期视频**，
把刷短视频的习惯变成一个稳定的晨间仪式 —— 不用打开抖音、不用找视频、不怕断网（本地缓存兜底）。

> 适用系统：Android 10+（在 Xiaomi HyperOS 3.0 上深度实测：息屏准时响铃、超级岛上岛）。
> 包名：`com.goodmorning.alarm`，当前版本见 [`goodmorning-alarm/CHANGELOG.md`](goodmorning-alarm/CHANGELOG.md)。

## 它解决什么问题

| 痛点 | 方案 |
|---|---|
| 早上手动去抖音找「每日早安」更新没有？ | 三档自动同步（05:30 / 12:00 / 21:00）+ 响铃现场轻量补拉，缓存无当天视频也能不等就出声 |
| 息屏 / Doze 下闹钟不响、哑火 | 全链路可靠性设计：精确闹钟 + 唤醒锁 + 起播看门狗 + 四级音频兜底（主音频 → 兜底铃声 → 系统铃声 → 蜂鸣） |
| 机房 IP 被抖音风控（403 签名） | 自建 `dyproxy` 数据代理，经 frp 隧道走住宅宽带出口；v4 起改用无头浏览器抓取抖音自有 API，彻底绕开签名 |
| 响铃时没有顺手控制入口 | 锁屏 / 通知栏 MediaStyle 媒体大卡：暂停 / 继续、拖动进度、停止、稍后提醒（贪睡），HyperOS 超级岛同款渲染 |

## 仓库结构

```
.
├── goodmorning-alarm/        # Android App（Kotlin + Compose + Media3/ExoPlayer）
│   ├── app/src/main/         #   源码：alarm / playback / sync / network / ui
│   ├── docs/                 #   PRD、架构、设计、QA 报告、使用说明书
│   ├── dyproxy/              #   数据代理（Node.js + Playwright，Docker 可部署）
│   │   └── frp/              #     frp 隧道部署包（住宅出口方案）
│   └── CHANGELOG.md          #   版本变更记录（逐条附 commit hash）
├── island-test/              # 独立实验工程：HyperOS 超级岛渲染验证
└── LICENSE                   # AGPL-3.0
```

## 构建

```bash
cd goodmorning-alarm
./gradlew assembleDebug        # 产物：app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # 本地单元测试（SelectionPolicy / TimeUtils / Constants）
```

要求：JDK 17、Android SDK 35。Windows 下请用 `gradlew.bat`（Git Bash 直跑 wrapper 会报
`GradleWrapperMain` 缺失，实为 cygpath 兼容问题）。

## 文档索引

| 文档 | 看这里 |
|---|---|
| **维护手册（技术栈/部署/历史 bug/迁移）** | [`MAINTENANCE.md`](MAINTENANCE.md) |
| 功能与使用方法 | [`goodmorning-alarm/docs/使用说明书.md`](goodmorning-alarm/docs/使用说明书.md) |
| 版本都改了什么 | [`goodmorning-alarm/CHANGELOG.md`](goodmorning-alarm/CHANGELOG.md) |
| 架构与模块划分 | [`goodmorning-alarm/docs/ARCHITECTURE.md`](goodmorning-alarm/docs/ARCHITECTURE.md) |
| 数据代理怎么部署 | [`goodmorning-alarm/dyproxy/README.md`](goodmorning-alarm/dyproxy/README.md)、[`dyproxy/DEPLOY.md`](goodmorning-alarm/dyproxy/DEPLOY.md) |
| frp 住宅出口方案 | [`goodmorning-alarm/dyproxy/frp/README.md`](goodmorning-alarm/dyproxy/frp/README.md) |

## 许可证

本项目以 [**GNU AGPL-3.0**](LICENSE) 发布。

AGPL-3.0 是强 copyleft 许可证：分发或修改本项目的衍生作品须以同一许可证开源；
**若将修改后的版本作为网络服务对外提供，同样必须公开其完整源码**（AGPL 特有的第 13 条）。
