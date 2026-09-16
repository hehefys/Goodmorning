// dyproxy.cjs — 抖音用户视频列表代理（v4.0，浏览器抓取 + 直连兜底）
//
// 为什么要有 v4.0：
//   抖音的 ArgusSecurityPlugin 会按出口 IP 画像分类放行 —— 被归为「非可信住宅」的 IP
//   （机房、校园网、移动蜂窝等）一律要求 uifid + a_bogus 签名，纯 HTTP 直连必然 403。
//   本版改为让**真浏览器**去访问博主主页，拦截抖音自己发出的作品列表响应：
//   指纹与签名由浏览器自动完成，无需逆向任何算法。
//
// 抓取模式（FETCH_MODE）：
//   auto    （默认）先试轻量 HTTP 直连，失败再退回浏览器 —— 抖音放宽时自动回到快路径
//   browser 只用浏览器
//   http    只用直连（旧行为，仅用于对比排查）
//
// 对 App 端完全兼容：路由、返回格式、状态码、X-Fetch-At 响应头均与 v3.1 一致。
//
// v3.1 起保留的核心行为（勿删）：
//   1) 账号校验：抖音对无效 sec_user_id 不报错而是返回 Cookie 所属账号的作品，必须校验
//      author.sec_uid 与请求一致，否则 502，绝不转发他人数据；
//   2) create_time 缺失时 pubDate 用 epoch 0（App 视为未知日期），不得用当前时间冒充；
//   3) 根节点 title = 博主昵称（aweme_list[0].author.nickname）。
//
// 返回格式（App 端约定）：
// {
//   "title": "博主昵称",
//   "items": [ { "title", "link", "pubDate"(ISO-8601), "description":"<video src=...>" } ]
// }
'use strict';

const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');

// playwright 完整包（自带浏览器管理）或 playwright-core（复用系统浏览器）都能用
let chromium = null;
try {
  ({ chromium } = require('playwright'));
} catch (e) {
  try {
    ({ chromium } = require('playwright-core'));
  } catch (e2) {
    chromium = null;
  }
}

// 兜底：没有通过环境变量注入时，自动读取同目录下的 .env
(function loadDotEnv() {
  try {
    const p = path.join(__dirname, '.env');
    if (!fs.existsSync(p)) return;
    for (const line of fs.readFileSync(p, 'utf8').split(/\r?\n/)) {
      const s = line.trim();
      if (!s || s[0] === '#') continue;
      const i = s.indexOf('=');
      if (i <= 0) continue;
      const k = s.slice(0, i).trim();
      if (process.env[k] === undefined) process.env[k] = s.slice(i + 1);
    }
  } catch (e) {
    log('WARN 读取 .env 失败，改用环境变量：' + e.message);
  }
})();

const PORT = Number(process.env.PORT || 1200);
const COOKIE = process.env.DOUYIN_COOKIE || '';
const SEC_UID = process.env.SEC_UID || 'MS4wLjABAAAAkme-Sn9GBLHkPFE6TSfhhmHbEfphTt7ZNL9BD14NWAneay8H7OxJQ05-CP9VgmSJ';
const UA = process.env.DOUYIN_UA ||
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36';
const CHROME_PATH = process.env.CHROME_PATH || '';
const FETCH_MODE = (process.env.FETCH_MODE || 'auto').toLowerCase();
const CACHE_TTL_MS = Number(process.env.CACHE_TTL_MS || 600_000);      // 10 分钟
const HTTP_TIMEOUT_MS = Number(process.env.HTTP_TIMEOUT_MS || 12_000);
const BROWSER_TIMEOUT_MS = Number(process.env.BROWSER_TIMEOUT_MS || 35_000);
const COUNT = Number(process.env.COUNT || 20);                          // 浏览器抓取顺带拿更多
const VERSION = '4.0';

function log(...a) { console.log(new Date().toISOString(), ...a); }
function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

// ==================== 浏览器 ====================

let browserPromise = null;

function launchOptions() {
  const o = {
    headless: true,
    args: [
      '--disable-blink-features=AutomationControlled',
      '--no-sandbox',
      '--disable-dev-shm-usage',
      '--disable-gpu',
      '--mute-audio',
    ],
  };
  if (CHROME_PATH) o.executablePath = CHROME_PATH;
  return o;
}

