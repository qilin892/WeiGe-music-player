/**
 * 跨平台「同一首歌」匹配。
 *
 * 移植自洛雪手机版 src/utils/musicSdk/index.js 的 findMusic()：
 *   1) 先按时长卡一道：|本地时长 - 候选时长| < 5 秒，否则直接丢弃
 *      —— 这一条才是「点播放却放了别的版本」的真正解药（同名不同版本时长几乎必然不同）
 *   2) 再依次尝试：歌名完全相同 + 歌手包含、歌手相同 + 歌名包含、专辑相同 + 歌手 + 歌名包含
 *   3) 最后按多个优先级分档排序（歌名/歌手/专辑/时长四项的命中组合）
 *
 * 不依赖任何平台特有字段，输入是统一的 { title, artist, album, duration }。
 */

const SINGER_SPLIT = /、|&|;|；|\/|,|，|\|/;

/** 多歌手的顺序不影响判断，统一排序后比较 */
function sortSinger(singer) {
  const s = String(singer || '');
  return SINGER_SPLIT.test(s)
    ? s.split(SINGER_SPLIT).sort((a, b) => a.localeCompare(b)).join('、')
    : s;
}

/** 去掉空格与各种标点，只留实质字符（与手机版 filterStr 一致） */
function filterStr(str) {
  return String(str || '')
    .replace(/\s|'|\.|,|，|&|"|、|\(|\)|（|）|`|~|-|<|>|\||\/|\]|\[|!|！/g, '')
    .toLowerCase();
}

/** 时长归一为秒：支持 269 / "4:29" / "00:04:29" */
function toSeconds(v) {
  if (v == null || v === '') return 0;
  if (typeof v === 'number') return v;
  const parts = String(v).split(':');
  let sec = 0;
  let unit = 1;
  while (parts.length) {
    sec += (parseInt(parts.pop(), 10) || 0) * unit;
    unit *= 60;
  }
  return sec;
}

function normalized(item) {
  return {
    item,
    fName: filterStr(item.title),
    fSinger: filterStr(sortSinger(item.artist)),
    fAlbum: filterStr(item.album),
    fInterval: Math.round(toSeconds(item.duration)),
  };
}

/**
 * 版本标签。
 *
 * 实测坑：搜索「晴天 周杰伦」，酷我上有一首「周杰伦 - 晴天 (KTV版伴奏)」，
 * 歌手对得上、时长也刚好 269 秒（与种子完全一致！），会被判定为同一首歌。
 * 光靠歌手 + 时长拦不住，必须看标题里有没有多出来的版本词。
 */
const VERSION_TAGS = [
  '伴奏', 'ktv', '翻唱', 'cover', 'live', '现场', '演唱会', 'concert',
  'dj', 'remix', '纯音乐', '铃声', '片段', '清唱', '降调', '升调',
  '童声', '女声版', '男声版', '钢琴版', '吉他版', 'acoustic',
  'instrumental', 'karaoke', '加速', '慢速', '抖音版',
];

/** 候选人标题里是否出现了「原曲标题没有」的版本标签 */
function hasNewVersionTag(baseTitle, candTitle) {
  const b = String(baseTitle || '').toLowerCase();
  const c = String(candTitle || '').toLowerCase();
  return VERSION_TAGS.some((tag) => c.includes(tag) && !b.includes(tag));
}

/**
 * 在若干平台的搜索结果里找「同一首歌」。
 * @param {{title:string, artist:string, album?:string, duration?:number}} base 原始歌曲
 * @param {Array<{platform:string, list:Array}>} sources 各平台的搜索结果
 * @returns {Array<{item:object, platform:string}>} 按可信度从高到低
 */
function findSameSong(base, sources) {
  const bName = filterStr(base.title);
  const bSinger = filterStr(sortSinger(base.artist));
  const bAlbum = filterStr(base.album);
  const bInterval = Math.round(toSeconds(base.duration));

  const sameInterval = (x) => Math.abs((bInterval || x) - (x || bInterval)) < 5;
  const includesName = (x) => Boolean(x) && (bName.includes(x) || x.includes(bName));
  const includesSinger = (x) => (bSinger ? bSinger.includes(x) || x.includes(bSinger) : true);
  const equalsAlbum = (x) => (bAlbum ? bAlbum === x : true);

  const picked = [];
  for (const src of sources) {
    const list = (src.list || []).map(normalized);
    if (!list.length) continue;

    let hit = null;
    // 版本标签对不上的（KTV/伴奏/翻唱/Live…）直接排除，别用它们顶替
    const okList = list.filter((n) => !hasNewVersionTag(base.title, n.item.title));

    // 第一轮：歌名完全相同 + 歌手对得上（时长已经在下面卡过）
    for (const n of okList) {
      if (!sameInterval(n.fInterval)) continue;
      if (n.fName === bName && includesSinger(n.fSinger)) {
        hit = n;
        break;
      }
    }
    // 第二轮：歌手完全相同 + 歌名包含
    if (!hit) {
      for (const n of okList) {
        if (!sameInterval(n.fInterval)) continue;
        if (n.fSinger === bSinger && includesName(n.fName)) {
          hit = n;
          break;
        }
      }
    }
    // 第三轮：专辑 + 歌手 + 歌名
    if (!hit) {
      for (const n of okList) {
        if (!sameInterval(n.fInterval)) continue;
        if (equalsAlbum(n.fAlbum) && includesSinger(n.fSinger) && includesName(n.fName)) {
          hit = n;
          break;
        }
      }
    }

    if (hit) picked.push({ item: hit.item, platform: src.platform, norm: hit });
  }

  if (!picked.length) return [];

  // 分档排序：完全一致优先，然后逐步放宽（同手机版 newResult 的顺序）
  const out = [];
  const take = (pred) => {
    for (let i = picked.length - 1; i >= 0; i--) {
      if (pred(picked[i].norm)) out.unshift(picked.splice(i, 1)[0]);
    }
  };

  take((n) => n.fSinger === bSinger && n.fName === bName && n.fInterval === bInterval);
  take((n) => n.fName === bName && n.fSinger === bSinger && n.fAlbum === bAlbum);
  take((n) => n.fSinger === bSinger && n.fName === bName);
  take((n) => n.fName === bName && n.fInterval === bInterval);
  take((n) => n.fInterval === bInterval);
  take((n) => n.fName === bName);
  take((n) => n.fSinger === bSinger);
  take((n) => n.fAlbum === bAlbum);
  out.push(...picked);

  return out.map(({ item, platform }) => ({ item, platform }));
}

/**
 * 放宽版：只按歌名相似度排序，用于「这首歌所有源都失败」时给用户列可选版本。
 * 明确标记为非原版候选，界面上要提示是别的版本。
 */
function listAlternatives(base, list, limit = 5) {
  const bName = filterStr(base.title);
  const bSinger = filterStr(sortSinger(base.artist));

  const scored = [];
  for (const item of list || []) {
    const n = normalized(item);
    if (!n.fName) continue;

    let titleHit = 0;
    if (n.fName === bName) titleHit = 3;
    else if (n.fName.includes(bName) || bName.includes(n.fName)) titleHit = 2;
    else continue; // 歌名都对不上就不要了

    const singerHit = bSinger && n.fSinger === bSinger ? 3 : bSinger && (n.fSinger.includes(bSinger) || bSinger.includes(n.fSinger)) ? 1 : 0;
    const durHit = Math.abs(n.fInterval - Math.round(toSeconds(base.duration))) < 5 ? 1 : 0;

    scored.push({ item, rank: titleHit * 10 + singerHit * 3 + durHit });
  }

  scored.sort((a, b) => b.rank - a.rank);
  return scored.slice(0, limit).map((x) => x.item);
}

module.exports = {
  findSameSong,
  listAlternatives,
  toSeconds,
  filterStr,
  sortSinger,
  hasNewVersionTag,
};
