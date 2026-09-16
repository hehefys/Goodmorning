# dyproxy — 抖音用户视频列表代理（「每日早安」App 数据源）

把指定抖音博主的最新作品列表转成 App 兼容的 JSON Feed。

**当前版本 v4.0**：改用**无头浏览器**抓取，绕开抖音按出口 IP 下发的签名要求。
对 App 端完全兼容 —— 路由、返回格式、状态码都没变，App 不用改任何东西。

---

## 一、v4 为什么改成浏览器（核心背景）

抖音的风控插件 `ArgusSecurityPlugin` 会**按出口 IP 的画像分类放行**：

| 出口类型 | 抖音的态度 |
|---|---|
| 被判定为「可信住宅」的 IP 段 | 放行，裸 HTTP 请求即可 |
| 其余（机房 / 校园网 / 移动蜂窝…） | **一律要求 `uifid` + `a_bogus` 签名**，缺则 403 |

这不是猜测，是实测。同一份 Cookie、同一条请求，只换出口：

| 出口 | 结果 |
|---|---|
| 校园网 `183.198.228.147` | **200**，正常返回 |
| 校园网 `36.143.16.83` | 403 `Blocked by ArgusSecurityPlugin Uifid Not Found` |
| 移动蜂窝 `111.55.24.199` | 403 同上，**错误一字不差** |

补参数的递进也验证了这一点：不给 `uifid` → 报缺 `uifid`；给了 `uifid` → 报缺 `signature`。
**签名是最后一道关，而它是按 URL + 设备指纹现算的，抄不来。**

**v4 的解法**：不用 HTTP 硬闯，改让**真浏览器**打开博主主页，
拦截抖音自己发出的作品列表响应 —— 指纹和签名由浏览器自动完成，**一个字节的算法都不用逆向**。

实测效果：在上面那个「被要求签名」的移动 IP 下，浏览器方案**一次就拿到了 18 条作品**。

> 顺带说明：曾尝试移植开源的 `a_bogus` 实现，实测其产出的签名不被服务端接受
> （带签名与不带签名返回完全相同的错误）。该路线已放弃 —— 且它与抖音的 JS 混淆版本强绑定，
> 平台一改版即失效，属于长期维护负担。

---

## 二、当前架构（2026-09-16 起）

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
│ Ubuntu VM（需 7×24 开机）       │
│   dyproxy  监听 127.0.0.1:1200 │
│     └─ 无头 Chromium 抓取       │
│   frpc     连 81.70.52.12:7000 │
└───────────────┬───────────────┘
                │ 出口 = 本地宽带
                ▼
         www.douyin.com
```

**保留 frps 中转的原因**：App 需要一个固定、任何网络都能访问的入口。
把 dyproxy 放在内网机器上、由它主动连出去建隧道，就不需要公网 IP 和端口转发。

（如果将来验证浏览器方案在机房 IP 下也能稳定工作，可以省掉 frp 这一层，
 把 dyproxy 直接部署在服务器上 —— 但目前未验证，不要贸然简化。）

**完整部署步骤见 [`DEPLOY.md`](DEPLOY.md)**（含最小配置需求、换机迁移、验收清单）。

---

## 三、抓取模式（`FETCH_MODE`）

| 值 | 行为 | 何时用 |
|---|---|---|
| `auto`（默认） | 先用轻量 HTTP 直连探一次，失败再退回浏览器 | 通用。抖音放宽时自动回到快路径 |
| `browser` | 只用浏览器 | 想要最稳、不在乎多几百毫秒 |
| `http` | 只用直连（v3 的旧行为） | 仅用于对比排查，一般会 403 |

两条路径共用同一套「账号一致性校验 + 数据映射」，所以返回格式完全一致。

---

## 四、环境变量

`dyproxy.cjs` 优先读环境变量；**环境变量不存在时自动读取同目录的 `.env`**，
所以裸 Node 场景直接 `node dyproxy.cjs` 即可，无需导出任何变量。

| 变量 | 必填 | 说明 |
|------|------|------|
| `DOUYIN_COOKIE` | ✅ | 抖音网页版登录 Cookie（整串）。**过期后所有请求 502** |
| `PORT` | ❌ | 监听端口，默认 `1200` |
| `FETCH_MODE` | ❌ | `auto` / `browser` / `http`，默认 `auto` |
| `CACHE_TTL_MS` | ❌ | 数据缓存时长，默认 `600000`（10 分钟）。同一博主在此时间内重复请求直接返回缓存，不会反复开浏览器 |
| `CHROME_PATH` | ❌ | 指定浏览器可执行文件；留空则用 playwright 自带的 chromium |
| `BROWSER_TIMEOUT_MS` | ❌ | 单次浏览器抓取超时，默认 `35000` |
| `COUNT` | ❌ | 每次抓多少条，默认 `20` |
| `SEC_UID` | ❌ | 默认博主 sec_uid（仅文档与自检用途） |

---

## 五、获取 Cookie

1. 电脑浏览器打开 `https://www.douyin.com` 并登录（**建议专用小号**）
2. `F12` → Network（网络）→ 刷新页面 → 找任意请求 → Request Headers → 复制整串 `cookie:` 的值
3. 写进 VM 上的 `/opt/dyproxy/.env`

