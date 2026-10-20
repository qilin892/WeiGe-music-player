'use strict';
/**
 * 伟哥音乐 · 云端 MCP 桥（模块版）
 *
 * 本机作为 MCP client 主动连 xiaozhi.me 的接入点（wss 出站），
 * 把「搜歌 / 放歌 / 停歌」暴露给云端大模型。
 *
 * 为什么是这个方向：云端访问不到家里的 192.168.x.x，但本机能往外连；
 * 连上之后模型就能调这些工具，真正的音频仍由设备在局域网内拉取。
 *
 * 由 server.js 在启动时调用；也可以单独跑（见 mcp-bridge.js）。
 */

const fs = require('node:fs');
const path = require('node:path');

const SERVICE = () => process.env.MUSIC_SERVICE || 'http://127.0.0.1:8090';

const stamp = () => new Date().toLocaleTimeString('zh-CN', { hour12: false });
const log = (...a) => console.log(`[${stamp()}] [MCP]`, ...a);
const warn = (...a) => console.warn(`[${stamp()}] [MCP] !`, ...a);

/* ------------------------------------------------------- 本地服务调用 */
async function callService(pathAndQuery, { timeout = 120000 } = {}) {
  const res = await fetch(`${SERVICE()}${pathAndQuery}`, {
    signal: AbortSignal.timeout(timeout),
  });
  const text = await res.text();
  let data;
  try {
    data = JSON.parse(text);
  } catch {
    throw new Error(`本地服务返回非 JSON（HTTP ${res.status}）`);
  }
  return { status: res.status, data };
}

/* ------------------------------------------------------------- 工具定义 */
const TOOLS = [
  {
    name: 'music_search',
    description:
      '在本地音乐库搜索歌曲（QQ音乐 + 酷我双平台，已按相关性排序）。' +
      '用户想听歌但没说清是哪一首时，先用它列出候选，再把歌名和歌手告诉用户。',
    inputSchema: {
      type: 'object',
      properties: {
        keyword: { type: 'string', description: '歌名，可带歌手，例如「晴天 周杰伦」' },
        limit: { type: 'number', description: '返回条数，默认 5' },
      },
      required: ['keyword'],
    },
  },
  {
    name: 'music_play',
    description:
      '在音箱上播放一首歌。只需要给歌名（可带歌手），会自动搜索、转码成设备能解码的 OGG 并推送到音箱。' +
      '不要自己构造音频地址，也不要传 URL。' +
      '调用后会先花几秒准备音频、再等音箱把当前回复说完，然后才出声，这期间不要重复调用。',
    inputSchema: {
      type: 'object',
      properties: {
        keyword: { type: 'string', description: '歌名，可带歌手，例如「晴天 周杰伦」' },
        index: { type: 'number', description: '用第几个搜索结果，从 0 开始，默认 0（最相关）' },
      },
      required: ['keyword'],
    },
  },
  {
    name: 'music_stop',
    description: '停止音箱上正在播放的音乐。',
    inputSchema: { type: 'object', properties: {} },
  },
];

/* --------------------------------------------------------- 设备状态工具 */
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** 轻量查设备状态：直接代理设备的 /status，不做额外探测 */
async function deviceState() {
  try {
    const { data } = await callService('/api/device/status', { timeout: 5000 });
    return { online: data?.ok === true, state: String(data?.state || '') };
  } catch {
    return { online: false, state: '' };
  }
}

/**
 * 等设备回到空闲。
 *
 * 语音点歌时设备必然在 listening / speaking（正在播 TTS 回复），
 * 这时推送会被固件拒绝，所以必须等这轮对话说完。
 * 如果它正在放别的歌（notifying），先停掉再等。
 */
async function waitForIdle(maxMs = 25000) {
  const until = Date.now() + maxMs;
  let stopped = false;

  while (Date.now() < until) {
    const st = await deviceState();
    if (!st.online) throw new Error('音箱连接中断，确认设备仍然开机');
    if (st.state === 'idle') return true;

    if (st.state === 'notifying' && !stopped) {
      stopped = true;
      await callService('/api/device/stop', { timeout: 6000 }).catch(() => {});
      continue;
    }
    await sleep(400);
  }
  return false;
}

