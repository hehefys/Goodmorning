# frp 内网穿透部署 —— 让 dyproxy 从家宽出口访问抖音

## 为什么需要这个

抖音的风控插件 `ArgusSecurityPlugin` 对**机房 IP**（腾讯云等）会强制校验 `a_bogus` 签名，
而对**住宅宽带 IP** 完全免检。实测同一份 `dyproxy.cjs`：

| 出口 IP | 同款请求 |
|---------|---------|
| 腾讯云机房（81.70.52.12） | 403 `Signature Not Found` |
| 家宽（住宅） | **200**，正常返回作品列表 |

所以把 dyproxy 挪到家里跑，用 frp 反向映射回服务器。**`dyproxy.cjs` 一行都不用改**，
App 端数据源地址也不用改。

## 架构

```
   手机 App
      │  http://81.70.52.12:1200
      ▼
┌─────────────────────────────┐
│  腾讯云服务器 81.70.52.12    │
│  frps  监听 7000（隧道）     │
│        绑定 1200（对外）     │
└─────────────┬───────────────┘
              │  frp 隧道（家里主动连出去，家里不需要公网 IP）
              ▼
┌─────────────────────────────┐
│  家里那台机器                │
│  frpc  ← 连 81.70.52.12:7000 │
│  dyproxy 监听 127.0.0.1:1200 │
└─────────────┬───────────────┘
              │  出站请求（家宽 IP，免检）
              ▼
        www.douyin.com
```

关键点：**家里不需要公网 IP、不需要端口转发**，因为 frpc 是主动往外连的。

## 前提

- 家里那台机器必须 **7×24 开机**（闹钟场景的关键时刻是清晨，机器睡了就取不到最新作品）
- 家里需要 Node.js ≥ 18（或 Docker）
- 腾讯云安全组放行 **7000/TCP**（1200 应该早就开了）

---

## 一、服务器端

```bash
# 0) 先让出 1200 端口：停掉原来那个 dyproxy 容器
cd ~/dyproxy && docker compose down

# 1) 上传 frp 目录（本地执行）
#    scp -r frp ubuntu@81.70.52.12:~/frp

# 2) 生成口令并填配置
cd ~/frp
cp frps.example.toml frps.local.toml
openssl rand -hex 24     # ← 生成隧道口令，记下来，家里那台也要填同一个
openssl rand -hex 12     # ← 生成管理面板密码
nano frps.local.toml     # 替换两处 REPLACE_...

# 3) 启动
docker compose up -d
docker compose logs -f   # 看到 "frps started successfully" 即成功
```

> ⚠️ `frps.local.toml` 已被 `.gitignore` 排除，**不要提交到公开仓库**。

## 二、家里那台机器

### 方式一：Linux 虚拟机 —— 一键脚本（推荐）

如果家里那台机器是 Linux（含 VMware / VirtualBox / Hyper-V 里的 Ubuntu、Debian 虚拟机），
本目录的 `setup-linux-vm.sh` 会一次装好依赖、Node、frpc、**npm 依赖与 Chromium**，
并注册好 systemd 服务与开机自启：

```bash
# 1) 先把 dyproxy.cjs / package.json / package-lock.json / .env / frpc.local.toml
#    放进 /opt/dyproxy/
sudo mkdir -p /opt/dyproxy
#   传文件建议：VM 里 apt install openssh-server，
#   然后用 VS Code Remote-SSH 连上去直接拖拽（.env 有 6500 字符，别用终端粘贴）

# 2) 一键部署（会下载约 200MB 的 Chromium，这一步最慢）
sudo bash setup-linux-vm.sh
```

脚本自带校验：Cookie 是否完整、frpc token 是否还是占位符、必需文件是否齐 —— 缺什么会直接点名。

跳过下面「方式二」的手工步骤。

### 方式二：手工部署（非 Linux，或想自己控制每一步）

### 1. 装 frpc

```bash
cd /tmp
curl -LO https://github.com/fatedier/frp/releases/download/v0.71.0/frp_0.71.0_linux_amd64.tar.gz
tar xzf frp_0.71.0_linux_amd64.tar.gz
sudo mv frp_0.71.0_linux_amd64/frpc /usr/local/bin/
frpc -v
```

