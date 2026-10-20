const { loadAll } = require('./lx-runtime.js');

(async () => {
  const keyword = process.argv[2] || '晴天';
  const sources = await loadAll();
  const qdy = sources.find((s) => s.file.includes('qdy'));
  if (!qdy) throw new Error('qdy 未加载');

  const res = await qdy.call({
    source: 'qsvip',
    action: 'musicSearch',
    info: { keyword, page: 1, pagesize: 5 },
  });

  const list = Array.isArray(res) ? res : res?.list || res?.data || [];
  console.log('搜索结果数:', list.length);
  if (list.length) console.log('首条:', JSON.stringify(list[0]).slice(0, 300));

  for (const item of list.slice(0, 3)) {
    try {
      const url = await qdy.call({
        source: 'qsvip',
        action: 'musicUrl',
        info: { musicInfo: item, type: '128k' },
      });
      console.log(`[OK] ${item.name || item.songName || item.title} -> ${String(url).slice(0, 120)}`);
    } catch (err) {
      console.log(`[FAIL] ${item.name || item.songName || item.title}: ${err.message}`);
    }
  }
})().catch((err) => {
  console.error('ERR', err.message);
  process.exit(1);
});
