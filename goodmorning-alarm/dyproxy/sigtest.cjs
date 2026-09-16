// sigtest.cjs — a_bogus 签名线上联调（一次性跑完多个变体）
//
// 在服务器上运行（利用现成的 rsshub-ready 镜像里的 node，宿主机无需装 Node）：
//   cd ~/dyproxy
//   docker run --rm -v ~/dyproxy:/w -w /w --entrypoint node rsshub-ready sigtest.cjs
//
// 需要同目录下有 .env（里面至少有 DOUYIN_COOKIE）。

const fs = require('fs');
const https = require('https');
const { abogus } = require('./a_bogus.cjs');

// ---------- 读 .env ----------
function readEnv() {
  const out = {};
  for (const line of fs.readFileSync('.env', 'utf8').split(/\r?\n/)) {
    const i = line.indexOf('=');
    if (i > 0) out[line.slice(0, i).trim()] = line.slice(i + 1);
  }
  return out;
}
const env = readEnv();
const COOKIE = (env.DOUYIN_COOKIE || '').trim();

// 新变量还没写进 .env 时，用抓包里的值兜底，保证现在就能跑
const UA = env.DOUYIN_UA || 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:156.0) Gecko/20100101 Firefox/156.0';
const UIFID = env.DOUYIN_UIFID || 'aa604c70eae1952c0be4020cf2c63575a417d2a1163fec98e1823467fc9ddc8cf665a36b43da074159f13b52a5ddf68b93928b208fe6f9c092c00807f851e9ac567eef7717faee53caa195812fc3ce9b8d964356ff99b73a3134d735a20b09b37ec0ea062bd2df45120c2dfd1961e612194a49a304821a820aaf41ed988488208406b7384b30875f03abf689cbfe69eb14d60e35a6ac39d307969e7b7957cda6';
const WEBID = env.DOUYIN_WEBID || '7684522545157244416';
const MSTOKEN = env.DOUYIN_MSTOKEN || '46eAaMwt-rXUdsKU0YkRT5v77vv1uWb2amMsRUEAs2cs_PBpqnwu55ZAM8zJabOWIHyKow2yY2cjD4mNOBn3PNKBoIpkzAlFY5N74lqZRYI2qqikvpB_mg6sAySxO-dtaJ7yH1loEu-AvZpa2Jf2F8t2AViS1wdtsu8Um039nGjx7C8XAXG8FWQxQw%3D%3D';
const VFP = env.DOUYIN_VERIFY_FP || 'verify_mtxyxdm5_MuktC14B_PwwZ_46ce_BfM9_Jfft8HGJcRjg';
const SEC_UID = env.DOUYIN_SEC_UID || 'MS4wLjABAAAAkme-Sn9GBLHkPFE6TSfhhmHbEfphTt7ZNL9BD14NWAneay8H7OxJQ05-CP9VgmSJ';

const PATH0 = '/aweme/v1/web/aweme/post/';
const BASE = 'device_platform=webapp&aid=6383&channel=channel_pc_web'
  + `&sec_user_id=${SEC_UID}&count=10&max_cursor=0&locate_query=false&publish_video_strategy_type=2`;
const EXTRA = `&webid=${WEBID}&uifid=${UIFID}&verifyFp=${VFP}&fp=${VFP}&msToken=${MSTOKEN}`;
const FINGER = '&update_version_code=170400&pc_client_type=1&pc_libra_divert=Windows&support_h265=1&support_dash=1'
  + '&version_code=170400&version_name=17.4.0&cookie_enabled=true&screen_width=1707&screen_height=960'
  + '&browser_language=zh-CN&browser_platform=Win32&browser_name=Firefox&browser_version=156.0'
  + '&browser_online=true&engine_name=Gecko&engine_version=156.0&os_name=Windows&os_version=10'
  + '&device_memory=&platform=PC';

function get(url, headers) {
  return new Promise((resolve) => {
    const req = https.get(url, { headers }, (res) => {
      let d = '';
      res.on('data', (c) => (d += c));
      res.on('end', () => resolve({ status: res.statusCode, body: d }));
    });
    req.setTimeout(25000, () => req.destroy(new Error('timeout')));
    req.on('error', (e) => resolve({ status: 0, body: 'ERR ' + e.message }));
  });
}

const HEADERS = {
  'User-Agent': UA,
  'Accept': 'application/json, text/plain, */*',
  'Accept-Language': 'zh-CN,zh;q=0.9,zh-TW;q=0.8',
  'Referer': 'https://www.douyin.com/user/MS4wLjABAAAASzz3-5_dxHYk-C4eBttxNdatG81A9jigMCq-KVCkSX4?from_tab_name=main',
  'uifid': UIFID,
  'Sec-Fetch-Dest': 'empty',
  'Sec-Fetch-Mode': 'cors',
  'Sec-Fetch-Site': 'same-origin',
  'Cookie': COOKIE,
};

const variants = [
  { name: 'V1 完整参数 + 签名（uri=纯 query）', query: () => BASE + EXTRA + FINGER, signUri: (q) => q },
  { name: 'V2 完整参数 + 签名（uri=含 path）', query: () => BASE + EXTRA + FINGER, signUri: (q) => PATH0 + '?' + q },
  { name: 'V3 精简参数 + 签名（原参数+uifid）', query: () => BASE + `&uifid=${UIFID}`, signUri: (q) => q },
  { name: 'V4 完整参数但不签名（对照）', query: () => BASE + EXTRA + FINGER, signUri: null },
];

(async () => {
  console.log('Cookie 长度:', COOKIE.length);
  console.log('UA        :', UA.slice(0, 60) + '...');
  console.log('UIFID 长度:', UIFID.length, ' WEBID:', WEBID, '\n');

  for (const v of variants) {
    const q = v.query();
    const url = 'https://www.douyin.com' + PATH0 + '?' + q
      + (v.signUri ? '&a_bogus=' + encodeURIComponent(abogus(v.signUri(q))) : '');
    const r = await get(url, HEADERS);
    let extra = '';
    if (r.body && r.body.trim().startsWith('{')) {
      try {
        const j = JSON.parse(r.body);
        extra = ` status_code=${j.status_code} aweme_list=${(j.aweme_list || []).length}`;
      } catch (e) { extra = ' (JSON 解析失败)'; }
    }
    console.log(`=== ${v.name} ===`);
    console.log(`HTTP ${r.status}${extra}`);
    console.log('  ' + r.body.slice(0, 180).replace(/\s+/g, ' '));
    console.log('');
  }
  console.log('---- 怎么读 ----');
  console.log('V1/V2/V3 任一 200 → 签名有效，我按那一版改 dyproxy.cjs');
  console.log('V1 与 V2 结果不同 → uri 要不要带 path 就定了');
  console.log('V4 应 403 Signature Not Found（对照）');
  console.log('全 403 且仍是 Signature → 签名算法/参数顺序不对，需要继续调');
})();