/* ------------------------------------------------------------- 工具实现 */
async function toolSearch(args) {
  const kw = String(args.keyword || '').trim();
  if (!kw) throw new Error('缺少 keyword');
  const limit = Math.max(1, Math.min(Number(args.limit) || 5, 10));

  const { data } = await callService(
    `/api/search?kw=${encodeURIComponent(kw)}&limit=${limit}`,
    { timeout: 30000 }
  );
  if (!data.ok) throw new Error(data.error || '搜索失败');
  if (!data.list || !data.list.length) return `没搜到「${kw}」相关的歌`;

  const lines = data.list
    .slice(0, limit)
    .map((x, i) => {
      const dur = x.duration
        ? `${Math.floor(x.duration / 60)}:${String(x.duration % 60).padStart(2, '0')}`
        : '未知时长';
      return `${i}. ${x.artist} - ${x.title}（${dur}，${x.platform === 'tx' ? 'QQ音乐' : '酷我'}）`;
    })
    .join('\n');

  return `搜索「${kw}」的结果：\n${lines}\n\n要播放第几首，就用 music_play 传对应的歌名。`;
}

/** 同一时刻只允许一个播放任务，新的请求直接顶掉还没完成的旧任务 */
let pendingPlay = null;

/** 尝试推送一次，失败不抛异常，交给调用方决定要不要重试 */
async function tryPush(url) {
  try {
    const { data } = await callService(`/api/device/play?url=${encodeURIComponent(url)}`, {
      timeout: 15000,
    });
    return { ok: data.ok === true, error: data.error || '' };
  } catch (err) {
    return { ok: false, error: err.message };
  }
}

/** 真正干活的流程：转码 → 推送（失败再等空闲重试一次） */
async function performPlay(kw, index, task) {
  const { data } = await callService(
    `/api/play?kw=${encodeURIComponent(kw)}&target=device&index=${index}`,
    { timeout: 90000 }
  );
  if (task.cancelled) return;

  if (!data.ok) {
    throw new Error(
      `「${kw}」没有可用音源${data.error ? '：' + data.error : ''}` +
        (data.failures ? `（${data.failures.slice(0, 2).join('；')}）` : '')
    );
  }

  // 直接推：固件已放宽到「对话中也能插播」，正常情况下这一步就成了，
  // 不必等设备说完 TTS（语音点歌时它永远等不到 idle）。
  let push = await tryPush(data.url);
  if (task.cancelled) return;

  // 万一被拒（旧固件，或设备处在 connecting 等状态），再等它空闲重试一次
  if (!push.ok) {
    const idle = await waitForIdle(45000);
    if (task.cancelled) return;
    if (!idle) throw new Error(`音箱一直在对话中，没能插播（${push.error || '设备忙'}）`);
    push = await tryPush(data.url);
    if (!push.ok) throw new Error(push.error || '推送到音箱失败');
  }

  const sec = data.durationSec
    ? `（${Math.floor(data.durationSec / 60)} 分 ${data.durationSec % 60} 秒）`
    : '';
  return `正在音箱上播放：${data.song.artist} - ${data.song.title}${sec}`;
}

async function toolPlay(args) {
  const kw = String(args.keyword || '').trim();
  if (!kw) throw new Error('缺少 keyword');
  const index = Math.max(0, Number(args.index) || 0);

  // 设备在就行 —— 不要求它此刻空闲，语音点歌时它正在播回复
  const st = await deviceState();
  if (!st.online) throw new Error('音箱连不上，确认设备已开机、地址正确');

  // 顶掉上一个未完成的播放任务（用户连点两首歌时以最后一首为准）
  if (pendingPlay) pendingPlay.cancelled = true;
  const task = { cancelled: false };
  pendingPlay = task;

  const work = performPlay(kw, index, task).finally(() => {
    if (pendingPlay === task) pendingPlay = null;
  });

  // 最多等 6 秒：缓存命中常常 1–3 秒就好，慢的（首次下载+转码）交给后台
  const quick = await Promise.race([
    work.then((text) => ({ done: true, text })).catch((err) => ({ done: true, error: err })),
    sleep(6000).then(() => ({ done: false })),
  ]);

  if (quick.done) {
    if (quick.error) throw quick.error;
    return quick.text;
  }

  // 后台继续，失败只记日志（此时已经回过话了，没法再改口）
  work.catch((err) => warn(`后台播放失败：${err.message}`));
  return `正在准备《${kw}》，大约十几秒后开始播放`;
}

async function toolStop() {
  const { data } = await callService('/api/device/stop', { timeout: 10000 });
  if (!data.ok) throw new Error(data.error || '停止失败');
  return '已停止播放';
}

async function runTool(name, args) {
  switch (name) {
    case 'music_search':
      return toolSearch(args);
    case 'music_play':
      return toolPlay(args);
    case 'music_stop':
      return toolStop();
    default:
      throw new Error(`未知工具：${name}`);
  }
}

