#!/usr/bin/env node
/* v2.17.0 录音链路 · 真机冒烟（npm run guardsmoke）
 * 覆盖两条录音主链路：① 声波警戒值守台  ② 语音转写
 * （两者曾共用 ScriptProcessorNode，在本机 Electron 28 下都会让渲染进程崩溃，故一起守住）
 *
 * 为什么需要它：qa-gate 第 9 组只能证明"值守台的 DOM 长齐了"，证明不了
 * "值守流程真跑得起来"——本底评估、状态机、环形仪表刷新、退出复原，全在运行期。
 * 本脚本用 Chrome 的假麦克风（--use-fake-device-for-media-stream）让 getUserMedia 成功，
 * 真正跑一遍「开始值守 → 本底评估 → 正常(蓝) → 停止」，断言每一步的实际结果。
 *
 * 注意：假设备输出的是平稳信号，不会越阈，故本脚本只覆盖 eval→normal 主链路与退出复原；
 *       事件/抓拍/推送需真声源与凭据，无法在本环境自动验证（已在 CHANGELOG 已知限制中写明）。
 *
 * 运行：npm run guardsmoke
 */
const path = require('path');
const os = require('os');
const ROOT = path.resolve(__dirname, '..');

let pw = null;
try { pw = require('playwright-core'); } catch (_) { }
if (!pw) {
  for (const c of [
    path.join(os.homedir(), '.workbuddy', 'binaries', 'node', 'workspace', 'node_modules', 'playwright-core'),
    'C:/Users/net2n/.workbuddy/binaries/node/workspace/node_modules/playwright-core',
  ]) { try { pw = require(c); break; } catch (_) { } }
}
if (!pw || !pw._electron) {
  console.error('✗ 找不到 playwright-core（需要 _electron）');
  process.exit(2);
}
const { _electron } = pw;

const results = [];
function chk(name, ok, detail) {
  results.push({ name, ok: !!ok });
  console.log((ok ? '  ✓ ' : '  ✗ ') + name + (detail ? '  — ' + detail : ''));
}

