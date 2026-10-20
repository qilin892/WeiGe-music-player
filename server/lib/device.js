/**
 * 小智音箱的地址管理。
 *
 * 之前的做法是把 192.168.0.7:8080 写死成默认值，一旦设备重新拿到 DHCP 地址就彻底失联，
 * 而且页面上没有任何地方能改。这里补上三层：
 *
 *   1. 优先级：命令行 --device > 环境变量 DEVICE_IP > device.json > 默认值
 *   2. 持久化：连上过一次就写进 device.json（exe 同级），下次启动直接用它
 *   3. 自动发现：先查 ARP 缓存里的同网段主机，不够再补扫整个网段
 *
 * 固件没有 mDNS（只有 DHCP hostname），所以不能靠 Xiaozhi-XXXX.local，只能扫。
 */

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFile } = require('node:child_process');
const { DATA_ROOT } = require('./paths.js');

const CONFIG_FILE = path.join(DATA_ROOT, 'device.json');
const DEFAULT_IP = '192.168.0.7';
const DEFAULT_PORT = 8080;

/** 固件 MusicHttp 的状态机取值，用来确认「这是自家设备」而不是随便一个 HTTP 服务 */
const KNOWN_STATES = new Set([
  'idle', 'listening', 'speaking', 'notifying',
  'connecting', 'activating', 'starting', 'unknown', 'wifi_configuring',
]);

function argValue(name) {
  const i = process.argv.indexOf(name);
  return i >= 0 ? process.argv[i + 1] : null;
}

function readSaved() {
  try {
    const raw = JSON.parse(fs.readFileSync(CONFIG_FILE, 'utf8'));
    if (raw && typeof raw.ip === 'string' && raw.ip) return raw;
  } catch {
    // 文件不存在或损坏都当作「没存过」
  }
  return null;
}

function writeSaved(obj) {
  try {
    fs.writeFileSync(CONFIG_FILE, JSON.stringify(obj, null, 2), 'utf8');
    return true;
  } catch {
    return false;
  }
}

/** 启动时按优先级决定初始地址 */
function initialTarget() {
  const argIp = argValue('--device');
  const argPort = argValue('--device-port');
  const envIp = process.env.DEVICE_IP;
  const envPort = process.env.DEVICE_PORT;
  const saved = readSaved();

  if (argIp) {
    return { ip: argIp, port: Number(argPort || envPort || saved?.port || DEFAULT_PORT), source: '启动参数' };
  }
  if (envIp) {
    return { ip: envIp, port: Number(envPort || saved?.port || DEFAULT_PORT), source: '环境变量' };
  }
  if (saved?.ip) {
    return { ip: saved.ip, port: Number(saved.port || DEFAULT_PORT), source: '已记住' };
  }
  return { ip: DEFAULT_IP, port: DEFAULT_PORT, source: '默认值' };
}

let current = initialTarget();

function getTarget() {
  return { ...current };
}

/** 手动设置：写内存 + 落盘，下次启动继续用 */
function setTarget(ip, port, source = '手动设置') {
  const cleanIp = String(ip || '').trim();
  const cleanPort = Number(port) || DEFAULT_PORT;
  if (!/^\d{1,3}(\.\d{1,3}){3}$/.test(cleanIp)) {
    throw new Error('地址格式不对，应形如 192.168.1.50');
  }
  current = { ip: cleanIp, port: cleanPort, source };
  writeSaved({ ip: cleanIp, port: cleanPort, updatedAt: new Date().toISOString() });
  return getTarget();
}

/** 探测某个地址是不是小智设备（看 /status 的返回结构，不只看端口通不通） */
async function probe(ip, port = DEFAULT_PORT, timeout = 1200) {
  const started = Date.now();
  try {
    const res = await fetch(`http://${ip}:${port}/status`, {
      signal: AbortSignal.timeout(timeout),
    });
    if (!res.ok) return { ok: false, reason: `HTTP ${res.status}` };

    const data = await res.json().catch(() => null);
    if (!data || data.ok !== true) return { ok: false, reason: '返回不是本项目的设备' };

    const state = String(data.state || '');
    if (!KNOWN_STATES.has(state)) return { ok: false, reason: `状态字段不认识（${state || '空'}）` };

    return { ok: true, state, latency: Date.now() - started };
  } catch (err) {
    const reason =
      err.name === 'TimeoutError' || err.name === 'AbortError' ? '超时' : err.message.slice(0, 60);
    return { ok: false, reason };
  }
}

