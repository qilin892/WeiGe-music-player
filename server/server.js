/**
 * 伟哥音乐 · 本地播放服务
 *
 * 职责：搜索歌曲 -> 多源解析直链 -> 转码成设备可播的 OGG/Opus -> 通过局域网静态文件供给设备。
 *
 * 为什么走「先落盘再给 URL」而不是边转边推：
 *   设备侧是 esp_http_client 直接 GET 一个 URL（audio_service/notify_player 那条路径），
 *   静态文件有 Content-Length、支持 Range，最稳；边转边推容易卡在缓冲和超时上。
 *
 * 启动：node server.js [--port 8090]
 * 接口：
 *   GET /api/health
 *   GET /api/search?kw=晴天
 *   GET /api/play?kw=晴天[&index=0]        搜索 + 取流 + 转码，返回可播放 URL
 *   GET /api/play?id=51685512&platform=kw  指定歌曲 id
 *   GET /media/<file>.ogg                  设备拉音频
 */

const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const { Readable } = require('node:stream');
const { pipeline } = require('node:stream/promises');

const { searchAll, searchKuwo, searchQQ, isBadVersion } = require('./lib/search.js');
const {
  resolveUrl,
  probe,
  detectActualQuality,
  getSources,
  getStats,
  effectivePlan,
  probeSources,
} = require('./lib/resolve.js');
const { getHealth, planSources } = require('./lib/health.js');
const { getLyric, getCover } = require('./lib/meta.js');
const { findSameSong, listAlternatives } = require('./lib/match.js');
const { suggestAll } = require('./lib/suggest.js');
// 播放走流式（streamOgg），只有下载到本地时才需要 ensureOgg
const { probeRemoteDuration, streamOgg, CACHE_DIR } = require('./lib/transcode.js');
const {
  ASSET_ROOT,
  DATA_ROOT,
  SAVED_DIR,
  BIN_DIR,
  PACKAGED,
} = require('./lib/paths.js');
const device = require('./lib/device.js');
const mcp = require('./lib/mcp.js');

const PORT = Number(process.argv.includes('--port') ? process.argv[process.argv.indexOf('--port') + 1] : 8090);

function lanIp() {
  const nets = os.networkInterfaces();
  const prefer = [];
  for (const [name, addrs] of Object.entries(nets)) {
    for (const addr of addrs || []) {
      if (addr.family !== 'IPv4' || addr.internal) continue;
      if (addr.address.startsWith('169.254.')) continue;
      prefer.push({ name, address: addr.address });
    }
  }
  // 优先常见家用网段
  prefer.sort((a, b) => {
    const score = (ip) => (/^192\.168\./.test(ip) ? 0 : /^10\./.test(ip) ? 1 : /^172\./.test(ip) ? 2 : 3);
    return score(a.address) - score(b.address);
  });
  return prefer[0]?.address || '127.0.0.1';
}

const LAN_IP = lanIp();
const PUBLIC_BASE = process.env.PUBLIC_BASE || `http://${LAN_IP}:${PORT}`;

// 设备的局域网点播接口（固件里的 MusicHttp，默认 8080）
/** 取命令行参数值：--port 8090 这种 */
function argValue(name) {
  const i = process.argv.indexOf(name);
  return i >= 0 ? process.argv[i + 1] : null;
}

// 音箱地址不再写死：启动参数 > 环境变量 > device.json > 默认值，
// 具体逻辑在 lib/device.js，页面里也能改。
const WEB_DIR = path.join(ASSET_ROOT, 'public');

/** 转码是重活，串行执行，避免多首并发把机器打满 */
let queue = Promise.resolve();
function enqueue(task) {
  const run = queue.then(task, task);
  queue = run.catch(() => {});
  return run;
}

/** 读取并解析 JSON 请求体（限 64KB，本地工具不需要更大） */
function readJsonBody(req, limit = 64 * 1024) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > limit) {
        req.destroy();
        reject(new Error('请求体过大'));
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => {
      if (!chunks.length) return resolve({});
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')));
      } catch {
        reject(new Error('请求体不是合法 JSON'));
      }
    });
    req.on('error', reject);
  });
}

function sendJson(res, code, obj) {
  const body = JSON.stringify(obj, null, 2);
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
  });
  res.end(body);
}

function serveFile(req, res, file, contentType, downloadName) {
  if (!fs.existsSync(file) || !fs.statSync(file).isFile()) {
    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    return res.end('not found');
  }
  const stat = fs.statSync(file);
  const range = req.headers.range;

  const baseHeaders = { 'Accept-Ranges': 'bytes' };
  if (downloadName) {
    // 触发浏览器「另存为」，文件名保留中文原名
    baseHeaders['Content-Disposition'] =
      `attachment; filename*=UTF-8''${encodeURIComponent(downloadName)}`;
  }

  if (range) {
    const m = /bytes=(\d*)-(\d*)/.exec(range);
    const start = m && m[1] ? parseInt(m[1], 10) : 0;
    const end = m && m[2] ? parseInt(m[2], 10) : stat.size - 1;
    res.writeHead(206, {
      ...baseHeaders,
      'Content-Type': contentType,
      'Content-Length': end - start + 1,
      'Content-Range': `bytes ${start}-${end}/${stat.size}`,
    });
    fs.createReadStream(file, { start, end }).pipe(res);
    return;
  }

  res.writeHead(200, {
    ...baseHeaders,
    'Content-Type': contentType,
    'Content-Length': stat.size,
  });
  fs.createReadStream(file).pipe(res);
}

function serveMedia(req, res, fileName) {
  const safe = path.basename(fileName);
  return serveFile(req, res, path.join(CACHE_DIR, safe), 'audio/ogg');
}

/**
 * 原音质直通。
 *
 * 设备只解 Opus/OGG，但电脑/浏览器完全可以直接放 MP3。
 * 这里把解析出来的原始直链登记成短 token，用 /orig/<token>.mp3 透传给浏览器：
 * 不转码、不损音质、秒开；只有「推送到音箱」时才走 OGG 转码那条路。
 * 用 token 而不是直接暴露 URL，避免变成任意地址代理（SSRF）。
 */
// 探测结果短期缓存：同一首歌来回切源 / 重复点播时不必再探一遍
const probeCache = new Map(); // key -> { at, cands }
const PROBE_TTL_MS = 3 * 60 * 1000;

const originTokens = new Map(); // token -> { url, expires }
const ORIGIN_TTL_MS = 30 * 60 * 1000;

