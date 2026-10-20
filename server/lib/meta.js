/**
 * 歌词与封面。
 *
 * 数据来源与洛雪手机版（lx-music-mobile）里的 musicSdk 一致：
 *   tx（QQ）  歌词 https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg（base64 的 LRC）
 *             封面 https://y.gtimg.cn/music/photo_new/T002R500x500M000<albumMid>.jpg
 *   kw（酷我）歌词 http://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=<rid>
 *             封面 http://artistpicserver.kuwo.cn/pic.web?...&rid=<rid>（返回一个图片地址）
 *
 * 洛雪的分项音源脚本（sources/*.js）只实现了 musicUrl，没有 lyric/pic，
 * 所以这里直接照手机版 SDK 的接口自己取。
 */

const { searchQQ, searchKuwo } = require('./search.js');

const LYRIC_TTL_MS = 30 * 60 * 1000;
const COVER_TTL_MS = 6 * 60 * 60 * 1000;

const lyricCache = new Map(); // key -> { at, value }
const coverCache = new Map(); // key -> { at, value }

function cacheGet(store, key, ttl) {
  const hit = store.get(key);
  if (!hit) return null;
  if (Date.now() - hit.at > ttl) {
    store.delete(key);
    return null;
  }
  return hit.value;
}

function cacheSet(store, key, value) {
  store.set(key, { at: Date.now(), value });
  if (store.size > 300) {
    const now = Date.now();
    for (const [k, v] of store) {
      if (now - v.at > LYRIC_TTL_MS) store.delete(k);
    }
  }
}

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36';

/* ------------------------------- LRC 解析 ------------------------------- */

const TAG_RE = /\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]/g;

/**
 * 解析标准 LRC 为 [{ t, text }]（t 单位秒，按时间升序）。
 * 一行可以有多个时间标签（重复句），全部展开。
 */
function parseLrc(lrc) {
  if (!lrc) return [];
  const out = [];
  for (const raw of String(lrc).split(/\r?\n/)) {
    const line = raw.trim();
    if (!line) continue;

    const times = [];
    TAG_RE.lastIndex = 0;
    let m;
    while ((m = TAG_RE.exec(line)) !== null) {
      const min = Number(m[1]);
      const sec = Number(m[2]);
      const frac = m[3] ? Number(`0.${m[3]}`) : 0;
      times.push(min * 60 + sec + frac);
    }
    if (!times.length) continue;

    // 去掉所有时间标签，剩下的才是正文
    const text = line.replace(TAG_RE, '').replace(/^[-–—\s]+/, '').trim();
    for (const t of times) out.push({ t, text });
  }
  out.sort((a, b) => a.t - b.t);
  return out;
}

/** 把翻译按时间轴并进主歌词（时间差 0.35 秒内算同一句） */
function mergeTranslation(lines, transLines) {
  if (!transLines.length) return lines;
  const byTime = transLines.map((x) => ({ ...x }));
  let j = 0;
  for (const line of lines) {
    while (j + 1 < byTime.length && byTime[j + 1].t <= line.t + 0.35) j += 1;
    const cand = byTime[j];
    if (cand && Math.abs(cand.t - line.t) <= 0.35 && cand.text && cand.text !== line.text) {
      line.tr = cand.text;
    }
  }
  return lines;
}

/* -------------------------------- 平台实现 -------------------------------- */

