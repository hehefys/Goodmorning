# dyproxy 部署与迁移手册

面向「**换一台机器重新部署**」或「**评估这台机器够不够跑**」的场景。

- 想了解**为什么**要把 dyproxy 放到家里跑 → 看 [`README.md`](README.md) 第一节
- 只想**按步骤操作** → 本文第五节

---

## 一、这套东西由什么组成

| 组件 | 跑在哪 | 作用 | 语言/形态 |
|---|---|---|---|
| **dyproxy** | 家里（VM/PC/NAS） | 把抖音博主作品列表转成 App 兼容 JSON | Node，单文件 |
| **Chromium** | 家里（与 dyproxy 同机） | 由 dyproxy 按需拉起，访问抖音网页并把数据交回 | 无头浏览器，约 200 MB |
| **frpc** | 家里（与 dyproxy 同机） | 主动连出去，建立反向隧道 | Go，单二进制 |
| **frps** | 云服务器 | 提供唯一公网入口，把 1200 端口流量转进隧道 | Go，Docker 容器 |

它们的关系：**App → 服务器:1200（frps）→ 隧道 → 家里:1200（dyproxy）→ 无头浏览器 → 抖音**。

关键性质：**家里那台不需要公网 IP、不需要路由器端口转发**，因为 frpc 是主动往外连的。

> **v4 起 dyproxy 会拉起一个无头 Chromium**（见 [`README.md`](README.md) 第一节）。
> 浏览器自带完整指纹与签名，因此**不再依赖「出口 IP 必须是住宅」** ——
> 这是把资源需求抬高的主要来源，评估机器时请按本文第三节的数值。

---

## 二、当前运行环境实况（2026-09-16 实测）

### 家庭端

| 项 | 值 |
|---|---|
| 系统 | Ubuntu 24.04 LTS（桌面版） |
| 架构 | x86_64 |
| 内核 | 7.0.0-30-generic |
| 主机名 | hehefys-VMware-Virtual-Platform |
| 虚拟化 | VMware（NAT 模式） |
| 内网 IP | 192.168.150.128/24 |
| **出口 IP** | 观测到 `183.198.228.147` 与 `36.143.16.83`（校园网多出口，会变）— **v4 的浏览器方案不依赖它** |
| CPU | 宿主共享 |
| 内存 | 7.7 GiB 总量，实测占用 **1.2 GiB**，交换区 3.6 GiB |
| 磁盘 | `/dev/sda2` 20 GB，清理后 **12 GB 已用 / 7.0 GB 可用**（v4 的 Chromium 另占约 200 MB） |
| 关键端口 | 1200（dyproxy 监听 127.0.0.1） |

### 服务器端

| 项 | 值 |
|---|---|
| 位置 | 腾讯云 `81.70.52.12` |
| 承担 | frps（`fatedier/frps:v0.71.0`，host 网络） |
| 端口 | 7000（隧道，需安全组放行）、1200（对外服务）、7500（管理面板，仅 127.0.0.1） |
| 资源占用 | 仅 frps 一个容器，CPU/内存近乎为零 |

---

## 三、最小配置需求

分「**最小可跑**」与「**推荐**」两档。数值依据在每项下方说明。

### CPU

| | 数值 | 依据 |
|---|---|---|
| **最小** | **1 核**（x86_64 或 arm64） | dyproxy 是单进程事件循环，frpc 只做字节流转发；任一时刻只有 App 一个客户端，无并发压力 |
| **推荐** | **2 核** | 4 个来源：dyproxy、frpc、系统更新（`unattended-upgrades`）、journald 落盘。单核在系统更新时会短暂卡顿 |

> 架构支持：`x86_64` 与 `aarch64/arm64`。`setup-linux-vm.sh` 自动识别并下载对应二进制。

### 内存

| | 数值 | 依据 |
|---|---|---|
| **最小** | **1 GB**（无桌面） | 实测拆解：系统 ≈300 MB + `node` **84 MB** + 无头 Chromium（5 进程）**226.6 MB** + `frpc` ≈20 MB ≈ **631 MB**；页面加载峰值再加 100–150 MB |
| **最小** | **3 GB**（有桌面 GNOME） | 上项 631 MB + GNOME 桌面 800 MB–1.2 GB ≈ 1.8 GB，留出峰值与系统更新余量 |
| **推荐** | **2 GB**（无桌面）/ **4 GB**（有桌面） | 当前 VM 分配 7.7 GB、实测只用 1.2 GB，余量充足 |

单项内存占用参考（**2026-09-16 实测**）：

| 进程 | 常驻内存 |
|---|---|
| Ubuntu 24.04 Server（无桌面） | 250–350 MB |
| Ubuntu 24.04 Desktop（GNOME） | 800 MB–1.2 GB |
| `node dyproxy.cjs` | **84 MB** |
| 无头 Chromium | **226.6 MB**（实测 5 个进程：主 123 + 渲染 43 + 其他 34 / 17 / 10） |
| `frpc` | ~20 MB |

