/**
 * 把任意音频转成设备能播的 OGG/Opus。
 *
 * 设备侧约束（来自 audio_service.cc / 本板 config.h）：
 *   - 解码器为 Opus 且固定单声道（ESP_AUDIO_MONO）
 *   - 板级输出采样率 24000，OGG 解复用支持 48k，内部再做重采样
 * 所以统一输出：OGG / Opus / 单声道 / 48kHz。
 */

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');

const { CACHE_DIR, ensureFfmpeg } = require('./paths.js');

// 打包后 ffmpeg 不能直接从快照里执行，ensureFfmpeg 会按需解压到磁盘
const FFMPEG = ensureFfmpeg();

function cacheKey(song, quality) {
  return crypto
    .createHash('sha1')
    .update(`${song.platform || 'kw'}|${song.id}|${quality}`)
    .digest('hex')
    .slice(0, 16);
}

function run(cmd, args, { timeout = 120000 } = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(cmd, args, { stdio: ['ignore', 'pipe', 'pipe'] });
    let stderr = '';
    const timer = setTimeout(() => {
      child.kill('SIGKILL');
      reject(new Error(`ffmpeg 超时`));
    }, timeout);

    child.stdout.on('data', () => {});
    child.stderr.on('data', (d) => {
      stderr += d.toString();
      if (stderr.length > 8000) stderr = stderr.slice(-8000);
    });
    child.on('error', (err) => {
      clearTimeout(timer);
      reject(err);
    });
    child.on('close', (code) => {
      clearTimeout(timer);
      if (code === 0) resolve();
      else reject(new Error(`ffmpeg 退出码 ${code}: ${stderr.split('\n').slice(-4).join(' ')}`));
    });
  });
}

async function download(url, dest) {
  const res = await fetch(url, {
    headers: {
      'User-Agent': 'Mozilla/5.0',
      Referer: 'http://www.kuwo.cn/',
    },
    signal: AbortSignal.timeout(60000),
    redirect: 'follow',
  });
  if (!res.ok) throw new Error(`下载失败 HTTP ${res.status}`);

  const tmp = `${dest}.part`;
  const buf = Buffer.from(await res.arrayBuffer());
  if (buf.length < 2048) throw new Error(`下载内容过小 (${buf.length} 字节)`);
  fs.writeFileSync(tmp, buf);
  fs.renameSync(tmp, dest);
  return buf.length;
}

/**
 * 确保存在可播放的 OGG，返回 { file, name, bytes }
 */
async function ensureOgg(url, song, { quality = '128k', log = console.log } = {}) {
  const key = cacheKey(song, quality);
  const ogg = path.join(CACHE_DIR, `${key}.ogg`);
  const name = `${key}.ogg`;

  if (fs.existsSync(ogg) && fs.statSync(ogg).size > 4096) {
    const head = Buffer.alloc(4);
    const fd = fs.openSync(ogg, 'r');
    fs.readSync(fd, head, 0, 4, 0);
    fs.closeSync(fd);
    if (head.toString() === 'OggS') {
      const durationSec = await probeDuration(ogg);
      return { file: ogg, name, bytes: fs.statSync(ogg).size, cached: true, durationSec };
    }
  }

  const src = path.join(CACHE_DIR, `${key}.src`);
  const bytes = await download(url, src);
  log(`[transcode] 已下载 ${(bytes / 1048576).toFixed(1)}MB，开始转 OGG/Opus`);

  await run(FFMPEG, [
    '-hide_banner',
    '-loglevel', 'error',
    '-y',
    '-i', src,
    '-vn',
    '-ac', '1',
    '-ar', '48000',
    '-c:a', 'libopus',
    '-b:a', '48k',
    '-application', 'audio',
    '-f', 'ogg',
    ogg,
  ]);

  try {
    fs.unlinkSync(src);
  } catch {
    // 源文件删不掉不影响播放
  }

  const size = fs.statSync(ogg).size;
  if (size < 2048) throw new Error(`转码结果异常 (${size} 字节)`);

  const durationSec = await probeDuration(ogg);
  log(`[transcode] 完成 ${(size / 1048576).toFixed(2)}MB / ${durationSec.toFixed(0)}s -> ${name}`);
  return { file: ogg, name, bytes: size, cached: false, durationSec };
}

/**
 * 读实际音频时长（秒）。
 * 用途：有些源返回的是试听片段或错版，必须用真实时长和搜索结果的预期时长对账，
 * 否则会出现「能播但只播了半首」的假成功。
 */
async function probeDuration(file) {
  return new Promise((resolve) => {
    const child = spawn(FFMPEG, ['-hide_banner', '-i', file], { stdio: ['ignore', 'ignore', 'pipe'] });
    let out = '';
    child.stderr.on('data', (d) => {
      out += d.toString();
      if (out.length > 20000) out = out.slice(-20000);
    });
    child.on('close', () => {
      const m = /Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)/.exec(out);
      if (!m) return resolve(0);
      resolve(Number(m[1]) * 3600 + Number(m[2]) * 60 + Number(m[3]));
    });
    child.on('error', () => resolve(0));
  });
}

