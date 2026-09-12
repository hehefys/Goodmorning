# dyproxy — 抖音用户视频代理（「每日早安」App 数据源）

把指定抖音博主的最新作品列表转成 App 兼容的 JSON Feed。纯 Node 直连（登录 Cookie + UA/Referer），无需签名、无需浏览器。

**当前版本 v3.1**：账号一致性校验（拒绝返回错误账号数据）/ 上游 12s 硬超时 / 根节点返回博主昵称 / X-Fetch-At 响应头。

---

## 一、环境变量

| 变量 | 必填 | 说明 |
|------|------|------|
| `DOUYIN_COOKIE` | ✅ | 抖音网页版登录 Cookie（整串）。**过期代理整体 502**，重新扫码获取后更新此变量重启即可 |
| `PORT` | ❌ | 监听端口，默认 `1200` |
| `SEC_UID` | ❌ | 默认博主 sec_uid（仅文档用途，App 端请求的 URL 已自带） |

## 二、获取 Cookie

1. 电脑 Chrome 打开 `https://www.douyin.com` 并登录（建议专用小号）
2. `F12` → Network（网络）→ 刷新页面 → 找任意请求 → Request Headers → 复制整串 `cookie:`
3. 粘贴到下方部署命令的 `DOUYIN_COOKIE` 变量

## 三、部署方式

### 方式 A：Docker Compose（推荐）

`docker-compose.yml` 已在本目录：

```bash
# 1. 编辑 docker-compose.yml，填入 DOUYIN_COOKIE
# 2. 启动
docker compose up -d
docker logs -f dyproxy     # 看到 "dyproxy v3.1 已启动" 即成功
```

### 方式 B：Docker Run

```bash
docker build -t dyproxy .
docker run -d --name dyproxy --restart unless-stopped -p 1200:1200 \
  -e DOUYIN_COOKIE='<粘贴整串 Cookie>' \
  dyproxy
```

### 方式 C：裸 Node（无 Docker）

```bash
# 要求 Node.js ≥ 18
PORT=1200 DOUYIN_COOKIE='<Cookie>' node dyproxy.cjs
# 常驻可用 pm2：pm2 start dyproxy.cjs --name dyproxy
```

## 四、验证

```bash
# ① 健康
curl -s http://127.0.0.1:1200/health
# 期待 {"ok":true,"service":"dyproxy","version":"3.1",...}

# ② 拉取（换成目标博主 sec_uid）
curl -s "http://127.0.0.1:1200/douyin/user/<sec_uid>?embed=1&format=json" | head -c 300
# 期待根节点 {"title":"博主昵称","items":[...]}，且响应头有 X-Fetch-At

# ③ 无效账号必须 502（账号校验生效）
curl -s -o /dev/null -w "%{http_code}\n" "http://127.0.0.1:1200/douyin/user/BADSECUID123?embed=1&format=json"
```

App 端：设置 → 数据源地址填 `http://<服务器IP>:1200` → 保存并同步。

## 五、从旧服务器迁移

1. 新服务器按上面任一方式部署（用新拷贝的本目录文件即可，代码与线上一致）
2. **Cookie 从旧服务器带过来**：旧机 `docker exec dyproxy env | grep DOUYIN`（或在旧 compose 文件里）拿到整串 Cookie，填入新机
3. App 端数据源地址改成新服务器 IP → 保存并同步
4. 旧服务器确认新机工作后 `docker rm -f dyproxy` 下线

## 六、常见问题

| 现象 | 原因与处理 |
|------|-----------|
| 全部请求 502，日志 `status_code=xxxx` | Cookie 过效/被风控 → 重新扫码取 Cookie 更新重启 |
| 日志 `返回数据属于 MS4w...，非请求的 ...` | 已被账号校验拦截（正常防护），确认 App 请求的 sec_uid 是否正确 |
| 无效账号返回了 200 + 某账号数据 | 版本过旧（v3 之前），务必使用本包 v3.1 |
| 响应慢 | 上游最坏 12s×3 次重试；App 端有 45s 总闸与本地缓存兜底，不影响响铃 |

## 七、文件说明

| 文件 | 用途 |
|------|------|
| `dyproxy.cjs` | 代理主程序（单文件，无第三方依赖） |
| `Dockerfile` | 构建镜像（node:24-alpine） |
| `docker-compose.yml` | Compose 部署（填 Cookie 后 `docker compose up -d`） |
| `README.md` | 本文档 |
