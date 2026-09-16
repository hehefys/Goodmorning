# dyproxy — 抖音用户视频代理（「每日早安」App 数据源）

把指定抖音博主的最新作品列表转成 App 兼容的 JSON Feed。纯 Node 直连（登录 Cookie + UA/Referer），无第三方依赖。

**当前版本 v3.1**：账号一致性校验（拒绝返回错误账号数据）/ 上游 12s 硬超时 / 根节点返回博主昵称 / X-Fetch-At 响应头。

---

## 一、当前架构（2026-09-16 起）

```
   手机 App
      │  http://81.70.52.12:1200          ← App 端配置，从未变过
      ▼
┌───────────────────────────────┐
│ 腾讯云 81.70.52.12             │
│   只跑 frps（Docker）          │
│   绑定 1200 ← 隧道转发          │
└───────────────┬───────────────┘
                │ frp 隧道（家里主动连出去，家宽无需公网 IP）
                ▼
┌───────────────────────────────┐
│ 家里 Ubuntu VM（需 7×24 开机）  │
│   dyproxy  监听 127.0.0.1:1200 │
│   frpc     连 81.70.52.12:7000 │
└───────────────┬───────────────┘
                │ 出口 = 家宽 IP
                ▼
         www.douyin.com
```

### 为什么不在服务器上直接跑

抖音的风控插件 `ArgusSecurityPlugin` 对**机房 IP**（腾讯云等）会额外要求 `a_bogus` 签名，
缺失即返回 `403 Blocked by ArgusSecurityPlugin Signature Not Found`；而对**住宅宽带 IP** 完全免检。

实测同一份 `dyproxy.cjs`、同一条请求：

| 出口 IP | 结果 |
|---|---|
| 腾讯云机房 | 403 `Signature Not Found` |
| 家宽 | **200**，正常返回作品列表 |

把 dyproxy 挪到家里跑、用 frp 把端口映射回服务器 —— **业务代码一行未改**，
且以后抖音再改签名算法也不会影响本方案。

> 另一条思路是逆向 `a_bogus` 签名算法。已评估并放弃：该算法与抖音的 JS 混淆版本强绑定
> （如 `1.0.1.19-fix.01`），平台一改版即失效，属于长期维护负担；公开参考实现多为教学复刻，
> 实测其产出的签名不被服务端接受。

**完整部署步骤见 [`frp/README.md`](frp/README.md)。**

---

## 二、环境变量

`dyproxy.cjs` 优先读环境变量；**环境变量不存在时自动读取同目录的 `.env`**，
所以裸 Node 场景直接 `node dyproxy.cjs` 即可，无需导出任何变量。

| 变量 | 必填 | 说明 |
|------|------|------|
| `DOUYIN_COOKIE` | ✅ | 抖音网页版登录 Cookie（整串）。**过期后所有请求 502** |
| `PORT` | ❌ | 监听端口，默认 `1200` |
| `SEC_UID` | ❌ | 默认博主 sec_uid（仅文档用途，App 端请求的 URL 已自带） |

## 三、获取 Cookie

1. 电脑浏览器打开 `https://www.douyin.com` 并登录（**建议专用小号**）
2. `F12` → Network（网络）→ 刷新页面 → 找任意请求 → Request Headers → 复制整串 `cookie:` 的值
3. 写进 VM 上的 `/opt/dyproxy/.env`

### ⚠️ 最容易踩的两个坑

**坑 1：复制不完整。** 整串 Cookie 以 `ttwid=` **开头**。如果粘出来的内容以 `enter_pc_once=`、
`s_v_web_id=`、`UIFID_TEMP=` 之类开头，说明**开头被截掉了** —— 缺 `ttwid` 时抖音直接拒绝，
表现为所有请求 502。粘贴后务必确认这 5 个字段都在：

