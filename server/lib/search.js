/**
 * 多平台搜索。
 *
 * 必须跨平台的原因：周杰伦等曲目的版权是平台独占的，酷我搜不到原版，
 * QQ 才有。所以搜索要合并多平台结果，并带着各自的 platform + id 进入取流层。
 *
 * 酷我的搜索有两个完全不同的入口：
 *   - web 版（itemset=web_2013）曲库被阉割，热门歌大量用「翻唱 / KTV 伴奏」顶替，
 *     实测「晴天」搜到的原版是 KTV 伴奏（rid 51685512）；
 *   - 客户端版（vipver=1&mobi=1&ver=kwplayer_ar_...）返回完整版权目录，
 *     同一次搜索能拿到真原版（rid 228908，专辑《叶惠美》，269 秒）。
 * 手机版用的是后者，这里照抄它的参数。
 */

const SEARCH_URL = 'http://search.kuwo.cn/r.s';

/** 客户端接口的固定参数（取自 lx-music-mobile 的 kw/musicSearch.js） */
const KW_CLIENT_PARAMS =
  'client=kt&uid=794762570&ver=kwplayer_ar_9.2.2.1&vipver=1' +
  '&show_copyright_off=1&newver=1&ft=music&cluster=0&strategy=2012' +
  '&encoding=utf8&rformat=json&vermerge=1&mobi=1&issubtitle=1';

const BAD_VERSION = [
  '伴奏', 'ktv', '翻唱', 'dj版', 'dj ', 'remix', '纯音乐', '铃声', '片段', 'cover', '清唱',
  '现场', 'live', '演唱会', '巡回', '音乐节', '季选', '未修音', '花絮',
];

function unescapeHtml(s) {
  return s
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/\\\//g, '/')
    // 酷我把 & 编码成字面量 \u0026（有时还多一层反斜杠），统一还原成 &
    .replace(/\\+u([0-9a-fA-F]{4})/g, (_, h) => String.fromCharCode(parseInt(h, 16)))
    .trim();
}

function pick(block, key) {
  const m = block.match(new RegExp(`'${key}':'([^']*)'`));
  return m ? unescapeHtml(m[1]) : '';
}

function parseItems(body) {
  const items = [];
  const start = body.indexOf("'abslist'");
  if (start < 0) return items;
  // 条目自身可能含嵌套花括号，按 `},{` 切块比花括号配对稳
  for (const chunk of body.slice(start).split('},{')) {
    const id = pick(chunk, 'DC_TARGETID');
    if (!id) continue;
    items.push({
      id,
      title: pick(chunk, 'SONGNAME') || pick(chunk, 'NAME'),
      artist: pick(chunk, 'ARTIST'),
      album: pick(chunk, 'ALBUM'),
      duration: Number(pick(chunk, 'DURATION')) || 0,
      pay: pick(chunk, 'PAY') === '1',
      formats: pick(chunk, 'FORMATS'),
    });
  }
  return items;
}

/**
 * 相关性打分：原始排序经常把 KTV 伴奏、翻唱、DJ 版顶到前面。
 * 按空格切词后与歌名比对，并对非原唱版本扣分。
 */
function scoreItem(item, keyword) {
  const norm = (x) => String(x || '').toLowerCase().replace(/\s+/g, '');
  const title = norm(item.title);
  const tokens = keyword
    .toLowerCase()
    .split(/[\s,，、]+/)
    .map(norm)
    .filter(Boolean);

  let score = 0;
  if (tokens.some((t) => title === t)) score += 100;
  else if (tokens.some((t) => t.length > 1 && title.startsWith(t))) score += 60;
  else if (tokens.some((t) => t.length > 1 && title.includes(t))) score += 35;
  else score -= 20;

  const artist = norm(item.artist);
  if (artist && tokens.some((t) => t === artist)) score += 40;

  if (BAD_VERSION.some((bad) => title.includes(bad))) score -= 70;
  if (item.duration && item.duration < 60) score -= 30;

  return score;
}