async function getBrowser() {
  if (chromium === null) throw new Error('未安装 playwright/playwright-core，无法使用浏览器模式');
  if (browserPromise) {
    const b = await browserPromise.catch(() => null);
    if (b && b.isConnected()) return b;
    browserPromise = null;
  }
  browserPromise = chromium.launch(launchOptions()).then(b => {
    log('浏览器已启动', CHROME_PATH || '(playwright 自带 chromium)');
    b.on('disconnected', () => { browserPromise = null; log('WARN 浏览器已断开，下次请求将重启'); });
    return b;
  }).catch(e => { browserPromise = null; throw e; });
  return browserPromise;
}

function parseCookieToJar(raw) {
  return raw.split('; ').map(s => {
    const i = s.indexOf('=');
    if (i <= 0) return null;
    return { name: s.slice(0, i).trim(), value: s.slice(i + 1), domain: '.douyin.com', path: '/' };
  }).filter(c => c && c.name && c.value);
}

// 串行化：同一时刻只跑一个抓取任务，避免并发开多个浏览器上下文
let fetchLock = Promise.resolve();
function withLock(fn) {
  const p = fetchLock.then(fn, fn);
  fetchLock = p.then(() => {}, () => {});
  return p;
}

// 用真浏览器打开博主主页，拦截抖音自己发出的作品列表响应
async function fetchViaBrowser(secUid) {
  const browser = await getBrowser();
  const ctx = await browser.newContext({
    userAgent: UA,
    viewport: { width: 1707, height: 960 },
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
  });

  try {
    if (COOKIE) await ctx.addCookies(parseCookieToJar(COOKIE));

    // 屏蔽图片/媒体/字体/样式，只保留文档与 XHR —— 大幅提速且不影响数据
    await ctx.route('**/*', route => {
      const t = route.request().resourceType();
      if (t === 'image' || t === 'media' || t === 'font' || t === 'stylesheet') return route.abort();
      return route.continue();
    });

    const page = await ctx.newPage();

    let settled = false;
    let resolveFeed;
    const feedPromise = new Promise(r => { resolveFeed = r; });

    page.on('response', async res => {
      if (settled) return;
      const u = res.url();
      if (!u.includes('/aweme/v1/web/aweme/post/')) return;
      if (res.status() !== 200) return;
      try {
        const j = await res.json();
        if (j && Array.isArray(j.aweme_list) && j.aweme_list.length) {
          settled = true;
          resolveFeed(j);
        }
      } catch (e) { /* 非 JSON，忽略 */ }
    });

    const timer = setTimeout(() => {
      if (!settled) { settled = true; resolveFeed(null); }
    }, BROWSER_TIMEOUT_MS);

    const target = `https://www.douyin.com/user/${secUid}`;
    page.goto(target, { waitUntil: 'domcontentloaded', timeout: BROWSER_TIMEOUT_MS })
      .catch(e => log('WARN 页面导航异常：' + e.message));

    const json = await feedPromise;
    clearTimeout(timer);

    if (!json) {
      const t = await page.title().catch(() => '');
      throw new Error(`浏览器未捕获到作品列表（页面标题「${t}」）—— 可能加载超时或被要求人机验证`);
    }
    return mapFeedCheck(json.aweme_list, secUid);
  } finally {
    await ctx.close().catch(() => {});
  }
}

// ==================== HTTP 直连（旧路径，作为 auto 模式的快路径）====================

function httpGet(url, headers) {
  return new Promise((resolve, reject) => {
    const mod = url.startsWith('https') ? https : http;
    const req = mod.get(url, { headers }, res => {
      let data = '';
      res.on('data', c => data += c);
      res.on('end', () => { clearTimeout(timer); resolve({ status: res.statusCode, body: data }); });
      res.on('error', e => { clearTimeout(timer); reject(e); });
    });
    const timer = setTimeout(() => req.destroy(new Error(`上游整体超时 ${HTTP_TIMEOUT_MS}ms`)), HTTP_TIMEOUT_MS);
    req.on('error', e => { clearTimeout(timer); reject(e); });
  });
}

class AccountMismatch extends Error {}

async function fetchViaHttp(secUid) {
  const params = new URLSearchParams({
    device_platform: 'webapp', aid: '6383', channel: 'channel_pc_web',
    sec_user_id: secUid, count: '10', max_cursor: '0',
    locate_query: 'false', publish_video_strategy_type: '2'
  });
  const url = 'https://www.douyin.com/aweme/v1/web/aweme/post/?' + params.toString();
  const res = await httpGet(url, {
    'User-Agent': UA,
    'Cookie': COOKIE,
    'Referer': 'https://www.douyin.com/'
  });
  if (res.status !== 200 || !res.body) throw new Error(`HTTP ${res.status} 空响应`);
  const json = JSON.parse(res.body);
  if (json.status_code !== 0) throw new Error(`status_code=${json.status_code}`);
  const list = json.aweme_list || [];
  if (!list.length) throw new Error('aweme_list 为空');
  return mapFeedCheck(list, secUid);
}

