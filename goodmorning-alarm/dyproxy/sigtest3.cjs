// sigtest3.cjs — 判别「端点是否校验签名」+ 用浏览器风格参数顺序重测 aweme/post
//
// 运行（服务器）：
//   cd ~/dyproxy && docker run --rm -v ~/dyproxy:/w -w /w --entrypoint node rsshub-ready sigtest3.cjs

const fs = require('fs');
const https = require('https');
const { abogus } = require('./a_bogus.cjs');

const COOKIE = (() => {
  for (const line of fs.readFileSync('.env', 'utf8').split(/\r?\n/)) {
    if (line.startsWith('DOUYIN_COOKIE=')) return line.slice('DOUYIN_COOKIE='.length).trim();
  }
  return '';
})();

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:156.0) Gecko/20100101 Firefox/156.0';
const REF = 'https://www.douyin.com/user/MS4wLjABAAAASzz3-5_dxHYk-C4eBttxNdatG81A9jigMCq-KVCkSX4?from_tab_name=main';
const UIFID = 'aa604c70eae1952c0be4020cf2c63575a417d2a1163fec98e1823467fc9ddc8cf665a36b43da074159f13b52a5ddf68b93928b208fe6f9c092c00807f851e9ac567eef7717faee53caa195812fc3ce9b8d964356ff99b73a3134d735a20b09b37ec0ea062bd2df45120c2dfd1961e612194a49a304821a820aaf41ed988488208406b7384b30875f03abf689cbfe69eb14d60e35a6ac39d307969e7b7957cda6';
const WEBID = '7684522545157244416';
const VFP = 'verify_mtxyxdm5_MuktC14B_PwwZ_46ce_BfM9_Jfft8HGJcRjg';
const MST = '46eAaMwt-rXUdsKU0YkRT5v77vv1uWb2amMsRUEAs2cs_PBpqnwu55ZAM8zJabOWIHyKow2yY2cjD4mNOBn3PNKBoIpkzAlFY5N74lqZRYI2qqikvpB_mg6sAySxO-dtaJ7yH1loEu-AvZpa2Jf2F8t2AViS1wdtsu8Um039nGjx7C8XAXG8FWQxQw%3D%3D';
const SEC_UID = 'MS4wLjABAAAAkme-Sn9GBLHkPFE6TSfhhmHbEfphTt7ZNL9BD14NWAneay8H7OxJQ05-CP9VgmSJ';

const P_NOTICE = '/aweme/v1/web/notice/count/';
const P_POST = '/aweme/v1/web/aweme/post/';

// 浏览器原始 query（notice/count），不含 a_bogus
const Q_NOTICE =
  'device_platform=webapp&aid=6383&channel=channel_pc_web&is_new_notice=1&need_social_count=1' +
  '&update_version_code=170400&pc_client_type=1&pc_libra_divert=Windows&support_h265=1&support_dash=1' +
  '&cpu_core_num=16&version_code=170400&version_name=17.4.0&cookie_enabled=true&screen_width=1707' +
  '&screen_height=960&browser_language=zh-CN&browser_platform=Win32&browser_name=Firefox' +
  '&browser_version=156.0&browser_online=true&engine_name=Gecko&engine_version=156.0' +
  '&os_name=Windows&os_version=10&device_memory=&platform=PC' +
  `&webid=${WEBID}&uifid=${UIFID}&verifyFp=${VFP}&fp=${VFP}&msToken=${MST}`;

// aweme/post：沿用浏览器的参数顺序，只把端点专属参数换成 post 的
const Q_POST_BROWSER =
  `device_platform=webapp&aid=6383&channel=channel_pc_web&sec_user_id=${SEC_UID}&count=10` +
  '&max_cursor=0&locate_query=false&publish_video_strategy_type=2' +
  '&update_version_code=170400&pc_client_type=1&pc_libra_divert=Windows&support_h265=1&support_dash=1' +
  '&cpu_core_num=16&version_code=170400&version_name=17.4.0&cookie_enabled=true&screen_width=1707' +
  '&screen_height=960&browser_language=zh-CN&browser_platform=Win32&browser_name=Firefox' +
  '&browser_version=156.0&browser_online=true&engine_name=Gecko&engine_version=156.0' +
  '&os_name=Windows&os_version=10&device_memory=&platform=PC' +
  `&webid=${WEBID}&uifid=${UIFID}&verifyFp=${VFP}&fp=${VFP}&msToken=${MST}`;