### ⚠️ 最容易踩的两个坑

**坑 1：复制不完整。** 整串 Cookie 以 `ttwid=` **开头**。如果粘出来的内容以 `enter_pc_once=`、
`s_v_web_id=`、`UIFID_TEMP=` 之类开头，说明**开头被截掉了**。粘贴后务必确认这 5 个字段都在：

| 字段 | 作用 |
|------|------|
| `ttwid` | 设备指纹，**必须有**，且排在整串最前面 |
| `sessionid_ss` / `sid_tt` | 登录态 |
| `odin_tt` | 设备令牌 |
| `passport_csrf_token` | CSRF 防护 |

长度参考：完整整串约 **6500 字符**。

**坑 2：别往终端里粘。** 整串 6500 字符，很多终端（尤其网页版终端）会**静默截断到 1024 字符**
且不报错。**务必用 VS Code Remote-SSH 打开文件粘贴，或 scp 传文件**，不要在 `nano` 里直接粘。

---

## 六、部署

### 一键部署（Linux 设备 / 虚拟机，推荐）

`frp/setup-linux-vm.sh` 会一次装好：Node、frpc、npm 依赖、Chromium、systemd 服务、开机自启：

```bash
# ① 把 dyproxy.cjs / package.json / .env / frpc.local.toml 放到 /opt/dyproxy/
# ② 一键部署（会下载 Chromium，约 150-200MB）
sudo bash setup-linux-vm.sh
```

详见 [`DEPLOY.md`](DEPLOY.md) 与 [`frp/README.md`](frp/README.md)。

### 手动部署

```bash
# 要求 Node.js ≥ 18
cd /opt/dyproxy
npm install                       # 装 playwright
npx playwright install chromium   # 下载浏览器（约 150-200MB）
node dyproxy.cjs
# 常驻建议交给 systemd，不要用 nohup
```

---

## 七、换 Cookie（日常操作，最常见）

**在 VM 上执行**：

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

---

## 八、验证

```bash
# ① 健康（会显示抓取模式与 playwright 是否可用）
curl -s http://127.0.0.1:1200/health
# {"ok":true,"service":"dyproxy","version":"4.0","port":1200,"mode":"auto","playwright":"available"}

# ② 拉取（首次会启动浏览器，约 5-25s；之后命中缓存则为毫秒级）
curl -s "http://127.0.0.1:1200/douyin/user/<sec_uid>?format=json" | head -c 300
# 期待根节点 {"title":"博主昵称","items":[...]}，响应头有 X-Fetch-At 与 X-Cache

# ③ 无效账号必须 502（账号校验生效）
curl -s -o /dev/null -w "%{http_code}\n" "http://127.0.0.1:1200/douyin/user/BADSECUID123?format=json"

# ④ 公网路径（App 实际走的路，localhost 通不代表公网通）
curl -s http://81.70.52.12:1200/health
```

App 端：设置 → 数据源地址填 `http://81.70.52.12:1200` → 保存并同步。

---

## 九、排查顺序

从内到外，逐层排除：

```bash
# 第 1 层：VM 上服务活着吗
systemctl status dyproxy frpc
journalctl -u dyproxy -n 30 --no-pager    # 看有没有 ERR

# 第 2 层：VM 本地直连（绕过隧道）
curl -s http://127.0.0.1:1200/douyin/user/<sec_uid>?format=json | head -c 120

# 第 3 层：隧道通不通（服务器上）
cd ~/frp && docker compose logs --tail 30
#   期待看到 new proxy [dyproxy] type [tcp] success

# 第 4 层：公网路径
curl -s http://81.70.52.12:1200/health
```