| 字段 | 作用 |
|------|------|
| `ttwid` | 设备指纹，**必须有**，且排在整串最前面 |
| `sessionid_ss` / `sid_tt` | 登录态 |
| `odin_tt` | 设备令牌 |
| `passport_csrf_token` | CSRF 防护 |

长度参考：完整整串约 **6500 字符**。

**坑 2：别往终端里粘。** 整串 6500 字符，很多终端（尤其网页版终端）会**静默截断到 1024 字符**
且不报错。**务必用 VS Code Remote-SSH 打开文件粘贴，或 scp 传文件**，不要在 `nano` 里直接粘。

## 四、部署

### 全新部署（Linux 设备 / 虚拟机）

`frp/setup-linux-vm.sh` 是一键脚本：装 Node + frpc、建低权用户、写 systemd 服务、
设开机自启、跑自检。详见 [`frp/README.md`](frp/README.md)。

```bash
# ① 把 dyproxy.cjs / .env / frpc.local.toml 放到 /opt/dyproxy/
# ② 一键部署
sudo bash setup-linux-vm.sh
```

### 手动 / 无 Docker 场景

```bash
# 要求 Node.js ≥ 18
cd /opt/dyproxy
node dyproxy.cjs
# 常驻建议交给 systemd，不要用 nohup
```

## 五、换 Cookie（日常操作，最常见）

**在 VM 上执行**（不再需要重建容器）：

```bash
sudo nano /opt/dyproxy/.env      # 只改 DOUYIN_COOKIE 这一行
sudo systemctl restart dyproxy
```

重启后自检：

```bash
# ① 长度（应接近 6521，含换行）
grep -m1 '^DOUYIN_COOKIE=' /opt/dyproxy/.env | sed 's/^DOUYIN_COOKIE=//' | wc -c

# ② 五个关键字段
for k in ttwid sessionid_ss sid_tt odin_tt passport_csrf_token; do
  grep -q "$k=" /opt/dyproxy/.env && echo "  ✓ $k" || echo "  ✗ 缺 $k"
done

# ③ 真实拉取 —— 唯一能证明 Cookie 有效的检查
curl -s "http://127.0.0.1:1200/douyin/user/<sec_uid>?format=json" | head -c 120
# 期待 {"title":"博主昵称","items":[...]}
```

> ⚠️ `/health` **只证明进程活着，不校验 Cookie**。只跑 `curl /health` 看到 200 就以为成功，
> 是最容易误判的一步。

Cookie 过期不会让服务崩溃，只会所有请求 502；App 端有 45s 总闸与本地缓存兜底，不影响响铃。

## 六、验证

```bash
# ① 健康
curl -s http://127.0.0.1:1200/health
# 期待 {"ok":true,"service":"dyproxy","version":"3.1","port":1200}

# ② 拉取（换成目标博主 sec_uid）
curl -s "http://127.0.0.1:1200/douyin/user/<sec_uid>?format=json" | head -c 300
# 期待根节点 {"title":"博主昵称","items":[...]}，且响应头有 X-Fetch-At

# ③ 无效账号必须 502（账号校验生效）
curl -s -o /dev/null -w "%{http_code}\n" "http://127.0.0.1:1200/douyin/user/BADSECUID123?format=json"

# ④ 公网路径（App 实际走的路，localhost 通不代表公网通）
curl -s http://81.70.52.12:1200/health
```

App 端：设置 → 数据源地址填 `http://81.70.52.12:1200` → 保存并同步。

## 七、排查顺序

从内到外，逐层排除：

```bash
# 第 1 层：VM 上服务活着吗
systemctl status dyproxy frpc
journalctl -u dyproxy -n 30 --no-pager    # 看有没有 ERR

# 第 2 层：VM 本地直连（绕过隧道，验证出口与 Cookie）
curl -s http://127.0.0.1:1200/douyin/user/<sec_uid>?format=json | head -c 120

# 第 3 层：隧道通不通（服务器上）
cd ~/frp && docker compose logs --tail 30
#   期待看到 new proxy [dyproxy] type [tcp] success

# 第 4 层：公网路径
curl -s http://81.70.52.12:1200/health
```