// 去掉 msToken 的版本（用于测试签名范围是否包含 msToken）
const Q_POST_NO_MST = Q_POST_BROWSER.replace(`&msToken=${MST}`, '');

const HEADERS = {
  'User-Agent': UA,
  'Accept': 'application/json, text/plain, */*',
  'Accept-Language': 'zh-CN,zh;q=0.9,zh-TW;q=0.8,zh-HK;q=0.7,en-US;q=0.6,en;q=0.5',
  'Referer': REF,
  'uifid': UIFID,
  'Sec-Fetch-Dest': 'empty',
  'Sec-Fetch-Mode': 'cors',
  'Sec-Fetch-Site': 'same-origin',
  'Cookie': COOKIE,
};

function get(url) {
  return new Promise((resolve) => {
    const req = https.get(url, { headers: HEADERS }, (res) => {
      let d = '';
      res.on('data', (c) => (d += c));
      res.on('end', () => resolve({ status: res.statusCode, body: d }));
    });
    req.setTimeout(25000, () => req.destroy(new Error('timeout')));
    req.on('error', (e) => resolve({ status: 0, body: 'ERR ' + e.message }));
  });
}

const arms = [
  { name: 'N0 notice/count 不带任何签名（判别该端点是否校验签名）',
    path: P_NOTICE, query: Q_NOTICE, signUri: null },
  { name: 'A0 aweme/post 浏览器风格参数 + 我们签名（uri=query）',
    path: P_POST, query: Q_POST_BROWSER, signUri: (q) => q },
  { name: 'A1 aweme/post 浏览器风格参数 不带签名（对照）',
    path: P_POST, query: Q_POST_BROWSER, signUri: null },
  { name: 'A2 aweme/post 浏览器风格参数 + 我们签名（uri 去掉 msToken）',
    path: P_POST, query: Q_POST_BROWSER, signUri: () => Q_POST_NO_MST },
];

(async () => {
  console.log('Cookie 长度:', COOKIE.length, ' | 对照 query 长度:', Q_NOTICE.length, '\n');
  for (const a of arms) {
    const sig = a.signUri ? abogus(a.signUri(a.query)) : '';
    const url = 'https://www.douyin.com' + a.path + '?' + a.query
      + (sig ? '&a_bogus=' + encodeURIComponent(sig) : '');
    const r = await get(url);
    let extra = '';
    if (r.body && r.body.trim().startsWith('{')) {
      try {
        const j = JSON.parse(r.body);
        extra = ` status_code=${j.status_code}${j.aweme_list ? ' aweme_list=' + j.aweme_list.length : ''}`;
      } catch (e) { extra = ' (JSON 解析失败)'; }
    }
    console.log(`=== ${a.name} ===`);
    if (sig) console.log(`  签名 len=${sig.length}: ${sig.slice(0, 46)}...`);
    else console.log('  （未带签名）');
    console.log(`  HTTP ${r.status}${extra}`);
    console.log('  ' + r.body.slice(0, 150).replace(/\s+/g, ' ') + '\n');
  }
  console.log('---- 怎么读 ----');
  console.log('N0=200 → notice/count 免检签名，sigtest2 的 T0~T2 全 200 没有参考价值，一切以 aweme/post 为准');
  console.log('N0=403 → notice/count 也校验，那么 T1/T2 的 200 说明我们的签名真的有效，问题只在 aweme/post 的参数');
  console.log('A0=200 → 算法+参数都对，直接按这一版改 dyproxy.cjs');
  console.log('A1=200 → 说明 aweme/post 也免检，之前 403 另有原因');
  console.log('A0=403 且 A1=403 → 签名在 aweme/post 上仍不被接受，考虑换生产级参考实现');
})();
