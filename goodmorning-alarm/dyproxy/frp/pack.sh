#!/usr/bin/env bash
# pack.sh — 生成 dyproxy 可移植部署归档（tar.gz）
#
# 用法：
#   bash pack.sh
#
# 产物：
#   ../dist/goodmorning-dyproxy-deploy-v<版本>-<日期>.tar.gz
#
# 归档刻意不含密钥（.env 的真实 Cookie、frpc.local.toml 的 token）。
# 新环境需按 DEPLOY.md 第六节自行填写 —— 不要为了省事把密钥塞进归档里到处传。

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"   # dyproxy/frp
ROOT="$(cd "$HERE/.." && pwd)"                          # dyproxy
OUT_DIR="$ROOT/dist"
STAMP="$(date +%Y%m%d)"

[ -f "$ROOT/dyproxy.cjs" ] || { echo "✗ 找不到 $ROOT/dyproxy.cjs" >&2; exit 1; }

# 版本号：优先取 `const VERSION = 'x.y'`，退回 `version: 'x.y'`
VER="$(grep -oE "(const )?VERSION *= *'[0-9.]+'" "$ROOT/dyproxy.cjs" | head -1 | grep -oE "[0-9.]+" || true)"
if [ -z "$VER" ]; then
  VER="$(grep -oE "version: *'[0-9.]+'" "$ROOT/dyproxy.cjs" | head -1 | grep -oE "[0-9.]+" || true)"
fi
VER="${VER:-unknown}"

NAME="goodmorning-dyproxy-deploy-v${VER}-${STAMP}"

# 用项目内的 staging 目录而不是 mktemp：
# Windows Git Bash 下 mktemp -d 会返回 C:\... 风格路径，cd 到它会失败。
STAGE="$OUT_DIR/.stage"
PKG="$STAGE/$NAME"
rm -rf "$STAGE"
mkdir -p "$PKG/vm" "$PKG/server"

# ---------- 家庭端（dyproxy + frpc）----------
cp "$ROOT/dyproxy.cjs"        "$PKG/vm/"
cp "$ROOT/package.json"       "$PKG/vm/"
cp "$ROOT/package-lock.json"  "$PKG/vm/"
cp "$ROOT/.env.example"       "$PKG/vm/"
cp "$HERE/frpc.example.toml"  "$PKG/vm/"
cp "$HERE/setup-linux-vm.sh"  "$PKG/vm/"

# ---------- 服务器端（frps）----------
cp "$HERE/frps.example.toml" "$PKG/server/"
cp "$HERE/docker-compose.yml" "$PKG/server/"

# ---------- 文档 ----------
cp "$ROOT/DEPLOY.md"  "$PKG/DEPLOY.md"          # 入口文档
cp "$ROOT/README.md"  "$PKG/README-dyproxy.md"
cp "$HERE/README.md"  "$PKG/README-frp.md"

chmod +x "$PKG/vm/setup-linux-vm.sh"

# ---------- 清单 ----------
{
  echo "# dyproxy 部署归档清单"
  echo
  echo "生成时间 : $(date '+%Y-%m-%d %H:%M:%S %z')"
  echo "dyproxy  : v${VER}"
  echo "Node.js  : v24.19.0（要求 >= 18）"
  echo "playwright: ^1.63.0（唯一的 npm 依赖，版本随 package-lock.json 锁定）"
  echo "Chromium : 由 playwright 自动下载（约 200MB），不必随本归档携带"
  echo "frp      : v0.71.0（frps 与 frpc 必须同大版本）"
  echo
  echo "## 先读这个"
  echo "  DEPLOY.md"
  echo "    第一节  这套东西由什么组成"
  echo "    第三节  最小配置需求（CPU / 内存 / 磁盘 / 网络，含依据与实测值）"
  echo "    第五节  从零到跑通（换机迁移）"
  echo "    第十节  验收清单"
  echo
  echo "## 目录结构"
  echo "  vm/      家庭端：dyproxy.cjs、package.json、package-lock.json、.env.example、"
  echo "           frpc.example.toml、setup-linux-vm.sh"
  echo "  server/  服务器端：frps.example.toml、docker-compose.yml"
  echo
  echo "## 部署时会发生什么"
  echo "  setup-linux-vm.sh 会自动 npm install 并下载 Chromium（约 200MB），"
  echo "  所以不需要把 node_modules/ 和 browsers/ 放进归档。"
  echo
  echo "## 本归档不含密钥，需自行准备"
  echo "  vm/.env            单行 DOUYIN_COOKIE=<整串 Cookie>，约 6500 字符"
  echo "  vm/frpc.local.toml 由 frpc.example.toml 复制而来，填入 auth.token"
  echo "                     （须与服务器 frps.local.toml 的 auth.token 完全一致）"
  echo "  Cookie 取法见 DEPLOY.md 第六节。切勿用终端粘贴，会被静默截断。"
  echo
  echo "## 文件校验和（SHA-256）"
  echo
} > "$PKG/MANIFEST.txt"

( cd "$PKG" && find . -type f ! -name MANIFEST.txt | LC_ALL=C sort | while read -r f; do
    sha256sum "${f#./}"
  done >> MANIFEST.txt )

# ---------- 打包 ----------
tar czf "$OUT_DIR/$NAME.tar.gz" -C "$STAGE" "$NAME"

echo
echo "已生成："
echo "  $(du -h "$OUT_DIR/$NAME.tar.gz" | cut -f1)  $OUT_DIR/$NAME.tar.gz"
echo
echo "解压查看内容：tar tzf \"$OUT_DIR/$NAME.tar.gz\""

# 清理 staging（失败不影响产物）
rm -rf "$STAGE" 2>/dev/null || echo "  （提示：中间目录 $STAGE 未自动清理，可手动删除）"
