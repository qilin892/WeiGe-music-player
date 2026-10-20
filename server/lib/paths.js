/**
 * 路径与运行环境。
 *
 * 开发时：一切都在项目目录里，cache/ 与 saved/ 落在 music-service 下。
 * 打包成 exe 后：代码与静态资源被塞进只读的「快照」，不能再往里面写，
 * 所以数据目录（cache / saved / bin）要落到 exe 同级目录——
 * 这是「拷贝一个 exe 到别人机器双击就能跑」的关键。
 *
 * exe 放在 Program Files 这类不可写目录时，退回到用户目录，避免启动即崩。
 */

const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

/** 资源根：开发时是项目根，打包后是快照内根目录（只读） */
const ASSET_ROOT = path.join(__dirname, '..');

/** 是不是被 pkg 打过包 */
const PACKAGED = Boolean(process.pkg);

function writable(dir) {
  try {
    fs.mkdirSync(dir, { recursive: true });
    const probe = path.join(dir, '.write-test');
    fs.writeFileSync(probe, '1');
    fs.unlinkSync(probe);
    return true;
  } catch {
    return false;
  }
}

/** 依次尝试：exe 同级 -> 用户目录 */
function pickDataRoot() {
  if (!PACKAGED) return writable(ASSET_ROOT) ? ASSET_ROOT : path.join(os.homedir(), '.xiaozhi-music');

  const candidates = [
    path.dirname(process.execPath),
    path.join(os.homedir(), '.xiaozhi-music'),
  ];
  for (const dir of candidates) {
    if (writable(dir)) return dir;
  }
  // 兜底：临时目录，至少能跑起来
  return os.tmpdir();
}

const DATA_ROOT = pickDataRoot();
const CACHE_DIR = path.join(DATA_ROOT, 'cache');
const SAVED_DIR = path.join(DATA_ROOT, 'saved');
const BIN_DIR = path.join(DATA_ROOT, 'bin');

for (const dir of [CACHE_DIR, SAVED_DIR, BIN_DIR]) {
  fs.mkdirSync(dir, { recursive: true });
}

/** 读打包进快照的资源（开发时就是普通文件） */
function readAsset(...parts) {
  return fs.readFileSync(path.join(ASSET_ROOT, ...parts));
}

function assetExists(...parts) {
  try {
    return fs.existsSync(path.join(ASSET_ROOT, ...parts));
  } catch {
    return false;
  }
}

let ffmpegCache = null;

/**
 * 定位 ffmpeg。
 *
 * 打包后快照里的文件不能直接当可执行程序跑，必须先解压到真实磁盘。
 * 顺序：已解压的 -> 打包进 exe 的副本 -> ffmpeg-static -> 系统 PATH。
 */
function ensureFfmpeg() {
  if (ffmpegCache) return ffmpegCache;

  const local = path.join(BIN_DIR, 'ffmpeg.exe');
  if (fs.existsSync(local) && fs.statSync(local).size > 1024 * 1024) {
    ffmpegCache = local;
    return local;
  }

  if (assetExists('assets', 'ffmpeg.exe')) {
    try {
      const buf = readAsset('assets', 'ffmpeg.exe');
      const tmp = `${local}.part`;
      fs.writeFileSync(tmp, buf);
      fs.renameSync(tmp, local);
      ffmpegCache = local;
      return local;
    } catch {
      // 落到下面的回退
    }
  }

  try {
    // 开发环境（node_modules 里有真实文件）
    ffmpegCache = require('ffmpeg-static');
    return ffmpegCache;
  } catch {
    ffmpegCache = 'ffmpeg'; // 指望系统 PATH
    return ffmpegCache;
  }
}

module.exports = {
  PACKAGED,
  ASSET_ROOT,
  DATA_ROOT,
  CACHE_DIR,
  SAVED_DIR,
  BIN_DIR,
  readAsset,
  assetExists,
  ensureFfmpeg,
};
