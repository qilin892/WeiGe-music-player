/**
 * 搜索联想（输入框下拉提示）。
 *
 * 与洛雪手机版一致，用各平台的「搜索建议」接口，而不是拿搜索结果冒充：
 *   tx  https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg   → data.song.itemlist
 *   kw  https://tips.kuwo.cn/t.s?...                            → WORDITEMS[].RELWORD
 *
 * 手机版对每个平台各取一份；这里合并去重，点一条就直接拿它去搜。
 */

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36';

const cache = new Map(); // kw -> { at, list }
const TTL_MS = 10 * 60 * 1000;

async function qqSuggest(kw) {
  const url =
    'https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg' +
    `?is_xml=0&format=json&key=${encodeURIComponent(kw)}` +
    '&loginUin=0&hostUin=0&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq&needNewCode=0';

  const res = await fetch(url, {
    headers: { 'User-Agent': UA, Referer: 'https://y.qq.com/portal/player.html' },
    signal: AbortSignal.timeout(8000),
  });
  if (!res.ok) return [];
  const data = await res.json();
  if (Number(data.code) !== 0) return [];

  return (data?.data?.song?.itemlist || [])
    .filter((x) => x.name)
    .map((x) => ({
      text: `${x.name}${x.singer ? ' ' + x.singer : ''}`,
      title: x.name,
      artist: x.singer || '',
      source: 'tx',
    }));
}

async function kuwoSuggest(kw) {
  const url =
    'https://tips.kuwo.cn/t.s?corp=kuwo&newver=3&p2p=1&notrace=0&c=mbox' +
    `&w=${encodeURIComponent(kw)}&encoding=utf8&rformat=json`;

  const res = await fetch(url, {
    headers: { 'User-Agent': UA, Referer: 'http://www.kuwo.cn/' },
    signal: AbortSignal.timeout(8000),
  });
  if (!res.ok) return [];
  const data = await res.json();

  return (data.WORDITEMS || [])
    .map((x) => String(x.RELWORD || '').trim())
    .filter(Boolean)
    .map((text) => ({ text, title: text, artist: '', source: 'kw' }));
}

/**
 * 合并两平台的建议，去重后取前 N 条。
 * 任一平台超时/报错都不影响另一个（输入提示不该因为单点失败而空着）。
 */
async function suggestAll(kw, { limit = 8 } = {}) {
  const key = String(kw || '').trim().toLowerCase();
  if (!key) return [];

  const hit = cache.get(key);
  if (hit && Date.now() - hit.at < TTL_MS) return hit.list;

  const [qq, kwList] = await Promise.all([
    qqSuggest(kw).catch(() => []),
    kuwoSuggest(kw).catch(() => []),
  ]);

  const seen = new Set();
  const merged = [];
  // 交错合并：两个平台交替取，避免全被某一平台占满
  const max = Math.max(qq.length, kwList.length);
  for (let i = 0; i < max; i++) {
    for (const item of [qq[i], kwList[i]]) {
      if (!item) continue;
      const k = item.text.toLowerCase().replace(/\s+/g, '');
      if (seen.has(k)) continue;
      seen.add(k);
      merged.push(item);
      if (merged.length >= limit) break;
    }
    if (merged.length >= limit) break;
  }

  cache.set(key, { at: Date.now(), list: merged });
  if (cache.size > 200) {
    const now = Date.now();
    for (const [k, v] of cache) if (now - v.at > TTL_MS) cache.delete(k);
  }
  return merged;
}

module.exports = { suggestAll };