/**
 * 流式播放的短链：设备拿到 /live/<token> 才去拉，
 * 服务端凭 token 找回直链再现场转码。有效期给 5 分钟——设备通常一秒内就来拉了。
 */
const liveTokens = new Map(); // token -> { url, song, quality, expires }
const LIVE_TTL_MS = 5 * 60 * 1000;

/** 音源服务器普遍要这两个头，缺了会 403 */
const UPSTREAM_HEADERS = 'User-Agent: Mozilla/5.0\r\nReferer: http://www.kuwo.cn/\r\n';

function rememberLive(url, song, quality) {
  const token = crypto.randomBytes(9).toString('hex');
  liveTokens.set(token, { url, song, quality, expires: Date.now() + LIVE_TTL_MS });
  if (liveTokens.size > 50) {
    const now = Date.now();
    for (const [k, v] of liveTokens) if (v.expires < now) liveTokens.delete(k);
  }
  return token;
}

function lookupLive(token) {
  const item = liveTokens.get(token);
  if (!item) return null;
  if (item.expires < Date.now()) {
    liveTokens.delete(token);
    return null;
  }
  return item;
}

function rememberOrigin(url) {
  const token = crypto.randomBytes(9).toString('hex');
  originTokens.set(token, { url, expires: Date.now() + ORIGIN_TTL_MS });
  // 顺手清理过期项，避免长期运行时无限增长
  if (originTokens.size > 200) {
    const now = Date.now();
    for (const [k, v] of originTokens) if (v.expires < now) originTokens.delete(k);
  }
  return token;
}

function lookupOrigin(token) {
  const item = originTokens.get(token);
  if (!item) return null;
  if (item.expires < Date.now()) {
    originTokens.delete(token);
    return null;
  }
  return item.url;
}

/** 透传上游音频字节，支持 Range，浏览器可直接拖动进度条 */
async function streamOrigin(req, res, token) {
  const upstream = lookupOrigin(token);
  if (!upstream) {
    res.writeHead(410, { 'Content-Type': 'text/plain; charset=utf-8' });
    return res.end('链接已过期，请重新点歌');
  }

  const headers = {
    'User-Agent': 'Mozilla/5.0',
    Referer: 'http://www.kuwo.cn/',
  };
  if (req.headers.range) headers.Range = req.headers.range;

  let upstreamRes;
  try {
    upstreamRes = await fetch(upstream, { headers, redirect: 'follow' });
  } catch (err) {
    res.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8' });
    return res.end(`上游不可达: ${err.message}`);
  }

  const passHeaders = {
    'Content-Type': upstreamRes.headers.get('content-type') || 'audio/mpeg',
    'Accept-Ranges': 'bytes',
    'Cache-Control': 'no-store',
  };
  for (const key of ['content-length', 'content-range']) {
    const v = upstreamRes.headers.get(key);
    if (v) passHeaders[key === 'content-length' ? 'Content-Length' : 'Content-Range'] = v;
  }

  res.writeHead(upstreamRes.status, passHeaders);
  if (!upstreamRes.body) return res.end();
  // 上游超时或客户端中途断开都会让这个流报错；用 pipeline 接住，否则进程会被
  // 未处理的 'error' 事件直接带走（实测下载大文件超时就整服务退出）。
  await pipeline(Readable.fromWeb(upstreamRes.body), res).catch(() => {
    try { res.destroy(); } catch {}
  });
}

/* ---------------------------- 保存到本地 ---------------------------- */

// SAVED_DIR 来自 lib/paths.js：开发时在项目内，打包后在 exe 同级目录
fs.mkdirSync(SAVED_DIR, { recursive: true });

