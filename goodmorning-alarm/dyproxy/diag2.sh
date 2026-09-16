#!/usr/bin/env bash
# dyproxy 403 诊断 · 第二轮
# 目的：找出 ArgusSecurityPlugin 在机房 IP 下到底要求哪几样东西
#   第一轮已确认：uifid 是第 1 关（补上后报错从 Uifid Not Found 变成 Signature Not Found）
#   本轮测试：完整浏览器指纹参数集能否免掉 a_bogus 签名
#
# 用法：bash diag2.sh

set -uo pipefail
cd "$(dirname "$0")"

CK=$(sed 's/^DOUYIN_COOKIE=//' .env | tr -d '\r\n')

UA='Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:156.0) Gecko/20100101 Firefox/156.0'
REF='https://www.douyin.com/user/MS4wLjABAAAASzz3-5_dxHYk-C4eBttxNdatG81A9jigMCq-KVCkSX4?from_tab_name=main'
SEC_UID='MS4wLjABAAAAkme-Sn9GBLHkPFE6TSfhhmHbEfphTt7ZNL9BD14NWAneay8H7OxJQ05-CP9VgmSJ'
WEBID='7684522545157244416'
UIFID='aa604c70eae1952c0be4020cf2c63575a417d2a1163fec98e1823467fc9ddc8cf665a36b43da074159f13b52a5ddf68b93928b208fe6f9c092c00807f851e9ac567eef7717faee53caa195812fc3ce9b8d964356ff99b73a3134d735a20b09b37ec0ea062bd2df45120c2dfd1961e612194a49a304821a820aaf41ed988488208406b7384b30875f03abf689cbfe69eb14d60e35a6ac39d307969e7b7957cda6'
VFP='verify_mtxyxdm5_MuktC14B_PwwZ_46ce_BfM9_Jfft8HGJcRjg'
MST='46eAaMwt-rXUdsKU0YkRT5v77vv1uWb2amMsRUEAs2cs_PBpqnwu55ZAM8zJabOWIHyKow2yY2cjD4mNOBn3PNKBoIpkzAlFY5N74lqZRYI2qqikvpB_mg6sAySxO-dtaJ7yH1loEu-AvZpa2Jf2F8t2AViS1wdtsu8Um039nGjx7C8XAXG8FWQxQw%3D%3D'

# 抖音网页端会带的一整套「浏览器指纹」参数
FINGERPRINT="pc_client_type=1&pc_libra_divert=Windows&support_h265=1&support_dash=1&cpu_core_num=16&update_version_code=170400&version_code=170400&version_name=17.4.0&cookie_enabled=true&screen_width=1707&screen_height=960&browser_language=zh-CN&browser_platform=Win32&browser_name=Firefox&browser_version=156.0&browser_online=true&engine_name=Gecko&engine_version=156.0&os_name=Windows&os_version=10&device_memory=&platform=PC"

BASE="https://www.douyin.com/aweme/v1/web/aweme/post/?device_platform=webapp&aid=6383&channel=channel_pc_web&sec_user_id=${SEC_UID}&count=10&max_cursor=0&locate_query=false&publish_video_strategy_type=2"

URL_CAP="https://www.douyin.com/aweme/v1/web/notice/count/?device_platform=webapp&aid=6383&channel=channel_pc_web&is_new_notice=1&need_social_count=1&update_version_code=170400&pc_client_type=1&pc_libra_divert=Windows&support_h265=1&support_dash=1&cpu_core_num=16&version_code=170400&version_name=17.4.0&cookie_enabled=true&screen_width=1707&screen_height=960&browser_language=zh-CN&browser_platform=Win32&browser_name=Firefox&browser_version=156.0&browser_online=true&engine_name=Gecko&engine_version=156.0&os_name=Windows&os_version=10&device_memory=&platform=PC&webid=${WEBID}&uifid=${UIFID}&verifyFp=${VFP}&fp=${VFP}&msToken=${MST}&a_bogus=DXsbhtyJQZ%2FfOdMG8CnJSa1U4t6lNPuyZrTdRncTyNOjLZtTEmPl%2FxayjxzvszRGybBTheV7IVU%2Fbddcp0XkpCrkKmkvuFw6Iz2n9g0o%2FqqdTFt0LHSPCLfFww0SUbzqe%2FnHiIs51ssJID25INAmAp3ae5zL5Oy2WNM9pMz9jDS8pBgTVo%2FlCrJAlXL%3D"