> 浏览器是主要开销，但它**只在服务启动时拉起来一次并常驻复用**，不是每次请求都新开。
> 抓取时新开页面会有短暂峰值（+100–150 MB），页面关闭后回落。
>
> 若要更省内存：不必用桌面版，Ubuntu Server / Debian 最小安装即可，体积和占用都小一半。

### 磁盘

| | 数值 | 依据 |
|---|---|---|
| **最小** | **10 GB**（无桌面） | Ubuntu Server 最小安装 3.5 GB + Node 0.2 + **Chromium 0.2 + node_modules 0.05** + frpc 0.02 + 日志上限 0.5 + 系统更新余量 ≈ **6.5 GB**，取整并留余量 |
| **最小** | **20 GB**（有桌面） | Ubuntu Desktop 安装后即约 8–10 GB；实测本机 14 GB 已用（含 snap 与历史项目） |
| **推荐** | **25 GB** | 本机 20 GB 装完 Chromium 后仍够用，但余量偏紧（清理前一度只剩 5.5 GB） |

磁盘明细：

| 项 | 占用 |
|---|---|
| Ubuntu 24.04 Desktop（含 snap） | 8–10 GB |
| Node.js v24 安装到 `/usr/local` | ~200 MB |
| **Chromium（playwright 自带）** | **~200 MB**（首次部署时下载，位于 `/opt/dyproxy/browsers`） |
| **`node_modules`（playwright）** | **~50 MB** |
| `frpc` 单二进制 | ~15 MB |
| dyproxy 代码 | ~100 KB |
| **journald 日志** | 默认可达磁盘 10%。**建议在 `/etc/systemd/journald.conf` 设 `SystemMaxUse=500M`**，否则长期运行会吃掉 2 GB |

### 网络

| 项 | 要求 | 依据 |
|---|---|---|
| **出口 IP 类型** | **无要求**（v4 起） | 无头浏览器自带完整指纹与签名，实测在**被抖音要求签名**的移动蜂窝 IP 下同样成功。机房 IP 未实测，理论上也应可行 |
| 入站端口 | **不需要开任何端口** | frpc 主动外连，家里无公网 IP 也能工作 |
| 出站端口 | 443/TCP | 抖音、GitHub（frp）、nodejs.org 或 npmmirror（Node）、npm registry、Chromium 下载源 |
| 带宽 | **< 5 Mbps 足够** | 首次部署需下载约 200 MB 的 Chromium；日常单次同步响应约 650 KB，App 每日同步次数很少 |
| 延迟 | 无特殊要求 | 浏览器抓取首次约 5–25s，之后 10 分钟内命中缓存为毫秒级；App 端有 45s 总闸与本地缓存兜底 |
| **开机时长** | **7×24，不休眠** | 闹钟关键时刻是清晨 —— 机器睡了就取不到最新作品（见第七节） |

> ⚠️ **v4 起不再依赖出口 IP 的「身份」**（住宅 vs 机房）—— 浏览器自带的指纹与签名
> 足以通过抖音校验，这正是 v4 改造的目的。**代价是内存与磁盘需求上升**（见上两节）。
> 也因此，机器是不是虚拟机、装在哪张网络，都不再是成败的关键。
>
> 唯一对网络的新要求是**首次部署要能下载约 200 MB 的 Chromium**。
> 如果目标网络对 GitHub / npm 出口很差，setup 脚本已内置 npmmirror 镜像兜底；
> 仍失败时可先在别处装好，再把 `/opt/dyproxy/browsers` 整个目录拷过去。

---

## 四、依赖清单（版本锁定）

| 依赖 | 版本 | 来源 | 是否可换 |
|---|---|---|---|
| **Node.js** | `v24.19.0` | nodejs.org（失败则自动退回 `registry.npmmirror.com`） | 可。要求 **≥ 18**；锁定版本是为行为一致 |
| **playwright** | `^1.63.0` | npm（脚本已用 npmmirror 加速） | 可。`package-lock.json` 已锁定版本 |
| **Chromium** | 随 playwright 1.63.0 | playwright 自带下载（约 200 MB，落在 `/opt/dyproxy/browsers`） | 可换系统浏览器，设 `CHROME_PATH` 即可 |
| **frp** | `v0.71.0` | GitHub Releases `fatedier/frp` | **两端必须同大版本**（frps 与 frpc） |
| **Ubuntu/Debian 基础包** | — | apt | `curl`、`ca-certificates`、`xz-utils`，以及 Chromium 运行所需的系统库 |
| **Docker** | 任意近期版本 | — | 仅服务器端用（跑 frps）。也可不用 Docker，直接跑 frps 二进制 |

