#!/usr/bin/env bash
# setup-linux-vm.sh — 在 Linux 虚拟机里一键部署 dyproxy + frpc（systemd 常驻）
#
# 用法：
#   1) 把 dyproxy.cjs、package.json、.env、frpc.local.toml 放到 /opt/dyproxy/
#   2) sudo bash setup-linux-vm.sh
#
# 脚本会依次：装依赖 → 装 Node → 装 frpc → 建运行用户 → 装 npm 依赖与 Chromium
#             → 写 systemd 服务 → 启动 → 自检
#
# 支持 Debian / Ubuntu；x86_64 与 arm64 架构自动识别。

set -euo pipefail

APP_DIR=/opt/dyproxy
RUN_USER=dyproxy
NODE_VER=v24.19.0
FRP_VER=0.71.0

# 国内镜像兜底
NPM_REGISTRY=https://registry.npmmirror.com
PW_DOWNLOAD_HOST=https://cdn.npmmirror.com/binaries/playwright

log() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
die() { printf '\033[31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "请用 sudo 运行：sudo bash setup-linux-vm.sh"

case "$(uname -m)" in
  x86_64|amd64)  A_NODE=x64;   A_FRP=amd64 ;;
  aarch64|arm64) A_NODE=arm64; A_FRP=arm64 ;;
  *) die "不支持的 CPU 架构：$(uname -m)" ;;
esac

# ---------- 1. 基础依赖 ----------
log "1/7 安装基础依赖"
export DEBIAN_FRONTEND=noninteractive