/** 文件名清洗：Windows 非法字符 + 超长截断，避免落盘失败 */
function sanitizeFileName(name) {
  const cleaned = String(name || '')
    .replace(/[\\/:*?"<>|\u0000-\u001f]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 100);
  return cleaned || 'untitled';
}

/**
 * 保存文件名。
 *
 * 320k / 无损 用干净名字（扩展名本身能区分 mp3 / flac），
 * 128k 加后缀：否则先存了 128k 再切 320k，会被误判成「已在本地」而播到低音质那份。
 */
function savedBaseName(song, quality) {
  const base = sanitizeFileName(`${song.artist ? `${song.artist} - ` : ''}${song.title}`);
  return quality === '128k' ? `${base} (128k)` : base;
}

/** 把上游音频流写成文件：先写 .part 再改名，中断不会留下半截文件 */
async function downloadTo(url, dest) {
  const upstream = await fetch(url, {
    headers: { 'User-Agent': 'Mozilla/5.0', Referer: 'http://www.kuwo.cn/' },
    redirect: 'follow',
  });
  if (!upstream.ok || !upstream.body) {
    throw new Error(`上游 HTTP ${upstream.status}`);
  }

  const tmp = `${dest}.part`;
  let size = 0;
  try {
    await pipeline(Readable.fromWeb(upstream.body), fs.createWriteStream(tmp));
    size = fs.statSync(tmp).size;
    // 太小基本是错误页或试听片段，不要留在库里
    if (size < 64 * 1024) throw new Error(`文件只有 ${size} 字节，不是完整音频`);
  } catch (err) {
    try { fs.unlinkSync(tmp); } catch {}
    throw err;
  }

  fs.renameSync(tmp, dest);
  return size;
}

function listSaved() {
  try {
    return fs
      .readdirSync(SAVED_DIR)
      .filter((f) => /\.(mp3|flac|ogg|m4a)$/i.test(f))
      .map((f) => {
        const st = fs.statSync(path.join(SAVED_DIR, f));
        return { name: f, bytes: st.size, mtime: st.mtimeMs };
      })
      .sort((a, b) => b.mtime - a.mtime);
  } catch {
    return [];
  }
}

/** 从 URL 参数还原一首歌（歌词/封面接口共用） */
function songFromParams(params) {
  if (!params.id) return null;
  return {
    id: params.id,
    platform: params.platform || 'kw',
    title: params.name || '',
    artist: params.artist || '',
    album: params.album || '',
    albumMid: params.albumMid || '',
    duration: Number(params.duration) || 0,
  };
}

/**
 * 保存当前这首歌的原始音质文件。
 *
 * 与 /api/play 的区别：play 只给一个可播地址（token 30 分钟过期、依赖上游在线），
 * save 把字节真正落到本地 saved/ 目录，之后离线也能听。
 * 走同一条探测链，所以默认保存的就是界面上「最优音源」那一份。
 */
/**
 * 浏览器下载。
 *
 * 与 /api/save 的区别：save 把文件落到程序目录的 saved/，download 则把字节直接
 * 交给浏览器（Content-Disposition: attachment），由浏览器弹下载/另存为，
 * 存到哪、叫什么名字都由用户和浏览器决定。
 *
 * 取流优先复用「正在播的那条直链」（orig token），保证下载的和听到的是同一份。
 */
async function handleDownload(res, params) {
  const quality = params.quality || '320k';

  let song;
  if (params.id) {
    song = {
      id: params.id,
      title: params.name || '',
      artist: params.artist || '',
      album: params.album || '',
      platform: params.platform || 'kw',
      duration: Number(params.duration) || 0,
    };
    if (params.variants) {
      try {
        const v = JSON.parse(params.variants);
        if (Array.isArray(v)) song.variants = v;
      } catch {}
    }
  } else {
    const kw = params.kw || params.keyword;
    if (!kw) return sendJson(res, 400, { ok: false, error: '缺少 kw 或 id 参数' });
    const list = await searchAll(kw, { limit: 15 });
    if (!list.length) return sendJson(res, 404, { ok: false, error: `搜索不到：${kw}` });
    song = list[0];
  }

  // 1) 正在播的直链
  let pick = null;
  if (params.orig) {
    const originUrl = lookupOrigin(String(params.orig));
    if (originUrl) pick = { url: originUrl, quality };
  }

  // 2) 没有 token（或已过期）→ 探测，主平台 + 同歌其它平台
  if (!pick) {
    const siblings = (Array.isArray(song.variants) ? song.variants : [])
      .filter((v) => v.platform !== (song.platform || 'kw') && v.id !== song.id)
      .filter(
        (v) =>
          !song.duration ||
          !v.duration ||
          Math.abs(Number(v.duration) - Number(song.duration)) <= 5
      )
      .slice(0, 2);

    for (const target of [song, ...siblings]) {
      const cacheKey = `${target.platform || 'kw'}|${target.id}|${quality}`;
      const cached = probeCache.get(cacheKey);
      const cands =
        cached && Date.now() - cached.at < PROBE_TTL_MS
          ? cached.cands
          : await probeSources(target, { quality });
      const ok = cands.filter((c) => c.ok);
      if (!ok.length) continue;
      pick = ok[0];
      break;
    }
  }

  if (!pick) {
    return sendJson(res, 404, { ok: false, error: '这首歌当前没有可下载的音源' });
  }

  const ext = pick.quality === 'flac' ? '.flac' : '.mp3';
  const fileName = `${savedBaseName(song, pick.quality)}${ext}`;

  let upstream;
  try {
    upstream = await fetch(pick.url, {
      headers: { 'User-Agent': 'Mozilla/5.0', Referer: 'http://www.kuwo.cn/' },
      redirect: 'follow',
      signal: AbortSignal.timeout(30000),
    });
  } catch (err) {
    return sendJson(res, 502, { ok: false, error: `音源不可达：${err.message}` });
  }
  if (!upstream.ok || !upstream.body) {
    return sendJson(res, 502, { ok: false, error: `音源返回 HTTP ${upstream.status}` });
  }

  // 中文文件名走 RFC 5987；再给一个纯 ASCII 兜底，老浏览器也不至于乱码
  const asciiName = fileName.replace(/[^\x20-\x7e]/g, '_');
  const headers = {
    'Content-Type': upstream.headers.get('content-type') || 'audio/mpeg',
    'Content-Disposition':
      `attachment; filename="${asciiName}"; filename*=UTF-8''${encodeURIComponent(fileName)}`,
    'Cache-Control': 'no-store',
  };
  const len = upstream.headers.get('content-length');
  if (len) headers['Content-Length'] = len;

  res.writeHead(200, headers);
  await pipeline(Readable.fromWeb(upstream.body), res).catch(() => {
    try { res.destroy(); } catch {}
  });
}

async function handleSave(res, params) {
  const quality = params.quality || '320k';

  let song;
  if (params.id) {
    song = {
      id: params.id,
      title: params.name || '',
      artist: params.artist || '',
      album: params.album || '',
      platform: params.platform || 'kw',
      duration: Number(params.duration) || 0,
    };
    // 前端会带上同歌在其它平台的 id，主平台取不到时可以直接换
    if (params.variants) {
      try {
        const v = JSON.parse(params.variants);
        if (Array.isArray(v)) song.variants = v;
      } catch {
        // 忽略非法参数
      }
    }
  } else {
    const kw = params.kw || params.keyword;
    if (!kw) return sendJson(res, 400, { ok: false, error: '缺少 kw 或 id 参数' });
    const list = await searchAll(kw, { limit: 15 });
    if (!list.length) return sendJson(res, 404, { ok: false, error: `搜索不到：${kw}` });
    const clean = list.filter((x) => !isBadVersion(x));
    const ordered = clean.length ? [...clean, ...list.filter(isBadVersion)] : list;
    song = ordered[Math.min(Number(params.index || 0), ordered.length - 1)];
  }

  const extOf = (q) => (q === 'flac' ? '.flac' : '.mp3');

  /** 落盘并返回响应（已存在则秒回，不重复下 60MB） */
  const saveBytesFrom = async (url, songForName, actualQuality, sourceLabel) => {
    const base = savedBaseName(songForName, actualQuality);
    const dest = path.join(SAVED_DIR, base + extOf(actualQuality));

    if (fs.existsSync(dest)) {
      const st = fs.statSync(dest);
      return sendJson(res, 200, {
        ok: true,
        existed: true,
        name: base + extOf(actualQuality),
        path: dest,
        bytes: st.size,
        quality: actualQuality,
        source: sourceLabel,
        dir: SAVED_DIR,
      });
    }

    const bytes = await downloadTo(url, dest);
    return sendJson(res, 200, {
      ok: true,
      existed: false,
      name: base + extOf(actualQuality),
      path: dest,
      bytes,
      quality: actualQuality,
      source: sourceLabel,
      dir: SAVED_DIR,
    });
  };

  /**
   * 首选：直接复用「当前正在播的直链」。
   *
   * 播放时 /api/play 已经把可用直链登记成 /orig/<token>，浏览器放的就是它。
   * 能播就说明这条链接是活的，保存没必要再从头探测一遍——之前正是这么做的，
   * 结果主平台（QQ）探测失败就报「所有音源都取不到」，明明歌正在放。
   */
  if (params.orig) {
    const originUrl = lookupOrigin(String(params.orig));
    if (originUrl) {
      try {
        return await saveBytesFrom(originUrl, song, quality, params.source || 'playing');
      } catch {
        // 直链过期或上游抖动，落到下面重新探测
      }
    }
  }

  // 其次：探测（主平台 + 同歌其它平台），复用 play 留下的探测缓存
  const siblings = (Array.isArray(song.variants) ? song.variants : [])
    .filter((v) => v.platform !== (song.platform || 'kw') && v.id !== song.id)
    .filter(
      (v) =>
        !song.duration ||
        !v.duration ||
        Math.abs(Number(v.duration) - Number(song.duration)) <= 5
    )
    .slice(0, 2);

  let lastCands = [];
  for (const target of [song, ...siblings]) {
    const cacheKey = `${target.platform || 'kw'}|${target.id}|${quality}`;
    const cached = probeCache.get(cacheKey);
    let cands;
    if (cached && Date.now() - cached.at < PROBE_TTL_MS) {
      cands = cached.cands;
    } else {
      cands = await probeSources(target, { quality });
      if (cands.some((c) => c.ok)) probeCache.set(cacheKey, { at: Date.now(), cands });
    }
    lastCands = cands;

    const usable = cands.filter((c) => c.ok);
    const pick = params.source
      ? usable.find((c) => c.source === params.source) || usable[0]
      : usable[0];
    if (!pick) continue;

    try {
      return await saveBytesFrom(pick.url, target, pick.quality, pick.source);
    } catch (err) {
      lastCands = cands;
    }
  }

  return sendJson(res, 502, {
    ok: false,
    error: '这首歌当前没有可下载的音源',
    candidates: lastCands.map((c) => ({
      source: c.source,
      ok: c.ok,
      reason: c.reason || '',
    })),
  });
}

/**
 * 播放器页面与静态资源。
 * 只按文件名平铺提供，不做子目录递归，避免路径穿越。
 */
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
};

function serveStatic(res, pathname) {
  const name = pathname === '/' ? 'index.html' : path.basename(pathname);
  const file = path.join(WEB_DIR, name);
  let body;
  try {
    body = fs.readFileSync(file);
  } catch {
    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    return res.end('not found');
  }
  res.writeHead(200, {
    'Content-Type': MIME[path.extname(file)] || 'application/octet-stream',
    // 本地工具不需要缓存：改了样式刷新就能看到，避免用户遇到旧页面
    'Cache-Control': 'no-store',
    'Content-Length': body.length,
  });
  res.end(body);
}

/**
 * 设备接口代理。
 * 设备（esp_http_server）没有 CORS 头，浏览器直连会被拦，
 * 所以统一由本服务转一层，前端只跟同源打交道。
 */
async function deviceCall(pathAndQuery, timeout = 8000) {
  const { ip, port } = device.getTarget();
  const target = `http://${ip}:${port}${pathAndQuery}`;
  const res = await fetch(target, { signal: AbortSignal.timeout(timeout) });
  const text = await res.text();
  let data;
  try {
    data = JSON.parse(text);
  } catch {
    data = { ok: false, error: text.slice(0, 120) || '设备返回空响应' };
  }
  return { status: res.status, data };
}

/**
 * 用真实时长对账：搜索结果说 269 秒，转出来只有 145 秒，那就是拿到了错版或试听片段。
 * 只按文件大小判断会误判（音乐段落的 Opus 码率波动很大），时长才靠得住。
 */function looksTruncatedSec(actualSec, expectedSec) {
  if (!expectedSec || expectedSec < 90) return null;
  const actual = Number(actualSec) || 0;
  if (!actual) return null;

  // 太短 = 试听片段；太长 = 串烧/合辑（实测「晴天」被 506 秒的「晴天+告白气球」顶替过），
  // 两头都要拦，只拦短的不够。
  if (actual < expectedSec * 0.75 || actual > expectedSec * 1.35) {
    return `实际时长 ${actual.toFixed(0)}s / 预期 ${expectedSec}s`;
  }
  return null;
}

/** 兼容旧调用：传的是转码结果对象 */
function looksTruncated(ogg, expectedSec) {
  return looksTruncatedSec(ogg && ogg.durationSec, expectedSec);
}

/** 实际播的歌手和请求的不一致时打标记：这是换源后的替代版本，界面上要明示 */
function markFallback(hit, requestedArtist) {
  const norm = (x) => String(x || '').toLowerCase().replace(/\s+/g, '');
  const want = norm(requestedArtist);
  const got = norm(hit.song.artist);
  if (want && got && !got.includes(want) && !want.includes(got)) {
    hit.fallback = true;
    hit.requestedArtist = requestedArtist;
  }
  return hit;
}

/** 尝试一组候选，返回响应体对象或 null（全部失败） */
async function trySongs(songs, quality, failures, tried, { crossPlatform = true } = {}) {
  for (const song of songs) {
    const key = `${song.platform || 'kw'}|${song.id}`;
    if (tried.has(key)) continue;
    tried.add(key);

    try {
      const resolved = await resolveUrl(song, { quality, crossPlatform });

      // 流式：不再「整首下载 + 整首转码」，只快速探一下源时长做对账。
      // 探不到（格式特殊/超时）就按 0 处理，不做拦截——宁可播，也别因为探不到就不播。
      const sourceDur = await probeRemoteDuration(resolved.url, {
        headers: UPSTREAM_HEADERS,
        timeout: 8000,
      });

      const bad = looksTruncatedSec(sourceDur, song.duration);
      if (bad) {
        failures.push(`${song.title}/${song.artist}(${resolved.platform}): ${bad}`);
        continue;
      }

      const liveToken = rememberLive(resolved.url, song, quality);

      return {
        ok: true,
        song: { title: song.title, artist: song.artist, album: song.album, id: song.id },
        source: resolved.source,
        platform: resolved.platform,
        quality,
        bytes: 0, // 流式没有确定大小
        durationSec: Math.round(sourceDur || song.duration || 0),
        cached: false,
        url: `${PUBLIC_BASE}/live/${liveToken}`,
        pcUrl: `${PUBLIC_BASE}/orig/${rememberOrigin(resolved.url)}.mp3`,
        direct: resolved.url,
        duration: song.duration || 0,
        mode: 'stream',
      };
    } catch (err) {
      failures.push(`${song.title}/${song.artist}: ${err.message}`);
      if (err.failures) failures.push(...err.failures.slice(0, 4));
    }
  }
  return null;
}

/** 音质降级链：要无损拿不到就退 320k，再退 128k */
function qualityChain(requested) {
  switch ((requested || '320k').toLowerCase()) {
    case 'flac':
    case 'lossless':
      return ['flac', '320k', '128k'];
    case '128k':
      return ['128k'];
    default:
      return ['320k', '128k'];
  }
}

/**
 * 只解析原音质直链，不转码（电脑端专用，秒开）。
 * 注意：源站对 flac 请求经常原样返回 320k mp3，所以这里按实际内容识别音质，
 * 并在响应里如实给出 requestedQuality / quality（实际）。
 */
async function tryResolveOnly(songs, requestedQuality, failures, tried) {
  for (const song of songs) {
    const key = `pc|${song.platform || 'kw'}|${song.id}`;
    if (tried.has(key)) continue;
    tried.add(key);

    for (const q of qualityChain(requestedQuality)) {
      try {
        const resolved = await resolveUrl(song, { quality: q, log: () => {} });
        const pr = await probe(resolved.url);
        const actual = detectActualQuality(pr, song.duration || 0);
        const ext = actual === 'flac' ? '.flac' : '.mp3';
        const token = rememberOrigin(resolved.url);

        return {
          ok: true,
          song: { title: song.title, artist: song.artist, album: song.album, id: song.id },
          source: resolved.source,
          platform: resolved.platform,
          requestedQuality: (requestedQuality || '320k').toLowerCase(),
          quality: actual,
          downgraded: actual !== 'flac' && (requestedQuality || '').toLowerCase() === 'flac',
          contentType: pr.contentType,
          totalBytes: pr.total || 0,
          pcUrl: `${PUBLIC_BASE}/orig/${token}${ext}`,
          duration: song.duration || 0,
          mode: 'pc',
        };
      } catch (err) {
        failures.push(`${song.title}/${song.artist}@${q}: ${err.message}`);
      }
    }
  }
  return null;
}

async function handlePlay(res, params) {
  const quality = params.quality || '128k';
  const failures = [];
  const tried = new Set();

  let firstRound;
  let title = '';
  let artist = '';

  if (params.id) {
    const song = {
      id: params.id,
      title: params.name || '',
      artist: params.artist || '',
      album: params.album || '',
      platform: params.platform || 'kw',
      duration: Number(params.duration) || 0,
    };
    // 前端会把「同一首歌在其它平台的 id」带过来（搜索结果的 variants），
    // 有了它就能并行探测多平台，不用先等主平台全灭再重新搜索。
    if (params.variants) {
      try {
        const v = JSON.parse(params.variants);
        if (Array.isArray(v)) song.variants = v;
      } catch {
        // 忽略非法参数
      }
    }
    title = song.title;
    artist = song.artist;
    firstRound = [song];
  } else {
    const kw = params.kw || params.keyword;
    if (!kw) return sendJson(res, 400, { ok: false, error: '缺少 kw 或 id 参数' });

    const list = await searchAll(kw, { limit: 15 });
    if (!list.length) return sendJson(res, 404, { ok: false, error: `搜索不到：${kw}` });

    // 优先原版：把伴奏/现场/翻唱/DJ 版排到后面
    const clean = list.filter((x) => !isBadVersion(x));
    const ordered = clean.length ? [...clean, ...list.filter(isBadVersion)] : list;
    const start = Math.min(Number(params.index || 0), ordered.length - 1);
    firstRound = ordered.slice(start, start + 3);
    title = firstRound[0]?.title || kw;
    artist = firstRound[0]?.artist || '';
  }

  // probe=1：并行问所有源，返回完整候选清单，并默认选中「音质最好 + 最快」的那个
  if ((params.target || 'device') === 'pc' && params.probe === '1') {
    const song = firstRound[0];
    const quality = params.quality || '320k';

    /**
     * 同歌的其它平台版本（来自搜索时的 variants）。
     * 时长必须对得上（±5 秒），否则宁可不用 —— 避免拿同名的另一版本顶替。
     */
    const variants = Array.isArray(song.variants) ? song.variants : [];
    const siblings = variants
      .filter((v) => v.platform !== (song.platform || 'kw') && v.id !== song.id)
      .filter(
        (v) =>
          !song.duration ||
          !v.duration ||
          Math.abs(Number(v.duration) - Number(song.duration)) <= 5
      )
      .slice(0, 2);

    // 并行探测：主平台 + 同歌的其它平台同时开跑。
    // 关键是「第一个成功就返回」——QQ 整条挂掉时会重试到 7 秒，
    // 若等 Promise.all，用户就要白等这么久，而酷我那边 1 秒内就已经拿到了。
    const jobs = [song, ...siblings].map(async (target, index) => {
      const key = `${target.platform || 'kw'}|${target.id}|${quality}`;
      const hit = probeCache.get(key);
      let list = hit && Date.now() - hit.at < PROBE_TTL_MS ? hit.cands : null;
      if (!list) {
        list = await probeSources(target, { quality });
        // 全失败的结果不缓存：上游常常几十秒就恢复
        if (list.some((c) => c.ok)) probeCache.set(key, { at: Date.now(), cands: list });
        if (probeCache.size > 120) {
          for (const [k, v] of probeCache) {
            if (Date.now() - v.at > PROBE_TTL_MS) probeCache.delete(k);
          }
        }
      }
      return { target, cands: list, isPrimary: index === 0 };
    });

    const winner = await new Promise((resolve) => {
      let remaining = jobs.length;
      for (const job of jobs) {
        job
          .then((r) => {
            if (r.cands.some((c) => c.ok)) resolve(r);
            else if (--remaining === 0) resolve(null);
          })
          .catch(() => {
            if (--remaining === 0) resolve(null);
          });
      }
    });

    let targetSong = song;
    let cands;
    let pick = null;

    if (winner) {
      targetSong = winner.target;
      cands = winner.cands;
      const ok = cands.filter((c) => c.ok);
      pick = (params.source && ok.find((c) => c.source === params.source)) || ok[0];
    } else {
      // 全灭：拿主平台的结果构造 candidates 明细
      cands = (await Promise.all(jobs)).find((x) => x.isPrimary).cands;
    }

    let alternatives = [];

    // 当前平台全灭时换平台重搜。
    // QQ 经常整条不可用（各源都拿不到直链），而酷我同时完全正常。
    // 但换源必须严格：只接受「时长 ±5 秒 + 歌手对得上」的同一首歌（见 lib/match.js），
    // 否则就会出现「列表显示周杰伦，点开播的是别人的翻唱」。
    if (!pick) {
      try {
        // 不能用 searchAll：它会把同名同歌手的跨平台结果合并成一条，
        // 于是目标平台那一版根本不会出现在结果里。
        const primary = song.platform || 'kw';
        const other = primary === 'kw' ? 'tx' : 'kw';
        const fetchAlt = other === 'tx' ? searchQQ : searchKuwo;
        const q = `${song.title} ${song.artist}`.trim();
        const otherList = await fetchAlt(q, { limit: 25 });

        // 严格匹配出的「同一首歌」
        const same = findSameSong(song, [{ platform: other, list: otherList }]);

        for (const cand of same.slice(0, 3)) {
          const alt = { ...cand.item, platform: cand.platform };
          const key2 = `${alt.platform}|${alt.id}|${params.quality || '320k'}`;
          const hit2 = probeCache.get(key2);
          let c2 = hit2 && Date.now() - hit2.at < PROBE_TTL_MS ? hit2.cands : null;
          if (!c2) {
            c2 = await probeSources(alt, { quality: params.quality || '320k' });
            if (c2.some((c) => c.ok)) probeCache.set(key2, { at: Date.now(), cands: c2 });
          }

          const ok2 = c2.filter((c) => c.ok);
          if (!ok2.length) continue;

          targetSong = alt;
          cands = c2;
          usable = ok2;
          pick = params.source
            ? ok2.find((c) => c.source === params.source) || ok2[0]
            : ok2[0];
          break;
        }

        // 严格匹配的也拿不到：把「同名不同版本」列出来交给用户决定，
        // 绝不自己挑一个顶上（宁可让用户点一下，也别偷偷换歌）
        if (!pick) {
          alternatives = listAlternatives(song, otherList, 5).map((x) => ({
            id: x.id,
            platform: other,
            title: x.title,
            artist: x.artist,
            album: x.album || '',
            duration: x.duration || 0,
            albumMid: x.albumMid || '',
          }));
        }
      } catch {
        // 兜底失败，落到下面的 502
      }
    }

    const brief = cands.map((c) => ({
      source: c.source,
      ok: c.ok,
      quality: c.quality || '',
      ms: c.ms || 0,
      reason: c.reason || '',
    }));

    if (!pick) {
      return sendJson(res, 502, {
        ok: false,
        error: alternatives.length
          ? '这个版本当前没有可用音源，可以换下面任一版本试试'
          : '这首歌在所有音源上都取不到',
        candidates: brief,
        alternatives,
      });
    }

    const ext = pick.quality === 'flac' ? '.flac' : '.mp3';
    const saveBase = savedBaseName(targetSong, pick.quality);
    const body = {
      ok: true,
      song: {
        title: targetSong.title,
        artist: targetSong.artist,
        album: targetSong.album,
        id: targetSong.id,
        albumMid: targetSong.albumMid || '',
      },
      saved: fs.existsSync(path.join(SAVED_DIR, saveBase + ext)),
      source: pick.source,
      platform: targetSong.platform || 'kw',
      requestedQuality: (params.quality || '320k').toLowerCase(),
      quality: pick.quality,
      downgraded:
        pick.quality !== 'flac' && (params.quality || '').toLowerCase() === 'flac',
      contentType: pick.contentType,
      totalBytes: pick.totalBytes || 0,
      pcUrl: `${PUBLIC_BASE}/orig/${rememberOrigin(pick.url)}${ext}`,
      duration: targetSong.duration || 0,
      mode: 'pc',
      candidates: brief,
    };

    if (targetSong.id !== song.id) {
      // 是不是「同一首歌的另一个平台版本」——搜索时就记在 variants 里、且时长对得上。
      // 是的话只是换了源，不算替代版本；不是才需要提示用户。
      const isSameViaVariant =
        Array.isArray(song.variants) &&
        song.variants.some((v) => v.platform === targetSong.platform && v.id === targetSong.id);

      body.switchedFrom = song.platform;
      if (isSameViaVariant) {
        body.platformSwitched = true;   // 同一首歌换平台，界面只需提示「已换源」
      } else {
        body.fallback = true;           // 真的是另一个版本，必须明示
        body.requestedTitle = song.title;
        body.requestedArtist = song.artist;
      }
    } else {
      markFallback(body, song.artist);
    }

    return sendJson(res, 200, body);
  }

  // target=pc：只解析原始音质给电脑/浏览器播，不走转码
  if ((params.target || 'device') === 'pc') {
    const pcHit = await tryResolveOnly(firstRound, params.quality, failures, tried);
    if (pcHit) return sendJson(res, 200, markFallback(pcHit, artist));

    if (title) {
      try {
        const alt = (await searchAll(`${title} ${artist}`.trim(), { limit: 12 })).filter(
          (x) => !isBadVersion(x)
        );
        const pcHit2 = await tryResolveOnly(alt.slice(0, 5), params.quality, failures, tried);
        if (pcHit2) return sendJson(res, 200, markFallback(pcHit2, artist));
      } catch (err) {
        failures.push(`重搜失败: ${err.message}`);
      }
    }
    return sendJson(res, 502, { ok: false, error: '这首歌当前没有可用的原音质音源', failures });
  }

  // 先只试「同一首歌」：主平台 + 搜索时记下的其它平台版本（时长对得上才算）
  const song0 = firstRound[0];
  const deviceSiblings = (Array.isArray(song0.variants) ? song0.variants : [])
    .filter((v) => v.platform !== (song0.platform || 'kw') && v.id !== song0.id)
    .filter(
      (v) =>
        !song0.duration ||
        !v.duration ||
        Math.abs(Number(v.duration) - Number(song0.duration)) <= 5
    )
    .slice(0, 2);

  const strictHit = await trySongs([song0, ...deviceSiblings], quality, failures, tried, {
    crossPlatform: false,
  });
  if (strictHit) return sendJson(res, 200, strictHit);

  const hit = await trySongs(firstRound, quality, failures, tried);
  if (hit) return sendJson(res, 200, markFallback(hit, artist));

  // 兜底：用「歌名 + 歌手」重新全局搜索。
  // 典型场景是 QQ 源挂了、改投酷我，而酷我上同一首可能有多个版本，
  // 只有逐个校验时长才能挑到完整的那版。
  if (title) {
    try {
      const alt = (await searchAll(`${title} ${artist}`.trim(), { limit: 12 })).filter(
        (x) => !isBadVersion(x)
      );
      const hit2 = await trySongs(alt.slice(0, 5), quality, failures, tried);
      if (hit2) return sendJson(res, 200, markFallback(hit2, artist));
    } catch (err) {
      failures.push(`重搜失败: ${err.message}`);
    }
  }

  return sendJson(res, 502, { ok: false, error: '这首歌当前没有可用的完整音源', failures });
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const params = Object.fromEntries(url.searchParams.entries());

  try {
    if (url.pathname === '/api/health') {
      return sendJson(res, 200, { ok: true, lan: LAN_IP, port: PORT, base: PUBLIC_BASE });
    }

    // 音源健康度与取流顺序（来自上游 README 的体检表）
    if (url.pathname === '/api/sources') {
      const health = await getHealth({ force: params.refresh === '1' });
      const loaded = (await getSources()).map((s) => s.label);
      const platforms = ['kw', 'tx', 'kg', 'wy', 'mg'];
      const plans = {};
      for (const p of platforms) {
        plans[p] = await effectivePlan(p);
      }
      return sendJson(res, 200, {
        ok: true,
        checkedAt: health.checkedAt || '',
        origin: health.origin || '',
        updatedAt: new Date(health.fetchedAt || Date.now()).toISOString(),
        health: health.sources,
        localStats: getStats(),
        loaded,
        plans,
      });
    }

    // 搜索联想（输入框下拉）：按手机版的思路，用平台自带的搜索建议接口
    if (url.pathname === '/api/suggest') {
      const kw = (params.kw || params.keyword || '').trim();
      if (!kw) return sendJson(res, 200, { ok: true, kw, list: [] });
      const list = await suggestAll(kw, { limit: Number(params.limit || 8) });
      return sendJson(res, 200, { ok: true, kw, count: list.length, list });
    }

    // 歌词：给播放器做逐行高亮
    if (url.pathname === '/api/lyric') {
      const song = songFromParams(params);
      if (!song) return sendJson(res, 400, { ok: false, error: '缺少 id 参数' });
      try {
        const data = await getLyric(song);
        return sendJson(res, 200, { ok: true, ...data });
      } catch (err) {
        return sendJson(res, 404, { ok: false, error: `没有歌词：${err.message}` });
      }
    }

    // 封面：服务端转一道，绕开 QQ/酷我的防盗链
    if (url.pathname === '/api/cover') {
      const song = songFromParams(params);
      if (!song) return sendJson(res, 400, { ok: false, error: '缺少 id 参数' });
      try {
        const { url: imgUrl } = await getCover(song);
        const upstream = await fetch(imgUrl, {
          headers: {
            'User-Agent':
              'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
            Referer: 'https://y.qq.com/',
          },
          redirect: 'follow',
          signal: AbortSignal.timeout(12000),
        });
        if (!upstream.ok || !upstream.body) throw new Error(`图片 HTTP ${upstream.status}`);

        const headers = {
          'Content-Type': upstream.headers.get('content-type') || 'image/jpeg',
          'Cache-Control': 'public, max-age=86400',
        };
        const len = upstream.headers.get('content-length');
        if (len) headers['Content-Length'] = len;

        res.writeHead(200, headers);
        await pipeline(Readable.fromWeb(upstream.body), res).catch(() => {
          try { res.destroy(); } catch {}
        });
        return;
      } catch (err) {
        return sendJson(res, 404, { ok: false, error: `没有封面：${err.message}` });
      }
    }

    if (url.pathname === '/api/search') {
      const kw = params.kw || params.keyword;
      if (!kw) return sendJson(res, 400, { ok: false, error: '缺少 kw 参数' });
      const list = await searchAll(kw, { limit: Number(params.limit || 10) });
      return sendJson(res, 200, { ok: true, kw, count: list.length, list });
    }

    if (url.pathname === '/api/play') {
      return await handlePlay(res, params);
    }

    if (url.pathname === '/api/save') {
      return await handleSave(res, params);
    }

    // 浏览器下载（弹下载/另存为，不落程序目录）
    if (url.pathname === '/api/download') {
      return await handleDownload(res, params);
    }

    // 已保存清单
    if (url.pathname === '/api/saved') {
      const list = listSaved();
      return sendJson(res, 200, { ok: true, dir: SAVED_DIR, count: list.length, list });
    }

    // 本地库：可直接播放，带 ?download=1 时按原文件名下载
    if (url.pathname.startsWith('/saved/')) {
      const raw = decodeURIComponent(url.pathname.slice('/saved/'.length));
      const safe = path.basename(raw);
      const ct = /\.flac$/i.test(safe)
        ? 'audio/flac'
        : /\.ogg$/i.test(safe)
        ? 'audio/ogg'
        : 'audio/mpeg';
      return serveFile(
        req,
        res,
        path.join(SAVED_DIR, safe),
        ct,
        params.download === '1' ? safe : null
      );
    }

    if (url.pathname.startsWith('/media/')) {
      return serveMedia(req, res, url.pathname.slice('/media/'.length));
    }

    // 流式播放：现场转码，边转边发。设备拿到这个短链就立刻能出声，
    // 不用等整首转完（原来的 /media 静态文件仅在下载到本地时使用）。
    if (url.pathname.startsWith('/live/')) {
      const token = url.pathname.slice('/live/'.length).replace(/\.[a-z0-9]+$/i, '');
      const entry = lookupLive(token);
      if (!entry) {
        res.writeHead(410, { 'Content-Type': 'text/plain; charset=utf-8' });
        return res.end('播放链接已过期，请重新点歌');
      }
      console.log(
        `[live] 开始流式转码：${entry.song ? entry.song.artist + ' - ' + entry.song.title : token}`
      );
      const ok = await streamOgg(entry.url, res, { headers: UPSTREAM_HEADERS });
      console.log(`[live] 流结束（${ok ? '正常' : '中断'}）`);
      return;
    }

    // 原音质直通（电脑/浏览器用）
    if (url.pathname.startsWith('/orig/')) {
      const token = url.pathname.slice('/orig/'.length).replace(/\.[a-z0-9]+$/i, '');
      return streamOrigin(req, res, token);
    }

    // 修改音箱地址：写入 device.json，并立即探活一次
    if (url.pathname === '/api/device/target' && req.method === 'POST') {
      try {
        const body = await readJsonBody(req);
        const saved = device.setTarget(body.ip, body.port);
        const probe = await device.probe(saved.ip, saved.port, 2500);
        return sendJson(res, 200, {
          ok: true,
          target: saved,
          online: probe.ok,
          state: probe.state || '',
          error: probe.ok ? undefined : probe.reason,
        });
      } catch (err) {
        return sendJson(res, 400, { ok: false, error: err.message });
      }
    }

    // 当前地址 + 在线状态
    if (url.pathname === '/api/device/target') {
      const t = device.getTarget();
      const probe = await device.probe(t.ip, t.port, 1500);
      return sendJson(res, 200, {
        ok: true,
        target: t,
        online: probe.ok,
        state: probe.state || '',
        error: probe.ok ? undefined : probe.reason,
      });
    }

    // 自动发现：先查 ARP 缓存，没找到再补扫网段
    if (url.pathname === '/api/device/discover') {
      const found = await device.discover({ port: params.port ? Number(params.port) : null });
      if (!found.ok) return sendJson(res, 404, found);

      const saved = device.setTarget(found.ip, found.port, '自动发现');
      return sendJson(res, 200, {
        ok: true,
        target: saved,
        state: found.state,
        latency: found.latency,
        scanned: found.scanned,
        rounds: found.rounds,
      });
    }

    // 设备代理：/api/device/play | stop | status
    if (url.pathname.startsWith('/api/device/')) {
      const sub = url.pathname.slice('/api/device/'.length);
      if (!['play', 'stop', 'status'].includes(sub)) {
        return sendJson(res, 404, { ok: false, error: 'unknown device endpoint' });
      }
      try {
        const r = await deviceCall(`/${sub}${url.search}`);
        return sendJson(res, r.status, r.data);
      } catch (err) {
        const t = device.getTarget();
        return sendJson(res, 502, {
          ok: false,
          error: `音箱不可达（${t.ip}:${t.port}）：${err.message}`,
          target: t,
        });
      }
    }

    // 其余非接口路径交给播放器页面
    if (!url.pathname.startsWith('/api/')) {
      return serveStatic(res, url.pathname);
    }

    sendJson(res, 404, { ok: false, error: 'unknown endpoint' });
  } catch (err) {
    const detail = err.failures ? { failures: err.failures } : {};
    sendJson(res, 500, { ok: false, error: err.message, ...detail });
  }
});

