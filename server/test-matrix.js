/**
 * 逐源测试某首歌能否解析出可播放直链。
 * 用法: node test-matrix.js <platform> <songId> [quality]
 *   node test-matrix.js wy 186016 128k
 */

const { loadAll } = require('./lx-runtime.js');

const platform = process.argv[2] || 'wy';
const songId = process.argv[3] || '186016';
const quality = process.argv[4] || '128k';

async function checkUrl(url) {
  try {
    const res = await fetch(url, {
      method: 'GET',
      headers: { Range: 'bytes=0-4095' },
      redirect: 'follow',
    });
    const type = res.headers.get('content-type') || '?';
    const len = res.headers.get('content-length') || '?';
    const buf = Buffer.from(await res.arrayBuffer());
    const head = buf.subarray(0, 4).toString('hex');
    return `${res.status} ${type} len=${len} head=0x${head}`;
  } catch (err) {
    return `FETCH_FAIL ${err.message}`;
  }
}

(async () => {
  const sources = await loadAll({ quiet: true });
  console.log(`目标: platform=${platform} id=${songId} quality=${quality}\n`);

  for (const src of sources) {
    const meta = src.sources[platform];
    if (!meta || !meta.actions?.includes('musicUrl')) continue;

    const musicInfo = {
      source: platform,
      songmid: songId,
      hash: songId,
      id: songId,
      musicId: songId,
      name: '晴天',
      singer: '周杰伦',
    };

    const t0 = Date.now();
    try {
      const url = await src.call({
        source: platform,
        action: 'musicUrl',
        info: { musicInfo, type: quality },
      });
      const ms = Date.now() - t0;
      if (typeof url !== 'string' || !/^https?:/.test(url)) {
        console.log(`[${src.label}] 非直链返回: ${String(url).slice(0, 120)} (${ms}ms)`);
        continue;
      }
      const probe = await checkUrl(url);
      console.log(`[${src.label}] ${ms}ms`);
      console.log(`   url  : ${url.slice(0, 140)}`);
      console.log(`   probe: ${probe}`);
    } catch (err) {
      console.log(`[${src.label}] 失败: ${String(err.message).slice(0, 120)}`);
    }
  }
})();
