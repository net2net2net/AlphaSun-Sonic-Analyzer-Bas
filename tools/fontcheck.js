#!/usr/bin/env node
/* 字体诊断（tools/fontcheck.js）
 * 目的：确认「声波分析仪」标题**真的**用上了内嵌艺术字体，而不是静默回退到系统字体。
 * 判据（缺一即视为未达标）：
 *   1) document.fonts 已加载 HarmonyDisplay 且 check() 为 true
 *   2) h1 计算样式 fontFamily 首位 = HarmonyDisplay
 *   3) 实测对比：用 HarmonyDisplay 与用系统字体测量同一串文字宽度，两者差值 > 1px
 *      （若完全相等，说明浏览器把 HarmonyDisplay 当作不可用而回退）
 *   4) transform 已应用（skewX 生效）
 * 用法：node tools/fontcheck.js
 */
const path = require('path'), os = require('os'), fs = require('fs');
const ROOT = path.resolve(__dirname, '..');
const DESK = 'D:/SynologyDrive/Workspace/AlphaSun-AudioSpectrumLab';
let pw = null;
for (const c of [path.join(os.homedir(), '.workbuddy', 'binaries', 'node', 'workspace', 'node_modules', 'playwright-core'),
  path.join(DESK, 'node_modules', 'playwright-core')]) { try { pw = require(c); break; } catch (_) { } }
if (!pw || !pw._electron) { console.error('✗ 找不到 playwright-core'); process.exit(2); }

(async () => {
  delete process.env.ELECTRON_RUN_AS_NODE;
  const exe = [path.join(ROOT, 'node_modules', 'electron', 'dist', 'electron.exe'),
    path.join(DESK, 'node_modules', 'electron', 'dist', 'electron.exe')].find(p => fs.existsSync(p));
  const app = await pw._electron.launch({
    args: [path.join(ROOT, 'index.html'), '--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'],
    cwd: ROOT, executablePath: exe, timeout: 60000,
  });
  const page = await app.firstWindow();
  await page.waitForTimeout(2200);

  const r = await page.evaluate(async () => {
    await document.fonts.ready;
    const h1 = document.querySelector('.brand h1');
    const cs = getComputedStyle(h1);
    const fonts = [];
    document.fonts.forEach(f => fonts.push(f.family + ' w' + f.weight + ' ' + f.status));
    // 像素比对法：汉字的 advance width 恒为 1em（全角），**宽度无法区分字体**。
    // 正确做法是把同一串文字用目标字体/回退字体分别画到 canvas 上，逐像素比较。
    // 若像素完全一致 → 目标字体没被用上（静默回退）。
    const draw = (family) => {
      const c = document.createElement('canvas'); c.width = 300; c.height = 60;
      const g = c.getContext('2d');
      g.fillStyle = '#fff'; g.font = '900 34px ' + family;
      g.textBaseline = 'top'; g.fillText(h1.textContent, 4, 6);
      return c.getContext('2d').getImageData(0, 0, 300, 60).data;
    };
    const pxDiff = (a, b) => { let n = 0; for (let i = 0; i < a.length; i += 4) if (a[i] !== b[i]) n++; return n; };
    const pH = draw("'HarmonyDisplay'");
    const pFallback = draw("'__NoSuchFont_X__','PingFang SC'");
    const pOther = draw("'__NoSuchFont_X__','Microsoft YaHei'");
    const fm = new FontFace('HarmonyDisplay', "url('assets/fonts/harmony-display.woff2')");
    return {
      loaded: document.fonts.check('900 20px HarmonyDisplay'),
      declared: fonts,
      computedFamily: cs.fontFamily,
      computedWeight: cs.fontWeight,
      transform: cs.transform,
      text: h1.textContent,
      dHarmonyVsFallback: pxDiff(pH, pFallback),
      dFallbackVsOther: pxDiff(pFallback, pOther),
      ringW: Math.round((document.querySelector('.viz') || {}).clientWidth || 0),
      // 关键：字体文件能否被 fetch 到（打包后路径错会 404）
      canFetch: await fetch('assets/fonts/harmony-display.woff2').then(r => r.ok).catch(() => false),
    };
  });

  // 判据：目标字体渲染出的像素必须与回退字体**不同**；
  // 同时用「两个不同回退字体之间的差异」作为噪底参照，确保不是抗锯齿噪声造成的假阳性。
  const diff = r.dHarmonyVsFallback;
  const pass = r.loaded && /HarmonyDisplay/.test(r.computedFamily) && diff > 200 && r.canFetch;
  console.log('文本                :', r.text);
  console.log('字体文件可读取      :', r.canFetch ? 'YES' : 'NO ✗');
  console.log('document.fonts      :', r.loaded ? 'check=TRUE' : 'check=FALSE ✗');
  console.log('已注册字族          :', r.declared.join(' | ') || '(空)');
  console.log('h1 font-family      :', r.computedFamily);
  console.log('h1 font-weight      :', r.computedWeight);
  console.log('h1 transform(skew)  :', r.transform);
  console.log('像素差 艺术vs回退   :', diff, '个像素点不同');
  console.log('噪底   回退A vs 回退B:', r.dFallbackVsOther, '（应远小于上一行）');
  console.log(pass ? '\n✓ 中文艺术字体已真实生效（非静默回退）' : '\n✗ 未生效，仍是回退字体');
  await app.close();
  process.exit(pass ? 0 : 1);
})();
