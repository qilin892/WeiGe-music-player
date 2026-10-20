/**
 * 音源健康度。
 *
 * 数据来自 pdone/lx-music-source 的 README「音源可用性」表（由上游定时体检更新）：
 *   | [changqing](#changqing) | 🟢 可用 |
 * 用它来排取流顺序：先🟢，再🟡，🔴 在还有别的源可用时直接跳过，省掉一串超时。
 *
 * 取不到远程时回退本地仓库快照，再不行就用内置默认顺序——不能让体检数据成为单点故障。
 */

const fs = require('node:fs');
const path = require('node:path');

const REMOTE_README =
  'https://raw.githubusercontent.com/pdone/lx-music-source/main/README.md';

// 依次尝试：开发时的仓库克隆 -> 打包进 exe 的快照副本
const LOCAL_CANDIDATES = [
  path.join(__dirname, '..', '..', 'lx-source', 'README.md'),
  path.join(__dirname, '..', 'assets', 'health-readme.md'),
];

function localReadme() {
  for (const file of LOCAL_CANDIDATES) {
    try {
      if (fs.existsSync(file)) return file;
    } catch {}
  }
  return LOCAL_CANDIDATES[LOCAL_CANDIDATES.length - 1];
}

const LEVEL_SCORE = { ok: 0, warn: 1, dead: 2, unknown: 1 };
const CACHE_TTL_MS = 10 * 60 * 1000;

let cache = null; // { sources, fetchedAt, origin, checkedAt }
let inflight = null;

function levelOf(cell) {
  if (cell.includes('🟢')) return 'ok';
  if (cell.includes('🟡')) return 'warn';
  if (cell.includes('🔴')) return 'dead';
  return 'unknown';
}

/** 解析 README 里的可用性表 */
function parseHealth(markdown) {
  const start = markdown.indexOf('HEALTH-CHECK:START');
  const end = markdown.indexOf('HEALTH-CHECK:END');
  const block = start >= 0 && end > start ? markdown.slice(start, end) : markdown;

  const sources = {};
  const rowRe = /^\|\s*\[([a-z0-9_-]+)\]\(#[^)]*\)\s*\|\s*([^|]+?)\s*\|/gim;
  let m;
  while ((m = rowRe.exec(block)) !== null) {
    const name = m[1].toLowerCase();
    const cell = m[2];
    sources[name] = { level: levelOf(cell), label: cell.replace(/[🟢🟡🔴]/g, '').trim() };
  }

  const checked = /检测时间：(.+)/.exec(markdown);
  return { sources, checkedAt: checked ? checked[1].trim() : '' };
}

async function fetchRemote() {
  const res = await fetch(REMOTE_README, {
    signal: AbortSignal.timeout(10000),
    headers: { 'User-Agent': 'xiaozhi-music/1.0' },
  });
  if (!res.ok) throw new Error(`README HTTP ${res.status}`);
  return res.text();
}

function readLocal() {
  const file = localReadme();
  if (!fs.existsSync(file)) throw new Error('本地没有 README 快照');
  return fs.readFileSync(file, 'utf8');
}

/**
 * 读取健康度（带缓存）。
 * force=true 时忽略缓存，用于手动刷新。
 */
async function getHealth({ force = false } = {}) {
  if (!force && cache && Date.now() - cache.fetchedAt < CACHE_TTL_MS) {
    return cache;
  }
  if (inflight) return inflight;

  inflight = (async () => {

  let markdown = null;
  let origin = '';

  try {
    markdown = await fetchRemote();
    origin = 'remote';
  } catch (err) {
    try {
      markdown = readLocal();
      origin = 'local';
    } catch {
      if (cache) return cache; // 至少还有旧数据
      return {
        sources: {},
        checkedAt: '',
        origin: 'none',
        fetchedAt: Date.now(),
        error: err.message,
      };
    }
  }

  const parsed = parseHealth(markdown);
  cache = { ...parsed, origin, fetchedAt: Date.now() };
  return cache;
  })().finally(() => {
    inflight = null;
  });

  return inflight;
}

/**
 * 按健康度给源排序。
 * @param {string[]} labels 本地实际加载到的源（目录名）
 * @param {string} [prefer] 上次成功的源，同一健康档内优先
 */
function planSources(labels, prefer, healthSources = {}) {
  const seen = new Set();
  const items = labels.map((label, i) => {
    seen.add(label);
    const h = healthSources[label];
    return {
      label,
      level: h ? h.level : 'unknown',
      score: h ? LEVEL_SCORE[h.level] ?? 1 : 1,
      defaultIndex: i,
    };
  });

  // 有活着的源时，🔴 直接排除，避免白等超时
  const alive = items.filter((x) => x.level !== 'dead');
  const usable = alive.length ? alive : items;

  usable.sort((a, b) => {
    if (a.score !== b.score) return a.score - b.score;
    if (prefer && a.label === prefer) return -1;
    if (prefer && b.label === prefer) return 1;
    return a.defaultIndex - b.defaultIndex;
  });

  const skipped = items.filter((x) => !usable.includes(x)).map((x) => x.label);
  return { list: usable.map((x) => x.label), skipped, levels: items };
}

module.exports = { getHealth, planSources, parseHealth, LEVEL_SCORE };