BODY=/tmp/_dyproxy_diag2_body

HDRS=(
  -H "User-Agent: $UA"
  -H 'Accept: application/json, text/plain, */*'
  -H 'Accept-Language: zh-CN,zh;q=0.9,zh-TW;q=0.8,zh-HK;q=0.7,en-US;q=0.6,en;q=0.5'
  -H "Referer: $REF"
  -H "uifid: $UIFID"
  -H 'Sec-Fetch-Dest: empty'
  -H 'Sec-Fetch-Mode: cors'
  -H 'Sec-Fetch-Site: same-origin'
)

probe() {
  local name="$1"; shift
  local code
  : > "$BODY"
  code=$(curl -sS -m 25 -o "$BODY" -w '%{http_code}' "$@" 2>/dev/null) || true
  printf '\n=== %s ===\nHTTP %s\n' "$name" "$code"
  head -c 200 "$BODY" 2>/dev/null | tr -d '\r'
  printf '\n'
}

echo "Cookie 长度：${#CK}"

probe "A. dyproxy URL + 仅 uifid" \
  "${HDRS[@]}" -H "Cookie: $CK" \
  "${BASE}&uifid=${UIFID}"

probe "B. dyproxy URL + uifid + webid" \
  "${HDRS[@]}" -H "Cookie: $CK" \
  "${BASE}&uifid=${UIFID}&webid=${WEBID}"

probe "C. dyproxy URL + 完整浏览器指纹集 + webid/uifid/verifyFp/msToken" \
  "${HDRS[@]}" -H "Cookie: $CK" \
  "${BASE}&${FINGERPRINT}&webid=${WEBID}&uifid=${UIFID}&verifyFp=${VFP}&fp=${VFP}&msToken=${MST}"

probe "D. 同 C，但 msToken 放进 Cookie 而不是 query" \
  "${HDRS[@]}" -H "Cookie: ${CK}; msToken=${MST}" \
  "${BASE}&${FINGERPRINT}&webid=${WEBID}&uifid=${UIFID}&verifyFp=${VFP}&fp=${VFP}"

probe "E. 同 C，但再加上浏览器抓到的 a_bogus（URL 不匹配，纯对照）" \
  "${HDRS[@]}" -H "Cookie: $CK" \
  "${BASE}&${FINGERPRINT}&webid=${WEBID}&uifid=${UIFID}&verifyFp=${VFP}&fp=${VFP}&msToken=${MST}&a_bogus=DXsbhtyJQZ%2FfOdMG8CnJSa1U4t6lNPuyZrTdRncTyNOjLZtTEmPl%2FxayjxzvszRGybBTheV7IVU%2Fbddcp0XkpCrkKmkvuFw6Iz2n9g0o%2FqqdTFt0LHSPCLfFww0SUbzqe%2FnHiIs51ssJID25INAmAp3ae5zL5Oy2WNM9pMz9jDS8pBgTVo%2FlCrJAlXL%3D"

probe "F. 对照组：浏览器原始 notice/count 请求原样复现（应 200）" \
  "${HDRS[@]}" -H "Cookie: $CK" \
  "$URL_CAP"

printf '\n---- 怎么读 ----\n'
printf 'A~D 里有任何一条 200 → 补参数就能修好，我把对应参数写进 dyproxy.cjs\n'
printf 'E 也 403（预期） → 印证 a_bogus 与 URL 绑定，抄过来没用\n'
printf 'F 是 200 → 再次确认 IP 正常、Cookie 正常，纯粹是参数凭证缺失\n'
printf '若 A~D 全 403 且 E 报 Signature → 机房 IP 强制要求有效签名，需走签名/换出口方案\n'