(async () => {
  delete process.env.ELECTRON_RUN_AS_NODE;
  const app = await _electron.launch({
    args: ['main.js', '--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'],
    cwd: ROOT,
    executablePath: require('./electron-path')(),
    timeout: 60000,
  });
  const page = await app.firstWindow();
  const errs = [];
  page.on('pageerror', e => errs.push(e.message));
  page.on('crash', () => errs.push('*** RENDERER CRASHED ***'));
  const cerr = [];
  page.on('console', m => { if (m.type() === 'error') cerr.push(m.text()); });
  const cerr404 = [];
  page.on('response', r => { if (r.status() === 404) cerr404.push(r.url()); });
  page.on('dialog', async d => { try { await d.accept(); } catch (_) { } });   // 自动确认（日志清空等 confirm，接受以真正测到清空）

  await page.waitForTimeout(1500);

  console.log('\n[1] 打开值守面板');
  // 分支自适应入口：移动版把「警戒值守」做成底部主操作条上的独立按钮 #mGuardBtn，
  // 顶部的「音频工具集」菜单已整组下线（#toolsBtn 被 CSS 隐藏，点击会一直等可见性超时）。
  const viaBar = await page.evaluate(() => {
    const b = document.getElementById('mGuardBtn');
    if (!b) return false;
    const r = b.getBoundingClientRect();
    return r.width > 0 && r.height > 0 && getComputedStyle(b).display !== 'none';
  });
  if (viaBar) {
    await page.click('#mGuardBtn');
  } else {
    await page.click('#toolsBtn');
    await page.waitForTimeout(200);
    await page.evaluate(() => {
      const b = Array.from(document.querySelectorAll('#toolsMenu button[data-tool]'))
        .find(x => x.dataset.tool === '声波警戒值守');
      if (b) b.click();
    });
  }
  await page.waitForTimeout(300);
  chk('值守面板已打开', await page.evaluate(() => {
    const m = document.getElementById('alertMask');
    return !!m && m.classList.contains('on');
  }));

  console.log('\n[2] 缩短本底评估并关闭全屏/摄像头（自动化可控）');
  await page.evaluate(() => {
    const e = document.getElementById('alertEval'); if (e) e.value = '2';
    const f = document.getElementById('alertFull'); if (f) f.checked = false;
    const c = document.getElementById('alertCam'); if (c) c.checked = false;
  });
  chk('参数已设为 评估2秒 / 不全屏 / 不启用摄像头', await page.evaluate(() =>
    document.getElementById('alertEval').value === '2' &&
    !document.getElementById('alertFull').checked && !document.getElementById('alertCam').checked));

  console.log('\n[3] 开始值守');
  await page.click('#alertStart');
  await page.waitForTimeout(1200);
  const afterStart = await page.evaluate(() => ({
    shown: document.getElementById('guardScreen').style.display !== 'none',
    stat: (document.getElementById('alertStat') || {}).textContent,
    state: (document.getElementById('gState') || {}).textContent,
    now: (document.getElementById('alertNow') || {}).textContent,
  }));
  chk('值守台已弹出', afterStart.shown, 'display 非 none=' + afterStart.shown);
  chk('面板状态变为「值守中」', afterStart.stat === '值守中', 'stat=' + afterStart.stat);
  chk('顶部进入本底评估阶段', /评估/.test(afterStart.state || ''), 'state=' + afterStart.state);

  console.log('\n[4] 等待本底评估完成（2 秒 + 余量）');
  await page.waitForTimeout(3500);
  const ev = await page.evaluate(() => {
    const g = id => document.getElementById(id);
    const cv = id => { const c = g(id); const r = c ? c.getBoundingClientRect() : null; return { w: r ? r.width : 0, h: r ? r.height : 0 }; };
    return {
      state: (g('gState') || {}).textContent,
      cls: (g('gState') || {}).className,
      lbl: (g('gDbLbl') || {}).textContent,
      floor: (g('gFloor') || {}).textContent,
      db: (g('gDb') || {}).textContent,
      lampNormal: g('gLampNormal') ? g('gLampNormal').classList.contains('on') : false,
      lampWarn: g('gLampWarn') ? g('gLampWarn').classList.contains('on') : false,
      lampAlarm: g('gLampAlarm') ? g('gLampAlarm').classList.contains('on') : false,
      wave: cv('gWave'), ring: cv('gRing'), level: cv('gLevel'),
      time1: (g('gTime') || {}).textContent,
    };
  });
  chk('状态已由「评估中」进入判定态（正常/预警/告警之一）',
    ['正常', '预警', '告警'].indexOf(ev.state) >= 0, 'state=' + ev.state + ' class=' + ev.cls);
  chk('阈值条已算出本底/预警/告警', /本底/.test(ev.lbl || '') && /预警/.test(ev.lbl || ''), 'lbl=' + ev.lbl);
  chk('本底与当前声级已出数值', ev.floor !== '—' && /\d/.test(ev.db || ''), 'floor=' + ev.floor + ' db=' + ev.db);
  // 不变量：亮着的灯必须与当前状态一致（假设备信号在变，不能死断言某一盏灯）
  chk('警戒灯与当前状态一致（不变量）',
    (ev.state === '正常' && ev.lampNormal && !ev.lampWarn && !ev.lampAlarm) ||
    (ev.state === '预警' && ev.lampWarn && !ev.lampAlarm) ||
    (ev.state === '告警' && ev.lampAlarm),
    'state=' + ev.state + ' normal=' + ev.lampNormal + ' warn=' + ev.lampWarn + ' alarm=' + ev.lampAlarm);
  chk('波形与环形画布在值守中已有尺寸', ev.wave.w > 0 && ev.ring.w > 0,
    'wave=' + JSON.stringify(ev.wave) + ' ring=' + JSON.stringify(ev.ring));
  chk('左上电平表画布已渲染（v2.19.0 缩小不遮挡）', ev.level.w > 0 && ev.level.h > 0, 'gLevel=' + JSON.stringify(ev.level));

  console.log('\n[4.5] 暂停 / 恢复（v2.24.0：保持麦克风，暂停判定与录音）');
  const durBefore = await page.evaluate(() => (document.getElementById('gDur') || {}).textContent);
  await page.click('#gPause');
  await page.waitForTimeout(1200);
  const paused = await page.evaluate(() => ({
    btn: (document.getElementById('gPause') || {}).textContent,
    state: (document.getElementById('gState') || {}).textContent,
    cls: (document.getElementById('gState') || {}).className || '',
    db: (document.getElementById('gDb') || {}).textContent,
  }));
  chk('点击暂停后状态显示「已暂停」且按钮变「继续」',
    /已暂停/.test(paused.state || '') && /继续/.test(paused.btn || ''),
    'state=' + paused.state + ' btn=' + paused.btn);
  chk('暂停态有独立样式标记（paused）', /paused/.test(paused.cls || ''), 'class=' + paused.cls);
  // 暂停时计时应冻结
  const durDuring = await page.evaluate(() => (document.getElementById('gDur') || {}).textContent);
  await page.waitForTimeout(1600);
  const durAfter = await page.evaluate(() => (document.getElementById('gDur') || {}).textContent);
  chk('暂停期间计时冻结（麦克风仍保持开启）', durDuring === durAfter,
    durBefore + ' → ' + durDuring + ' → ' + durAfter);
  await page.click('#gPause');
  await page.waitForTimeout(1200);
  const resumed = await page.evaluate(() => ({
    btn: (document.getElementById('gPause') || {}).textContent,
    state: (document.getElementById('gState') || {}).textContent,
  }));
  chk('再次点击恢复值守（按钮回到「暂停」且状态不再是已暂停）',
    /暂停/.test(resumed.btn || '') && !/已暂停/.test(resumed.state || ''),
    'state=' + resumed.state + ' btn=' + resumed.btn);

  console.log('\n[5] 顶部时钟在走');
  await page.waitForTimeout(1500);
  const time2 = await page.evaluate(() => (document.getElementById('gTime') || {}).textContent);
  chk('时钟持续刷新', !!time2 && time2 !== ev.time1, ev.time1 + ' → ' + time2);

  console.log('\n[5.5] 值守中持续观察（假设备周期性发声，事件在回落持续设定秒数后才归档）');
  await page.waitForTimeout(9000);
  const mid = await page.evaluate(() => {
    const L = document.getElementById('alertLog');
    return { log: L ? Array.from(L.children).map(c => c.textContent).slice(-6) : [] };
  });
  chk('值守日志已记录越限/事件（证明状态机在真跑）', mid.log.length > 0,
    mid.log.length + ' 条，最新：' + (mid.log[mid.log.length - 1] || '').slice(0, 80));

  console.log('\n[6] 退出值守并复原（值守台覆盖全屏，只能走值守台上的退出/Esc）');
  await page.click('#gExit');
  await page.waitForTimeout(1500);
  const afterStop = await page.evaluate(() => ({
    hidden: document.getElementById('guardScreen').style.display === 'none',
    stat: (document.getElementById('alertStat') || {}).textContent,
    now: (document.getElementById('alertNow') || {}).textContent,
  }));
  chk('值守台已隐藏', afterStop.hidden, 'hidden=' + afterStop.hidden);
  chk('状态复位为待命', afterStop.stat === '待命', 'stat=' + afterStop.stat);
  chk('读数复位', /--/.test(afterStop.now || ''), 'now=' + afterStop.now);

  console.log('\n[6.5] 第二段值守：手动极低阈值 → 确定性造出事件（不依赖假设备的发声时机）');
  await page.evaluate(() => {
    const a = document.getElementById('alertAuto'); if (a) a.checked = false;   // 关自动本底 → 走手动阈值
    const t = document.getElementById('alertThr'); if (t) t.value = '-120';      // 环境本底约 -100 dB，必然越阈
    const h = document.getElementById('alertHold'); if (h) h.value = '1';        // 回落 1 秒即归档
  });
  await page.click('#alertStart');
  await page.waitForTimeout(4000);
  const during = await page.evaluate(() => ({
    state: (document.getElementById('gState') || {}).textContent,
    shown: document.getElementById('guardScreen').style.display !== 'none',
    ringShadow: getComputedStyle(document.getElementById('gRing')).boxShadow,
  }));
  chk('手动阈值下立即进入告警态', during.shown && during.state === '告警', 'state=' + during.state);
  chk('告警时经典环谱整环变红（boxShadow 已着色）', during.shown && during.state === '告警' && !!during.ringShadow && during.ringShadow !== 'none',
    'ringShadow=' + (during.ringShadow || '').slice(0, 60));
  await page.click('#gExit');
  await page.waitForTimeout(1500);

  console.log('\n[6.6] 值守日志清空（v2.24.1：改用应用内确认框，window.confirm 在 Electron 永远返回 false）');
  const logBefore = await page.evaluate(() => { const L = document.getElementById('alertLog'); return L ? L.children.length : -1; });
  await page.click('#alertClrLog').catch(() => { });
  await page.waitForTimeout(500);
  const askUp = await page.evaluate(() => { const m = document.getElementById('askMask'); return !!(m && m.style.display !== 'none'); });
  chk('点「清空日志」弹出应用内确认框', askUp, 'askMask 显示=' + askUp);
  await page.click('#askYes').catch(() => { });
  await page.waitForTimeout(500);
  const logAfter = await page.evaluate(() => { const L = document.getElementById('alertLog'); return L ? L.children.length : -1; });
  chk('确认后值守日志清空生效（条目归零、未崩溃）', logAfter === 0, 'before=' + logBefore + ' after=' + logAfter);

  const evs = await page.evaluate(() => {
    const box = document.getElementById('gEvList');
    const rows = box ? Array.from(box.querySelectorAll('.gEv')) : [];
    return {
      num: parseInt((document.getElementById('gEvNum') || {}).textContent || '0', 10),
      rows: rows.length,
      withAudio: rows.filter(r => !!r.querySelector('audio')).length,
      withBtn: rows.filter(r => Array.from(r.querySelectorAll('button')).some(b => /导出/.test(b.textContent))).length,
      sample: rows.length ? rows[0].textContent.slice(0, 140) : '',
    };
  });
  chk('事件已归档并列入底部列表', evs.num > 0 && evs.rows > 0,
    '事件数=' + evs.num + ' 列表行=' + evs.rows);
  chk('每条事件都带录音回放与导出入口',
    evs.rows > 0 && evs.withAudio === evs.rows && evs.withBtn === evs.rows,
    '含 audio=' + evs.withAudio + '/' + evs.rows + ' 含导出=' + evs.withBtn + '/' + evs.rows);
  if (evs.sample) console.log('     事件样例：' + evs.sample);

  console.log('\n[6.7] 媒体预览灯箱（v2.19.0 新增：拍照/录像回放支持旋转）');
  const media = await page.evaluate(() => ['gMedia', 'gMediaImg', 'gMediaVid', 'gMediaX', 'gRotL', 'gRot0', 'gRotR']
    .filter(id => !!document.getElementById(id)).length);
  chk('媒体预览灯箱 DOM 齐备（容器/图片/视频/关闭/左转/复位/右转）', media === 7, 'found=' + media + '/7');

  console.log('\n[7] 会议转写录音链路（同属 ScriptProcessor 事故面，防回归）');
  await page.click('#alertClose');           // 先关掉值守面板，否则它会挡住后续点击
  await page.waitForTimeout(300);
  // 移动分支：语音转写整组已下线（#asrMask 被 CSS 隐藏、工具集菜单不可达）。
  // 这里必须**显式跳过**而不是硬点 —— 否则 30 秒可见性超时会把整轮冒烟判成失败。
  const asrUp = await page.evaluate(() => {
    const m = document.getElementById('asrMask');
    const b = document.getElementById('toolsBtn');
    const vis = e => !!e && getComputedStyle(e).display !== 'none' && e.getBoundingClientRect().width > 0;
    return { maskDeclared: !!m, menuReachable: vis(b) };
  });
  if (!asrUp.menuReachable) {
    console.log('  · 跳过：本分支「语音转写」已按需求下线（工具集菜单不可达），ScriptProcessor 链路不适用');
  } else {
    await page.click('#toolsBtn');
    await page.waitForTimeout(200);
    await page.evaluate(() => {
      const b = Array.from(document.querySelectorAll('#toolsMenu button[data-tool]')).find(x => x.dataset.tool === '语音转写');
      if (b) b.click();
    });
    await page.waitForTimeout(300);
    await page.click('#asrStart');
    await page.waitForTimeout(4000);
    const asr = await page.evaluate(() => ({
      timer: (document.getElementById('asrTimer') || {}).textContent,
      stopEnabled: !document.getElementById('asrStop').disabled,
    }));
    // 核心断言：活到 4 秒后页面还在（若 ScriptProcessor 崩溃，这里 evaluate 会抛 Target crashed）
    chk('开始转写后录音链路存活（计时在走、未崩溃）', !!asr.timer && asr.stopEnabled,
      'timer=' + asr.timer + ' stopEnabled=' + asr.stopEnabled);
    await page.click('#asrStop');
    await page.waitForTimeout(600);
  }

  // v0.08：前后摄抓拍是用户实测出问题的功能（"后置成功、前置无画面"）。
  // 锁住：① 设备下拉被填充 ② 手动指定入口存在 ③ 前后配对不把同一设备当两路。
  // 假摄像头只有 1 路，故只断言结构与配对自洽性。
  console.log('\n[7.5] 前后摄识别与手动兜底（用户实测「前置无画面」的回归防线）');
  await page.click('#alertClose').catch(() => { });
  await page.waitForTimeout(300);
  // 关键：先真正授权一次摄像头，否则 enumerateDevices 因隐私保护返回空列表、
  // label 也全为空 —— 而"前置无画面"的根因正是 label 为空时配对退化。
  await page.evaluate(async () => {
    try { const s = await navigator.mediaDevices.getUserMedia({ video: true }); s.getTracks().forEach(t => t.stop()); }
    catch (e) { /* 假设备环境可能拒绝，断言里按空列表降级处理 */ }
  }).catch(() => { });
  await page.waitForTimeout(600);
  await page.click('#mGuardBtn').catch(() => { });
  await page.waitForTimeout(700);
  const cam = await page.evaluate(() => {
    const opt = id => { const e = document.getElementById(id); return e ? e.options.length : 0; };
    return {
      frontSel: !!document.getElementById('alertCamFront'),
      backSel: !!document.getElementById('alertCamBack'),
      probeBtn: !!document.getElementById('alertCamProbe'),
      hint: (document.getElementById('alertCamHint') || {}).textContent || '',
      optFront: opt('alertCamFront'), optBack: opt('alertCamBack'),
    };
  });
  chk('提供「前置/后置」手动指定下拉（识别出错时的兜底）',
    cam.frontSel && cam.backSel, 'front=' + cam.frontSel + ' back=' + cam.backSel);
  chk('提供「🔍 识别前后摄」按钮', cam.probeBtn, cam.probeBtn ? '已就位' : '缺失');
  // ★ 用户实测「只检测到 1 路摄像头」��根因：未授权时 enumerateDevices 只返回
  //   一个 label/deviceId 均空的**占位**设备。界面必须明确说明这一点，
  //   否则用户会以为手机真的只有一颗镜头。
  chk('对「1 路且设备名为空」解释为未授权占位设备（而非手机只有一颗镜头）',
    /占位设备|尚未授予摄像头权限/.test(cam.hint) || cam.optFront > 1,
    cam.hint.trim().slice(0, 80));
  chk('摄像头下拉已按设备数填充选项', cam.optFront >= 1 && cam.optBack >= 1,
    '前置 ' + cam.optFront + ' 项 / 后置 ' + cam.optBack + ' 项');
  // 单路设备时文案是「只有 1 路…无法前后双摄」，双路时才提示先后顺序 —— 两种都算通过
  chk('界面说明给出设备检测结论或抓拍顺序提示',
    /先开后摄/.test(cam.hint) || /只检测到/.test(cam.hint) || /检测到/.test(cam.hint),
    cam.hint.trim().slice(0, 60));
  const pair = await page.evaluate(async () => {
    try { const d = await alCamListDual(); return { front: d.front, back: d.back }; }
    catch (e) { return { err: e.message }; }
  });
  // 权限未授予时 enumerateDevices 可能返回空（隐私保护），
  // 此时配对返回 null 是**合规降级**，不算缺陷；只断言"不会把同一设备当前后两路"。
  chk('前后摄配对不把同一设备当前后两路（空列表=权限未授予定为合规降级）',
    (!pair.front && !pair.back) || (!!pair.front && (!pair.back || pair.back !== pair.front)),
    'front=' + (pair.front || 'null') + ' back=' + (pair.back || 'null'));

  console.log('\n[8] 全程零错误');
  chk('无 pageerror / 崩溃', errs.length === 0, errs.join(' | ').slice(0, 300));
  // 过滤可选离线资源 404（VOSK 42MB 模型未随包发布，属预期，非 JS 崩溃）——与 audio-tools-smoke 一致
  const cerrJs = cerr.filter(t => !/Failed to load resource|ERR_FILE_NOT_FOUND|net::ERR/.test(t));
  if (cerr.length !== cerrJs.length)
    console.log('  · 另有 ' + (cerr.length - cerrJs.length) + ' 条可选资源 404（VOSK 离线模型未捆绑等）：' + (cerr404.slice(0, 3).join(' , ') || '（见上方）'));
  chk('无 console.error（JS 类）', cerrJs.length === 0, cerrJs.join(' | ').slice(0, 300));

  await app.close();
  const pass = results.filter(r => r.ok).length, fail = results.length - pass;
  console.log('\n========================================');
  console.log('值守台冒烟：' + pass + ' 通过 / ' + fail + ' 失败 / 共 ' + results.length);
  if (fail) results.filter(r => !r.ok).forEach(r => console.log('  ✗ ' + r.name));
  console.log('========================================');
  process.exit(fail ? 1 : 0);
})().catch(e => {
  console.error('\n✗ 冒烟执行异常：', (e && e.message) || e);
  try { process.exit(3); } catch (_) { }
});
