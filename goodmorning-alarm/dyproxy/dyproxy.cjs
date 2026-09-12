// dyproxy.cjs — 抖音用户视频列表代理（v3.1，纯 Cookie 直连 + 账号一致性校验）
// 用法（Docker）：见同目录 docker-compose.yml 或 README.md
// 用法（裸 Node）：PORT=1200 DOUYIN_COOKIE="..." node dyproxy.cjs
//
// v3.1 改动：
//   1) 账号校验：抖音对无效 sec_user_id 不报错，而是返回 Cookie 所属账号的作品（实测），
//      必须校验 author.sec_uid 与请求一致，否则 502，绝不转发他人数据；
//   2) create_time 缺失时 pubDate 用 epoch 0（App 端视为未知日期），
//      不得用当前时间冒充（会让旧视频伪装成最新被选中）；
//   3) 上游单次请求 12s 整体硬超时，3 次重试最坏 38s；
//   4) X-Fetch-At 响应头暴露数据抓取时间；
//   5) 根节点返回 title = 博主昵称（aweme_list[0].author.nickname），供 App 显示博主真名。
//
// 返回格式（App 端约定）：
// {
//   "title": "博主昵称",
//   "items": [ { "title", "link", "pubDate"(ISO-8601), "description":"<video src=...>" } ]
// }
'use strict';

const http = require('http');
const https = require('https');

const PORT = Number(process.env.PORT || 1200);
const COOKIE = process.env.DOUYIN_COOKIE || '';
const SEC_UID = process.env.SEC_UID || 'MS4wLjABAAAAkme-Sn9GBLHkPFE6TSfhhmHbEfphTt7ZNL9BD14NWAneay8H7OxJQ05-CP9VgmSJ';
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36';
const FETCH_TIMEOUT_MS = 12_000;
const RETRY_ATTEMPTS = 3;
const RETRY_SLEEP_MS = 1_000;

function log(...a) { console.log(new Date().toISOString(), ...a); }

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

// 整体硬超时：无论 socket 是否还在滴数据，timeoutMs 内必有结果
function httpGet(url, headers) {
  return new Promise((resolve, reject) => {
    const mod = url.startsWith('https') ? https : http;
    const req = mod.get(url, { headers }, res => {
      let data = '';
      res.on('data', c => data += c);
      res.on('end', () => { clearTimeout(timer); resolve({ status: res.statusCode, body: data }); });
      res.on('error', e => { clearTimeout(timer); reject(e); });
    });
    const timer = setTimeout(() => req.destroy(new Error(`上游整体超时 ${FETCH_TIMEOUT_MS}ms`)), FETCH_TIMEOUT_MS);
    req.on('error', e => { clearTimeout(timer); reject(e); });
  });
}

// 账号不一致错误：确定性失败，不参与重试
class AccountMismatch extends Error {}

// 直连抖音作品列表 API（带 Cookie，重试 3 次）
async function fetchUserVideos(secUid) {
  const params = new URLSearchParams({
    device_platform: 'webapp', aid: '6383', channel: 'channel_pc_web',
    sec_user_id: secUid, count: '10', max_cursor: '0',
    locate_query: 'false', publish_video_strategy_type: '2'
  });
  const url = 'https://www.douyin.com/aweme/v1/web/aweme/post/?' + params.toString();
  const headers = {
    'User-Agent': UA,
    'Cookie': COOKIE,
    'Referer': 'https://www.douyin.com/'
  };
  let lastErr = null;
  for (let i = 0; i < RETRY_ATTEMPTS; i++) {
    try {
      const res = await httpGet(url, headers);
      if (res.status !== 200 || !res.body) { lastErr = new Error(`HTTP ${res.status} 空响应`); await sleep(RETRY_SLEEP_MS); continue; }
      const json = JSON.parse(res.body);
      if (json.status_code !== 0) { lastErr = new Error(`status_code=${json.status_code}`); await sleep(RETRY_SLEEP_MS); continue; }
      const list = json.aweme_list || [];
      if (!list.length) { lastErr = new Error('aweme_list 为空'); await sleep(RETRY_SLEEP_MS); continue; }
      // 账号一致性校验（v3 核心）：校验作品作者与请求账号一致
      const withAuthor = list.filter(v => v && v.author && v.author.sec_uid);
      if (withAuthor.length && !withAuthor.some(v => v.author.sec_uid === secUid)) {
        throw new AccountMismatch(`返回数据属于 ${String(withAuthor[0].author.sec_uid).slice(0, 16)}…，非请求的 ${secUid.slice(0, 16)}…`);
      }
      return mapFeed(list);
    } catch (e) {
      if (e instanceof AccountMismatch) throw e; // 不重试，立即失败
      lastErr = e; await sleep(RETRY_SLEEP_MS);
    }
  }
  throw lastErr || new Error('请求失败');
}

function pickPlayUrl(video) {
  if (!video) return null;
  const list = (video.play_addr && video.play_addr.url_list) || [];
  // 优先 CDN 直链（跳过 douyin.com/aweme 接口地址）
  for (const u of list) {
    if (u && !u.includes('douyin.com/aweme') && !u.split('?')[0].includes('/play')) return u;
  }
  return list.length ? list[list.length - 1] : null;
}

function mapFeed(awemeList) {
  const items = (awemeList || [])
    .filter(v => v && v.aweme_id)
    .map(v => {
      const id = String(v.aweme_id);
      const title = v.desc || '';
      const ts = v.create_time || 0;
      // 缺 create_time 用 epoch 0，App 端视为未知日期（绝不冒充新发布）
      const pubDate = ts ? new Date(ts * 1000).toISOString() : '1970-01-01T00:00:00.000Z';
      const playUrl = pickPlayUrl(v.video);
      const description = playUrl ? `<video src="${playUrl}">` : title;
      return {
        title: title || `早安 ${id}`,
        link: `https://www.douyin.com/video/${id}`,
        pubDate,
        description
      };
    });
  // v3.1：根节点返回博主昵称（App 端「当前博主」名称来源）
  const nick = (awemeList || []).map(v => v && v.author && v.author.nickname).find(n => n) || '';
  return { title: nick, items };
}

// ---- HTTP 服务 ----
const server = http.createServer(async (req, res) => {
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.setHeader('Access-Control-Allow-Origin', '*');
  let pathname = '/';
  try { pathname = new URL(req.url, `http://localhost:${PORT}`).pathname; } catch (e) {}
  if (pathname === '/' || pathname === '/health') {
    res.end(JSON.stringify({ ok: true, service: 'dyproxy', version: '3.1', port: PORT }));
    return;
  }
  const m = pathname.match(/^\/douyin\/user\/([^/]+)/);
  if (!m) { res.statusCode = 404; res.end(JSON.stringify({ error: { message: 'unknown route' } })); return; }
  const secUid = m[1];
  try {
    const feed = await fetchUserVideos(secUid);
    res.setHeader('X-Fetch-At', new Date().toISOString());
    res.end(JSON.stringify(feed));
    log('OK', secUid.slice(0, 10), feed.items.length, '条');
  } catch (e) {
    log('ERR', secUid.slice(0, 10), e.message);
    // 账号不一致 / 上游失败：502 让 App 端归为 NETWORK: 并走本地缓存兜底
    res.statusCode = 502;
    res.end(JSON.stringify({ error: { message: e.message } }));
  }
});

server.listen(PORT, () => log(`dyproxy v3.1 已启动：http://localhost:${PORT}`));
