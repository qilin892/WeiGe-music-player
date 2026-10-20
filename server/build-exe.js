/**
 * 打包成单文件 exe。
 *
 * 产物：dist/WeigeMusic.exe
 * 双击即可运行：内置 Node 运行时、播放器页面、4 个音源脚本、ffmpeg。
 * 数据（缓存 / 保存的歌 / 解压出的 ffmpeg）写在 exe 同级的 cache、saved、bin 目录。
 *
 * 用法：
 *   node build-exe.js             # 准备资源 + 打包
 *   node build-exe.js --pkg-only  # 只打包（资源已就绪时复用）
 */

const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const ROOT = __dirname;
const ASSETS = path.join(ROOT, 'assets');
const DIST = path.join(ROOT, 'dist');
const pkgOnly = process.argv.includes('--pkg-only');

function log(msg) {
  console.log(`[build] ${msg}`);
}

function ensureDir(dir) {
  fs.mkdirSync(dir, { recursive: true });
}

/** 把 ffmpeg 复制进 resources：打包后由 lib/paths.js 解压到磁盘执行 */
function stageFfmpeg() {
  const src = path.join(ROOT, 'node_modules', 'ffmpeg-static', 'ffmpeg.exe');
  const dst = path.join(ASSETS, 'ffmpeg.exe');
  if (!fs.existsSync(src)) {
    throw new Error('找不到 node_modules/ffmpeg-static/ffmpeg.exe，先执行 npm install');
  }
  const srcSize = fs.statSync(src).size;
  if (fs.existsSync(dst) && fs.statSync(dst).size === srcSize) {
    log(`ffmpeg 已就绪 (${(srcSize / 1048576).toFixed(1)} MB)`);
    return;
  }
  log('复制 ffmpeg.exe ...');
  fs.copyFileSync(src, dst);
  log(`ffmpeg 就绪 (${(srcSize / 1048576).toFixed(1)} MB)`);
}

/** 把上游 README 快照打包进去，离线时仍能按体检表排源 */
function stageHealthReadme() {
  const candidates = [
    path.join(ROOT, '..', 'lx-source', 'README.md'),
    path.join(ROOT, 'assets', 'health-readme.md'),
  ];
  const src = candidates.find((f) => fs.existsSync(f));
  if (!src) {
    log('警告：找不到 lx-source/README.md，音源体检表将只走远程');
    return;
  }
  const dst = path.join(ASSETS, 'health-readme.md');
  fs.copyFileSync(src, dst);
  log(`体检表快照就绪 (${(fs.statSync(dst).size / 1024).toFixed(0)} KB)`);
}

function checkSources() {
  const dir = path.join(ROOT, 'sources');
  const files = fs.existsSync(dir) ? fs.readdirSync(dir).filter((f) => f.endsWith('.js')) : [];
  if (!files.length) throw new Error('sources/ 里没有音源脚本，打包出来会放不了歌');
  log(`音源脚本 ${files.length} 个：${files.join(', ')}`);
}

function run(cmd, args) {
  log(`$ ${cmd} ${args.join(' ')}`);
  // 直接 spawn（不走 shell）：node.exe 路径里有空格，经 shell 会被截断
  const r = spawnSync(cmd, args, { stdio: 'inherit', cwd: ROOT });
  if (r.status !== 0) throw new Error(`命令失败（exit ${r.status}）`);
}

function main() {
  ensureDir(ASSETS);
  ensureDir(DIST);

  if (!pkgOnly) {
    stageFfmpeg();
    stageHealthReadme();
    checkSources();
  }

  const pkgBin = path.join(ROOT, 'node_modules', '@yao-pkg', 'pkg', 'lib-es5', 'bin.js');
  if (!fs.existsSync(pkgBin)) {
    throw new Error('找不到 @yao-pkg/pkg，先执行 npm install');
  }

  const out = path.join(DIST, 'WeigeMusic.exe');
  if (fs.existsSync(out)) fs.unlinkSync(out);

  run(process.execPath, [
    pkgBin,
    '.',
    '--targets',
    'node22-win-x64',
    '--output',
    out,
    '--compress',
    'GZip',
  ]);

  const size = fs.statSync(out).size;
  log(`完成：${out}（${(size / 1048576).toFixed(1)} MB）`);
}

try {
  main();
} catch (err) {
  console.error(`[build] 失败：${err.message}`);
  process.exit(1);
}