| 现象 | 原因与处理 |
|------|-----------|
| 全部请求 502，日志 `status_code=xxxx` | Cookie 过期/被风控 → 重新取 Cookie 更新重启 |
| 换了 Cookie 还是 502 | 检查是否以 `ttwid=` 开头、长度是否 6500 左右（很可能是粘贴被截断） |
| 日志 `返回数据属于 MS4w...，非请求的 ...` | 已被账号校验拦截（正常防护），确认 App 请求的 sec_uid 是否正确 |
| 无效账号返回了 200 + 某账号数据 | 版本过旧（v3 之前），务必使用 v3.1 |
| App 502 但 VM 本地 curl 正常 | 隧道或公网侧问题 → 查第 3、4 层 |
| frpc 日志刷 `connect to server error` | 服务器 frps 没起来，或安全组没放行 7000/TCP |
| 响应慢 | 上游最坏 12s×3 次重试，叠加隧道往返；App 端有 45s 总闸与本地缓存兜底，不影响响铃 |

## 八、⚠️ 运维铁律：宿主机不能睡

dyproxy 跑在**虚拟机**里，而 VM 跟着宿主 Windows 走。

> **宿主一休眠/关机，隧道即断，App 就取不到最新视频。**

而闹钟场景的关键时刻是**清晨**——那正是机器最容易在睡觉的时候。所以：

- 宿主 Windows 电源设置里，**睡眠改成「从不」**，并长期开机
- 断电重启后，VM 需能自动启动（VMware 可设为随宿主开机自启），VM 内两个 systemd 服务本身已是开机自启

## 九、文件说明

### 当前必需

| 文件 | 放哪 | 用途 |
|------|------|------|
| `dyproxy.cjs` | VM `/opt/dyproxy/` | 代理主程序（单文件，无第三方依赖） |
| `.env` | VM `/opt/dyproxy/` | 真实 Cookie。**已在仓库根 `.gitignore` 排除，切勿提交、切勿外发** |
| `frp/frpc.local.toml` | VM `/opt/dyproxy/` | 隧道客户端配置（含 token，**不入库**） |
| `frp/frps.example.toml` | 服务器 | frps 服务端模板 → 复制成 `frps.local.toml` 后填 token |
| `frp/docker-compose.yml` | 服务器 | 用容器跑 frps |
| `frp/setup-linux-vm.sh` | VM | 一键部署脚本（装依赖 + 建服务 + 开机自启 + 自检） |
| `frp/README.md` | — | frp 部署完整文档 |

### 文档与工具

| 文件 | 用途 |
|------|------|
| `DEPLOY.md` | **部署与迁移手册**：环境实况、最小配置需求（CPU/内存/磁盘/网络，含依据）、依赖清单、换机迁移步骤、验收清单 |
| `frp/pack.sh` | 生成可移植部署归档 tar.gz（产物落在 `dist/`，已 gitignore） |

### 旧方案遗留（当前不用，保留作回退参考）

2026-09-16 之前 dyproxy 直接在腾讯云服务器上跑容器，这批文件属于那个模型：

| 文件 | 说明 |
|------|------|
| `docker-compose.yml` | 在服务器上跑 dyproxy 容器（`image: rsshub-ready`） |
| `restart.sh` | 服务器上换 Cookie 的脚本（重建容器流程） |
| `Dockerfile` | 构建 dyproxy 镜像（node:24-alpine） |
| `.env.example` | 环境变量模板（当前仍用于提示 `.env` 格式） |

> 服务器上现在已经没有 dyproxy 容器了（`docker compose down` 已移除），1200 端口由 frps 占用。
> 若某天需要回退到机房直连，改回读抖音会再次 403 —— 回退只适用于其他不受风控的场景。
