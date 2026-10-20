/**
 * 多源取流 + 可用性探测。
 *
 * 两个关键教训：
 * 1) **id 不能跨平台复用**。QQ 的 songmid 拿去酷我查会命中完全不相干的歌
 *    （实测拿到一条 11 秒的试听片段）。所以只能在本平台内用 id 取流；
 *    跨平台必须重新搜索。
 * 2) 拿到 URL 不等于成功。这些后端经常返回 404 页面、403、或 TLS 被阻断，
 *    必须实际发 Range 请求看是不是真音频。
 */

const { loadAll } = require('../lx-runtime.js');
const { searchKuwo } = require('./search.js');
const { getHealth, planSources } = require('./health.js');

// 源优先级：星海实测最稳（直连酷我 CDN），其余依次回退
const SOURCE_ORDER = ['xinghai', 'qdy', 'changqing', 'huanyin'];

const AUDIO_MAGIC = [
  Buffer.from('ID3'),
  Buffer.from([0xff, 0xfb]),
  Buffer.from([0xff, 0xf3]),
  Buffer.from([0xff, 0xf2]),
  Buffer.from('OggS'),
  Buffer.from('fLaC'),
];

let sourcesPromise = null;

/**
 * 音质降级链。
 *
 * 实测坑：星海对 QQ 平台只出 320k，请求 128k 直接返回空，
 * 于是「标准音质」整首歌都放不了。所以任何档位失败都要往上退一档再试，
 * 而不是原样重试同一个档位。
 */
function qualityChain(quality) {
  const q = String(quality || '320k').toLowerCase();
  if (q === 'flac' || q === 'lossless') return ['flac', '320k', '128k'];
  if (q === '128k') return ['128k', '320k'];
  return ['320k', '128k'];
}

// 每个平台上次成功的源：音源可用性差异极大，记住命中过的能省掉一串失败重试
const lastGoodSource = new Map(); // platform -> source label

// 某平台整轮失败后，短时间内不再尝试（音源后端经常整片挂掉，
// 每次都等它把 4 个源超时一遍会让起播多等好几秒）
const platformDownUntil = new Map(); // platform -> timestamp
const PLATFORM_DOWN_MS = 3 * 60 * 1000;

// 本地实测成功率：上游体检表是「全局视角」，常和本机网络不一致
// （README 标 🟢 的源在本机可能 404/TLS 被拦），所以健康度只当先验，实际表现说了算。
const stats = new Map(); // `${label}|${platform}` -> { ok, fail, failStreak }
const sourceDownUntil = new Map(); // `${label}|${platform}` -> timestamp
const SOURCE_DOWN_MS = 3 * 60 * 1000;
const FAIL_STREAK_LIMIT = 2;

function statOf(key) {
  if (!stats.has(key)) stats.set(key, { ok: 0, fail: 0, failStreak: 0 });
  return stats.get(key);
}

/** 拉普拉斯平滑，避免只成功过一次的源被无限高估 */
function successScore(key) {
  const st = stats.get(key);
  if (!st) return 0.5;
  return (st.ok + 1) / (st.ok + st.fail + 2);
}

function recordOk(key) {
  const st = statOf(key);
  st.ok += 1;
  st.failStreak = 0;
  sourceDownUntil.delete(key);
}

function recordFail(key) {
  const st = statOf(key);
  st.fail += 1;
  st.failStreak += 1;
  if (st.failStreak >= FAIL_STREAK_LIMIT) {
    sourceDownUntil.set(key, Date.now() + SOURCE_DOWN_MS);
  }
}

/**
 * 取流顺序：按上游体检表排（🟢 → 🟡，🔴 在有其它源时跳过），
 * 同一档内优先上次成功的源。
 */
