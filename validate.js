// AlphaSun 声波分析仪 · 构建期 DOM 引用校验守卫（v2.0.0 稳定性架构）
// 背景：v1.3.0 新增「声音状态」行时，E 映射漏注册 voiceStateV，运行时 TypeError
// 被误报成「无法访问麦克风」，连带分析循环中断、全部参数卡空白的连锁故障。
// 本守卫把这类问题拦截在构建阶段：任何 JS 引用的 DOM id 在 HTML 中不存在 → 退出码 1，构建失败。
// 运行：node validate.js  （已接入 npm run sync 前置）
const fs = require('fs');
const path = require('path');
const root = __dirname;
const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8');

let failed = false;
const die = msg => { console.error('  ✗ ' + msg); failed = true; };
const ok = msg => console.log('  ✓ ' + msg);

// ---- 1. 提取 HTML 中所有 id 属性 ----
const idAttrs = new Set();
for (const m of html.matchAll(/\sid\s*=\s*"([^"]+)"/g)) m[1].split(/\s+/).forEach(x => idAttrs.add(x));
for (const m of html.matchAll(/\sid\s*=\s*'([^']+)'/g)) m[1].split(/\s+/).forEach(x => idAttrs.add(x));

// ---- 2. 提取内联 script（只看开标签是否带 src）----
const scripts = [];
const re = /<script([^>]*)>([\s\S]*?)<\/script>/gi;
let m2;
while ((m2 = re.exec(html)) !== null) if (!/\bsrc\s*=/.test(m2[1])) scripts.push(m2[2]);
const js = scripts.join('\n');
if (!js.length) die('未提取到内联 script，校验逻辑失效');

