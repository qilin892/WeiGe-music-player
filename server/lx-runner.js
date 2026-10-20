/**
 * 洛雪（LX Music）自定义音源的本地运行时。
 *
 * 音源脚本是给 LX Music 客户端用的，依赖宿主提供全局 lx 对象。
 * 这里用 Node 的 vm 模块造一个最小宿主，实现脚本真正会用到的四个接口：
 *   lx.EVENT_NAMES / lx.on / lx.send / lx.request（外加 lx.utils 的一部分）
 *
 * 用法：
 *   node lx-runner.js --list
 *   node lx-runner.js --search "晴天" --source kw
 *   node lx-runner.js --url --source kw --id <hash> --quality 128k
 */

'use strict';

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const zlib = require('node:zlib');
const crypto = require('node:crypto');

const SOURCE_DIR = __dirname + path.sep + 'sources';

/** 构造一次 http 请求，签名对齐 LX 的 lx.request */
async function lxRequest(url, options = {}) {
  const {
    method = 'GET',
    headers = {},
    body,
    form,
    formData,
    timeout = 30000,
    binary = false,
  } = options;

  const init = { method, headers: { ...headers }, redirect: 'follow' };
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeout);
  init.signal = controller.signal;

  if (form) {
    init.body = new URLSearchParams(form).toString();
    if (!init.headers['Content-Type']) {
      init.headers['Content-Type'] = 'application/x-www-form-urlencoded';
    }
  } else if (formData) {
    const parts = [];
    for (const [k, v] of Object.entries(formData)) {
      parts.push(
        `--lxboundary\r\nContent-Disposition: form-data; name="${k}"\r\n\r\n${v}\r\n`
      );
    }
    parts.push('--lxboundary--\r\n');
    init.body = Buffer.from(parts.join(''), 'utf8');
    init.headers['Content-Type'] = 'multipart/form-data; boundary=lxboundary';
  } else if (body !== undefined && body !== null) {
    if (typeof body === 'string' || Buffer.isBuffer(body)) {
      init.body = body;
    } else {
      init.body = new URLSearchParams(body).toString();
      if (!init.headers['Content-Type']) {
        init.headers['Content-Type'] = 'application/x-www-form-urlencoded';
      }
    }
  }

  try {
    const res = await fetch(url, init);
    const contentType = res.headers.get('content-type') || '';
    const raw = Buffer.from(await res.arrayBuffer());

    let parsedBody;
    if (binary) {
      parsedBody = raw;
    } else if (contentType.includes('json')) {
      try {
        parsedBody = JSON.parse(raw.toString('utf8'));
      } catch {
        parsedBody = raw.toString('utf8');
      }
    } else {
      parsedBody = raw.toString('utf8');
    }

    const respHeaders = {};
    res.headers.forEach((v, k) => {
      respHeaders[k] = v;
    });

    return {
      statusCode: res.status,
      statusMessage: res.statusText,
      headers: respHeaders,
      bytes: raw.length,
      raw,
      body: parsedBody,
    };
  } finally {
    clearTimeout(timer);
  }
}

/** 音源脚本偶尔会用到 utils，按需实现最小可用子集 */
const utils = {
  buffer: {
    from: (...args) => Buffer.from(...args),
    bufToString: (buf, format) => Buffer.from(buf).toString(format || 'utf8'),
  },
  crypto: {
    md5: (str) => crypto.createHash('md5').update(str).digest('hex'),
    randomBytes: (n) => crypto.randomBytes(n),
    aesEncrypt: (buf, mode, key, iv) => {
      const algo = mode === 'aes-128-ecb' ? 'aes-128-ecb' : 'aes-128-cbc';
      const cipher = crypto.createCipheriv(algo, key, algo.endsWith('ecb') ? null : iv);
      return Buffer.concat([cipher.update(buf), cipher.final()]);
    },
    rsaEncrypt: () => {
      throw new Error('rsaEncrypt not implemented');
    },
  },
  zlib: {
    inflate: (buf) => zlib.inflateSync(buf),
    deflate: (buf) => zlib.deflateSync(buf),
  },
};

/**
 * 载入一个音源脚本，返回它的能力描述与调用句柄。
 */