// 启动即预热体检表，别让首次点歌去承担这次网络往返
getHealth().catch(() => {});

// 预热：把 4 个音源脚本先加载进 vm 沙箱。
// 冷启动第一次点歌要多付这 1 秒左右，放在启动时做掉，用户点歌时无感。
getSources().catch(() => {});

/** 双击运行时自动开浏览器；`--no-open` 可关掉 */
/** 用系统默认浏览器打开页面；两条退路，尽量别让用户自己去复制地址 */
function openBrowser(url) {
  if (process.argv.includes('--no-open')) return;
  const { spawn } = require('node:child_process');

  const fallback = () => {
    try {
      // explorer 一定会把 URL 交给默认浏览器
      spawn('explorer.exe', [url], { detached: true, stdio: 'ignore' }).unref();
    } catch {
      // 两条路都失败就只在控制台留地址
    }
  };

  try {
    // cmd start 能复用已开的浏览器窗口，新开标签而不是再起一个实例
    const child = spawn('cmd', ['/c', 'start', '', url], {
      detached: true,
      stdio: 'ignore',
      windowsHide: true,
    });
    child.on('error', fallback);
    child.on('exit', (code) => {
      if (code) fallback();
    });
    child.unref();
  } catch {
    fallback();
  }
}

/** 本机这个端口上是不是已经跑着一个「我们」 */
async function runningInstance() {
  try {
    const res = await fetch(`http://127.0.0.1:${PORT}/api/health`, {
      signal: AbortSignal.timeout(1500),
    });
    if (!res.ok) return false;
    const data = await res.json();
    return Boolean(data && data.ok && data.port);
  } catch {
    return false;
  }
}