| 现象 | 原因与处理 |
|------|-----------|
| 全部请求 502，日志 `HTTP 403 空响应` | Cookie 过期/被风控 → 重新取 Cookie 更新重启 |
| 换了 Cookie 还是 502 | 检查是否以 `ttwid=` 开头、长度是否 6500 左右（很可能是粘贴被截断） |
| 日志 `浏览器未捕获到作品列表` | 页面加载超时或被要求人机验证 → 提高 `BROWSER_TIMEOUT_MS`；或换 `FETCH_MODE=browser`；必要时在 VM 上有头模式手动登录一次确认账号状态 |
| 日志 `未安装 playwright` | 依赖没装好 → `cd /opt/dyproxy && npm install && npx playwright install chromium` |
| 首次请求很慢（10-25s） | 正常。浏览器冷启动 + 页面加载；之后 10 分钟内命中缓存 |
| 服务重启后第一次很慢 | 同上（缓存是内存态，重启即清空） |
| 日志 `返回数据属于 MS4w...，非请求的 ...` | 已被账号校验拦截（正常防护），确认 App 请求的 sec_uid 是否正确 |
| 无效账号返回了 200 + 某账号数据 | 版本过旧（v3 之前），务必使用 v4 |
| App 502 但 VM 本地 curl 正常 | 隧道或公网侧问题 → 查第 3、4 层 |
| frpc 日志刷 `connect to server error` | 服务器 frps 没起来，或安全组没放行 7000/TCP |

---

## 十、⚠️ 运维铁律：宿主机不能睡

dyproxy 跑在**虚拟机**里，而 VM 跟着宿主 Windows 走。

> **宿主一休眠/关机，隧道即断，App 就取不到最新视频。**

而闹钟场景的关键时刻是**清晨**——那正是机器最容易在睡觉的时候。所以：

- 宿主 Windows 电源设置里，**睡眠改成「从不」**，并长期开机
- 断电重启后，VM 需能自动启动（VMware 可设为随宿主开机自启），VM 内两个 systemd 服务本身已是开机自启

---

## 十一、文件说明

### 当前必需

| 文件 | 放哪 | 用途 |
|------|------|------|
| `dyproxy.cjs` | VM `/opt/dyproxy/` | 代理主程序（v4，浏览器抓取） |
| `package.json` + `package-lock.json` | VM `/opt/dyproxy/` | npm 依赖清单（playwright） |
| `.env` | VM `/opt/dyproxy/` | 真实 Cookie。**已在仓库根 `.gitignore` 排除，切勿提交、切勿外发** |
| `frp/frpc.local.toml` | VM `/opt/dyproxy/` | 隧道客户端配置（含 token，**不入库**） |
| `frp/frps.example.toml` | 服务器 | frps 服务端模板 → 复制成 `frps.local.toml` 后填 token |
| `frp/docker-compose.yml` | 服务器 | 用容器跑 frps |
| `frp/setup-linux-vm.sh` | VM | 一键部署脚本（装依赖 + 浏览器 + 建服务 + 开机自启 + 自检） |
| `frp/README.md` | — | frp 部署文档 |

### 文档与工具

| 文件 | 用途 |
|------|------|
| `DEPLOY.md` | **部署与迁移手册**：环境实况、最小配置需求（CPU/内存/磁盘/网络，含依据）、依赖清单、换机迁移步骤、验收清单 |
| `frp/pack.sh` | 生成可移植部署归档 tar.gz（产物落在 `dist/`，已 gitignore） |

### VM 上运行时生成（不入库）

| 路径 | 说明 |
|------|------|
| `node_modules/` | npm 依赖，由 `setup-linux-vm.sh` 安装 |
| `browsers/` | playwright 的 Chromium（约 150-200MB） |

### 旧方案遗留（当前不用，保留作回退参考）

2026-09-16 之前 dyproxy 直接在腾讯云服务器上跑容器，这批文件属于那个模型：

| 文件 | 说明 |
|------|------|
| `docker-compose.yml` | 在服务器上跑 dyproxy 容器（`image: rsshub-ready`） |
| `restart.sh` | 服务器上换 Cookie 的脚本（重建容器流程） |
| `Dockerfile` | 构建 dyproxy 镜像（node:24-alpine） |

> 服务器上现在已经没有 dyproxy 容器了（`docker compose down` 已移除），1200 端口由 frps 占用。
