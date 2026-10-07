#!/usr/bin/env node
/* 移动版 v0.01 界面快照（tools/mobile-shot.js）
 * 用途：在**手机/平板视口**下截图，验证移动端布局（品牌/可视化/横排 DSP 链/底部按钮条）。
 * 依赖：与其它 tools 相同的 playwright-core + electron 启动方式。
 * 用法：node tools/mobile-shot.js
 */
const path = require('path'); const os = require('os'); const fs = require('fs');
const ROOT = path.resolve(__dirname, '..');
const OUT = path.join(ROOT, '.workbuddy', 'shots');
let pw = null;
try { pw = require('playwright-core'); } catch (_) { }
if (!pw) for (const c of [path.join(os.homedir(), '.workbuddy', 'binaries', 'node', 'workspace', 'node_modules', 'playwright-core'),
  'C:/Users/net2n/.workbuddy/binaries/node/workspace/node_modules/playwright-core']) { try { pw = require(c); break; } catch (_) { } }
if (!pw || !pw._electron) { console.error('✗ 找不到 playwright-core'); process.exit(2); }
const { _electron } = pw;

const VIEWPORTS = [
  { key: 'phone-portrait', w: 390, h: 844, dsf: 3, mobile: true },
  { key: 'phone-landscape', w: 844, h: 390, dsf: 3, mobile: true },
  { key: 'tablet-portrait', w: 820, h: 1180, dsf: 2, mobile: true },
];

(async () => {
  delete process.env.ELECTRON_RUN_AS_NODE;
  fs.mkdirSync(OUT, { recursive: true });
  // 移动版刻意不安装 electron（Capacitor 打包，node_modules 从 250MB 降到 46MB），
  // 故开发期运行统一借用桌面仓库的 electron 可执行文件，与其它 mobile-* 工具一致。
  const DESK = 'D:/SynologyDrive/Workspace/AlphaSun-AudioSpectrumLab';
  const exe = [path.join(ROOT, 'node_modules', 'electron', 'dist', 'electron.exe'),
    path.join(DESK, 'node_modules', 'electron', 'dist', 'electron.exe')].find(p => fs.existsSync(p));
  if (!exe) { console.error('✗ 找不到 electron 可执行文件（本机 / 桌面仓库均不存在）'); process.exit(2); }
  const app = await _electron.launch({
    args: [path.join(ROOT, 'index.html'), '--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'],
    cwd: ROOT, executablePath: exe, timeout: 60000,
  });
  const page = await app.firstWindow();
  await page.waitForTimeout(1800);
  const errs = [];
  page.on('pageerror', e => errs.push(e && e.message));
  // 开始采集（有假设备），让界面处于真实工作态
  await page.click('#capBtn').catch(() => { });
  await page.waitForTimeout(2200);

  for (const v of VIEWPORTS) {
    const cdp = await page.context().newCDPSession(page);
    await cdp.send('Emulation.setDeviceMetricsOverride', {
      width: v.w, height: v.h, deviceScaleFactor: v.dsf, mobile: v.mobile,
    });
    await page.waitForTimeout(900);
    const f = path.join(OUT, 'M-' + v.key + '.png');
    await page.screenshot({ path: f });
    // 关键布局断言
    const m = await page.evaluate(() => {
      const r = s => { const e = document.querySelector(s); if (!e) return null; const b = e.getBoundingClientRect(); return { x: Math.round(b.x), y: Math.round(b.y), w: Math.round(b.width), h: Math.round(b.height) }; };
      const cs = s => { const e = document.querySelector(s); return e ? getComputedStyle(e) : null; };
      const pl = cs('.pipeline'), nb = r('.mbar'), br = r('.brand'), vz = r('.viz');
      return {
        pipelineDir: pl ? pl.flexDirection : null,
        pipelineScrollW: (document.querySelector('.pipeline') || {}).scrollWidth || 0,
        pipelineClientW: (document.querySelector('.pipeline') || {}).clientWidth || 0,
        mbar: nb, brand: br, viz: vz,
        order: { brand: br && br.y, viz: vz && vz.y, mbar: nb && nb.y },
        capBtnH: r('#capBtn') ? r('#capBtn').h : 0,
        guardBtnH: r('#mGuardBtn') ? r('#mGuardBtn').h : 0,
        vh: window.innerHeight,
        overflowX: document.documentElement.scrollWidth > window.innerWidth + 1,
      };
    });
    const brandFirst = m.order.brand <= m.order.viz;
    const mbarAtBottom = m.mbar && m.mbar.y >= m.vh - m.mbar.h - 2;
    console.log('\n== ' + v.key + ' (' + v.w + 'x' + v.h + ') ==');
    console.log('  DSP 链方向 =', m.pipelineDir, '| 横排可滑动 =', m.pipelineScrollW > m.pipelineClientW);
    console.log('  顺序 品牌.y=' + m.order.brand + ' ≤ 可视化.y=' + m.order.viz + ' →', brandFirst ? 'OK' : 'FAIL');
    console.log('  底部条 y=' + (m.mbar && m.mbar.y) + ' (视口高 ' + m.vh + ') →', mbarAtBottom ? 'OK 贴底' : 'FAIL');
    console.log('  按钮高 开始=' + m.capBtnH + 'px 警戒=' + m.guardBtnH + 'px（原桌面 52px）');
    console.log('  横向溢出 =', m.overflowX ? '有（需修）' : '无');
    console.log('  →', f);
    await cdp.send('Emulation.clearDeviceMetricsOverride').catch(() => { });
    await cdp.detach().catch(() => { });
  }
  console.log('\npageerror: ' + (errs.length ? errs.join(' | ') : '无'));
  await app.close();
})().catch(e => { console.error('ERR', e && e.message); process.exit(3); });