const QUIET = process.argv.includes('--quiet');

/**
 * 最后一道防线。
 *
 * 这是个常驻的本地服务：某个上游音源抽风、某条流中途断开，都不该让整个进程退出
 * （一旦退出，用户看到的就是「页面突然打不开」，还以为程序崩了）。
 * 真正的错误细节仍然打到控制台，方便排查。
 */
process.on('uncaughtException', (err) => {
  console.error('[未捕获异常]', err && err.message ? err.message : err);
});
process.on('unhandledRejection', (err) => {
  console.error('[未处理 Promise]', err && err.message ? err.message : err);
});

server.on('error', async (err) => {
  if (err.code === 'EADDRINUSE') {
    // 双击两次是很常见的操作：第二次不应该报错，直接打开已经跑着的那个
    if (await runningInstance()) {
      console.log(`已在运行（端口 ${PORT}），直接打开页面…`);
      openBrowser(`http://127.0.0.1:${PORT}/`);
      setTimeout(() => process.exit(0), 500);
      return;
    }

    console.error('');
    console.error(`端口 ${PORT} 被别的程序占用了（不是本程序）。`);
    console.error(`换个端口启动：${path.basename(process.execPath)} --port 8091`);
    console.error('');
    process.exit(1);
  }
  console.error(err);
  process.exit(1);
});