async function planFor(platform, labels) {
  const health = await getHealth();

  // 先按本地成功率降序（同分保持原顺序），再让健康度做分档：🟢 组内就是这个顺序
  const ranked = [...labels].sort(
    (a, b) => successScore(`${b}|${platform}`) - successScore(`${a}|${platform}`)
  );

  const plan = planSources(ranked, lastGoodSource.get(platform), health.sources);

  // 连续失败达到阈值的源，本平台暂时跳过
  const now = Date.now();
  const cooled = plan.list.filter((label) => (sourceDownUntil.get(`${label}|${platform}`) || 0) < now);
  if (cooled.length && cooled.length !== plan.list.length) {
    plan.skipped = [...plan.skipped, ...plan.list.filter((x) => !cooled.includes(x))];
    plan.list = cooled;
  }
  return plan;
}

async function getSources() {
  if (!sourcesPromise) sourcesPromise = loadAll({ quiet: true });
  return sourcesPromise;
}

function looksLikeAudio(buf) {
  if (!buf || buf.length < 4) return false;
  if (buf.length >= 8 && buf.subarray(4, 8).toString() === 'ftyp') return true;
  return AUDIO_MAGIC.some((magic) => buf.subarray(0, magic.length).equals(magic));
}

async function probe(url, timeout = 12000) {
  try {
    const res = await fetch(url, {
      headers: {
        Range: 'bytes=0-1023',
        'User-Agent': 'Mozilla/5.0',
        Referer: 'http://www.kuwo.cn/',
      },
      signal: AbortSignal.timeout(timeout),
      redirect: 'follow',
    });
    const contentType = res.headers.get('content-type') || '';
    const buf = Buffer.from(await res.arrayBuffer());

    // 从 Content-Range 里抠出总长度，用来估算真实码率
    let total = 0;
    const cr = res.headers.get('content-range');
    if (cr && cr.includes('/')) {
      const n = Number(cr.split('/')[1]);
      if (Number.isFinite(n)) total = n;
    }
    if (!total) {
      const cl = Number(res.headers.get('content-length'));
      if (res.status === 200 && Number.isFinite(cl)) total = cl;
    }

    const ok =
      (res.status === 200 || res.status === 206) &&
      (looksLikeAudio(buf) || /audio|octet-stream/i.test(contentType));
    return {
      ok,
      status: res.status,
      contentType,
      total,
      head: buf.subarray(0, 8).toString('hex'),
    };
  } catch (err) {
    return { ok: false, status: 0, contentType: '', total: 0, error: err.message };
  }
}

/** 在指定平台内解析（id 只在本平台有效） */
async function resolveOnPlatform(song, platform, quality, failures, log) {
  const sources = await getSources();
  const musicInfo = {
    source: platform,
    songmid: song.id,
    hash: song.id,
    id: song.id,
    musicId: song.id,
    name: song.title || '',
    singer: song.artist || '',
    albumName: song.album || '',
  };

  const plan = await planFor(platform, sources.map((s) => s.label));
  if (plan.skipped.length) {
    log?.(`[resolve] ${platform} 跳过失效源: ${plan.skipped.join(',')}`);
  }

  for (const name of plan.list) {
    const src = sources.find((s) => s.label === name);
    if (!src) continue;
    const meta = src.sources[platform];
    if (!meta || !meta.actions?.includes('musicUrl')) continue;

    let hit = null;
    for (const q of qualityChain(quality)) {
      try {
        const url = await src.call({
          source: platform,
          action: 'musicUrl',
          info: { musicInfo, type: q },
        });
        if (typeof url !== 'string' || !/^https?:/.test(url)) continue;

        const p = await probe(url);
        if (p.ok) {
          hit = { url, source: name, platform, quality: q };
          break;
        }
      } catch (err) {
        // 换下一档音质再试
      }
    }

    if (hit) {
      lastGoodSource.set(platform, name);
      recordOk(`${name}|${platform}`);
      log?.(`[resolve] 命中 ${name}/${platform} @${hit.quality}`);
      return hit;
    }

    failures.push(`${name}/${platform}: 各档位均取不到直链`);
    recordFail(`${name}|${platform}`);
  }
  return null;
}

/**
 * 解析可播放直链。
 * 顺序：本平台 -> 用歌名+歌手在酷我重新搜（跨平台必须重搜）
 */