/**
 * 探测**远程**音频时长（不下载整首）。
 *
 * 流式播放没有「转完再对账」这一步，但「试听片段/串烧错版」的防线不能丢，
 * 所以在开播前用 ffmpeg 读一下源元数据。多数 mp3/flac 的时长在文件头里，
 * 1–2 秒就能拿到；探不到（格式特殊或超时）就返回 0，交给上层按「信搜索结果」处理。
 */
async function probeRemoteDuration(url, { headers = '', timeout = 8000 } = {}) {
  return new Promise((resolve) => {
    const args = ['-hide_banner'];
    if (headers) args.push('-headers', headers);
    args.push('-i', url);

    const child = spawn(FFMPEG, args, { stdio: ['ignore', 'ignore', 'pipe'] });
    let out = '';
    let done = false;

    const finish = (value) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      try {
        child.kill('SIGKILL');
      } catch {
        // 已经退出了
      }
      resolve(value);
    };

    const timer = setTimeout(() => finish(0), timeout);

    child.stderr.on('data', (d) => {
      out += d.toString();
      if (out.length > 20000) out = out.slice(-20000);
      const m = /Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)/.exec(out);
      if (m) finish(Number(m[1]) * 3600 + Number(m[2]) * 60 + Number(m[3]));
    });

    child.on('close', () => {
      if (done) return;
      const m = /Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)/.exec(out);
      finish(m ? Number(m[1]) * 3600 + Number(m[2]) * 60 + Number(m[3]) : 0);
    });

    child.on('error', () => finish(0));
  });
}

/**
 * 边转边发：ffmpeg 直接从远程源读，Opus 编码结果通过管道写给客户端。
 *
 * 相比「整首转完再给文件」，这样首播延迟只取决于源的首块数据（通常 1–3 秒），
 * 内存占用从几 MB 降到几十 KB，也不落盘。
 *
 * 注意：不设 Content-Length（客户端也就无法 seek），这是流式的固有代价。
 */
function streamOgg(url, res, { headers = '', timeout = 15000 } = {}) {
  return new Promise((resolve) => {
    const args = [
      '-hide_banner', '-loglevel', 'error',
      // 这三个参数是低延迟的关键：不做预缓冲、只分析极少量数据就开编
      '-fflags', 'nobuffer',
      '-probesize', '65536',
      '-analyzeduration', '200000',
    ];
    if (headers) args.push('-headers', headers);
    args.push(
      '-i', url,
      '-vn',
      '-ac', '1',
      '-ar', '48000',
      '-c:a', 'libopus',
      '-b:a', '48k',
      '-application', 'audio',
      '-f', 'ogg',
      'pipe:1'
    );

    const child = spawn(FFMPEG, args, { stdio: ['ignore', 'pipe', 'pipe'] });
    let started = false;
    let killed = false;
    let stderr = '';

    const kill = () => {
      if (killed) return;
      killed = true;
      try {
        child.kill('SIGKILL');
      } catch {
        // 已退出
      }
    };

    const fail = (code, msg) => {
      if (res.headersSent) {
        try {
          res.end();
        } catch {
          // 客户端已断开
        }
        return resolve(false);
      }
      res.writeHead(code, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end(msg);
      resolve(false);
    };

    // 首块数据还没出来：先别发响应头，否则源失效时客户端会收到一个空流
    const timer = setTimeout(() => {
      if (started) return;
      kill();
      fail(504, '音源响应超时');
    }, timeout);

    child.stdout.once('data', (chunk) => {
      started = true;
      clearTimeout(timer);
      if (res.destroyed || res.writableEnded) {
        kill();
        return resolve(false);
      }
      res.writeHead(200, {
        'Content-Type': 'audio/ogg',
        'Cache-Control': 'no-store',
        'Accept-Ranges': 'none',
      });
      res.write(chunk); // 先补上刚才被 once 吃掉的第一块
      child.stdout.pipe(res);
    });

    child.stderr.on('data', (d) => {
      stderr += d.toString();
      if (stderr.length > 4000) stderr = stderr.slice(-4000);
    });

    child.on('error', (err) => {
      clearTimeout(timer);
      fail(502, `ffmpeg 启动失败：${err.message}`);
    });

    child.on('close', (code) => {
      clearTimeout(timer);
      if (!started) {
        const tail = stderr.split('\n').filter(Boolean).slice(-2).join(' ');
        return fail(502, tail || `ffmpeg 退出码 ${code}`);
      }
      try {
        res.end();
      } catch {
        // 客户端已断开
      }
      resolve(code === 0);
    });

    // 客户端中途断开（切歌、关页面）：立刻停掉转码，别让它空转
    res.on('close', () => {
      if (!res.writableEnded) kill();
    });
  });
}

module.exports = { ensureOgg, probeDuration, probeRemoteDuration, streamOgg, CACHE_DIR, FFMPEG };