# Ubuntu 桌面版常残留一个指向安装光盘的 apt 源（cdrom:// 或 file:/cdrom）。
# 光盘早就不在了，会让 apt-get update 直接报错、进而让整个脚本中止。这里自动注释掉。
for f in /etc/apt/sources.list /etc/apt/sources.list.d/*.list /etc/apt/sources.list.d/*.sources; do
  [ -f "$f" ] || continue
  if grep -qE '^[[:space:]]*(deb(-src)?[[:space:]]+)?(cdrom:|file:/+cdrom)' "$f"; then
    echo "  注释掉 $f 里的 cdrom 源"
    sed -i -E 's,^([[:space:]]*)((deb(-src)?[[:space:]]+)?(cdrom:|file:/+cdrom).*)$,\1# \2,' "$f"
  fi
done

apt-get update -qq || echo "  ⚠ apt-get update 有报错，继续尝试安装"
apt-get install -y -qq curl ca-certificates xz-utils

# ---------- 2. Node.js ----------
log "2/7 安装 Node.js ${NODE_VER}"
if command -v node >/dev/null 2>&1 && [ "$(node -v)" = "$NODE_VER" ]; then
  echo "  已存在 $(node -v)，跳过"
else
  TMP=$(mktemp -d)
  FILE="node-${NODE_VER}-linux-${A_NODE}.tar.xz"
  OK=0
  for BASE in "https://nodejs.org/dist/${NODE_VER}" \
              "https://registry.npmmirror.com/-/binary/node/${NODE_VER}"; do
    echo "  尝试：$BASE"
    if curl -fsSL --retry 2 --connect-timeout 15 -o "$TMP/node.tar.xz" "$BASE/$FILE"; then
      OK=1
      break
    fi
  done
  [ "$OK" -eq 1 ] || die "Node 下载失败（官方源与国内镜像都不通），请检查网络后重试"
  tar xJf "$TMP/node.tar.xz" -C /usr/local --strip-components=1
  rm -rf "$TMP"
  echo "  已安装 $(node -v)"
fi
NODE_BIN=$(command -v node)

# ---------- 3. frpc ----------
log "3/7 安装 frpc v${FRP_VER}"
if command -v frpc >/dev/null 2>&1; then
  echo "  已存在，跳过"
else
  TMP=$(mktemp -d)
  curl -fsSL --retry 3 -o "$TMP/frp.tar.gz" \
    "https://github.com/fatedier/frp/releases/download/v${FRP_VER}/frp_${FRP_VER}_linux_${A_FRP}.tar.gz"
  tar xzf "$TMP/frp.tar.gz" -C "$TMP"
  install -m 0755 "$TMP/frp_${FRP_VER}_linux_${A_FRP}/frpc" /usr/local/bin/frpc
  rm -rf "$TMP"
  echo "  已安装 $(frpc -v 2>&1 | head -1)"
fi
FRPC_BIN=$(command -v frpc)

# ---------- 4. 运行用户与配置检查 ----------
log "4/7 准备运行用户与目录"
id -u "$RUN_USER" >/dev/null 2>&1 || useradd -r -s /bin/false -d "$APP_DIR" "$RUN_USER"
mkdir -p "$APP_DIR"

MISSING=0
for f in dyproxy.cjs package.json .env frpc.local.toml; do
  if [ -f "$APP_DIR/$f" ]; then
    printf '  ✓ %s\n' "$f"
  else
    printf '  ✗ 缺少 %s\n' "$APP_DIR/$f" >&2
    MISSING=1
  fi
done
if [ "$MISSING" -ne 0 ]; then
  echo
  echo "请先把上面缺的文件放进 $APP_DIR（尤其 .env 里要有完整 Cookie），再重跑本脚本。" >&2
  echo "传文件建议：VM 里装 openssh-server，然后用 VS Code Remote-SSH 连上去拖拽。" >&2
  exit 1
fi

if ! grep -q '^DOUYIN_COOKIE=.\{100,\}' "$APP_DIR/.env"; then
  die "$APP_DIR/.env 里的 DOUYIN_COOKIE 缺失或过短（完整整串应 6000+ 字符）"
fi

if grep -q 'REPLACE_WITH_A_RANDOM_TOKEN' "$APP_DIR/frpc.local.toml"; then
  die "$APP_DIR/frpc.local.toml 里的 token 还是占位符，请替换成服务器 frps.local.toml 里的那个"
fi

chmod 600 "$APP_DIR/.env" "$APP_DIR/frpc.local.toml"
echo "  权限已收紧（.env / frpc.local.toml 仅属主可读）"

# ---------- 5. npm 依赖与浏览器 ----------
log "5/7 安装 npm 依赖与 Chromium"
export PLAYWRIGHT_BROWSERS_PATH="$APP_DIR/browsers"
export PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1   # 先只装包，浏览器在下一步显式装（便于控制镜像与位置）

cd "$APP_DIR"
if [ -d node_modules/playwright ] || [ -d node_modules/playwright-core ]; then
  echo "  npm 依赖已存在，跳过"
else
  echo "  安装 playwright（registry=${NPM_REGISTRY}）"
  npm install --omit=dev --no-audit --no-fund --registry="$NPM_REGISTRY" \
    || npm install --omit=dev --no-audit --no-fund \
    || die "npm 安装失败，请检查网络"
fi

if [ -d "$PLAYWRIGHT_BROWSERS_PATH" ] && [ -n "$(ls -A "$PLAYWRIGHT_BROWSERS_PATH" 2>/dev/null)" ]; then
  echo "  Chromium 已存在，跳过"
else
  echo "  下载 Chromium（共约 150-200MB，请耐心等待）"
  unset PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD
  PLAYWRIGHT_DOWNLOAD_HOST="$PW_DOWNLOAD_HOST" npx playwright install chromium \
    || PLAYWRIGHT_DOWNLOAD_HOST="" npx playwright install chromium \
    || die "Chromium 下载失败，请检查网络后重试"
  echo "  安装 Chromium 所需系统库"
  npx playwright install-deps chromium >/dev/null 2>&1 || echo "  ⚠ install-deps 有报错（多数桌面版系统已自带所需库，通常不影响运行）"
fi

chown -R "$RUN_USER:$RUN_USER" "$APP_DIR"
echo "  依赖与浏览器就绪"

# ---------- 6. systemd 服务 ----------
log "6/7 写入 systemd 服务"
cat > /etc/systemd/system/dyproxy.service <<'UNIT'
[Unit]
Description=dyproxy — 抖音作品列表代理（每日早安数据源）
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=dyproxy
Group=dyproxy
WorkingDirectory=/opt/dyproxy
Environment=PLAYWRIGHT_BROWSERS_PATH=/opt/dyproxy/browsers
Environment=NODE_ENV=production
ExecStart=/usr/local/bin/node /opt/dyproxy/dyproxy.cjs
Restart=always
RestartSec=5
StandardOutput=journal
StandardError=journal
# 浏览器抓取的内存与启动开销
TimeoutStopSec=15

[Install]
WantedBy=multi-user.target
UNIT

cat > /etc/systemd/system/frpc.service <<'UNIT'
[Unit]
Description=frpc — dyproxy 内网穿透隧道客户端
After=network-online.target dyproxy.service
Wants=network-online.target

[Service]
Type=simple
User=dyproxy
Group=dyproxy
WorkingDirectory=/opt/dyproxy
ExecStart=/usr/local/bin/frpc -c /opt/dyproxy/frpc.local.toml
Restart=always
RestartSec=5
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
UNIT

sed -i "s|/usr/local/bin/node|${NODE_BIN}|" /etc/systemd/system/dyproxy.service
sed -i "s|/usr/local/bin/frpc|${FRPC_BIN}|" /etc/systemd/system/frpc.service

# ---------- 7. 启动与自检 ----------
log "7/7 启动并自检"
systemctl daemon-reload
systemctl enable --now dyproxy.service frpc.service
sleep 5

for s in dyproxy frpc; do
  printf '  %-8s %s\n' "$s" "$(systemctl is-active $s.service)"
done

echo
echo "--- 本地健康检查 ---"
if curl -fsS --max-time 10 http://127.0.0.1:1200/health; then
  echo
else
  echo "✗ 不通，看日志：journalctl -u dyproxy -n 30 --no-pager" >&2
fi

echo
echo "--- 真实拉取测试（首次需启动浏览器，约 5-25s）---"
SEC="MS4wLjABAAAAkme-Sn9GBLHkPFE6TSfhhmHbEfphTt7ZNL9BD14NWAneay8H7OxJQ05-CP9VgmSJ"
OUT=$(curl -s --max-time 60 "http://127.0.0.1:1200/douyin/user/${SEC}?format=json" || true)
if printf '%s' "$OUT" | grep -q '"items"'; then
  echo "  ✓ 抓取成功，返回 $(printf '%s' "$OUT" | grep -o '"link"' | wc -l) 条作品"
else
  echo "  ✗ 抓取失败：$(printf '%s' "$OUT" | head -c 160)" >&2
  echo "    看日志：journalctl -u dyproxy -n 40 --no-pager" >&2
fi

echo
echo "--- frpc 最近日志 ---"
journalctl -u frpc -n 8 --no-pager 2>/dev/null || true

cat <<'DONE'

完成。常用命令：
  systemctl status dyproxy frpc        # 看两个服务状态
  journalctl -u dyproxy -f             # 跟代理日志
  journalctl -u frpc -f                # 跟隧道日志
  systemctl restart dyproxy            # 换完 .env 后重启

注意：这两个服务已设为开机自启。但虚拟机本身跟着宿主 Windows 走 ——
      宿主一休眠/关机，隧道就断，所以宿主必须设成不休眠、长期开机。
DONE
