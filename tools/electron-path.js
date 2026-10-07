/* 开发期 Electron 可执行文件定位（tools/electron-path.js）
 *
 * 背景：移动版（AlphaSun Sonic Analyzer）是 Capacitor 目标，**刻意不安装 electron**，
 * node_modules 因此从 ~250MB 降到 ~46MB。但开发期的视觉/回归测试仍需要 Chromium 内核，
 * 于是统一"借用"桌面仓库（AlphaSun-AudioSpectrumLab）的 electron 可执行文件。
 *
 * 之前的写法是硬编码 `path.join(ROOT,'node_modules','electron','dist','electron.exe')`，
 * 在移动仓库里必然指向不存在的路径 —— playwright 只报一句无信息的
 * `Error: Process failed to launch!`，既不告诉你缺什么、也不告诉你去哪找，
 * 极易被误判成"应用启动崩溃"。本模块把这个坑收敛到一处：
 *   1. 先找本仓库内（未来若重新安装 electron 可直接生效）
 *   2. 再找桌面仓库
 *   3. 都找不到就**明确报错并给出修复指引**，而不是抛无信息的 launch 失败
 */
const path = require('path'), fs = require('fs');

const ROOT = path.resolve(__dirname, '..');
const DESKTOP = process.env.ALPHASUN_DESKTOP_REPO || 'D:/SynologyDrive/Workspace/AlphaSun-AudioSpectrumLab';

function electronExe() {
  const cands = [
    path.join(ROOT, 'node_modules', 'electron', 'dist', 'electron.exe'),
    path.join(ROOT, 'node_modules', 'electron', 'dist', 'electron'),
    path.join(DESKTOP, 'node_modules', 'electron', 'dist', 'electron.exe'),
    path.join(DESKTOP, 'node_modules', 'electron', 'dist', 'electron'),
  ];
  const hit = cands.find(p => { try { return fs.existsSync(p); } catch (_) { return false; } });
  if (hit) return hit;
  console.error('✗ 找不到 Electron 可执行文件。已尝试：\n  ' + cands.join('\n  '));
  console.error('  修复：在本仓库执行 `npm i -D electron`，或用环境变量 ALPHASUN_DESKTOP_REPO 指向桌面仓库根目录。');
  process.exit(2);
}

module.exports = electronExe;
module.exports.DESKTOP = DESKTOP;
module.exports.ROOT = ROOT;