**npm 依赖只有 playwright 一个**（它自带依赖 playwright-core），由 `setup-linux-vm.sh` 自动安装。
`package-lock.json` 已入库，换环境时 `npm install` 会装出完全一致的版本。

---

## 五、从零到跑通（换机迁移）

假设你拿到一台新的 Linux 机器，要把它变成 dyproxy 宿主。全程约 15 分钟。

### 前置：先在服务器侧准备（若服务器不变，可跳过）

```bash
cd ~/frp
cp frps.example.toml frps.local.toml
TOKEN=$(openssl rand -hex 24)      # 记下来，家里要用同一个
PW=$(openssl rand -hex 12)
sed -i "s/REPLACE_WITH_A_RANDOM_TOKEN/$TOKEN/; s/REPLACE_WITH_A_DASHBOARD_PASSWORD/$PW/" frps.local.toml
docker compose up -d && docker compose logs --tail 10
```

腾讯云安全组放行 **7000/TCP**。

### 第 1 步 · 新机器上准备文件

把仓库 `dyproxy/` 目录下这几样放进 `/opt/dyproxy/`：

| 文件 | 说明 |
|---|---|
| `dyproxy.cjs` | 主程序（**代码不用改**） |
| `package.json` / `package-lock.json` | npm 依赖清单（playwright 版本已锁定） |
| `.env` | 内容为 `DOUYIN_COOKIE=<整串>`，见第六节怎么取 |
| `frpc.local.toml` | `cp frpc.example.toml frpc.local.toml` 后填 `auth.token`（= 服务器那个 TOKEN） |

> `node_modules/` 与 `browsers/` **不用拷** —— 脚本会自动装好。

> ⚠️ **`.env` 里是 6500 字符的 Cookie，绝对不要手工往终端里粘** ——
> 很多终端会静默截断到 1024 字符且不报错。用 scp 传文件，或用 VS Code Remote-SSH 打开文件粘贴。

### 第 2 步 · 一键部署

```bash
sudo mkdir -p /opt/dyproxy
# 把上面列出的文件放进去
sudo bash setup-linux-vm.sh
```

脚本依次做 **7** 件事：装 apt 依赖（自动注释失效的 cdrom 源）→ 装 Node v24.19.0 →
装 frpc v0.71.0 → 建低权用户 `dyproxy` 并收紧权限 →
**装 npm 依赖与 Chromium（约 200 MB，这一步最慢）** → 写两个 systemd 服务（`Restart=always` + 开机自启）→
启动并自检（含一次**真实拉取**测试）。

脚本自带前置校验，缺什么会直接点名，不会带着残缺配置启动。

### 第 3 步 · 验证（**顺序很重要，逐层排除**）

```bash
# 第 1 层：VM 上服务活着吗
systemctl status dyproxy frpc

# 第 2 层：VM 本地真实拉取 ← 这一层过了，说明 Cookie 与浏览器都正常
curl -s "http://127.0.0.1:1200/douyin/user/<sec_uid>?format=json" | head -c 120
# 首次需启动浏览器，约 5-25s；期待 {"title":"博主昵称","items":[...]}

# 第 3 层：隧道（服务器上跑，看有没有 new proxy [dyproxy] type [tcp] success）
cd ~/frp && docker compose logs --tail 30

# 第 4 层：公网路径（App 实际走的路）
curl -s http://81.70.52.12:1200/health
```

> `/health` **只证明进程活着，不校验 Cookie** —— 只跑它会得出错误结论。

### 第 4 步 · App 端

数据源地址填 `http://81.70.52.12:1200`，**保持不变**（本次架构变更没有改这个地址）。

### 第 5 步 · 收尾（别漏，否则会在某天清晨失效）

- 宿主 Windows/宿主 OS：**电源设置里睡眠改成「从不」**，并长期开机
- VMware：设置 VM「随宿主开机自启」
- 建议加 journald 日志上限：`/etc/systemd/journald.conf` 里 `SystemMaxUse=500M`

---

## 六、Cookie 怎么取

1. 浏览器打开 `https://www.douyin.com` 并登录（**建议专用小号**）
2. `F12` → Network → 刷新 → 任一请求 → Request Headers → 复制整串 `cookie:` 的值
3. 写入 `/opt/dyproxy/.env`，格式为单行 `DOUYIN_COOKIE=<整串>`

**必须有的 5 个字段**（缺 `ttwid` 会全部 502）：

| 字段 | 作用 |
|---|---|
| `ttwid` | 设备指纹，**必须有，且是整串的开头** |
| `sessionid_ss` / `sid_tt` | 登录态 |
| `odin_tt` | 设备令牌 |
| `passport_csrf_token` | CSRF 防护 |