server.listen(PORT, '0.0.0.0', () => {
  if (QUIET) return;

  console.log('');
  console.log('  伟哥音乐  WEIGE HI-FI');
  console.log('  ─────────────────────────────────────────────');
  console.log(`  本机播放   http://127.0.0.1:${PORT}`);
  console.log(`  手机访问   ${PUBLIC_BASE}`);
  const t = device.getTarget();
  console.log(`  音箱地址   ${t.ip}:${t.port}（来源：${t.source}；未上电也可正常放歌）`);
  console.log('  ─────────────────────────────────────────────');
  console.log(`  数据目录   ${DATA_ROOT}`);
  if (PACKAGED) console.log(`  运行模式   打包版（数据写在 exe 同级目录）`);
  console.log('');
  console.log('  浏览器若没自动打开，手动访问上面的「本机播放」地址即可。');
  console.log('  关闭这个黑窗口即退出服务。');
  console.log('');

  // 云端 MCP 桥：配了 token 就自动连，让音箱能"说歌名就放"
  const mcpFromArg = argValue('--mcp-token') || process.env.MCP_TOKEN;
  const mcpToken = mcpFromArg || mcp.readMcpConfig(DATA_ROOT)?.token;
  if (mcpToken) {
    if (mcpFromArg) mcp.saveMcpConfig(DATA_ROOT, mcpToken);
    console.log('  云端 MCP   已启用（音箱可说「播放xx」直接点歌）');
    process.env.MUSIC_SERVICE = `http://127.0.0.1:${PORT}`;
    mcp.createBridge({ token: mcpToken }).start();
  } else {
    console.log('  云端 MCP   未配置（用 --mcp-token 开启语音点歌）');
  }
  console.log('');

  // 黑窗口标题直接带地址，方便从任务栏认出来
  process.title = `伟哥音乐 ${PORT}`;

  openBrowser(`http://127.0.0.1:${PORT}/`);
});
