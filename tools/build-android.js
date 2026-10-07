#!/usr/bin/env node
/* Android release 构建入口（tools/build-android.js）
 *
 * 为什么需要它：原来的 npm script 写的是 `cd android && ./gradlew assembleRelease`。
 * 在 Windows 的 cmd / Git Bash 下 `./gradlew`（POSIX shell 脚本）不会被当成可执行文件，
 * 终端会抛出**本地化且乱码**的报错（实测 GBK 下显示
 * `'.' 不是内部或外部命令，也不是可运行的程序或批处理文件`），
 * 完全看不出是"该用 gradlew.bat"。
 *
 * 本脚本按平台挑包装器，并校验 JAVA_HOME / SDK：
 *   - Windows → android/gradlew.bat
 *   - macOS / Linux → android/gradlew
 *   - JAVA_HOME 未设时，尝试仓库已知的 JDK 17 位置（本机约定）
 */
const path = require('path'), fs = require('fs'), { spawnSync } = require('child_process');

const ROOT = path.resolve(__dirname, '..');
const ANDROID = path.join(ROOT, 'android');
const isWin = process.platform === 'win32';
const wrapper = path.join(ANDROID, isWin ? 'gradlew.bat' : 'gradlew');

if (!fs.existsSync(wrapper)) {
  console.error('✗ 找不到 gradle 包装器：' + wrapper);
  process.exit(2);
}

const env = { ...process.env };
// JAVA_HOME 兜底：本机约定的 JDK 17 位置（仅在本机存在时生效，不影响其它环境）
if (!env.JAVA_HOME) {
  const cands = ['C:/Users/net2n/android-dev/jdk/jdk-17.0.20.1+1'];
  const hit = cands.find(p => { try { return fs.existsSync(path.join(p, 'bin', 'java.exe')); } catch (_) { return false; } });
  if (hit) { env.JAVA_HOME = hit; console.log('· JAVA_HOME 未设置，自动使用 ' + hit); }
}
console.log('· gradle 包装器：' + path.basename(wrapper));
console.log('· JAVA_HOME  ：' + (env.JAVA_HOME || '(未设置，将依赖 PATH 中的 java)'));

const args = process.argv.slice(2).filter(a => !a.startsWith('--'));
const task = args.length ? args : ['assembleRelease'];

/* Windows 必须走 shell:true —— 这是 2026-10-04 实测踩到的坑：
   Node 自 18.20.2 / 20.12.2 / 21.7.3 起为修复 CVE-2024-27980（批处理参数注入），
   **禁止**在 shell:false 时直接 spawn `.bat` / `.cmd`，一律抛 `spawnSync ... EINVAL`。
   gradlew.bat 正是 .bat，所以只能让 cmd.exe 代为执行。
   参数来自本脚本的 CLI（非外部输入），且已过滤掉 -- 开关，注入面可控。 */
let r;
if (isWin) {
  const cmdline = '"' + wrapper + '" ' + task.join(' ');
  r = spawnSync(cmdline, { cwd: ANDROID, stdio: 'inherit', env, shell: true });
} else {
  r = spawnSync(wrapper, task, { cwd: ANDROID, stdio: 'inherit', env });
}
if (r.error) { console.error('✗ 启动 gradle 失败：' + r.error.message); process.exit(3); }
if (r.status !== 0) { console.error('✗ gradle 退出码 ' + r.status); process.exit(r.status || 1); }

// 打印产物位置与体积，省得每次手动翻目录
const outDir = path.join(ANDROID, 'app', 'build', 'outputs', 'apk', 'release');
if (fs.existsSync(outDir)) {
  const apks = fs.readdirSync(outDir).filter(f => f.endsWith('.apk'));
  for (const f of apks) {
    const p = path.join(outDir, f);
    console.log('★ 产物：' + p + '  (' + (fs.statSync(p).size / 1048576).toFixed(2) + ' MB)');
  }
}