async function fetchJson(url, headers = {}) {
  const res = await fetch(url, {
    headers: { 'User-Agent': UA, ...headers },
    signal: AbortSignal.timeout(12000),
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

function b64Utf8(s) {
  return Buffer.from(String(s || ''), 'base64').toString('utf8');
}

/** QQ 音乐：songmid 换歌词（含翻译） */
async function qqLyric(song) {
  const url =
    'https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg' +
    `?songmid=${encodeURIComponent(song.id)}&g_tk=5381&loginUin=0&hostUin=0` +
    '&format=json&inCharset=utf8&outCharset=utf-8&platform=yqq';

  // 这个接口必须带 Referer，否则返回 -1
  const data = await fetchJson(url, { Referer: 'https://y.qq.com/portal/player.html' });
  if (Number(data.code) !== 0 && Number(data.retcode) !== 0) throw new Error('QQ 返回失败');
  if (!data.lyric) throw new Error('QQ 没有歌词');

  const lines = parseLrc(b64Utf8(data.lyric));
  const trans = parseLrc(b64Utf8(data.trans));
  mergeTranslation(lines, trans);
  return { lines, source: 'tx', translated: lines.some((l) => l.tr) };
}

/** 酷我：musicId 换歌词 */
async function kuwoLyric(song) {
  const data = await fetchJson(
    `http://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=${encodeURIComponent(song.id)}`
  );
  const list = data?.data?.lrclist;
  if (!Array.isArray(list) || !list.length) throw new Error('酷我没有歌词');

  const lines = list
    .map((x) => ({ t: Number(x.time) || 0, text: String(x.lineLyric || '').trim() }))
    .filter((x) => x.text)
    .sort((a, b) => a.t - b.t);

  return { lines, source: 'kw', translated: false };
}

/** QQ 封面：需要 albummid */
async function qqCover(song) {
  if (!song.albumMid) throw new Error('缺少 albummid');
  return {
    url: `https://y.gtimg.cn/music/photo_new/T002R500x500M000${song.albumMid}.jpg`,
    source: 'tx',
  };
}

/** 酷我封面：接口返回一个图片地址文本 */
async function kuwoCover(song) {
  const res = await fetch(
    'http://artistpicserver.kuwo.cn/pic.web?corp=kuwo&type=rid_pic&pictype=500&size=500' +
      `&rid=${encodeURIComponent(song.id)}`,
    { headers: { 'User-Agent': UA }, signal: AbortSignal.timeout(10000) }
  );
  const text = (await res.text()).trim();
  if (!/^https?:\/\//.test(text)) throw new Error('酷我没有封面');
  return { url: text, source: 'kw' };
}

/* ------------------------------ 跨平台兜底 ------------------------------ */

const norm = (x) =>
  String(x || '')
    .toLowerCase()
    .replace(/[\s\-_/・.]+/g, '')
    .replace(/[（(【\[].*?[)）】\]]/g, '')
    .trim();

/** 在目标平台搜同一首歌，按「歌名匹配度 + 是否同歌手」排序，返回前几个候选 */
function rankCandidates(song, list) {
  const wantTitle = norm(song.title);
  const wantArtist = norm(song.artist);
  if (!wantTitle) return [];

  const scored = [];
  for (const item of list) {
    const t = norm(item.title);
    let titleHit = 0;
    if (t === wantTitle) titleHit = 3;
    else if (t.length > 1 && (t.includes(wantTitle) || wantTitle.includes(t))) titleHit = 1;
    if (!titleHit) continue;

    const a = norm(item.artist);
    const artistHit = wantArtist && a && (a === wantArtist || a.includes(wantArtist)) ? 2 : 0;
    scored.push({ item, rank: titleHit * 10 + artistHit });
  }

  scored.sort((x, y) => y.rank - x.rank);
  return scored.map((x) => x.item);
}

/**
 * 到另一个平台取同一首歌的歌词/封面，返回候选列表。
 * 必须先用歌名+歌手在目标平台搜到它自己的 id ——
 * 拿本平台的 id 去查对方接口，返回的一定是失败。
 */
async function crossCandidates(song, target) {
  const search = target === 'tx' ? searchQQ : searchKuwo;
  const list = await search(`${song.title} ${song.artist}`.trim(), { limit: 10 });
  return rankCandidates(song, list)
    .slice(0, 3)
    .map((x) => ({ ...x, platform: target }));
}

/* -------------------------------- 对外接口 -------------------------------- */

/**
 * 取歌词。跨平台兜底：QQ 拿不到就用歌名+歌手去酷我再搜一次。
 * @returns {Promise<{lines:Array, source:string, translated:boolean}>}
 */
async function getLyric(song) {
  const key = `l|${song.platform}|${song.id}`;
  const cached = cacheGet(lyricCache, key, LYRIC_TTL_MS);
  if (cached) return cached;

  const other = song.platform === 'kw' ? 'tx' : 'kw';
  const order = [
    () => (song.platform === 'kw' ? kuwoLyric(song) : qqLyric(song)),
    async () => {
      const cands = await crossCandidates(song, other);
      let lastErr = null;
      for (const cand of cands) {
        try {
          const v = other === 'tx' ? await qqLyric(cand) : await kuwoLyric(cand);
          if (v.lines.length) return v;
        } catch (err) {
          lastErr = err;
        }
      }
      throw lastErr || new Error(`在 ${other} 没找到能用的歌词`);
    },
  ];

  let lastErr = null;
  for (const run of order) {
    try {
      const value = await run();
      if (value.lines.length) {
        cacheSet(lyricCache, key, value);
        return value;
      }
    } catch (err) {
      lastErr = err;
    }
  }
  throw lastErr || new Error('没有取到歌词');
}

/** 取封面地址 */
async function getCover(song) {
  const key = `c|${song.platform}|${song.id}|${song.albumMid || ''}`;
  const cached = cacheGet(coverCache, key, COVER_TTL_MS);
  if (cached) return cached;

  const other = song.platform === 'kw' ? 'tx' : 'kw';
  const order = [
    () => (song.platform === 'kw' ? kuwoCover(song) : qqCover(song)),
    async () => {
      const cands = await crossCandidates(song, other);
      let lastErr = null;
      for (const cand of cands) {
        try {
          return other === 'tx' ? await qqCover(cand) : await kuwoCover(cand);
        } catch (err) {
          lastErr = err;
        }
      }
      throw lastErr || new Error(`在 ${other} 没找到能用的封面`);
    },
  ];

  let lastErr = null;
  for (const run of order) {
    try {
      const value = await run();
      cacheSet(coverCache, key, value);
      return value;
    } catch (err) {
      lastErr = err;
    }
  }
  throw lastErr || new Error('没有取到封面');
}

module.exports = { getLyric, getCover, parseLrc, mergeTranslation };