async function resolveUrl(song, { quality = '128k', log = console.error, crossPlatform = true } = {}) {
  const failures = [];
  const primary = song.platform || 'kw';

  const downUntil = platformDownUntil.get(primary) || 0;
  if (Date.now() > downUntil) {
    const direct = await resolveOnPlatform(song, primary, quality, failures, log);
    if (direct) {
      platformDownUntil.delete(primary);
      return direct;
    }
    platformDownUntil.set(primary, Date.now() + PLATFORM_DOWN_MS);
    log?.(`[resolve] ${primary} 平台本轮无源，${PLATFORM_DOWN_MS / 60000} 分钟内优先走其它平台`);
  } else {
    failures.push(`${primary}: 平台近期无源，已跳过`);
  }

  // 内部兜底搜索会把「同名的另一个版本」顶上来（实测把 506 秒的串烧当成了晴天），
  // 所以调用方可以关掉它，改用自己严格筛过的同歌候选。
  if (crossPlatform && primary !== 'kw') {
    try {
      const list = await searchKuwo(`${song.title} ${song.artist}`, { limit: 8 });
      list.sort((a, b) => b.score - a.score);
      const alt = list.find((x) => x.score >= 60);
      if (alt) {
        log?.(`[resolve] ${primary} 无源，改用酷我「${alt.title}/${alt.artist}」重试`);
        const viaKw = await resolveOnPlatform(
          { ...alt, platform: 'kw' },
          'kw',
          quality,
          failures,
          log
        );
        if (viaKw) return viaKw;
      }
    } catch (err) {
      failures.push(`酷我重搜失败: ${err.message}`);
    }
  }

  const err = new Error('这首歌当前没有可用音源');
  err.failures = failures;
  throw err;
}

/**
 * 按实际返回判定音质。
 * 实测：请求 flac 时源站常直接返回 320k mp3（content-type 仍是 audio/mpeg，
 * 总字节数与 320k 完全相同），所以不能相信请求参数，只能看内容。
 */
function detectActualQuality(probeResult, durationSec) {
  const ct = String(probeResult.contentType || '').toLowerCase();
  if (ct.includes('flac')) return 'flac';
  if (ct.includes('mpeg') || ct.includes('mp3')) {
    if (probeResult.total && durationSec > 30) {
      const kbps = Math.round((probeResult.total * 8) / durationSec / 1000);
      if (kbps >= 200) return '320k';
      if (kbps >= 96) return '128k';
    }
    return 'mp3';
  }
  return 'unknown';
}

/**
 * 并行探测「这首歌在哪些源上能取到」。
 *
 * 与 resolveUrl 的区别：不是按顺序撞到第一个就返回，而是并发问所有源，
 * 拿到完整清单（谁可用、什么音质、耗时多少），供界面展示与择优。
 * 失败也要返回条目（ok:false），否则用户看不出"这个源试过但不行"。
 */
