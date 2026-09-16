#!/usr/bin/env bash
# dyproxy 403 诊断脚本
# 目的：一次跑完，区分「服务器 IP 被风控」与「请求缺少参数/请求头」
#       并判断 a_bogus / msToken / User-Agent 哪个是关键因素。
#
# 用法：bash diag.sh

set -uo pipefail
cd "$(dirname "$0")"

CK=$(sed 's/^DOUYIN_COOKIE=//' .env | tr -d '\r\n')

UA_FF='Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:156.0) Gecko/20100101 Firefox/156.0'
UA_CR='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36'
REF_USER='https://www.douyin.com/user/MS4wLjABAAAASzz3-5_dxHYk-C4eBttxNdatG81A9jigMCq-KVCkSX4?from_tab_name=main'
SEC_UID='MS4wLjABAAAAkme-Sn9GBLHkPFE6TSfhhmHbEfphTt7ZNL9BD14NWAneay8H7OxJQ05-CP9VgmSJ'
WEBID='7684522545157244416'
UIFID='aa604c70eae1952c0be4020cf2c63575a417d2a1163fec98e1823467fc9ddc8cf665a36b43da074159f13b52a5ddf68b93928b208fe6f9c092c00807f851e9ac567eef7717faee53caa195812fc3ce9b8d964356ff99b73a3134d735a20b09b37ec0ea062bd2df45120c2dfd1961e612194a49a304821a820aaf41ed988488208406b7384b30875f03abf689cbfe69eb14d60e35a6ac39d307969e7b7957cda6'
VERIFYFP='verify_mtxyxdm5_MuktC14B_PwwZ_46ce_BfM9_Jfft8HGJcRjg'
MSTOKEN='46eAaMwt-rXUdsKU0YkRT5v77vv1uWb2amMsRUEAs2cs_PBpqnwu55ZAM8zJabOWIHyKow2yY2cjD4mNOBn3PNKBoIpkzAlFY5N74lqZRYI2qqikvpB_mg6sAySxO-dtaJ7yH1loEu-AvZpa2Jf2F8t2AViS1wdtsu8Um039nGjx7C8XAXG8FWQxQw%3D%3D'

# dyproxy 自己拼的那条 URL
URL_POST="https://www.douyin.com/aweme/v1/web/aweme/post/?device_platform=webapp&aid=6383&channel=channel_pc_web&sec_user_id=${SEC_UID}&count=10&max_cursor=0&locate_query=false&publish_video_strategy_type=2"

# 浏览器抓到的那条原始请求（含 a_bogus / msToken），逐字复现
URL_CAP="https://www.douyin.com/aweme/v1/web/notice/count/?device_platform=webapp&aid=6383&channel=channel_pc_web&is_new_notice=1&need_social_count=1&update_version_code=170400&pc_client_type=1&pc_libra_divert=Windows&support_h265=1&support_dash=1&cpu_core_num=16&version_code=170400&version_name=17.4.0&cookie_enabled=true&screen_width=1707&screen_height=960&browser_language=zh-CN&browser_platform=Win32&browser_name=Firefox&browser_version=156.0&browser_online=true&engine_name=Gecko&engine_version=156.0&os_name=Windows&os_version=10&device_memory=&platform=PC&webid=${WEBID}&uifid=${UIFID}&verifyFp=${VERIFYFP}&fp=${VERIFYFP}&msToken=${MSTOKEN}&a_bogus=DXsbhtyJQZ%2FfOdMG8CnJSa1U4t6lNPuyZrTdRncTyNOjLZtTEmPl%2FxayjxzvszRGybBTheV7IVU%2Fbddcp0XkpCrkKmkvuFw6Iz2n9g0o%2FqqdTFt0LHSPCLfFww0SUbzqe%2FnHiIs51ssJID25INAmAp3ae5zL5Oy2WNM9pMz9jDS8pBgTVo%2FlCrJAlXL%3D"

BODY=/tmp/_dyproxy_diag_body

probe() {
  local name="$1"; shift
  local code
  : > "$BODY"
  code=$(curl -sS -m 25 -o "$BODY" -w '%{http_code}' "$@" 2>&1) || true
  printf '\n=== %s ===\nHTTP %s\n' "$name" "$code"
  head -c 240 "$BODY" 2>/dev/null | tr -d '\r'
  printf '\n'
}

echo "Cookie 长度：${#CK}"
echo "服务器出口 IP：$(curl -s -m 10 https://api.ipify.org 2>/dev/null || echo '取不到')"

# --- 0. IP 基础连通性 ---
probe "0. 抖音首页（IP 是否能正常访问抖音）" \
  -H "User-Agent: $UA_FF" \
  "https://www.douyin.com/"

# --- 1. 逐字复现浏览器原始请求（最关键的一条）---
probe "1. 复现浏览器原始请求（全套头 + msToken + a_bogus）" \
  -H "User-Agent: $UA_FF" \
  -H 'Accept: application/json, text/plain, */*' \
  -H 'Accept-Language: zh-CN,zh;q=0.9,zh-TW;q=0.8,zh-HK;q=0.7,en-US;q=0.6,en;q=0.5' \
  -H "Referer: $REF_USER" \
  -H "uifid: $UIFID" \
  -H 'Sec-Fetch-Dest: empty' -H 'Sec-Fetch-Mode: cors' -H 'Sec-Fetch-Site: same-origin' \
  -H "Cookie: $CK" \
  "$URL_CAP"

# --- 2. dyproxy 原样请求（应复现 403）---
probe "2. dyproxy 原样（Chrome UA，无 msToken / a_bogus）" \
  -H "User-Agent: $UA_CR" \
  -H "Referer: https://www.douyin.com/" \
  -H "Cookie: $CK" \
  "$URL_POST"

# --- 3. 只把 UA 换成 Firefox ---
probe "3. 同上，仅 UA 换成 Firefox 156" \
  -H "User-Agent: $UA_FF" \
  -H "Referer: https://www.douyin.com/" \
  -H "Cookie: $CK" \
  "$URL_POST"

# --- 4. UA + msToken（query 参数）---
probe "4. 同上 + msToken（query 参数）" \
  -H "User-Agent: $UA_FF" \
  -H "Referer: https://www.douyin.com/" \
  -H "Cookie: $CK" \
  "${URL_POST}&msToken=${MSTOKEN}"

# --- 5. UA + msToken(query) + msToken(cookie) + webid/uifid/verifyFp ---
probe "5. 同上 + msToken(cookie) + webid/uifid/verifyFp" \
  -H "User-Agent: $UA_FF" \
  -H 'Accept: application/json, text/plain, */*' \
  -H 'Accept-Language: zh-CN,zh;q=0.9' \
  -H "Referer: $REF_USER" \
  -H "uifid: $UIFID" \
  -H "Cookie: ${CK}; msToken=${MSTOKEN}" \
  "${URL_POST}&msToken=${MSTOKEN}&webid=${WEBID}&uifid=${UIFID}&verifyFp=${VERIFYFP}&fp=${VERIFYFP}"

printf '\n---- 结果怎么看 ----\n'
printf '0 若 403 → 服务器 IP 被抖音整体风控，得换出口 IP，换 Cookie 没用\n'
printf '1 若 200 而 2 是 403 → IP 和 Cookie 都没问题，问题在 dyproxy 的请求本身\n'
printf '1 也是 403 → 大概率是服务器 IP 被判定为数据中心出口\n'
printf '3/4/5 比 2 变 200 → 对应那一步补的东西就是关键（UA / msToken / webid 等）\n'
printf '注意 body 内容：JSON 说明进了业务层；HTML 或空 说明被边缘风控挡下\n'
