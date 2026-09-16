#!/usr/bin/env bash
# dyproxy 换 Cookie 一键脚本
#
# 用法：
#   nano .env        # 只改 DOUYIN_COOKIE 这一行
#   ./restart.sh     # 校验 -> 重建容器 -> 真实验证
#   ./restart.sh --force   # 跳过「长度不足」拦截（确认自己没填错时用）
#
# 原理：环境变量在容器「创建」时就固化了，restart / start 都不会重新读取，
#       所以换 Cookie 必须重建容器（--force-recreate）。

set -euo pipefail
cd "$(dirname "$0")"

DEFAULT_SEC_UID='MS4wLjABAAAAkme-Sn9GBLHkPFE6TSfhhmHbEfphTt7ZNL9BD14NWAneay8H7OxJQ05-CP9VgmSJ'

if [ ! -f .env ]; then
  echo "✗ 找不到 .env —— 请先执行：cp .env.example .env 并填入 Cookie" >&2
  exit 1
fi

if ! grep -q '^DOUYIN_COOKIE=' .env; then
  echo "✗ .env 里没有 DOUYIN_COOKIE 这一行" >&2
  exit 1
fi

COOKIE=$(grep -m1 '^DOUYIN_COOKIE=' .env | sed 's/^DOUYIN_COOKIE=//' | tr -d '\r\n')
LEN=${#COOKIE}
echo "Cookie 长度：$LEN"

# ---- 拦截 1：还是模板占位文字 ----
if printf '%s' "$COOKIE" | grep -qE '^<|粘贴|在这里|你的抖音'; then
  echo "✗ .env 里还是模板里的占位文字，不是真实 Cookie。" >&2
  echo "  请执行：nano .env   把整串 Cookie 粘到 DOUYIN_COOKIE= 后面（不要带尖括号）" >&2
  echo "  已中止，未重建容器。" >&2
  exit 1
fi

# ---- 拦截 2：长度明显不对（几乎一定是复制时被截断）----
if [ "$LEN" -lt 1000 ]; then
  echo "✗ Cookie 只有 $LEN 个字符，抖音网页版整串通常 2000 字符以上。" >&2
  echo "  最常见原因：复制时只截到了后半段（丢了开头的 ttwid=）。" >&2
  if [ "${1:-}" != "--force" ]; then
    echo "  已中止，未重建容器。确认无误可用 ./restart.sh --force 跳过。" >&2
    exit 1
  fi
  echo "  （--force 已指定，继续）" >&2
fi

# ---- 关键字段自检 ----
echo "关键字段自检："
MISSING=0
for k in ttwid sessionid_ss sid_tt odin_tt passport_csrf_token; do
  if printf '%s' "$COOKIE" | grep -q "$k="; then
    echo "  ✓ $k"
  else
    echo "  ✗ 缺少 $k" >&2
    MISSING=$((MISSING + 1))
  fi
done
if [ "$MISSING" -gt 0 ]; then
  echo "  ⚠ 缺 $MISSING 个关键字段，换上去大概率还是 502。" >&2
fi

case "$COOKIE" in
  ttwid=*) : ;;
  *) echo "  ⚠ 开头不是 ttwid=，很可能被截掉了前面一段。" >&2 ;;
esac

# ---- 重建容器 ----
echo ""
echo "重建容器…"
docker compose up -d --force-recreate

echo ""
echo "等待启动…"
sleep 3

# ---- health（只证明进程活着，不证明 Cookie 有效）----
echo "--- health（进程存活，不校验 Cookie）---"
curl -fsS http://127.0.0.1:1200/health && echo || echo "✗ health 不通" >&2

# ---- 真实拉取：这才是唯一能证明 Cookie 有效的检查 ----
SEC_UID=$(grep -m1 '^SEC_UID=' .env 2>/dev/null | sed 's/^SEC_UID=//' | tr -d '\r\n' || true)
SEC_UID=${SEC_UID:-$DEFAULT_SEC_UID}

echo ""
echo "--- 真实拉取测试（验证 Cookie 是否有效）---"
CODE=$(curl -s -o /tmp/dyproxy_selftest.json -w '%{http_code}' \
  "http://127.0.0.1:1200/douyin/user/${SEC_UID}?format=json" || echo 000)
echo "HTTP $CODE"
head -c 300 /tmp/dyproxy_selftest.json 2>/dev/null; echo

case "$CODE" in
  200) echo "✓ Cookie 有效，代理解析正常" ;;
  502) echo "✗ 502 —— Cookie 无效 / 被风控 / 缺关键字段。看下面容器日志里的 status_code。" >&2 ;;
  *)   echo "✗ 非预期状态码，看下面容器日志。" >&2 ;;
esac

echo ""
echo "--- 最近日志 ---"
docker compose logs --tail 15