async function searchKuwo(keyword, { limit = 10, page = 0 } = {}) {
  const url =
    `${SEARCH_URL}?${KW_CLIENT_PARAMS}` +
    `&all=${encodeURIComponent(keyword)}&pn=${page}&rn=${limit}`;

  const res = await fetch(url, {
    headers: { 'User-Agent': 'Mozilla/5.0', Referer: 'http://www.kuwo.cn/' },
    signal: AbortSignal.timeout(12000),
  });
  if (!res.ok) throw new Error(`酷我搜索 HTTP ${res.status}`);

  const text = await res.text();

  let items;
  try {
    // 客户端接口返回标准 JSON
    const data = JSON.parse(text);
    items = (data.abslist || []).map((it) => ({
      id: String(it.DC_TARGETID || String(it.MUSICRID || '').replace('MUSIC_', '')),
      title: it.NAME || it.SONGNAME || '',
      artist: it.ARTIST || '',
      album: it.ALBUM || '',
      duration: Number(it.DURATION) || 0,
      pay: false,
      formats: it.MINFO || '',
    }));
  } catch {
    // 兜底：老的单引号格式
    items = parseItems(text);
  }

  return items
    .filter((x) => x.id && x.title)
    .map((item) => {
      const withPlatform = { ...item, platform: 'kw' };
      return { ...withPlatform, score: scoreItem(withPlatform, keyword) };
    });
}

async function searchQQ(keyword, { limit = 10, page = 1 } = {}) {
  const url =
    `https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=${encodeURIComponent(keyword)}` +
    `&format=json&n=${limit}&p=${page}`;

  const res = await fetch(url, {
    headers: { 'User-Agent': 'Mozilla/5.0', Referer: 'https://y.qq.com/' },
    signal: AbortSignal.timeout(12000),
  });
  if (!res.ok) throw new Error(`QQ 搜索 HTTP ${res.status}`);
  const data = await res.json();

  return (data?.data?.song?.list || []).map((it) => {
    const item = {
      id: it.songmid,
      title: it.songname || it.title || '',
      artist: (it.singer || []).map((s) => s.name).join('&'),
      album: it.albumname || '',
      // 封面地址是 y.gtimg.cn/.../T002R500x500M000<albummid>.jpg，必须带上
      albumMid: it.albummid || '',
      duration: Number(it.interval) || 0,
      pay: false,
      platform: 'tx',
    };
    return { ...item, score: scoreItem(item, keyword) };
  });
}

/** 合并多平台结果：同名同歌手只保留分数最高的一条 */
const normKey = (x) => String(x || '').toLowerCase().replace(/\s+/g, '');

function toVariant(x) {
  return {
    platform: x.platform,
    id: x.id,
    title: x.title,
    artist: x.artist,
    album: x.album || '',
    albumMid: x.albumMid || '',
    duration: x.duration || 0,
  };
}

/**
 * 合并两平台搜索结果。
 *
 * 关键：同名同歌手的两条**不能简单去重丢掉一条**。
 * 之前只留分数高的那条，结果「晴天」留下的是 QQ 版，酷我的真原版（rid 228908）被丢掉了，
 * 于是 QQ 一挂就整首歌放不了——而其实酷我上原版本就在那儿。
 * 现在保留主条目 + variants（同歌在各平台的 id），取流时可以并行探测、直接换平台。
 */
async function searchAll(keyword, { limit = 10 } = {}) {
  const [qq, kw] = await Promise.all([
    searchQQ(keyword, { limit }).catch(() => []),
    searchKuwo(keyword, { limit }).catch(() => []),
  ]);

  const best = new Map();
  for (const item of [...qq, ...kw]) {
    const key = `${normKey(item.title)}|${normKey(item.artist)}`;
    const prev = best.get(key);

    if (!prev) {
      best.set(key, { ...item, variants: [toVariant(item)] });
      continue;
    }

    prev.variants.push(toVariant(item));
    if ((item.score || 0) > (prev.score || 0)) {
      const variants = prev.variants;
      Object.assign(prev, item);
      prev.variants = variants;   // 累积的 variants 不能被覆盖
    }
  }

  return [...best.values()].sort((a, b) => (b.score || 0) - (a.score || 0)).slice(0, limit);
}

/** 是否是伴奏/现场/翻唱等非原唱版本 */
function isBadVersion(item) {
  const title = String(item.title || '').toLowerCase();
  return BAD_VERSION.some((bad) => title.includes(bad));
}

module.exports = { searchKuwo, searchQQ, searchAll, scoreItem, isBadVersion };