> ARM 机器（树莓派等）把 `linux_amd64` 换成 `linux_arm64`。

### 2. 准备目录

把这几样放到同一个目录里（先建 `cp frpc.example.toml frpc.local.toml` 再填 token）：

```
~/dyproxy/
├── dyproxy.cjs        ← 从仓库拷，代码不用改
├── package.json       ← npm 依赖清单（playwright）
├── package-lock.json  ← 依赖版本锁定
├── .env               ← Cookie，和服务器上那份是同一个
└── frpc.local.toml    ← auth.token 必须和服务器 frps.local.toml 一致
```

装依赖与浏览器（只需做一次，约 200MB，耗时较长）：

```bash
cd ~/dyproxy
npm install
npx playwright install chromium
# 若下载慢，可加国内镜像：
#   npm install --registry=https://registry.npmmirror.com
#   PLAYWRIGHT_DOWNLOAD_HOST=https://cdn.npmmirror.com/binaries/playwright npx playwright install chromium
```

### 3. 启动两个进程

```bash
cd ~/dyproxy

# 终端 A：先起 dyproxy
node dyproxy.cjs
# 期待：dyproxy v4.0 已启动：http://localhost:1200

# 终端 B：再起 frpc
frpc -c ~/dyproxy/frpc.local.toml
# 期待：start proxy success
```

## 三、验证

**在服务器上**（注意：此时 1200 已经不是容器的了，是 frps 转发的）：

```bash
curl -s http://127.0.0.1:1200/health
# {"ok":true,"service":"dyproxy","version":"4.0","port":1200,"mode":"auto","playwright":"available"}

curl -s "http://127.0.0.1:1200/douyin/user/MS4wLjABAAAAkme-Sn9GBLHkPFE6TSfhhmHbEfphTt7ZNL9BD14NWAneay8H7OxJQ05-CP9VgmSJ?format=json" | head -c 200
# 期待 200 且 {"title":"每日早安","items":[...]}
```

**重点**：这条一旦是 200，说明 **Cookie、浏览器、隧道三者都正常**，整条链路通了。
（首次约 5-25s，因为要拉起浏览器；之后 10 分钟内命中缓存，毫秒级返回。）

App 端：数据源地址保持 `http://81.70.52.12:1200` 不变，直接同步即可。

## 四、排错

| 现象 | 原因 |
|------|------|
| frpc 报 `connect to server error` | 服务器 7000 没放行（腾讯云安全组），或 frps 没起来 |
| frpc 报 `token in login doesn't match` | 两边 `auth.token` 不一致 |
| frpc 报 `port already used` | 服务器上 1200 被占用 —— 旧 dyproxy 容器没停干净，`docker ps \| grep 1200` 查一下 |
| frpc 显示 `start proxy success` 但请求 502 | 家里 dyproxy 没起来，或端口不是 1200 |
| 请求 403 | dyproxy 还在走机房出口 —— 检查家里那台的公网 IP 是不是住宅宽带（`curl ip.sb`） |

## 五、文件说明

| 文件 | 放哪 | 用途 |
|------|------|------|
| `frps.example.toml` | 服务器 | 服务端配置模板 |
| `frps.local.toml` | 服务器 | 服务端真实配置（**不入库**，需自己 cp 生成） |
| `frpc.example.toml` | 家里 | 客户端配置模板 |
| `frpc.local.toml` | 家里 | 客户端真实配置（**不入库**） |
| `docker-compose.yml` | 服务器 | 用容器跑 frps |
| `setup-linux-vm.sh` | 家里 | Linux 一键部署脚本（含 systemd 服务定义） |
| `pack.sh` | — | 生成可移植部署归档 tar.gz（产物落在 `../dist/`，已 gitignore） |

> 想评估「这台机器够不够跑」或「把整套搬到另一台机器」，看
> [`../DEPLOY.md`](../DEPLOY.md) —— 里面有实测环境数据、最小配置需求（含依据）、
> 换机迁移步骤和验收清单。
