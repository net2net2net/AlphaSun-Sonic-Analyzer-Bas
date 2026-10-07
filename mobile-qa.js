#!/usr/bin/env node
/* AlphaSun 声波分析仪 · 移动分支 QA 门禁（tools/mobile-qa.js）
 *
 * 为什么单独一份：tools/qa-gate.js 是从桌面仓库继承的，断言深度绑定「音频工具集」菜单
 * （语音转写 / 环境音频采集 / 二级分析台）。这些功能已按移动版需求整组下线，
 * 门禁里的 `page.click('#toolsBtn')` 会等 30 秒可见性超时 → 整轮误判失败。
 * 与其在桌面脚本里塞满 if，不如为移动分支写一份**真正贴合其需求**的门禁。
 *
 * v0.04 断言项（对应本轮 7 条需求的机器可验证部分）：
 *   [1] 诊断模块注入、报告可生成
 *   [2] 关键 DOM 齐备（含新增的 5 按钮 / 3 弹层 / 3 顶栏行）
 *   [3] 预期下线项确实不可达；三大功能卡在**未打开弹层时**主界面不可见
 *   [4] 中文软件名真的用上了内嵌艺术字体（像素判据，防静默回退）
 *   [5] 顶栏三行顺序：品牌 → 参数芯片(rowParams) → 切换+设备+灵敏度(rowCtl) → DSP 链(plBox) → 可视化
 *   [6] 底部主操作条：五连同排 / 顺序正确 / 贴底 / 互不重叠 / ≥44px / 中心点均未被遮挡
 *   [7] 「说明」已移到软件底部：页脚可见 + 说明按钮可达且不被底栏压住
 *   [8] 输入设备与灵敏度位于第 2 行（rowCtl），与主界面一键可达
 *   [9] 三个功能按钮均可开可关对应弹层，弹层内画布/条组有真实尺寸
 *   [10] 可视化：7 种模式可循环、形状族正确、名称同步、中央环点击暂停/继续
 *   [11] 三视口（竖屏/横屏/平板）无横向溢出、DSP 横排、关键浮层无重叠
 *   [12] 交互后零 pageerror / 零 console.error
 *
 * 运行：npm run qa
 */
const path = require('path'), os = require('os'), fs = require('fs');
const ROOT = path.resolve(__dirname, '..');
let pw = null;
for (const c of [path.join(ROOT, 'node_modules', 'playwright-core'),
  path.join(os.homedir(), '.workbuddy', 'binaries', 'node', 'workspace', 'node_modules', 'playwright-core')]) {
  try { pw = require(c); break; } catch (_) { }
}
if (!pw || !pw._electron) { console.error('✗ 找不到 playwright-core（需要 _electron）'); process.exit(2); }

const results = [];
/* textContent 里 &nbsp; 是字面 ' '，比较前统一还原为普通空格 */
const nbsp = t => String(t || '').replace(/ /g, ' ').replace(/\s+/g, ' ').trim();
const chk = (name, ok, detail) => { results.push({ name, ok: !!ok }); console.log((ok ? '  ✓ ' : '  ✗ ') + name + (detail ? '  — ' + detail : '')); };
const VP = [
  { k: '竖屏 390×844', w: 390, h: 844, dsf: 3 },
  { k: '横屏 844×390', w: 844, h: 390, dsf: 3 },
  { k: '平板 820×1180', w: 820, h: 1180, dsf: 2 },
];