/* ------------------------------------------------------------ MCP 协议 */
function createBridge({ token, debug = false }) {
  const url = `wss://api.xiaozhi.me/mcp/?token=${encodeURIComponent(token)}`;
  let ws = null;
  let retry = 2000;
  let heartbeat = null;
  let closedByUs = false;

  const send = (obj) => {
    if (!ws || ws.readyState !== WebSocket.OPEN) return false;
    const payload = JSON.stringify(obj);
    if (debug) log('→', payload.slice(0, 180));
    ws.send(payload);
    return true;
  };

  const replyResult = (id, result) => send({ jsonrpc: '2.0', id, result });
  const replyError = (id, code, message) => send({ jsonrpc: '2.0', id, error: { code, message } });

  async function onMessage(raw) {
    let msg;
    try {
      msg = JSON.parse(raw);
    } catch {
      warn('收到非 JSON：', String(raw).slice(0, 100));
      return;
    }
    if (debug) log('←', JSON.stringify(msg).slice(0, 260));

    const { method, id } = msg;

    // 通知类不回包
    if (id === undefined || id === null) {
      if (method === 'notifications/initialized') log('云端确认初始化完成');
      return;
    }

    switch (method) {
      case 'initialize': {
        const pv = msg.params?.protocolVersion || '2024-11-05';
        log(`初始化握手完成（协议 ${pv}）`);
        replyResult(id, {
          protocolVersion: pv,
          capabilities: { tools: {} },
          serverInfo: { name: 'weige-music', version: '1.0.0' },
        });
        return;
      }
      case 'ping':
        replyResult(id, {});
        return;
      case 'tools/list':
        log(`云端拉取工具列表（${TOOLS.length} 个）`);
        replyResult(id, { tools: TOOLS });
        return;
      case 'tools/call': {
        const name = msg.params?.name;
        const args = msg.params?.arguments || {};
        log(`调用 ${name} ${JSON.stringify(args).slice(0, 100)}`);
        try {
          const text = await runTool(name, args);
          log(`→ ${text.split('\\n')[0].slice(0, 110)}`);
          replyResult(id, { content: [{ type: 'text', text }] });
        } catch (err) {
          warn(`失败：${err.message}`);
          replyResult(id, {
            content: [{ type: 'text', text: `失败：${err.message}` }],
            isError: true,
          });
        }
        return;
      }
      default:
        warn(`未支持的方法：${method}`);
        replyError(id, -32601, `Method not found: ${method}`);
    }
  }

  function connect() {
    log(`连接云端 MCP 接入点 ${url.replace(/token=[^&]+/, 'token=***')}`);
    ws = new WebSocket(url);

    ws.addEventListener('open', () => {
      log('已连上，等待云端初始化');
      retry = 2000;
      clearInterval(heartbeat);
      heartbeat = setInterval(() => send({ jsonrpc: '2.0', method: 'notifications/ping' }), 25000);
    });

    ws.addEventListener('message', (ev) => {
      onMessage(typeof ev.data === 'string' ? ev.data : String(ev.data));
    });

    ws.addEventListener('error', () => {
      // 具体原因一般在随后的 close 事件里，这里不重复刷屏
    });

    ws.addEventListener('close', (ev) => {
      clearInterval(heartbeat);
      if (closedByUs) return;
      warn(`连接断开（code=${ev.code}），${retry / 1000}s 后重连`);
      setTimeout(connect, retry);
      retry = Math.min(Math.round(retry * 1.6), 30000);
    });
  }

  return {
    start: connect,
    stop() {
      closedByUs = true;
      clearInterval(heartbeat);
      try {
        ws?.close();
      } catch {
        // 忽略
      }
    },
  };
}

/* ------------------------------------------------------------- 读取配置 */
function readMcpConfig(dataRoot) {
  try {
    const raw = JSON.parse(fs.readFileSync(path.join(dataRoot, 'mcp.json'), 'utf8'));
    return raw && typeof raw.token === 'string' ? raw : null;
  } catch {
    return null;
  }
}

function saveMcpConfig(dataRoot, token) {
  try {
    fs.writeFileSync(
      path.join(dataRoot, 'mcp.json'),
      JSON.stringify({ token, savedAt: new Date().toISOString() }, null, 2),
      'utf8'
    );
    return true;
  } catch {
    return false;
  }
}

module.exports = { createBridge, readMcpConfig, saveMcpConfig, TOOLS, _runTool: runTool };
