/**
 * 洛雪（LX Music）自定义音源运行时。
 *
 * 音源脚本依赖宿主注入的全局 lx 对象。用 Node 的 vm 造一个最小宿主，
 * 实现脚本实际会用的：EVENT_NAMES / on / send / request / utils。
 *
 * 注意：request 必须**同时支持两种调用风格**——不同音源写法不一致：
 *   const r = await request(url, opts)          // Promise 风格
 *   request(url, opts, (err, res) => { ... })   // 回调风格（幻音、全豆要都是这种）
 */

'use strict';

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const zlib = require('node:zlib');
const crypto = require('node:crypto');

const SOURCE_DIR = path.join(__dirname, 'sources'); // 打包后指向快照内的只读目录

async function doRequest(url, options = {}) {
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
      parts.push(`--lxboundary\r\nContent-Disposition: form-data; name="${k}"\r\n\r\n${v}\r\n`);
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

    let parsed;
    if (binary) {
      parsed = raw;
    } else if (contentType.includes('json')) {
      try {
        parsed = JSON.parse(raw.toString('utf8'));
      } catch {
        parsed = raw.toString('utf8');
      }
    } else {
      parsed = raw.toString('utf8');
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
      body: parsed,
    };
  } finally {
    clearTimeout(timer);
  }
}

/** 双模式 request：有回调就走回调，没有就走 Promise */
function lxRequest(url, options, callback) {
  if (typeof options === 'function') {
    callback = options;
    options = {};
  }
  const promise = doRequest(url, options || {});
  if (typeof callback === 'function') {
    promise.then(
      (res) => callback(null, res),
      (err) => callback(err)
    );
    return undefined;
  }
  return promise;
}

const utils = {
  buffer: {
    from: (...args) => Buffer.from(...args),
    bufToString: (buf, format) => Buffer.from(buf).toString(format || 'utf8'),
  },
  crypto: {
    md5: (str) => crypto.createHash('md5').update(str).digest('hex'),
    randomBytes: (n) => crypto.randomBytes(n),
    aesEncrypt: (buf, mode, key, iv) => {
      const algo = String(mode).includes('ecb') ? 'aes-128-ecb' : 'aes-128-cbc';
      const cipher = crypto.createCipheriv(algo, key, algo.includes('ecb') ? null : iv);
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

/** 载入单个音源脚本 */
async function loadSource(file, { initTimeout = 10000 } = {}) {
  const code = fs.readFileSync(file, 'utf8');
  const handlers = [];
  let initResolve;
  const initPromise = new Promise((resolve) => {
    initResolve = resolve;
  });

  const lx = {
    EVENT_NAMES: { request: 'request', inited: 'inited', updateAlert: 'updateAlert' },
    version: '2.0.0',
    env: 'desktop',
    on(eventName, handler) {
      if (eventName === 'request') handlers.push(handler);
    },
    send(eventName, data) {
      if (eventName === 'inited') initResolve(data);
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
    atob: (s) => Buffer.from(s, 'base64').toString('binary'),
    btoa: (s) => Buffer.from(s, 'binary').toString('base64'),
  };
  sandbox.globalThis = sandbox;
  sandbox.window = sandbox;

  vm.createContext(sandbox);
  vm.runInContext(code, sandbox, { filename: path.basename(file), timeout: 15000 });

  const inited = await Promise.race([
    initPromise,
    new Promise((_, reject) =>
      setTimeout(() => reject(new Error('音源初始化超时（未收到 inited 事件）')), initTimeout)
    ),
  ]);

  return {
    file,
    label: path.basename(file, '.js'),
    sources: inited?.sources || {},
    async call({ source, action, info }) {
      if (!handlers.length) throw new Error('该音源未注册 request 处理器');
      let lastErr;
      for (const handler of handlers) {
        try {
          const out = await handler({ source, action, info });
          if (out !== undefined) return out;
        } catch (err) {
          lastErr = err;
        }
      }
      throw lastErr || new Error(`音源不支持该动作：${source}/${action}`);
    },
  };
}

function listSourceFiles() {
  if (!fs.existsSync(SOURCE_DIR)) return [];
  return fs
    .readdirSync(SOURCE_DIR)
    .filter((f) => f.endsWith('.js'))
    .map((f) => path.join(SOURCE_DIR, f));
}

async function loadAll({ quiet = false } = {}) {
  const out = [];
  for (const file of listSourceFiles()) {
    try {
      out.push(await loadSource(file));
    } catch (err) {
      if (!quiet) console.error(`[skip] ${path.basename(file)}: ${err.message}`);
    }
  }
  return out;
}

module.exports = { loadSource, loadAll, listSourceFiles, lxRequest, SOURCE_DIR };
