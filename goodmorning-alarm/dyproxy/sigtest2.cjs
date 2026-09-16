// sigtest2.cjs — a_bogus 算法「一锤定音」对照实验
//
// 思路：用浏览器抓包里那条原始请求（参数与顺序一字不改），只替换 a_bogus。
//   T0 用抓包原装的 a_bogus  → 已验证过是 200，用来证明本脚本的请求骨架是对的
//   T1 用我们算的 a_bogus（uri = 纯 query）
//   T2 用我们算的 a_bogus（uri = 含 path）
// 若 T0=200 而 T1/T2=403 → 算法本身产出的签名不被抖音接受（不是参数问题）
//
// 运行（服务器）：
//   cd ~/dyproxy && docker run --rm -v ~/dyproxy:/w -w /w --entrypoint node rsshub-ready sigtest2.cjs

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

const PATH0 = '/aweme/v1/web/notice/count/';

// 浏览器抓包的原始 query（不含 a_bogus）
const CAP_QUERY =
  'device_platform=webapp&aid=6383&channel=channel_pc_web&is_new_notice=1&need_social_count=1' +
  '&update_version_code=170400&pc_client_type=1&pc_libra_divert=Windows&support_h265=1&support_dash=1' +
  '&cpu_core_num=16&version_code=170400&version_name=17.4.0&cookie_enabled=true&screen_width=1707' +
  '&screen_height=960&browser_language=zh-CN&browser_platform=Win32&browser_name=Firefox' +
  '&browser_version=156.0&browser_online=true&engine_name=Gecko&engine_version=156.0' +
  '&os_name=Windows&os_version=10&device_memory=&platform=PC' +
  '&webid=7684522545157244416' +
  `&uifid=${UIFID}` +
  '&verifyFp=verify_mtxyxdm5_MuktC14B_PwwZ_46ce_BfM9_Jfft8HGJcRjg' +
  '&fp=verify_mtxyxdm5_MuktC14B_PwwZ_46ce_BfM9_Jfft8HGJcRjg' +
  '&msToken=46eAaMwt-rXUdsKU0YkRT5v77vv1uWb2amMsRUEAs2cs_PBpqnwu55ZAM8zJabOWIHyKow2yY2cjD4mNOBn3PNKBoIpkzAlFY5N74lqZRYI2qqikvpB_mg6sAySxO-dtaJ7yH1loEu-AvZpa2Jf2F8t2AViS1wdtsu8Um039nGjx7C8XAXG8FWQxQw%3D%3D';

// 抓包原装的 a_bogus（已 URL 解码），T0 对照用
const CAP_SIG =
  'DXsbhtyJQZ/fOdMG8CnJSa1U4t6lNPuyZrTdRncTyNOjLZtTEmPl/xayjxzvszRGybBTheV7IVU/bddcp0XkpCrkKmkvuFw6Iz2n9g0o/qqdTFt0LHSPCLfFww0SUbzqe/nHiIs51ssJID25INAmAp3ae5zL5Oy2WNM9pMz9jDS8pBgTVo/lCrJAlXL=';

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

(async () => {
  console.log('Cookie 长度:', COOKIE.length);
  console.log('对照 query 长度:', CAP_QUERY.length);

  const cases = [
    { name: 'T0 对照：抓包原装 a_bogus', sig: CAP_SIG },
    { name: 'T1 我们算的（uri = 纯 query）', sig: abogus(CAP_QUERY) },
    { name: 'T2 我们算的（uri = 含 path）', sig: abogus(PATH0 + '?' + CAP_QUERY) },
  ];

  for (const c of cases) {
    const url = 'https://www.douyin.com' + PATH0 + '?' + CAP_QUERY
      + '&a_bogus=' + encodeURIComponent(c.sig);
    const r = await get(url);
    let extra = '';
    if (r.body && r.body.trim().startsWith('{')) {
      try {
        const j = JSON.parse(r.body);
        extra = ` status_code=${j.status_code}`;
      } catch (e) { extra = ' (JSON 解析失败)'; }
    }
    console.log(`\n=== ${c.name} ===`);
    console.log(`  签名: ${c.sig.slice(0, 46)}... (len=${c.sig.length})`);
    console.log(`  HTTP ${r.status}${extra}`);
    console.log('  ' + r.body.slice(0, 160).replace(/\s+/g, ' '));
  }

  console.log('\n---- 怎么读 ----');
  console.log('T0=200 且 T1/T2=403 → 请求骨架没问题，是我们的签名算法产出的签名不被接受');
  console.log('T0 也是 403 → 本脚本请求骨架有问题（query 抄错），先别管算法');
  console.log('T1 或 T2 = 200 → 算法正确！按那一版改 dyproxy.cjs');
})();