// 账号一致性校验 + 映射（v3 核心，两条路径共用）
function mapFeedCheck(awemeList, secUid) {
  const list = awemeList || [];
  const withAuthor = list.filter(v => v && v.author && v.author.sec_uid);
  if (withAuthor.length && !withAuthor.some(v => v.author.sec_uid === secUid)) {
    throw new AccountMismatch(
      `返回数据属于 ${String(withAuthor[0].author.sec_uid).slice(0, 16)}…，非请求的 ${secUid.slice(0, 16)}…`
    );
  }
  return mapFeed(list);
}

function pickPlayUrl(video) {
  if (!video) return null;
  const list = (video.play_addr && video.play_addr.url_list) || [];
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
  const nick = (awemeList || []).map(v => v && v.author && v.author.nickname).find(n => n) || '';
  return { title: nick, items };
}

// ==================== 统一入口（带缓存 + 模式选择）====================

const cache = new Map();   // secUid -> { at, feed }

async function getFeed(secUid) {
  const hit = cache.get(secUid);
  if (hit && Date.now() - hit.at < CACHE_TTL_MS) {
    log('CACHE', secUid.slice(0, 10), hit.feed.items.length, '条');
    return { feed: hit.feed, cached: true };
  }

  const feed = await withLock(async () => {
    const again = cache.get(secUid);
    if (again && Date.now() - again.at < CACHE_TTL_MS) return again.feed;

    let feed;
    if (FETCH_MODE === 'http') {
      feed = await fetchViaHttp(secUid);
    } else if (FETCH_MODE === 'browser') {
      feed = await fetchViaBrowser(secUid);
    } else {
      // auto：先用轻量直连探一次，走不通再上浏览器
      try {
        feed = await fetchViaHttp(secUid);
        log('直连命中');
      } catch (e) {
        if (e instanceof AccountMismatch) throw e;
        log('直连失败（' + e.message + '），改用浏览器');
        feed = await fetchViaBrowser(secUid);
      }
    }
    cache.set(secUid, { at: Date.now(), feed });
    return feed;
  });

  return { feed, cached: false };
}

// ==================== HTTP 服务 ====================

const server = http.createServer(async (req, res) => {
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.setHeader('Access-Control-Allow-Origin', '*');
  let pathname = '/';
  try { pathname = new URL(req.url, `http://localhost:${PORT}`).pathname; } catch (e) {}

  if (pathname === '/' || pathname === '/health') {
    res.end(JSON.stringify({
      ok: true, service: 'dyproxy', version: VERSION, port: PORT,
      mode: FETCH_MODE, playwright: chromium ? 'available' : 'missing'
    }));
    return;
  }

  const m = pathname.match(/^\/douyin\/user\/([^/]+)/);
  if (!m) { res.statusCode = 404; res.end(JSON.stringify({ error: { message: 'unknown route' } })); return; }

  const secUid = m[1];
  try {
    const { feed, cached } = await getFeed(secUid);
    res.setHeader('X-Fetch-At', new Date().toISOString());
    res.setHeader('X-Cache', cached ? 'HIT' : 'MISS');
    res.end(JSON.stringify(feed));
    log('OK', secUid.slice(0, 10), feed.items.length, '条', cached ? '(cache)' : '');
  } catch (e) {
    log('ERR', secUid.slice(0, 10), e.message);
    res.statusCode = 502;
    res.end(JSON.stringify({ error: { message: e.message } }));
  }
});

server.listen(PORT, () => {
  log(`dyproxy v${VERSION} 已启动：http://localhost:${PORT}`);
  log(`  抓取模式=${FETCH_MODE}  缓存=${CACHE_TTL_MS / 1000}s  playwright=${chromium ? 'ok' : '缺失'}`);
  if (FETCH_MODE === 'browser' || FETCH_MODE === 'auto') {
    getBrowser().catch(e => log('WARN 预启动浏览器失败（不影响启动，首次请求会重试）：' + e.message));
  }
});

process.on('SIGTERM', async () => {
  log('收到 SIGTERM，正在关闭…');
  const b = browserPromise ? await browserPromise.catch(() => null) : null;
  if (b) await b.close().catch(() => {});
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 3000);
});