async function loadSource(file) {
  const code = fs.readFileSync(file, 'utf8');
  const handlers = [];
  let inited = null;
  let initResolve;
  const initPromise = new Promise((resolve) => {
    initResolve = resolve;
  });

  const lx = {
    EVENT_NAMES: {
      request: 'request',
      inited: 'inited',
      updateAlert: 'updateAlert',
    },
    version: '2.0.0',
    env: 'desktop',
    on(eventName, handler) {
      if (eventName === 'request') handlers.push(handler);
    },
    send(eventName, data) {
      if (eventName === 'inited') {
        inited = data;
        initResolve(data);
      }
    },
    request: lxRequest,
    utils,
  };

  const sandbox = {
    lx,
    console,
    Buffer,
    URL,
    URLSearchParams,
    TextEncoder,
    TextDecoder,
    setTimeout,
    clearTimeout,
    setInterval,
    clearInterval,
    Promise,
    Date,
    Math,
    JSON,
    parseInt,
    parseFloat,
    encodeURIComponent,
    decodeURIComponent,
    atob: (s) => Buffer.from(s, 'base64').toString('binary'),
    btoa: (s) => Buffer.from(s, 'binary').toString('base64'),
  };
  sandbox.globalThis = sandbox;
  sandbox.window = sandbox;

  vm.createContext(sandbox);
  vm.runInContext(code, sandbox, { filename: file, timeout: 15000 });

  const timeoutMs = 10000;
  const result = await Promise.race([
    initPromise,
    new Promise((_, reject) =>
      setTimeout(() => reject(new Error('音源初始化超时（未收到 inited 事件）')), timeoutMs)
    ),
  ]);

  return {
    file,
    name: result?.sources ? Object.keys(result.sources).join(',') : '?',
    sources: result?.sources || {},
    status: result?.status,
    async call({ source, action, info }) {
      if (!handlers.length) throw new Error('该音源未注册 request 处理器');
      for (const handler of handlers) {
        try {
          const out = await handler({ source, action, info });
          if (out !== undefined) return out;
        } catch (err) {
          // 音源内部对不支持的动作会 reject，继续尝试下一个处理器
          if (String(err?.message || '').includes('不支持')) continue;
          throw err;
        }
      }
      throw new Error(`音源不支持该动作：${source}/${action}`);
    },
  };
}

/** 扫描 sources/ 目录下所有音源 */
function listSourceFiles() {
  if (!fs.existsSync(SOURCE_DIR)) return [];
  return fs
    .readdirSync(SOURCE_DIR)
    .filter((f) => f.endsWith('.js'))
    .map((f) => path.join(SOURCE_DIR, f));
}

async function loadAll() {
  const loaded = [];
  for (const file of listSourceFiles()) {
    try {
      const src = await loadSource(file);
      loaded.push(src);
    } catch (err) {
      console.error(`[skip] ${path.basename(file)}: ${err.message}`);
    }
  }
  return loaded;
}

async function cmdList() {
  const sources = await loadAll();
  for (const src of sources) {
    console.log(`\n== ${path.basename(src.file)} ==`);
    for (const [platform, meta] of Object.entries(src.sources)) {
      console.log(
        `  ${platform.padEnd(4)} ${(meta.name || '').padEnd(20)} actions=${JSON.stringify(
          meta.actions
        )} qualities=${JSON.stringify(meta.qualitys)}`
      );
    }
  }
}

async function cmdSearch(keyword, platform, page = 1) {
  const sources = await loadAll();
  for (const src of sources) {
    const supported = src.sources[platform];
    if (!supported || !supported.actions?.includes('search')) continue;
    try {
      const res = await src.call({
        source: platform,
        action: 'search',
        info: { keyword, page, limit: 10 },
      });
      const list = res?.list || res?.data?.list || res?.songs || [];
      console.log(`\n== ${path.basename(src.file)} (${platform}) ==`);
      for (const item of list.slice(0, 10)) {
        console.log(JSON.stringify(item));
      }
      if (list.length) return;
    } catch (err) {
      console.error(`[search fail] ${path.basename(src.file)}: ${err.message}`);
    }
  }
  console.error('没有音源能完成搜索');
}

async function cmdUrl(platform, id, quality = '128k') {
  const sources = await loadAll();
  const musicInfo = { songmid: id, hash: id, musicId: id, id, source: platform };
  for (const src of sources) {
    const supported = src.sources[platform];
    if (!supported || !supported.actions?.includes('musicUrl')) continue;
    try {
      const url = await src.call({
        source: platform,
        action: 'musicUrl',
        info: { musicInfo, type: quality },
      });
      console.log(`${path.basename(src.file)} -> ${url}`);
    } catch (err) {
      console.error(`[url fail] ${path.basename(src.file)}: ${err.message}`);
    }
  }
}

async function main() {
  const argv = process.argv.slice(2);
  const arg = (name) => {
    const i = argv.indexOf(name);
    return i >= 0 ? argv[i + 1] : undefined;
  };

  if (argv.includes('--list')) return cmdList();
  if (argv.includes('--search')) {
    return cmdSearch(arg('--search'), arg('--source') || 'kw', Number(arg('--page') || 1));
  }
  if (argv.includes('--url')) {
    return cmdUrl(arg('--source') || 'kw', arg('--id'), arg('--quality') || '128k');
  }
  console.log('用法: node lx-runner.js [--list | --search 关键词 --source kw | --url --source kw --id xxx]');
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