(async () => {
  delete process.env.ELECTRON_RUN_AS_NODE;
  const app = await pw._electron.launch({
    args: [path.join(ROOT, 'index.html'), '--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'],
    cwd: ROOT, executablePath: require('./electron-path')(), timeout: 60000,
  });
  const page = await app.firstWindow();
  const perr = [], cerr = [];
  page.on('pageerror', e => perr.push((e && e.message) || String(e)));
  page.on('console', m => { if (m.type() === 'error') cerr.push(m.text()); });
  await page.waitForTimeout(2000);

  const cdp = await page.context().newCDPSession(page);
  const setVp = async (v) => {
    await cdp.send('Emulation.setDeviceMetricsOverride', { width: v.w, height: v.h, deviceScaleFactor: v.dsf, mobile: true });
    await page.waitForTimeout(700);
  };
  await setVp(VP[0]);

  console.log('\n[1] 诊断模块与加载期健康度');
  const diag = await page.evaluate(() => {
    const d = window.__diag;
    if (!d) return null;
    const s = d.summary();
    let len = 0; try { len = (d.report ? d.report() : '').length; } catch (_) { }
    return { s, len, hasExport: typeof d.exportLog === 'function' || !!document.querySelector('[data-diag]') };
  });
  chk('诊断模块已注入且报告可生成', !!diag && diag.len > 200, diag ? 'len=' + diag.len : '无 __diag');
  chk('加载期零 pageerror', perr.length === 0, perr.join(' | ').slice(0, 200));

  console.log('\n[2] 关键 DOM 齐备');
  const ids = ['cv', 'capBtn', 'capTxt', 'mGuardBtn', 'mParamBtn', 'mFgBtn', 'mLocBtn',
    'pmPop', 'fgPop', 'locPop', 'pmPopClose', 'fgPopClose', 'locPopClose',
    'pmCard', 'fgBars', 'locCv', 'fgLearnBtn', 'fgResetBtn', 'locSimBtn',
    'micSel', 'sens', 'sensVal', 'vizSwitch', 'vizPrevM', 'vizNameM', 'vizNextM',
    'rowParams', 'rowCtl', 'plBox', 'bandBars', 'clockTime', 'dbBig',
    'guardScreen', 'alertStart', 'askMask', 'helpBtn'];
  const miss = await page.evaluate(l => l.filter(i => !document.getElementById(i)), ids);
  chk('关键控件 / 面板 DOM 存在', miss.length === 0, miss.length ? '缺失=' + JSON.stringify(miss) : ids.length + ' 项齐全');

  console.log('\n[3] 下线项不可达 + 三大功能卡不再常驻主界面');
  const dead = await page.evaluate(() => {
    const vis = id => { const e = document.getElementById(id); if (!e) return false; const cs = getComputedStyle(e); return cs.display !== 'none' && cs.visibility !== 'hidden' && e.getBoundingClientRect().width > 0; };
    const hidden = sel => { const e = document.querySelector(sel); if (!e) return '(元素不存在)'; const cs = getComputedStyle(e); return cs.display === 'none' || e.getBoundingClientRect().height === 0; };
    const w = id => { const e = document.getElementById(id); return e ? Math.round(e.getBoundingClientRect().width) : -1; };
    return {
      lab2Btn: vis('lab2Btn'), toolsBtn: vis('toolsBtn'),
      asrMask: vis('asrMask'), envMask: vis('envMask'),
      smartCard: hidden('.card.mhide'),
      // v0.04 需求⑥：三块功能在**未点击按钮时**必须完全不可见
      fgW: w('fgBars'), locW: w('locCv'), pmW: w('pmCard'),
      popDisplays: ['fgPop', 'locPop', 'pmPop'].map(i => getComputedStyle(document.getElementById(i)).display),
      domKept: ['lab2Btn', 'toolsBtn', 'cpuV', 'memV'].every(i => !!document.getElementById(i)),
    };
  });
  chk('「二级分析台 / 音频工具集」入口不可达', !dead.lab2Btn && !dead.toolsBtn,
    'lab2Btn可见=' + dead.lab2Btn + ' toolsBtn可见=' + dead.toolsBtn);
  chk('「语音转写 / 环境采集」面板不可达', !dead.asrMask && !dead.envMask,
    'asrMask可见=' + dead.asrMask + ' envMask可见=' + dead.envMask);
  chk('「智能分类」卡片已隐藏', dead.smartCard === true, 'display=' + dead.smartCard);
  chk('前景/背景分离 未点击时主界面不可见', dead.fgW === 0, 'fgBars 宽=' + dead.fgW + 'px');
  chk('声源定位 未点击时主界面不可见', dead.locW === 0, 'locCv 宽=' + dead.locW + 'px');
  chk('声波参数 未点击时主界面不可见', dead.pmW === 0, 'pmCard 宽=' + dead.pmW + 'px');
  chk('三个功能层默认均未显示', dead.popDisplays.every(d => d === 'none'), dead.popDisplays.join('/'));
  chk('下线功能的 DOM 仍保留（防 JS 取空报错）', dead.domKept, 'lab2Btn/toolsBtn/cpuV/memV 均在');

  console.log('\n[4] 中文软件名用上内嵌艺术字体（像素判据）');
  const font = await page.evaluate(async () => {
    await document.fonts.ready;
    const h1 = document.querySelector('.brand h1');
    const fam = getComputedStyle(h1).fontFamily.split(',')[0].replace(/["']/g, '').trim();
    let loaded = false; document.fonts.forEach(f => { if (f.family.replace(/["']/g, '') === fam && f.status === 'loaded') loaded = true; });
    const draw = (family) => { const c = document.createElement('canvas'); c.width = 300; c.height = 60; const g = c.getContext('2d');
      g.fillStyle = '#fff'; g.font = '900 34px ' + family; g.textBaseline = 'top'; g.fillText(h1.textContent, 4, 6); return g.getImageData(0, 0, 300, 60).data; };
    const px = (a, b) => { let n = 0; for (let i = 0; i < a.length; i += 4) if (a[i] !== b[i]) n++; return n; };
    return { fam, loaded, diff: px(draw("'" + fam + "'"), draw("'__NoSuchFont_X__','PingFang SC'")),
      text: h1.textContent, sub: document.querySelector('.brand .sub.sonic').textContent.trim(),
      author: document.querySelector('.brand .author').textContent.trim(),
      logoW: Math.round(document.querySelector('.brand .logo').getBoundingClientRect().width),
      fetched: await fetch('assets/fonts/harmony-display.woff2').then(r => r.ok).catch(() => false) };
  });
  chk('字体文件可读取（打包后路径正确）', font.fetched, 'assets/fonts/harmony-display.woff2');
  chk('中文标题使用内嵌艺术字体且非静默回退', /HarmonyDisplay/.test(font.fam) && font.loaded && font.diff > 200,
    'family=' + font.fam + ' loaded=' + font.loaded + ' 像素差=' + font.diff + ' 文本=' + font.text);
  /* 副标题里的 S 被拆成 <b class="hot2"> 单独上荧光黄，textContent 不含空格；
     用去空白后比较，且同时校验 hot2 存在（S 确实是荧光黄而不是普通字）。 */
  chk('英文副标题为 AlphaSun Sonic Analyzer', /Alpha\s*Sun\s*Sonic\s*Analyzer/i.test(nbsp(font.sub)),
    font.sub + '（hot2=' + (await page.evaluate(() => !!document.querySelector('.brand .sub.sonic .hot2'))) + '）');
  chk('作者行改为「作者：阳光 net2net2net（ VX: net2net ）」',
    /作者：阳光\s*net2net2net（\s*VX:\s*net2net\s*）/.test(font.author), font.author);
  chk('品牌徽标已绘制（SVG logo 有尺寸）', font.logoW >= 32, 'logo 宽=' + font.logoW + 'px');

  console.log('\n[5] 顶栏三行顺序：品牌 → 参数行 → 切换/设备/灵敏度 → DSP 链 → 可视化');
  const order = await page.evaluate(() => {
    const R = s => { const e = document.querySelector(s); if (!e) return null; const b = e.getBoundingClientRect(); return { y: Math.round(b.y), bottom: Math.round(b.bottom), h: Math.round(b.height) }; };
    return { brand: R('.brand'), rowParams: R('#rowParams'), rowCtl: R('#rowCtl'), plBox: R('#plBox'),
      viz: R('.viz'), db: R('.ov.db'), clock: R('.ov.clock') };
  });
  const seq = [order.brand, order.rowParams, order.rowCtl, order.plBox, order.viz];
  let seqOk = true; for (let i = 1; i < seq.length; i++) if (!seq[i - 1] || !seq[i] || seq[i - 1].bottom > seq[i].y + 2) seqOk = false;
  chk('品牌 → 参数行 → 控制行 → DSP 链 → 可视化 依次向下排列', seqOk,
    seq.map(s => (s ? s.y + '~' + s.bottom : 'null')).join(' | '));
  chk('电平表与毫秒时间位于可视化区内上部',
    order.db && order.clock && order.db.y >= order.viz.y - 2 && order.db.y < order.viz.bottom && order.clock.y < order.viz.bottom,
    'viz.y=' + order.viz.y + ' db.y=' + order.db.y + ' clock.y=' + order.clock.y);

  console.log('\n[6] 底部主操作条（五连 / 顺序 / 贴底 / 不重叠 / 可点）');
  const bar = await page.evaluate(() => {
    const R = id => { const e = document.getElementById(id); const b = e.getBoundingClientRect(); return { x: Math.round(b.x), y: Math.round(b.y), w: Math.round(b.width), h: Math.round(b.height), r: Math.round(b.right), cx: Math.round(b.x + b.width / 2), cy: Math.round(b.y + b.height / 2), txt: e.textContent.replace(/\s+/g, '') }; };
    const ov = (a, b) => !(a.r <= b.x + 1 || b.r <= a.x + 1 || (a.y + a.h) <= b.y + 1 || (b.y + b.h) <= a.y + 1);
    const ids = ['capBtn', 'mParamBtn', 'mFgBtn', 'mLocBtn', 'mGuardBtn', 'mNoiseBtn'];
    const bs = ids.map(R), mb = R('mbar');
    const hit = (p, id) => { const el = document.elementFromPoint(p.cx, p.cy); const t = document.getElementById(id); return !!el && (el === t || t.contains(el)); };
    let overlap = false;
    for (let i = 0; i < bs.length; i++) for (let j = i + 1; j < bs.length; j++) if (ov(bs[i], bs[j])) overlap = true;
    let xAsc = true; for (let i = 1; i < bs.length; i++) if (bs[i].x < bs[i - 1].x) xAsc = false;
    return { bs, ids, mb, vh: innerHeight, vw: innerWidth, overlap, xAsc,
      sameRow: bs.every(b => Math.abs(b.y - bs[0].y) <= 2),
      hits: bs.map((b, i) => hit(b, ids[i])),
      minH: Math.min(...bs.map(b => b.h)), capFullWidth: bs[0].w >= innerWidth - 6 };
  });
  chk('六按钮同排', bar.sameRow && bar.bs.length === 6, 'y=' + bar.bs.map(b => b.y).join('/'));
  chk('横向顺序 = 开始采集 · 声波参数 · 前景/背景分离 · 声源定位 · 声波警戒 · 噪音评估', bar.xAsc,
    bar.bs.map(b => b.txt).join(' → '));
  chk('主操作不再占满整行（观感"变小"的关键）', !bar.capFullWidth, '开始采集宽=' + bar.bs[0].w + 'px / 视口 ' + bar.vw + 'px');
  chk('按钮高度 ≥ 44px 触控下限', bar.minH >= 44, 'min-h=' + bar.minH + 'px（各=' + bar.bs.map(b => b.h).join('/') + '）');
  chk('六按钮两两不重叠', !bar.overlap);
  /* v0.05 需求②：按钮条不再贴底 —— 底部让给「版本/作者/说明」信息行，
     按钮条改为**浮在信息行之上**（bottom = 信息行高 + 安全区）。
     判据随之从「贴底」改为「贴信息行上沿 + 仍固定可见」。 */
  chk('按钮条浮于信息行之上（不再贴底，但仍固定）', bar.mb.y + bar.mb.h <= bar.vh - 8 && bar.mb.y > bar.vh * 0.6,
    'mbar 底=' + (bar.mb.y + bar.mb.h) + ' 视口高=' + bar.vh + '（下方留信息行）');
  chk('六按钮中心点均未被遮挡（真实可达）', bar.hits.every(Boolean),
    bar.ids.map((id, i) => id + '=' + bar.hits[i]).join(' '));

  console.log('\n[7] 底部为两行固定坞：上=按钮条，下=版本/作者/说明');
  const foot = await page.evaluate(() => {
    const f = document.querySelector('footer'), h = document.getElementById('helpBtn'), mb = document.getElementById('mbar');
    const fb = f.getBoundingClientRect(), hb = h.getBoundingClientRect(), mb2 = mb.getBoundingClientRect();
    const el = document.elementFromPoint(Math.round(hb.x + hb.width / 2), Math.round(hb.y + hb.height / 2));
    return { footerDisp: getComputedStyle(f).display, helpDisp: getComputedStyle(h).display,
      footerBottom: Math.round(fb.bottom), footerTop: Math.round(fb.top), vh: innerHeight,
      mbarTop: Math.round(mb2.top), mbarBottom: Math.round(mb2.bottom),
      helpHit: !!el && (el === h || h.contains(el)),
      helpY: Math.round(hb.y), helpBottom: Math.round(hb.bottom), helpTxt: h.textContent.trim(),
      inFooter: f.contains(h),
      /* v0.05 需求② 的核心判据：信息行在**按钮条下方**且**贴视口底**。
         改前 footer 在按钮条之上（要靠 62px 下内边距躲开），改后按钮条 bottom 抬高、
         footer 落到最底，两行不再互相遮挡 → 判据从"不相交"改为"下上关系"。 */
      belowMbar: fb.top >= mb2.bottom - 1,
      atBottom: fb.bottom >= innerHeight - 2 };
  });
  chk('页脚在移动端可见', foot.footerDisp !== 'none', 'display=' + foot.footerDisp);
  chk('「说明」按钮位于页脚内（软件底部）', foot.inFooter && foot.helpDisp !== 'none', 'text=' + foot.helpTxt);
  chk('「说明」按钮位于视口下半部（确在底部）', foot.helpY > foot.vh * 0.6, 'y=' + foot.helpY + ' / 视口高 ' + foot.vh);
  chk('信息行在按钮条**下方**且贴视口底（两行固定坞）', foot.belowMbar && foot.atBottom,
    'footer.top=' + foot.footerTop + ' mbar.bottom=' + foot.mbarBottom + ' footer.bottom=' + foot.footerBottom + ' 视口高=' + foot.vh);
  chk('「说明」真实可点（命中自身）', foot.helpHit, '命中=' + foot.helpHit);

  console.log('\n[8] 输入设备 / 灵敏度 位于顶栏第 2 行');
  const ctl = await page.evaluate(() => {
    const row = document.getElementById('rowCtl');
    const sw = document.getElementById('vizSwitch'), mic = document.getElementById('micSel'), se = document.getElementById('sens');
    const inRow = row.contains(sw) && row.contains(mic) && row.contains(se);
    const rr = row.getBoundingClientRect();
    const inside = e => { const b = e.getBoundingClientRect(); return b.width > 0 && b.y >= rr.y - 2 && b.bottom <= rr.bottom + 2; };
    return { inRow, optN: mic.options.length,
      micW: Math.round(mic.getBoundingClientRect().width), sensW: Math.round(se.getBoundingClientRect().width),
      swW: Math.round(sw.getBoundingClientRect().width),
      micIn: inside(mic), sensIn: inside(se), swIn: inside(sw),
      rowY: Math.round(rr.y), rowH: Math.round(rr.height) };
  });
  chk('切换 / 输入设备 / 灵敏度 三者同属第 2 行', ctl.inRow, 'rowCtl y=' + ctl.rowY + ' h=' + ctl.rowH);
  chk('输入设备下拉已渲染出可用宽度', ctl.micIn && ctl.micW > 60 && ctl.optN >= 1, '宽=' + ctl.micW + 'px 设备项=' + ctl.optN);
  chk('灵敏度滑杆已渲染出可用宽度', ctl.sensIn && ctl.sensW > 60, '宽=' + ctl.sensW + 'px');
  chk('可视化切换条已渲染出可用宽度', ctl.swIn && ctl.swW > 60, '宽=' + ctl.swW + 'px');

  console.log('\n[9] 三个功能按钮 → 弹层 可开可关');
  const popTest = async (btnId, popId, innerId, label) => {
    await page.click('#' + btnId);
    await page.waitForTimeout(500);
    const o = await page.evaluate(([p, i]) => {
      const pop = document.getElementById(p), inner = document.getElementById(i);
      const pb = pop.getBoundingClientRect(), ib = inner.getBoundingClientRect();
      const closeBtn = document.getElementById(p.replace('Pop', 'PopClose'));
      const cb = closeBtn.getBoundingClientRect();
      const el = document.elementFromPoint(Math.round(cb.x + cb.width / 2), Math.round(cb.y + cb.height / 2));
      return { disp: getComputedStyle(pop).display, popW: Math.round(pb.width), popH: Math.round(pb.height),
        innerW: Math.round(ib.width), innerH: Math.round(ib.height),
        innerVisible: ib.width > 0 && ib.height > 0,
        closeHit: !!el && (el === closeBtn || closeBtn.contains(el)),
        btnOn: document.getElementById(p.replace('Pop', 'Btn')) ? true : false,
        vw: innerWidth, vh: innerHeight };
    }, [popId, innerId]);
    chk(label + '：点击按钮可打开功能层', o.disp === 'flex' && o.popW >= o.vw - 2 && o.innerVisible,
      'display=' + o.disp + ' 弹层 ' + o.popW + '×' + o.popH + ' 内容 ' + o.innerW + '×' + o.innerH);
    chk(label + '：关闭按钮真实可点', o.closeHit);
    await page.click('#' + popId.replace('Pop', 'PopClose'));
    await page.waitForTimeout(400);
    const c = await page.evaluate(p => getComputedStyle(document.getElementById(p)).display, popId);
    chk(label + '：点击关闭可收回功能层', c === 'none', 'display=' + c);
  };
  await popTest('mParamBtn', 'pmPop', 'pmCard', '声波参数');
  await popTest('mFgBtn', 'fgPop', 'fgBars', '前景/背景分离');
  // 需求②：定位必须有**操作指示**（实时状态 + 步骤条），而不是名词列表。
  // 先开阵列仿真并放置一个声源，拿到**真实解算结果**再断言 ——
  // 单声道无解算时状态卡本就显示"待解算"，那样断言测不到真实路径。
  // 采集必须开着，否则定位分析循环不跑、状态卡不会刷新
  const locCap = await page.evaluate(() => !document.getElementById('capBtn').classList.contains('paused'));
  if (!locCap) { await page.click('#capBtn').catch(() => { }); await page.waitForTimeout(2500); }
  await page.click('#mLocBtn'); await page.waitForTimeout(600);
  await page.click('#locSimBtn').catch(() => { });
  await page.waitForTimeout(600);
  // GC 在闭包内，页面上下文取不到；改为直接点雷达图放置声源（等效且走真实交互路径）
  const cvBox = await page.evaluate(() => { const c = document.getElementById('locCv').getBoundingClientRect();
    return { x: Math.round(c.x + c.width * 0.58), y: Math.round(c.y + c.height * 0.34) }; });
  await page.mouse.click(cvBox.x, cvBox.y);
  await page.waitForTimeout(2500);
  const loc = await page.evaluate(() => {
    const T = id => { const e = document.getElementById(id); return e ? e.textContent.trim() : ''; };
    const steps = [...document.querySelectorAll('.lstep')];
    return {
      hasStat: !!document.getElementById('locStat'),
      ch: T('locStCh'), ap: T('locStAp'), pk: T('locStPk'),
      rs: T('locStRs'), pd: T('locStPd'), cf: T('locStCf'),
      stepN: steps.length,
      stepMarked: steps.filter(s => s.classList.contains('ok') || s.classList.contains('warn')).length,
      // 每一步都要有可执行的说明（p 文本非空且长度足够）
      // 阈值 15：意图是「必须有可执行说明」而非「字数够多」；
      // 实测第 2 步达标文案仅 19 字（「孔径 1.0 m 合适，角分辨率良好。」），卡 20 会误报。
      stepTextOk: steps.every(s => { const p = s.querySelector('p'); return p && p.textContent.trim().length >= 15; }),
      stepLens: steps.map(s => { const p = s.querySelector('p'); return p ? p.textContent.trim().length : -1; }),
    };
  });
  chk('定位有实时状态卡（通道/孔径/相关峰/残差/时差对/置信度）',
    loc.hasStat && loc.ch !== '' && loc.ap !== '' && loc.cf !== '',
    '通道=' + loc.ch + ' 孔径=' + loc.ap + ' 置信度=' + loc.cf);
  chk('置信度为可量化数值（仿真解算后为百分比）', /%/.test(loc.cf), loc.cf);
  chk('操作指示共 5 步且已按状态自动标记', loc.stepN === 5 && loc.stepMarked === 5,
    loc.stepMarked + '/' + loc.stepN + ' 步已标记（通道=' + loc.ch + ' 孔径=' + loc.ap + '）');
  chk('每一步都有具体操作说明（可执行指引）', loc.stepTextOk, '各步字数=' + JSON.stringify(loc.stepLens));
  await page.click('#locPopClose'); await page.waitForTimeout(400);
  await popTest('mLocBtn', 'locPop', 'locCv', '声源定位');

  // 移动端适配层必须就位（用户明确要求程序要在安卓/苹果上正确运行）
  const mob = await page.evaluate(() => {
    const has = !!document.querySelector('script[src*="mobile/compat.js"]');
    const p = (typeof window.__asMobileProbe === 'function') ? window.__asMobileProbe() : null;
    return { has, p };
  });
  chk('移动端适配层已加载（mobile/compat.js）', mob.has, mob.has ? '已引用' : '未引用');
  chk('移动端平台自检可用（能报告平台/权限/音频解锁）',
    !!(mob.p && mob.p.platform && typeof mob.p.permMic === 'string'),
    mob.p ? ('平台=' + mob.p.platform + ' 麦克风权限=' + mob.p.permMic + ' 音频解锁=' + mob.p.audioUnlocked) : '无自检');
  await page.click('#mFgBtn'); await page.waitForTimeout(700);

  // 需求④：独立分离试听必须有**真实可听的声音**。
  // 这条断言守着两个曾让"试听没声音"的根因：
  //   ① 离线渲染漏了 src.start()（BufferSource 不会自动播放）
  //   ② 门限写成 10^(-20/20) —— JS 的 ^ 是按位异或，不是幂运算
  console.log('\n[9.55] 分离试听：录制 → 离线分离 → 真实音频能量');
  const capRun = await page.evaluate(() => !document.getElementById('capBtn').classList.contains('paused'));
  if (!capRun) { await page.click('#capBtn').catch(() => { }); await page.waitForTimeout(3000); }
  // ⚠ 必须先确保分离层是**关闭**状态：直接点 #mFgBtn 在层已打开时会命中被遮住的
  //   底栏按钮（Playwright 报 audio 拦截点击）。用状态判断而不是无条件点。
  const fgOpen = await page.evaluate(() => document.getElementById('fgPop').classList.contains('on'));
  if (!fgOpen) { await page.click('#mFgBtn'); await page.waitForTimeout(700); }
  await page.click('#fgLearnBtn'); await page.waitForTimeout(4000);
  await page.click('#fgRecBtn'); await page.waitForTimeout(11000);
  const sep = await page.evaluate(() => {
    const T = id => { const e = document.getElementById(id); return e ? e.textContent.trim() : ''; };
    /* 解码 audio 元素当前加载的 blob，量真实能量（不看 UI 文本，避免自证） */
    const meas = async () => {
      const a = document.getElementById('fgAudio');
      if (!a.src.startsWith('blob:')) return null;
      const v = new DataView(await (await fetch(a.src)).arrayBuffer());
      let off = 12, d = null;
      while (off < v.byteLength - 8) {
        const id = String.fromCharCode(v.getUint8(off), v.getUint8(off + 1), v.getUint8(off + 2), v.getUint8(off + 3));
        const sz = v.getUint32(off + 4, true);
        if (id === 'data') { d = v; off += 8; break; }
        off += 8 + sz + (sz % 2);
      }
      if (!d) return null;
      const n = (d.byteLength - off) / 2;
      let pk = 0, su = 0;
      for (let i = 0; i < n; i++) { const s2 = d.getInt16(off + i * 2, true) / 32768; const A = Math.abs(s2); if (A > pk) pk = A; su += s2 * s2; }
      return { sec: +(n / d.getUint32(24, true)).toFixed(2), peak: +pk.toFixed(4), rms: +Math.sqrt(su / n).toFixed(4) };
    };
    return (async () => {
      const out = { stat: T('fgStatDbV'), gate: T('fgStatGateV'), gain: T('fgStatGainV'),
        btnEnabled: !document.getElementById('fgPlayBtn').disabled };
      document.getElementById('fgPlayBtn').click();
      await new Promise(r => setTimeout(r, 1400));
      const a = document.getElementById('fgAudio');
      out.playing = !a.paused && a.currentTime > 0.3;
      out.curTime = +a.currentTime.toFixed(2);
      out.dur = +(a.duration || 0).toFixed(2);
      out.fg = await meas();
      return out;
    })();
  });
  chk('分离完成并给出可核查指标（电平/门限/增益）',
    /\d/.test(sep.stat) && /\d/.test(sep.gate) && /\d/.test(sep.gain) && sep.btnEnabled,
    '电平=' + sep.stat + ' 门限=' + sep.gate + ' 增益=' + sep.gain);
  chk('前景音频真实有能量（rms > 0.02，非静音）',
    !!(sep.fg && sep.fg.rms > 0.02), '前景 ' + JSON.stringify(sep.fg));
  chk('前景时长与录制时长一致（6 秒）', !!(sep.fg && Math.abs(sep.fg.sec - 6) < 1.2), '实测 ' + (sep.fg && sep.fg.sec) + 's');
  chk('点击试听后音频真实在播放（进度推进）', sep.playing, 'currentTime=' + sep.curTime + 's / 时长 ' + sep.dur + 's');
  // v0.08 需求②：必须**真的用上学习到的背景模型**，并给出质量分析
  const q = await page.evaluate(() => {
    const T = id => { const e = document.getElementById(id); return e ? e.textContent.trim() : ''; };
    const cv = document.getElementById('fgProfileCv');
    let ink = 0;
    if (cv && cv.width) { const d = cv.getContext('2d').getImageData(0, 0, cv.width, cv.height).data;
      for (let i = 3; i < d.length; i += 40) if (d[i] > 20) ink++; }
    return { model: T('fgAnModel'), supp: T('fgAnSupp'), resid: T('fgAnResid'),
      hear: T('fgAnHear'), verdict: T('fgAnVerdict'),
      feat: T('fgProfFeat'), flat: T('fgProfFlat'), judge: T('fgProfJudge'), profInk: ink };
  });
  chk('学习背景的效果已可视化（背景噪声谱已绘制）', q.profInk > 50,
    '着色点=' + q.profInk + ' 特征=' + q.feat + ' 平坦度=' + q.flat);
  chk('背景模型可用性已给出判读结论', q.judge && q.judge !== '待学习' && q.judge !== '—', q.judge);
  chk('分离确实用上了学习到的背景模型（不是退化为等量假设）',
    /已用学习到的背景模型/.test(q.model), q.model);
  chk('背景能量抑制率为有效数值（非 0.0% / 非占位符）',
    /%/.test(q.supp) && !/^0\.0 %/.test(q.supp), q.supp);
  chk('残留背景比与前景可听性已量化', /%/.test(q.resid) && /RMS/.test(q.hear),
    q.resid + ' / ' + q.hear);
  chk('综合评价给出可执行结论', q.verdict && q.verdict !== '—', q.verdict);
  await page.click('#fgPopClose'); await page.waitForTimeout(400);

  // 参数详情弹层必须浮在功能层**之上** —— 这是 v0.04 实测到的真缺陷：
  // .pmask 基础层级只有 60，而 .fnpop 是 960，点参数行像"没反应"。
  console.log('\n[9.5] 弹层内二级弹窗层级（参数详情须最前）');
  await page.click('#mParamBtn');
  await page.waitForTimeout(500);
  const rowHit = await page.evaluate(() => {
    const r = document.querySelector('#pmCard .row.clk');
    if (!r) return null;
    const b = r.getBoundingClientRect();
    const el = document.elementFromPoint(Math.round(b.x + 40), Math.round(b.y + b.height / 2));
    return { topIsRow: !!(el && (r === el || r.contains(el))), y: Math.round(b.y) };
  });
  chk('功能层内参数行可命中（未被其它层遮挡）', !!(rowHit && rowHit.topIsRow), rowHit ? 'y=' + rowHit.y : '未找到参数行');
  await page.click('#pmCard .row.clk').catch(() => { });
  await page.waitForTimeout(600);
  const zTest = await page.evaluate(() => {
    const pm = document.getElementById('pmask');
    const fn = document.getElementById('pmPop');
    const z = pm ? parseInt(getComputedStyle(pm).zIndex, 10) : -1;
    const fz = fn ? parseInt(getComputedStyle(fn).zIndex, 10) : -1;
    const on = pm && getComputedStyle(pm).display !== 'none';
    /* 层级根因（v0.04 排查四轮才定位）：z-index **跨层叠上下文不可比**。
       #pmask 原在 #appRoot 内（position:relative + z-index:1，自建上下文），
       .fnpop 是 body 直属 —— 两者分属不同体系，数字大小毫无意义。
       正解是把 #pmask 的 DOM 移出 .app 改为 body 直属（已实施）。
       故断言不看数字大小，而是**实测命中** + **祖先归属**。 */
    const inApp = pm ? !!pm.closest('#appRoot') : true;
    let hit = false, probe = null, topmost = '', root = '';
    if (on) {
      const t = pm.querySelector('#pCanvas') || pm.querySelector('.pcard') || pm;
      const b = t.getBoundingClientRect();
      const px = Math.round(b.x + b.width / 2), py = Math.round(b.y + b.height / 2);
      const el = document.elementFromPoint(px, py);
      probe = { px, py };
      topmost = el ? ((el.id ? '#' + el.id : '') + el.tagName) : '(null)';
      let n = el;
      while (n && n !== document.body) {
        if (n.classList && n.classList.contains('pmask')) { root = n.id; break; }
        n = n.parentElement;
      }
      hit = root === 'pmask';
    }
    return { z, fz, on, hit, probe, topmost, root, inApp,
      bodyCls: document.body.classList.contains('fnpop-open') };
  });
  chk('参数详情弹层已打开', zTest.on, 'display=' + (zTest.on ? 'flex' : 'none'));
  chk('详情弹层 DOM 已移出 .app（与功能层同属 body 体系，否则层级不可比）', !zTest.inApp,
    '仍在 #appRoot 内=' + zTest.inApp);
  chk('详情弹窗位于最顶层（画布中心命中自身 mask）', zTest.hit,
    '命中=' + zTest.hit + ' 顶层=' + zTest.topmost + ' 所属mask=#' + zTest.root + ' 探针=' + JSON.stringify(zTest.probe));
  // Esc 应"只关最上层"：先 Esc 关详情弹窗（功能层须保持打开），再 Esc 才关功能层
  await page.keyboard.press('Escape');
  await page.waitForTimeout(500);
  const esc1 = await page.evaluate(() => ({
    pm: getComputedStyle(document.getElementById('pmask')).display,
    fn: getComputedStyle(document.getElementById('pmPop')).display,
  }));
  chk('Esc 优先关详情弹层，功能层保持打开（只关最上层）',
    esc1.pm === 'none' && esc1.fn === 'flex', 'pmask=' + esc1.pm + ' fnpop=' + esc1.fn);
  await page.keyboard.press('Escape');
  await page.waitForTimeout(500);
  const cleaned = await page.evaluate(() => ({
    fn: getComputedStyle(document.getElementById('pmPop')).display,
    cls: document.body.classList.contains('fnpop-open'),
  }));
  chk('再按 Esc 才关功能层，且 body.fnpop-open 已摘除', cleaned.fn === 'none' && !cleaned.cls,
    'fnpop=' + cleaned.fn + ' bodyClass=' + cleaned.cls);

  // 参数排版：手机竖屏必须多排紧凑，且每个参数仍可点
  console.log('\n[9.6] 声波参数多排紧凑布局（充分利用屏幕）');
  await page.click('#mParamBtn');
  await page.waitForTimeout(600);
  const pl = await page.evaluate(() => {
    const card = document.getElementById('pmCard');
    const body = document.querySelector('#pmPop .fnpop-body');
    const rows = [...card.querySelectorAll('.row.clk')];
    const col = card.querySelector('.pgcol');
    const cs = getComputedStyle(col);
    // 统计"行"的分布：把每个 tile 的 top 归一化分桶，看有几排
    const tops = [...new Set(rows.map(r => Math.round(r.getBoundingClientRect().top)))].sort((a, b) => a - b);
    const first = rows[0] ? rows[0].getBoundingClientRect() : null;
    const minH = rows.length ? Math.min(...rows.map(r => Math.round(r.getBoundingClientRect().height))) : 0;
    // 抽样验证可点性：取第 1、5、12 个参数的左中点
    const hits = [0, 4, 11].map(i => {
      const r = rows[i]; if (!r) return false;
      const b = r.getBoundingClientRect();
      const el = document.elementFromPoint(Math.round(b.x + b.width / 2), Math.round(b.y + b.height / 2));
      return !!el && (el === r || r.contains(el));
    });
    return { n: rows.length, cols: cs.gridTemplateColumns.split(' ').filter(Boolean).length,
      display: cs.display, rowCount: tops.length, contentH: Math.round(card.getBoundingClientRect().height),
      bodyH: body ? Math.round(body.getBoundingClientRect().height) : 0, vh: innerHeight,
      firstW: first ? Math.round(first.width) : 0, minH, hitsAll: hits.every(Boolean) };
  });
  chk('参数以多排网格展示（非单列长列表）', pl.cols >= 2,
    '列数=' + pl.cols + ' 排数=' + pl.rowCount + ' display=' + pl.display);
  chk('参数条目齐全（23 项）', pl.n >= 23, pl.n + ' 项');
  chk('参数区高度显著缩短（≤2 屏）', pl.contentH <= pl.vh * 2,
    '内容高=' + pl.contentH + 'px / 视口高 ' + pl.vh + 'px（改前 1816px）');
  chk('tile 仍达触控下限（≥44px）', pl.minH >= 44, '最矮 tile=' + pl.minH + 'px');
  chk('多排后参数仍可点（抽样 3 个命中自身）', pl.hitsAll, 'tile 宽=' + pl.firstW + 'px');
  await page.click('#pmPopClose');
  await page.waitForTimeout(400);

  // 需求⑥：噪音评估
  // ⚠ 必须先开始采集：nzTick 由主分析循环驱动，未采集时 rms=0 → 无任何读数。
  //   之前的版本把本段放在"开始采集"之前，8 条断言全红，是测试顺序问题而非功能缺陷。
  const nzWasRunning = await page.evaluate(() => !document.getElementById('capBtn').classList.contains('paused'));
  if (!nzWasRunning) { await page.click('#capBtn').catch(() => { }); await page.waitForTimeout(3000); }
  console.log('\n[9.7] 噪音评估（专业分贝表 / 柱状电平 / 曲线 / 识别 / 报告）');
  await page.click('#mNoiseBtn');
  await page.waitForTimeout(2500);
  const nz = await page.evaluate(() => {
    const T = id => { const e = document.getElementById(id); return e ? e.textContent.trim() : '(缺)'; };
    const cv = document.getElementById('nzCv');
    const ink = c => { if (!c || !c.width) return 0;
      const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data;
      let n = 0; for (let i = 3; i < d.length; i += 40) if (d[i] > 20) n++; return n; };
    return {
      popW: Math.round(document.getElementById('noisePop').getBoundingClientRect().width),
      db: T('nzDbV'), lv: T('nzLvV'), type: T('nzTypeV'), band: T('nzBandV'),
      cent: T('nzCentV'), stat: T('nzStatV'),
      bars: document.querySelectorAll('#nzBars .nb').length,
      onBars: document.querySelectorAll('#nzBars .nb.on').length,
      bandRows: document.querySelectorAll('#nzBands .nbr').length,
      tabs: document.querySelectorAll('#nzTabs button[data-w]').length,
      meterInk: ink(cv), histInk: ink(document.getElementById('nzHistCv')),
    };
  });
  chk('噪音评估层可打开且满屏', nz.popW >= bar.vw - 2, '宽=' + nz.popW);
  chk('实时分贝值已刷新（非占位符）', nz.db !== '--' && nz.db !== '—' && /[0-9]/.test(nz.db), '当前=' + nz.db + ' dB');
  chk('噪声级别有判定文案', nz.lv && nz.lv !== '未测量' && nz.lv !== '—', nz.lv);
  chk('柱状电平随 dB 点亮', nz.bars >= 20 && nz.onBars > 0, '总格=' + nz.bars + ' 点亮=' + nz.onBars);
  chk('三频段能量条已渲染', nz.bandRows === 3, nz.bandRows + ' 行');
  chk('噪音类型与频段已给出', nz.type !== '—' && nz.band !== '—', nz.type + ' / ' + nz.band);
  chk('频谱质心已计算', /Hz/.test(nz.cent), nz.cent);
  chk('曲线统计非空', nz.stat !== '—' && nz.stat.length > 6, nz.stat);
  chk('时间窗 1/5/10/15/30 分钟齐备', nz.tabs === 5, nz.tabs + ' 个');
  chk('分贝表盘已渲染（非空白）', nz.meterInk > 50, '着色点=' + nz.meterInk);
  chk('历史曲线已渲染（非空白）', nz.histInk > 50, '着色点=' + nz.histInk);
  await page.click('#nzTabs button[data-w="1800"]');
  await page.waitForTimeout(500);
  const winNow = await page.evaluate(() => document.querySelector('#nzTabs button.on').dataset.w);
  chk('可切换到 30 分钟窗口', winNow === '1800', '当前=' + winNow + 's');
  await page.click('#noisePopClose');
  await page.waitForTimeout(500);

  console.log('\n[10] 可视化：7 种模式 / 形状族 / 名称同步 / 中央环暂停');
  // ⚠ 只在**未采集**时才点开始 —— [9.7] 噪音评估需要真实数据，已提前启动采集。
  //   原写法无条件 page.click('#capBtn')，会把正在运行的采集**停掉**，
  //   导致后面"点击中央环可暂停"的 before 态为 false（实测 2 条红）。
  const capNow = await page.evaluate(() => !document.getElementById('capBtn').classList.contains('paused'));
  if (!capNow) { await page.click('#capBtn').catch(() => { }); }
  await page.waitForTimeout(2600);
  const viz = await page.evaluate(async () => {
    const name = () => document.getElementById('vizNameM').textContent.trim();
    const shape = () => document.querySelector('.viz').getAttribute('data-shape');
    const seen = [], shapes = {};
    // 连点压力：120ms 间隔（远快于原 350ms 时间窗去重）——这是本门禁抓到过的真实缺陷
    for (let i = 0; i < 7; i++) { seen.push(name()); shapes[name()] = shape(); document.getElementById('vizNextM').click(); await new Promise(r => setTimeout(r, 120)); }
    return { seen, shapes, colored: (() => { const cv = document.getElementById('cv'); const g = cv.getContext('2d');
        try { const d = g.getImageData(0, 0, cv.width, cv.height).data; let n = 0;
          for (let i = 0; i < d.length; i += 40) if (d[i] > 24 || d[i + 1] > 24 || d[i + 2] > 24) n++;
          return Math.round(n / (d.length / 40) * 100); } catch (_) { return -1; } })() };
  });
  const uniq = [...new Set(viz.seen)];
  chk('快速连点 7 次不丢事件（原 350ms 时间窗去重会吞掉点击）', uniq.length === 7, '序列=' + viz.seen.join(' → '));
  chk('形状族标注正确（环谱/极坐标=round，其余=linear）',
    viz.shapes['经典环谱'] === 'round' && viz.shapes['极坐标'] === 'round' && viz.shapes['频谱柱'] === 'linear' && viz.shapes['瀑布图'] === 'linear',
    JSON.stringify(viz.shapes));
  chk('画布有真实着色像素（非空白）', viz.colored > 20, '着色占比≈' + viz.colored + '%');

  const real = [];
  for (let i = 0; i < 7; i++) {
    real.push(await page.evaluate(() => document.getElementById('vizNameM').textContent.trim()));
    await page.click('#vizNextM');
    await page.waitForTimeout(150);
  }
  chk('◀/▶ 真实点击可逐个切换（命中链路正常）', [...new Set(real)].length === 7, '序列=' + real.join(' → '));
  for (let i = 0; i < 7; i++) {
    const n = await page.evaluate(() => document.getElementById('vizNameM').textContent.trim());
    if (n === '经典环谱') break;
    await page.click('#vizNextM'); await page.waitForTimeout(150);
  }
  const back2 = await page.evaluate(() => document.getElementById('vizNameM').textContent.trim());
  chk('可切回默认「经典环谱」', back2 === '经典环谱', '当前=' + back2);

  const center = await page.evaluate(() => {
    const b = document.getElementById('cv').getBoundingClientRect();
    return { x: Math.round(b.x + b.width / 2), y: Math.round(b.y + b.height / 2) };
  });
  const modeNow = await page.evaluate(() => document.querySelector('.viz').getAttribute('data-shape'));
  if (modeNow !== 'round') {
    for (let i = 0; i < 7; i++) {
      await page.click('#vizNextM'); await page.waitForTimeout(160);
      const s = await page.evaluate(() => document.querySelector('.viz').getAttribute('data-shape'));
      if (s === 'round') break;
    }
  }
  const before = await page.evaluate(() => !document.getElementById('capBtn').classList.contains('paused'));
  await page.mouse.click(center.x, center.y);
  await page.waitForTimeout(800);
  const mid = await page.evaluate(() => !document.getElementById('capBtn').classList.contains('paused'));
  await page.mouse.click(center.x, center.y);
  await page.waitForTimeout(800);
  const after = await page.evaluate(() => !document.getElementById('capBtn').classList.contains('paused'));
  const modeUsed = await page.evaluate(() => document.getElementById('vizNameM').textContent.trim());
  chk('点击中央环可暂停', before && !mid, '模式=' + modeUsed + ' 采集态 ' + before + ' → ' + mid);
  chk('再次点击中央环可继续', !mid && after, mid + ' → ' + after);

  console.log('\n[11] 三视口布局（无横向溢出 / DSP 横排 / 浮层不重叠）');
  for (const v of VP) {
    await setVp(v);
    const L = await page.evaluate(() => {
      const R = s => { const e = document.querySelector(s); if (!e) return null; const b = e.getBoundingClientRect(); return { x: Math.round(b.x), y: Math.round(b.y), w: Math.round(b.width), h: Math.round(b.height), r: Math.round(b.right), b: Math.round(b.bottom) }; };
      const inter = (a, b) => !!a && !!b && !(a.r <= b.x + 1 || b.r <= a.x + 1 || a.b <= b.y + 1 || b.b <= a.y + 1);
      const pl = document.getElementById('plBox');
      const nodes = [...document.querySelectorAll('.pl-node')];
      const sw = document.getElementById('vizSwitch');
      // 切换条仍在文档流里，不应压住任何 DSP 节点
      const covered = nodes.filter(n => { const b = n.getBoundingClientRect(); const el = document.elementFromPoint(b.x + b.width / 2, b.y + b.height / 2); return el && sw.contains(el) && !n.contains(el); }).length;
      const plNodesSameRow = nodes.length >= 2 && Math.abs(nodes[0].getBoundingClientRect().y - nodes[nodes.length - 1].getBoundingClientRect().y) < nodes[0].getBoundingClientRect().height;
      return {
        overflowX: document.documentElement.scrollWidth > innerWidth + 1,
        plDir: pl ? getComputedStyle(pl).flexDirection : null,
        plNodesSameRow, plBoxInToolbar: pl ? !!pl.closest('.toolbar') : false,
        dbCk: inter(R('.ov.db'), R('.ov.clock')),
        dbVer: inter(R('.ov.db'), R('.ov.ver')),
        plViz: inter(R('#plBox'), R('.viz')),
        rowCtlViz: inter(R('#rowCtl'), R('.viz')),
        coveredNodes: covered,
        viz: R('.viz'), shape: document.querySelector('.viz').getAttribute('data-shape'),
        vh: innerHeight, vw: innerWidth,
        /* ⚠ 基准必须用 .stage 的内容宽度，不能用 innerWidth：
           CDP 的 setDeviceMetricsOverride 只改**布局视口**（app 实测 390），
           而 innerWidth 仍报窗口尺寸（竖屏实测 429），拿它当分母会凭空差 23px
           把"本来就满宽"的画布误判成不满宽。 */
        /* 内容宽 = clientWidth − 左右内边距（clientWidth 是含 padding 的） */
        stageW: (() => { const s = document.querySelector('.stage'), c = getComputedStyle(s);
          return Math.round(s.clientWidth - parseFloat(c.paddingLeft) - parseFloat(c.paddingRight)); })(),
        appW: Math.round(document.getElementById('appRoot').getBoundingClientRect().width),
        mbarTop: Math.round(document.getElementById('mbar').getBoundingClientRect().top),
        mbarBottom: Math.round(document.getElementById('mbar').getBoundingClientRect().bottom),
        footerTop: Math.round(document.querySelector('footer').getBoundingClientRect().top),
        footerBottom: Math.round(document.querySelector('footer').getBoundingClientRect().bottom),
        helpBottom: Math.round(document.getElementById('helpBtn').getBoundingClientRect().bottom),
        footerVisible: getComputedStyle(document.querySelector('footer')).display !== 'none',
        /* 品牌三行文本是否被裁切：用 Range 量**真实渲染宽度**再与元素盒宽比。
           只看 clientWidth 会被 flex 约束掩盖 —— .author 是 nowrap 文本，
           其 min-content 会把 .brand 撑到不收缩，元素盒跟着变宽、看不出溢出。
           （v0.04 实测缺陷：390/360/320px 屏上右边界分别超出工具栏 6/36/76px） */
        brandText: (() => {
          const tb = document.querySelector('.toolbar'), tbR = tb.getBoundingClientRect().right;
          const rg = document.createRange();
          const out = {};
          for (const [k, sel] of [['author', '.brand .author'], ['sub', '.brand .sub.sonic']]) {
            const e = document.querySelector(sel); rg.selectNodeContents(e);
            const real = rg.getBoundingClientRect().width, box = e.getBoundingClientRect();
            out[k] = { trunc: real > box.width + 1, over: Math.round(box.right - tbR) };
          }
          return out;
        })(),
      };
    });
    chk('[' + v.k + '] 无横向溢出', !L.overflowX, '视口=' + L.vw);
    chk('[' + v.k + '] DSP 处理链为横排', L.plDir === 'row' && L.plNodesSameRow, 'flex-direction=' + L.plDir);
    chk('[' + v.k + '] DSP 链已移出可视化区（位于顶栏、不与画面重叠）', L.plBoxInToolbar && !L.plViz && !L.rowCtlViz,
      '在工具栏=' + L.plBoxInToolbar + ' 压画面=' + L.plViz);
    chk('[' + v.k + '] 电平表与时间不重叠', !L.dbCk, 'db右=' + (L.db && L.db.r));
    chk('[' + v.k + '] 电平表与采集状态不重叠', !L.dbVer);
    chk('[' + v.k + '] 切换条未压住任何 DSP 节点', L.coveredNodes === 0, '遮挡节点=' + L.coveredNodes);
    chk('[' + v.k + '] 可视化满宽显示（占满 .stage 内容宽度）', L.viz && L.viz.w >= L.stageW - 4,
      'viz宽=' + (L.viz && L.viz.w) + ' / stage内容宽 ' + L.stageW + '（app宽 ' + L.appW + '）');
    /* 「说明」在页脚里，页脚自带 62px 下内边距让开 fixed 的 .mbar；
       判据取**页脚内容（说明按钮）底边**而非页脚盒子底边 —— 盒子底边本就压在
       mbar 之下（那是留白区），拿它比会把"内容明明没被压住"误判成失败。 */
    chk('[' + v.k + '] 品牌「版本+作者」与英文副标题未被裁切、未溢出工具栏',
      !L.brandText.author.trunc && !L.brandText.sub.trunc && L.brandText.author.over <= 1 && L.brandText.sub.over <= 1,
      '作者行 截断=' + L.brandText.author.trunc + ' 溢出=' + L.brandText.author.over + 'px；副标题 截断=' + L.brandText.sub.trunc + ' 溢出=' + L.brandText.sub.over + 'px');
    /* v0.05 需求②：两行固定坞 —— 信息行在按钮条**下方**并贴视口底。 */
    chk('[' + v.k + '] 信息行在按钮条下方且贴视口底', L.footerVisible && L.footerTop >= L.mbarBottom - 1
      && L.footerBottom >= L.vh - 2,
      'footer.top=' + L.footerTop + ' mbar.bottom=' + L.mbarBottom + ' footer.bottom=' + L.footerBottom + ' 视口高=' + L.vh);
    if (L.shape === 'round') chk('[' + v.k + '] 圆形族纵向空耗已消除（高≈宽）',
      L.viz.h <= L.viz.w + 24, 'canvas ' + L.viz.w + '×' + L.viz.h);
  }

  console.log('\n[12] 交互后零错误');
  chk('全程零 pageerror', perr.length === 0, perr.join(' | ').slice(0, 240));
  const jsErr = cerr.filter(t => !/Failed to load resource|ERR_FILE_NOT_FOUND|net::ERR/.test(t));
  chk('全程零 console.error（JS 类）', jsErr.length === 0, jsErr.join(' | ').slice(0, 240));

  await app.close();
  const pass = results.filter(r => r.ok).length, fail = results.length - pass;
  console.log('\n========================================');
  console.log('移动分支门禁：' + pass + ' 通过 / ' + fail + ' 失败 / 共 ' + results.length);
  if (fail) results.filter(r => !r.ok).forEach(r => console.log('  ✗ ' + r.name));
  console.log('========================================');
  process.exit(fail ? 1 : 0);
})().catch(e => {
  console.error('\n✗ 门禁执行异常：', (e && e.message) || e);
  try { process.exit(3); } catch (_) { }
});