/** 本机所有 IPv4 网卡（排除回环） */
function localSubnets() {
  const out = [];
  for (const list of Object.values(os.networkInterfaces())) {
    for (const nic of list || []) {
      if (nic.family !== 'IPv4' || nic.internal) continue;
      out.push({ address: nic.address, netmask: nic.netmask });
    }
  }
  return out;
}

function ipToInt(ip) {
  return ip.split('.').reduce((acc, x) => (acc << 8) + Number(x), 0) >>> 0;
}

function intToIp(n) {
  return [(n >>> 24) & 255, (n >>> 16) & 255, (n >>> 8) & 255, n & 255].join('.');
}

/**
 * 枚举网段内可用主机。
 * 掩码大于 /24（如 /16）时不铺开几万个地址，退化成该地址所在的 /24。
 */
function enumerate(subnet) {
  const base = ipToInt(subnet.address) & ipToInt(subnet.netmask);
  const size = ~ipToInt(subnet.netmask) >>> 0;

  if (size > 255) {
    const prefix = ipToInt(subnet.address) & 0xffffff00;
    return Array.from({ length: 254 }, (_, i) => intToIp(prefix + i + 1));
  }

  const hosts = [];
  for (let i = 1; i < size; i++) hosts.push(intToIp(base + i));
  return hosts;
}

function inSubnets(ip, subnets) {
  if (!/^\d{1,3}(\.\d{1,3}){3}$/.test(ip)) return false;
  const n = ipToInt(ip);
  return subnets.some((s) => (n & ipToInt(s.netmask)) >>> 0 === (ipToInt(s.address) & ipToInt(s.netmask)) >>> 0);
}

/** Windows 的 arp -a 里有最近通信过的主机，先问它比盲扫快得多 */
function arpHosts() {
  return new Promise((resolve) => {
    execFile('arp', ['-a'], { timeout: 3000, windowsHide: true }, (err, stdout) => {
      if (err) return resolve([]);
      const ips = [...String(stdout).matchAll(/\b(\d{1,3}(?:\.\d{1,3}){3})\b/g)].map((m) => m[1]);
      resolve([...new Set(ips)]);
    });
  });
}

/** 固定并发的任务池；命中后通过 stop 让其余任务尽快退出 */
async function pool(items, worker, concurrency) {
  let cursor = 0;
  let hit = null;
  const workers = Array.from({ length: Math.min(concurrency, items.length) }, async () => {
    while (cursor < items.length && !hit) {
      const item = items[cursor++];
      const r = await worker(item);
      if (r) hit = r;
    }
  });
  await Promise.all(workers);
  return hit;
}

/**
 * 找设备。
 *
 * 两轮：先探 ARP 缓存里的同网段地址（一般十几个，1 秒内出结果），
 * 没找到再补扫整个 /24。找到就立刻返回，不等剩下的。
 */
async function discover({ port = null, deep = false } = {}) {
  const subnets = localSubnets();
  if (!subnets.length) return { ok: false, error: '本机没有可用的局域网地址' };

  const targetPort = Number(port) || current.port || DEFAULT_PORT;
  const self = new Set(subnets.map((s) => s.address));

  const arp = (await arpHosts()).filter((ip) => inSubnets(ip, subnets) && !self.has(ip));
  const near = arp.filter((ip) => !ip.endsWith('.255') && !ip.endsWith('.0'));

  const tryHost = async (ip) => {
    const r = await probe(ip, targetPort, 420);
    return r.ok ? { ip, port: targetPort, state: r.state, latency: r.latency } : null;
  };

  // 第一轮：ARP 里出现过的
  const first = await pool(near, tryHost, 40);
  if (first) return { ok: true, ...first, scanned: near.length, rounds: 1 };

  // 第二轮：整段扫
  const all = [];
  for (const s of subnets) {
    for (const ip of enumerate(s)) if (!self.has(ip)) all.push(ip);
  }
  const second = await pool(all, tryHost, deep ? 64 : 48);
  if (second) return { ok: true, ...second, scanned: all.length, rounds: 2 };

  return {
    ok: false,
    error: `扫描 ${near.length + all.length} 个地址没找到设备（端口 ${targetPort}）`,
    scanned: near.length + all.length,
  };
}

module.exports = {
  CONFIG_FILE,
  DEFAULT_IP,
  DEFAULT_PORT,
  getTarget,
  setTarget,
  probe,
  discover,
  localSubnets,
};