// ---- 3. JS 引用的 id 必须存在于 HTML ----
const refs = new Set();
for (const m of js.matchAll(/\$\('([^']+)'\)/g)) refs.add(m[1]);
for (const m of js.matchAll(/getElementById\('([^']+)'\)/g)) refs.add(m[1]);
const missing = [...refs].filter(r => !idAttrs.has(r));
if (missing.length) missing.forEach(id => die(`JS 引用的 #${id} 在 HTML 中不存在（运行时将抛 null TypeError）`));
else ok(`JS 引用的 ${refs.size} 个 DOM id 全部存在`);

// ---- 4. E 映射：定义 key 覆盖全部 E.xxx 使用；指向的 id 必须存在 ----
const eBlock = js.match(/const E=\{[\s\S]*?\n\};/);
if (!eBlock) die('未找到 E 映射定义');
else {
  const eKeys = new Set(); const eToId = {};
  for (const m of eBlock[0].matchAll(/(\w+):\$\('([^']+)'\)/g)) { eKeys.add(m[1]); eToId[m[1]] = m[2]; }
  const eUses = new Set();
  for (const m of js.matchAll(/\bE\.(\w+)/g)) eUses.add(m[1]);
  const eMissing = [...eUses].filter(k => !eKeys.has(k));
  if (eMissing.length) eMissing.forEach(k => die(`代码使用了 E.${k} 但 E 映射未定义（v1.x voiceStateV 事故同类问题！运行时 TypeError）`));
  else ok(`E 映射 ${eKeys.size} 个 key 覆盖全部 ${eUses.size} 处使用`);
  const badId = Object.entries(eToId).filter(([, id]) => !idAttrs.has(id));
  if (badId.length) badId.forEach(([k, id]) => die(`E.${k} -> #${id} 不存在`));
}

// ---- 5. 平台层文件存在性（index.html 底部 script 引用）----
for (const f of ['desktop/boot.js', 'mobile/bridge.js']) {
  if (!fs.existsSync(path.join(root, f))) die(`平台层文件缺失：${f}（index.html 已引用）`);
  else ok(`平台层就位：${f}`);
}

// ---- 6. 版本号六点一致性（index 常量 / appVer span / package.json / sw.js / gradle / Kotlin APP_VERSION）----
const verIdx = (js.match(/APP_VER='([\d.]+)'/) || [])[1];
const verSpan = (html.match(/id="appVer">v([\d.]+)</) || [])[1];
const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
const verSw = (fs.readFileSync(path.join(root, 'sw.js'), 'utf8').match(/alphasun-audio-v([\d.]+)/) || [])[1];
const gradle = fs.readFileSync(path.join(root, 'android/app/build.gradle'), 'utf8');
const verGradle = (gradle.match(/versionName "([\d.]+)"/) || [])[1];
const kt = fs.readFileSync(path.join(root, 'android/app/src/main/java/com/alphasun/sonicanalyzer/MainViewModel.kt'), 'utf8');
const verKt = (kt.match(/const val APP_VERSION = "v([\d.]+)"/) || [])[1];
const vers = { 'index APP_VER': verIdx, 'appVer span': verSpan, 'package.json': pkg.version, 'sw.js CACHE': verSw, 'gradle versionName': verGradle, 'kotlin APP_VERSION': verKt };
const uniq = [...new Set(Object.values(vers).filter(Boolean))];
if (uniq.length !== 1) { die('版本号不一致：' + JSON.stringify(vers)); }
else ok('版本号六点一致：v' + uniq[0]);
/* versionCode 必须**整行锚定**读取（2026-10-05 事故）：非锚定正则会命中注释里的
   "versionCode <n>" 字样，实际 defaultConfig 值漏检——门禁跟着 bump 一起被骗。 */
const codeGradle = (gradle.match(/^[ \t]*versionCode[ \t]+(\d+)[ \t]*$/m) || [])[1];
const verMajor = (uniq[0].split('.')[0] || '0');
if (codeGradle && +codeGradle < +verMajor) die(`gradle versionCode(${codeGradle}) 小于主版本号(${verMajor})`);

// ---- 7. 陈旧版本号残留扫描（v2.0.0 事故教训：页脚静态文本漏改逃过五点校验）----
// 提取 index.html 中所有形如 x.y.z 的版本串，除当前 APP_VER 外一律视为残留。
// 注：形如 `v2.5.0 需求②` 的历史标记**不会**被命中 —— 正则开头的 \b 要求数字前是边界，
//     而 "v" 与数字同为单词字符、之间无边界，故 v 前缀写法天然被排除（这正是原设计意图：
//     注释里保留历史版本标记是允许的，页脚/文案里漏改的**裸版本号**才是事故源）。
// 白名单：标准文献编号与应用版本号同形（WCAG 2.5.5 / GB/T 28181-2016 等），属误报源——
//     实测本文件因注释里写 "WCAG 2.5.5 规定的 44px 最小点击目标" 被误判为版本残留。
// 修订（2026-10-05）：原实现把 build.gradle 里**依赖库版本**（AGP 8.2.1 / Compose BOM
// 2024.09.03 / Kotlin 1.9.24 等）也当成「陈旧残留」→ 健康工程永远过不了门禁。
// 现改为：gradle 侧**只校验 versionName**（产品版本），依赖版本一律不扫；HTML 侧扫描前
// 先剔除 `<!-- -->` 注释，避免历史版本标记被误判。versionName 一致性已由第 6 项覆盖。
const STD_RE = /(WCAG|ISO|IEC|IEEE|RFC|GB\/T|GB|EN)\s*$/;
const stale = new Set();
const htmlNoComment = html.replace(/<!--[\s\S]*?-->/g, ' ');   // 剔除注释，历史版本标记不误判
for (const m of htmlNoComment.matchAll(/[vV]?\b(\d+\.\d+\.\d+)\b/g)) {
  if (STD_RE.test(htmlNoComment.slice(Math.max(0, m.index - 12), m.index))) continue;   // 标准编号，跳过
  if (m[1] !== verIdx) stale.add(m[1]);
}
// gradle 侧仅校验 versionName（产品版本），不扫依赖库版本，避免 AGP/Kotlin/Compose BOM 误报
const gn = (gradle.match(/versionName "([\d.]+)"/) || [])[1];
if (gn && gn !== verIdx) stale.add(gn);
if (stale.size) die('发现陈旧版本号残留: ' + [...stale].join(', ') + '（当前版本 ' + verIdx + '）');
else ok('无陈旧版本号残留（全文件仅 ' + verIdx + '）');

// ---- 8. 版本-提交一致性守卫（v0.07 事故）----
// 事故经过：v0.1.0 之后又交付了整轮功能（定位操作指示、移动适配层、分离试听修复），
// 却在提交信息里写了个自造的 "v0.07" 标签就发布，**从未真正升过版本号** ——
// 结果 APK 一直停在 0.1.0，用户装上发现"迭代了怎么版本没变"。
// 根因：版本升级只靠"记得手动跑 bump"，没有任何机制兜底。
// 修法：对比 HEAD 与工作区的 APP_VER；若**代码有实质改动但版本号未变**，直接判失败。
const { execFile } = require('child_process');
const curVer = (html.match(/APP_VER\s*=\s*'([\d.]+)'/) || [])[1] || '';
let committedVer = '';
let diffDirty = null;   // null = 尚未取到结果

/* ⚠ v1.0.2 Windows 沙箱修复（两层）：
   ① 同步版 execSync/execFileSync 会抛 `spawnSync ... EBUSY` —— 此环境禁止 Node
      同步拉起子进程，catch 吞掉后 committedVer='' → bumped 恒 false → 门禁永久误报。
   ② 改为**异步** execFile（同样不经过 shell）即可正常工作，实测可取到 HEAD 版本号。
   两条判断互不依赖：任一取不到就保守按「有改动且未升版」处理，宁可多提醒也不静默放行。 */
function gitAsync(args) {
  return new Promise(resolve => {
    try {
      execFile('git', args, { cwd: root, encoding: 'utf8', maxBuffer: 1 << 28 },
        (err, stdout) => {
          if (!err) return resolve({ ok: true, out: stdout || '' });
          if (typeof err.code === 'number') return resolve({ ok: err.code === 0, out: stdout || '' });
          if (err.code === 'ENOENT') return resolve(null);
          resolve(null);                       // EBUSY 等
        });
    } catch (_) { resolve(null); }
  });
}

(async function runVersionGuard() {
  const show = await gitAsync(['show', 'HEAD:index.html']);
  if (show && typeof show.out === 'string') {
    committedVer = (show.out.match(/APP_VER\s*=\s*'([\d.]+)'/) || [])[1] || '';
  }
  const d = await gitAsync(['diff', '--quiet', 'HEAD', '--', 'index.html']);
  diffDirty = d === null ? true : !d.ok;

  // 两条**互相独立**的判断（不是 if/else）：
  //   A. 有没有升版？（相对 HEAD）  B. index.html 相对 HEAD 有没有改动？
  // 只有「有改动 且 没升版」才判失败。
  // ⚠ 曾写成 if/else，结果"本轮已升版"会短路掉 B —— 于是升版之后继续改代码就漏检了。
  const bumped = !!(committedVer && committedVer !== curVer);
  const dirty = diffDirty;

  if (bumped) {
    ok('版本号已升级: ' + committedVer + ' → ' + curVer + '（本次发布已升版）');
  }
  if (bumped && dirty) {
    ok('升版后仍有后续改动，将随下一版一并发布（正常）');
  } else if (!bumped && dirty) {
    die('index.html 有实质改动但版本号未变（当前仍为 ' + (curVer || '?') + '）—— ' +
      '发布新功能/修复必须升版。规则见 README「版本号规则」：' +
      '新增功能→升次版本，纯修缺陷→升修订号。执行：npm run bump 0.2.1');
  } else if (!bumped && !dirty) {
    ok('版本号与 HEAD 一致（' + curVer + '），且本次无代码改动');
  }

  console.log(failed ? '\n✋ validate 校验未通过，构建终止。' : '\n★ validate 全部通过。');
  process.exit(failed ? 1 : 0);
})();