async function probeSources(
  song,
  {
    quality = '320k',
    timeoutMs = 2500,
    graceMs = 600,
    maxMs = 2500,
    retry = true,
  } = {}
) {
  const sources = await getSources();
  const platform = song.platform || 'kw';
  const health = await getHealth();
  const plan = planSources(
    sources.map((s) => s.label),
    lastGoodSource.get(platform),
    health.sources
  );

  const musicInfo = {
    source: platform,
    songmid: song.id,
    hash: song.id,
    id: song.id,
    musicId: song.id,
    name: song.title || '',
    singer: song.artist || '',
    albumName: song.album || '',
  };

  const chain = qualityChain(quality);

  const startedAt = Date.now();
  const settled = new Map(); // index -> candidate
  let firstOkAt = 0;

  const labels = plan.list;
  const tasks = labels.map(async (label, index) => {
    const src = sources.find((s) => s.label === label);
    const meta = src?.sources?.[platform];
    if (!meta || !meta.actions?.includes('musicUrl')) {
      return { source: label, ok: false, reason: `不支持 ${platform}` };
    }

    const started = Date.now();
    for (const q of chain) {
      try {
        const url = await Promise.race([
          src.call({ source: platform, action: 'musicUrl', info: { musicInfo, type: q } }),
          new Promise((_, rej) => setTimeout(() => rej(new Error('超时')), timeoutMs)),
        ]);
        if (typeof url !== 'string' || !/^https?:/.test(url)) continue;

        const pr = await probe(url);
        if (!pr.ok) continue;

        const actual = detectActualQuality(pr, song.duration || 0);
        recordOk(`${label}|${platform}`);
        if (!firstOkAt) firstOkAt = Date.now();
        return {
          source: label,
          ok: true,
          url,
          quality: actual,
          requestedQuality: q,
          contentType: pr.contentType,
          totalBytes: pr.total || 0,
          ms: Date.now() - started,
        };
      } catch (err) {
        // 换下一档音质再试
      }
    }

    recordFail(`${label}|${platform}`);
    return {
      source: label,
      ok: false,
      reason: '取不到可播放直链',
      ms: Date.now() - started,
    };
  });

  // 每个任务落地即登记，便于提前收工
  tasks.forEach((task) => {
    task.then((r) => settled.set(labels.indexOf(r.source), r)).catch(() => {});
  });

  // 等待策略：首个成功后再宽限 graceMs 收其它结果；
  // 全部失败也要在 maxMs 内收工，绝不能因为一个慢源把起播拖到 5 秒。
  await new Promise((resolve) => {
    const tick = setInterval(() => {
      const now = Date.now();
      const allDone = settled.size >= labels.length;
      const okGraceOver = firstOkAt && now - firstOkAt > graceMs;
      const hardStop = now - startedAt > maxMs;
      if (allDone || okGraceOver || hardStop) {
        clearInterval(tick);
        resolve();
      }
    }, 50);
  });

  const results = labels.map(
    (label, i) =>
      settled.get(i) || {
        source: label,
        ok: false,
        reason: '超时未返回',
        ms: Date.now() - startedAt,
      }
  );

  // 排序：可用优先；同为可用时按音质档位（无损>320k>128k），再按耗时短的优先
  const QUALITY_RANK = { flac: 3, '320k': 2, '128k': 1, mp3: 1, unknown: 0 };
  results.sort((a, b) => {
    if (a.ok !== b.ok) return a.ok ? -1 : 1;
    if (!a.ok) return 0;
    const qd = (QUALITY_RANK[b.quality] || 0) - (QUALITY_RANK[a.quality] || 0);
    if (qd !== 0) return qd;
    return a.ms - b.ms;
  });

  // 全灭时重试一次，并放宽超时：
  // 冷启动/换网络后首帧要付 DNS + TLS 成本，硬截断会误杀唯一可用的源。
  if (retry && !results.some((r) => r.ok)) {
    return probeSources(song, {
      quality,
      timeoutMs: 7000,
      graceMs: 1200,
      maxMs: 7000,
      retry: false,
    });
  }

  return results;
}

/** 对外暴露「实际会用的顺序」，让 /api/sources 展示和真实取流一致 */
async function effectivePlan(platform) {
  const sources = await getSources();
  const labels = sources.map((s) => s.label);
  const plan = await planFor(platform, labels);
  const scores = {};
  for (const label of labels) {
    scores[label] = Number(successScore(`${label}|${platform}`).toFixed(2));
  }
  return { ...plan, scores };
}

/** 本地实测统计，供 /api/sources 展示与排查 */
function getStats() {
  return [...stats.entries()].map(([key, v]) => {
    const [label, platform] = key.split('|');
    const cooling = (sourceDownUntil.get(key) || 0) > Date.now();
    return { label, platform, ok: v.ok, fail: v.fail, failStreak: v.failStreak, cooling };
  });
}

module.exports = {
  qualityChain,
  resolveUrl,
  probeSources,
  effectivePlan,
  probe,
  looksLikeAudio,
  getSources,
  detectActualQuality,
  getStats,
};
