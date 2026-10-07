// AlphaSun 声波分析仪 · Android Web 资源增量复制（替代 npx cap copy android）
// 背景：WorkBuddy safe-delete 守卫会拦截一次删除 ≥50 文件的批量操作，
//   而 cap copy 每次运行都 rmSync 整个 android/app/src/main/assets/public（53 个文件）→ 被拦截且半途删坏。
// 本脚本与 sync-www.js 同款策略：逐文件覆盖复制 + 清理陈旧（正常 0 个），绝不整目录删除。
// 运行: node tools/cap-copy.js
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const www = path.join(root, 'www');
const pub = path.join(root, 'android', 'app', 'src', 'main', 'assets', 'public');

function walk(dir, base, out) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const rel = base ? base + '/' + e.name : e.name;
    if (e.isDirectory()) walk(path.join(dir, e.name), rel, out);
    else out.push(rel);
  }
  return out;
}

if (!fs.existsSync(www)) { console.error('www/ 不存在，请先运行 node sync-www.js'); process.exit(1); }
fs.mkdirSync(pub, { recursive: true });

const files = walk(www, '', []);
const keep = new Set();
let copied = 0;
for (const rel of files) {
  keep.add(rel);
  const s = path.join(www, rel), d = path.join(pub, rel);
  fs.mkdirSync(path.dirname(d), { recursive: true });
  fs.copyFileSync(s, d);
  copied++;
}

let removed = 0;
for (const rel of walk(pub, '', [])) {
  if (!keep.has(rel)) { try { fs.unlinkSync(path.join(pub, rel)); removed++; } catch (_) {} }
}

// —— Capacitor 运行时配置（补齐 `npx cap copy` 的另两步）——
// 参考 APK（dist/AlphaSun-Sonic-Analyzer-0.3.0.apk）内含
//   assets/capacitor.config.json 与 assets/capacitor.plugins.json，
// 缺任何一个 Bridge 都读不到配置。plugins 为空数组 = 本项目未使用任何 Capacitor 插件。
const assets = path.join(root, 'android', 'app', 'src', 'main', 'assets');
fs.mkdirSync(assets, { recursive: true });
fs.writeFileSync(path.join(assets, 'capacitor.config.json'),
  fs.readFileSync(path.join(root, 'capacitor.config.json')));
fs.writeFileSync(path.join(assets, 'capacitor.plugins.json'), '[]');

console.log(`cap-copy(增量): 复制 ${copied} 个文件到 android public/，清理陈旧 ${removed} 个；` +
  `已生成 capacitor.config.json + capacitor.plugins.json`);