**两个坑**（都踩过，见 `README.md` 第三节）：
- 复制不完整 —— 内容若以 `enter_pc_once=` 开头说明**开头被截掉了**
- 终端粘贴被静默截断 —— 长度参考 **6500 字符**，明显偏短就是被截了

---

## 七、日常运维

```bash
# 换 Cookie（最常见）
sudo nano /opt/dyproxy/.env      # 只改 DOUYIN_COOKIE 一行
sudo systemctl restart dyproxy

# 看状态
systemctl status dyproxy frpc
journalctl -u dyproxy -f         # 代理日志
journalctl -u frpc -f            # 隧道日志

# 服务器侧
cd ~/frp && docker compose logs --tail 30
```

### ⚠️ 运维铁律：宿主不能睡

dyproxy 跑在虚拟机里，**VM 跟着宿主走**。宿主一休眠/关机，隧道即断。
而闹钟场景的关键时刻是**清晨** —— 那正是机器最容易在睡觉的时候。

---

## 八、故障恢复对照表

| 现象 | 原因 | 处理 |
|---|---|---|
| 全部请求 502，日志 `HTTP 403 空响应` | Cookie 过期/被风控 | 重新取 Cookie，改 `.env`，重启 dyproxy |
| 换了 Cookie 还是 502 | 粘贴被截断 | 检查是否以 `ttwid=` 开头、长度是否 6500 左右 |
| 日志 `浏览器未捕获到作品列表（页面标题「…」）` | 页面加载超时，或被要求人机验证 | ① 调大 `BROWSER_TIMEOUT_MS`；② 设 `FETCH_MODE=browser` 跳过直连探测；③ 仍失败则说明该账号被要求验证 —— 在 VM 上用带界面模式打开一次主页确认 |
| 日志 `未安装 playwright` | npm 依赖没装好 | `cd /opt/dyproxy && npm install && npx playwright install chromium` |
| 首次请求很慢（10–25s） | 浏览器冷启动 + 页面加载 | 正常。10 分钟内再次请求命中缓存，毫秒级返回 |
| 服务重启后第一次又很慢 | 缓存是内存态，重启即清空 | 正常 |
| 日志出现 `直连失败…改用浏览器` | `auto` 模式的正常降级 | 无需处理。抖音放宽时会自动走回直连快路径 |
| frpc 日志刷 `connect to server error` | frps 没起，或 7000 未放行 | 查服务器 `docker compose logs`、查安全组 |
| frpc 报 `token in login doesn't match` | 两端 token 不一致 | 对齐服务器 `frps.local.toml` 的 `auth.token` |
| frpc 报 `port already used` | 服务器 1200 被占 | 旧 dyproxy 容器没停干净，`docker ps` 查 |
| App 502 但 VM 本地 curl 正常 | 隧道或公网侧问题 | 查第五步第 3、4 层 |
| VM 重启后服务没起来 | 未设开机自启 | `systemctl enable dyproxy frpc` |

---

## 九、归档与恢复

`pack.sh` 生成一个自包含的 tar.gz，用于把整套东西搬到新环境：

```bash
bash frp/pack.sh                    # 产物在 dist/ 下
```

**归档内容**：`dyproxy.cjs`、`package.json` / `package-lock.json`（依赖版本锁定）、
所有配置模板、部署脚本、本文档、版本清单（`MANIFEST.txt`，含每个文件的 SHA-256）。

**归档不含密钥**（`.env` 的真实 Cookie、`frpc.local.toml` 的 token）—— 这是刻意的：
密钥需要在新环境按第六节重新填写，或从旧机器单独拷贝。**不要为了省事把密钥塞进归档里到处传。**

---

## 十、验收清单（换机后逐项打勾）

- [ ] VM 上 `node -v` 输出 `v24.19.0`
- [ ] VM 上 `frpc -v` 输出 `0.71.0`
- [ ] `systemctl is-active dyproxy frpc` 两个都是 `active`
- [ ] `systemctl is-enabled dyproxy frpc` 两个都是 `enabled`（开机自启）
- [ ] npm 依赖与浏览器就位：`ls /opt/dyproxy/node_modules/playwright` 和 `ls /opt/dyproxy/browsers` 都非空
- [ ] `/health` 返回里 `"playwright":"available"`
- [ ] VM 本地 curl 返回 `{"title":"...","items":[...]}`（**不是 502**）—— 首次约 5–25s
- [ ] 紧接着再发一次同一请求，应毫秒级返回且响应头 `X-Cache: HIT`
- [ ] 服务器上 `curl http://127.0.0.1:1200/health` 返回 `{"ok":true,...}`
- [ ] 服务器上真实拉取返回 200
- [ ] App 端同步成功，能看到最新作品
- [ ] **宿主 OS 睡眠已设为「从不」**
- [ ] 断电重启演练：重启宿主 → 等 VM 起来 → 服务器上 curl 仍通
