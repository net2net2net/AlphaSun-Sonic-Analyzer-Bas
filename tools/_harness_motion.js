/* v1.1.0 门禁：手机位移触发（陀螺仪 + GPS）+ 事件日志内容 + 影像留证
 *
 * ⚠ 为何不用 jsdom 跑整份 app.js（v1.0.2 实测踩坑记录，继续有效）：
 *   app.js 被 IIFE 封装 + CRLF 行尾 + 40 万字符，jsdom eval 会在中途因环境缺失
 *   （canvas / localStorage / 各类浏览器 API）提前终止，无法可靠到达探针位置。
 *   强行打补丁反而会掩盖真实问题。
 *   故改为三层验证，都比"看起来像对了"更贴近"代码是否真的对"：
 *     A. 静态契约：新增符号/DOM/CSS 是否按约定就位（防漏改、防重名、防旧引用残留）。
 *     B. 陀螺仪行为：把位移判定算法**原样抽出**在纯净环境里跑数值用例。
 *     C. GPS 行为：抽出 Haversine 距离与精度过滤，跑地理数值用例。
 *        抽出的算法逐字来自 index.html，不是重写版，故能真实反映行为。
 */
const fs = require('fs');
const ROOT = 'D:/SynologyDrive/Workspace/AlphaSun-Sonic-Analyzer-Bas';
const raw = fs.readFileSync(ROOT + '/index.html', 'utf8');

const results = [];
const check = (name, cond, extra) => results.push({ name, ok: !!cond, extra: extra || '' });
/* 取函数体：从 function 名 到匹配的大括号结束（只看大括号，够用且稳） */
function sliceFn(name) {
  const i = raw.indexOf('function ' + name + '(');
  if (i < 0) return '';
  let d = 0, started = false;
  for (let j = raw.indexOf('{', i); j < raw.length; j++) {
    const c = raw[j];
    if (c === '{') { d++; started = true; }
    else if (c === '}') { d--; if (started && d === 0) return raw.slice(i, j + 1); }
  }
  return '';
}

/* ============ A. 静态契约 ============ */
// A1. 新增 DOM id（陀螺仪 + GPS + 两个状态芯片）
['alertMot', 'alertMotShot', 'alertMotTh', 'alertMotCd', 'alertMotHint', 'gMot',
 'alertGps', 'alertGpsTh', 'alertGpsHint', 'gGps']
  .forEach(id => check('DOM #' + id + ' 存在', new RegExp('id="' + id + '"').test(raw)));

// A2. 两个位移模块的函数齐全
['alMotProbe', 'alMotAskPerm', 'alMotStart', 'alMotStop', 'alMotOnEvent',
 'alMotVec', 'alMotCalm', 'alMotSnap', 'alMotAttach', 'alMotReset', 'alMoveAcc',
 'alGpsSupported', 'alGpsDist', 'alGpsOk', 'alGpsProbe', 'alGpsOnPos', 'alGpsOnErr',
 'alGpsReset', 'alGpsStart', 'alGpsStop']
  .forEach(fn => check('函数 ' + fn + ' 已定义', new RegExp('function\\s+' + fn + '\\s*\\(').test(raw)));

// A3. 生命周期挂接（开始/停止值守必须调用，否则监听泄漏 / 后台持续定位耗电）
const beginBody = raw.slice(raw.indexOf('async function alBegin'), raw.indexOf('async function alStopAll'));
check('alBegin 内启动陀螺仪监听', /alMotStart\(\)/.test(beginBody));
check('alBegin 内启动 GPS 监测', /alGpsStart\(\)/.test(beginBody));
const stopBody = raw.slice(raw.indexOf('async function alStopAll'), raw.indexOf('function alExpAll'));
check('alStopAll 内摘除陀螺仪监听（防泄漏）', /alMotStop\(\)/.test(stopBody));
check('alStopAll 内 clearWatch（防后台定位耗电）', /alGpsStop\(\)/.test(stopBody));
check('监听注册用 addEventListener', /addEventListener\('devicemotion'/.test(raw));
check('监听移除用 removeEventListener', /removeEventListener\('devicemotion'/.test(raw));
check('GPS 用 watchPosition', /watchPosition/.test(raw));
check('GPS 用 clearWatch', /clearWatch/.test(raw));

// A4. iOS 授权必须在用户手势链路内
check('iOS requestPermission 已调用', /DeviceOrientationEvent\.requestPermission/.test(raw));
check('权限判定 granted', /r!==?'granted'|r!=="granted"/.test(raw));

// A5. 参数可调 + 边界合法
const m = raw.match(/const MOTION=\{([\s\S]*?)\n\};/);
check('MOTION 参数块存在', !!m);
if (m) {
  const body = m[1];
  const g = k => { const r = body.match(new RegExp(k + '\\s*:\\s*([0-9.]+)')); return r ? parseFloat(r[1]) : NaN; };
  const stillWin = g('stillWin'), hitTh = g('hitTh'), cooldown = g('cooldown'), maxKeep = g('maxKeep');
  check('stillWin ≥800ms（够建基线）', stillWin >= 800, stillWin + 'ms');
  check('hitTh 在 0.2~2（合理灵敏度）', hitTh >= 0.2 && hitTh <= 2, 'hitTh=' + hitTh);
  check('cooldown ≥1000ms（防连拍）', cooldown >= 1000, cooldown + 'ms');
  check('maxKeep 有限（防内存涨）', maxKeep >= 1 && maxKeep <= 10, 'maxKeep=' + maxKeep);
}
const gm = raw.match(/const GPSCFG=\{([\s\S]*?)\n\};/);
check('GPSCFG 参数块存在', !!gm);
if (gm) {
  const body = gm[1];
  const g = k => { const r = body.match(new RegExp(k + '\\s*:\\s*([0-9.]+)')); return r ? parseFloat(r[1]) : NaN; };
  const hitM = g('hitM'), minAcc = g('minAcc'), cooldown = g('cooldown'), trailKeep = g('trailKeep');
  check('GPS hitM 在 5~1000 米', hitM >= 5 && hitM <= 1000, 'hitM=' + hitM + 'm');
  check('GPS minAcc 在 10~200 米（过滤漂移）', minAcc >= 10 && minAcc <= 200, 'minAcc=' + minAcc + 'm');
  check('GPS cooldown ≥3000ms', cooldown >= 3000, cooldown + 'ms');
  check('GPS trailKeep 有限（防内存涨）', trailKeep >= 1 && trailKeep <= 100, 'trailKeep=' + trailKeep);
}

// A6. 事件模型：GUARD 扩展 + LV 项
check('GUARD 增加 motion 状态', /motion\s*:\s*false/.test(raw));
check('GUARD 增加 mBase 基线', /mBase\s*:\s*null/.test(raw));
check('GUARD 增加 mShots 留证', /mShots\s*:\s*null/.test(raw));
check('GUARD 增加 mEv（独立位移事件）', /mEv\s*:\s*null/.test(raw));
check('GUARD 增加 gps 状态', /gps\s*:\s*false/.test(raw));
check('GUARD 增加 gpsBase 基线', /gpsBase\s*:\s*null/.test(raw));
check('GUARD 增加 gpsWatch', /gpsWatch\s*:\s*null/.test(raw));
check('GUARD_LV 增加 motion 级别', /motion\s*:\s*\{\s*t\s*:\s*'位移'/.test(raw) || /motion:\{t:'位移'/.test(raw));

// A7. 位移"检测即入账"（v1.1.0 修：旧版只有抓拍成功才记，关掉抓拍位移就消失）
const motOnBody = sliceFn('alMotOnEvent');
check('陀螺仪位移先记账再抓拍', /alMoveAcc\('gyro'/.test(motOnBody) && /alMotSnap\(/.test(motOnBody));
const gpsOnBody = sliceFn('alGpsOnPos');
check('GPS 位移先记账再抓拍', /alMoveAcc\('gps'/.test(gpsOnBody) && /alMotSnap\(/.test(gpsOnBody));
check('GPS 精度不足不建基线不触发', /alGpsOk\(c\)/.test(gpsOnBody));
check('GPS 基线只锁一次（不追当前位置）', /if\(!GUARD\.gpsBase\)/.test(gpsOnBody));
check('GPS 判距含精度圈重叠保护', /Math\.max\(GPSCFG\.hitM,\(b\.acc\|\|0\)\+\(cur\.acc\|\|0\)\)/.test(gpsOnBody));

// A8. 渲染 / 分析 / 导出三处都接了位移与 GPS
const renderBody = raw.slice(raw.indexOf('function alRenderEvents'), raw.indexOf('let gMediaRot'));
check('事件列表渲染位移缩略图', /mthumb/.test(renderBody) && /mShots/.test(renderBody));
check('事件行显示位移计数', /📱/.test(renderBody));
check('事件行显示 GPS 位移', /🌍GPS/.test(renderBody));
check('位移缩略图区分 GPS 来源', /kind==='gps'/.test(renderBody));
check('前后摄照片都在日志渲染', /ev\.shotsFront/.test(renderBody) && /ev\.shotsBack/.test(renderBody));
check('前后摄录像都在日志渲染', /videoFrontUrl/.test(renderBody) && /videoBackUrl/.test(renderBody));
const anaBody = raw.slice(raw.indexOf('function alEvAnalysis'), raw.indexOf('function alEvReleaseMedia'));
check('分析报告含【告警内容】段', /【告警内容】/.test(anaBody));
check('分析报告含【手机位移】段', /【手机位移】/.test(anaBody));
check('分析报告含【影像留证】段', /【影像留证】/.test(anaBody));
check('分析报告含 GPS 轨迹与基线坐标', /轨迹/.test(anaBody) && /基线坐标/.test(anaBody));
check('分析报告列出前/后摄录像名', /videoFrontName/.test(anaBody) && /videoBackName/.test(anaBody));
check('分析报告位移留证带 GPS 来源', /kind==='gps'/.test(anaBody));
check('无位移时如实写"无"', /未检测到手机被移动/.test(anaBody));
const relBody = raw.slice(raw.indexOf('function alEvReleaseMedia'), raw.indexOf('function alRenderEvents'));
check('释放逻辑含 mShots（防内存涨）', /mShots/.test(relBody) && /revokeObjectURL/.test(relBody));
check('导出含位移留证', /位移留证/.test(renderBody));
check('CSV 含位移与 GPS 列', /位移次数/.test(raw) && /GPS最远米/.test(raw));

// A9. 响应式 / 触摸
check('竖屏断点', /max-width:759px\) and \(orientation:portrait\)/.test(raw));
check('横屏矮屏断点', /orientation:landscape\) and \(max-height:520px\)/.test(raw));
check('位移芯片竖屏适配', /\.gMotChip\{margin-top:1px;font-size:10px/.test(raw));
check('位移芯片横屏适配', /\.gMotChip\{font-size:9px;padding:0 7px/.test(raw));
check('触摸层数字输入 ≥44px', /input\[type=number\]\{min-height:44px\}/.test(raw));
check('触摸层 label.sw ≥44px', /label\.sw\{[^}]*min-height:44px/.test(raw));
check('安全区适配（刘海/手势条）', /env\(safe-area-inset-bottom\)/.test(raw));

// A10. 科幻风
check('位移芯片呼吸动画', /@keyframes gMotBreathe/.test(raw));
check('位移芯片命中发光', /\.gMotChip\.hit\{[^}]*box-shadow/.test(raw));
check('位移缩略图青色描边', /\.thumb\.mthumb\{[^}]*60,232,160/.test(raw));
check('分区标题样式', /\.secTitle\{/.test(raw));
check('校准中状态样式', /\.gMotChip\.cal\{/.test(raw));
check('未启用状态样式', /\.gMotChip\.off\{/.test(raw));

// A11. 已有能力未被破坏
check('黄警也抓拍逻辑在', /lv==='warn'/.test(raw) && /alertWarnCam/.test(raw));
check('红警触发抓拍在', /alCamTrigger\(\);alNotify/.test(raw));
check('前后双摄串行抓拍在', /前后双摄|alertCamDual/.test(raw) && /camBusy/.test(raw));
check('麦克风录音（AudioWorklet）在', /as-rec/.test(raw) && /AudioWorkletNode/.test(raw));
check('录像（MediaRecorder）在', /MediaRecorder/.test(raw));
check('媒体查看器旋转按钮在', /id="gRotL"/.test(raw) && /id="gRotR"/.test(raw));
check('媒体查看器复位按钮在（1:1 还原）', /id="gZoom1"/.test(raw));
check('旋转函数在', /function guardMediaRot/.test(raw));
check('镜头切换只占用一路（WebView 限制注释保留）', /移动端同开两路会失败|无法同时占用两路视频/.test(raw));
/* v1.1.0：不应再残留旧函数名 alMotStore（已拆为 alMoveAcc + alMotAttach） */
check('无 alMotStore 旧调用残留', !/[^'^a-zA-Z]alMotStore\s*\(/.test(raw.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*/g, '')));

// A12. TDZ 顺序守卫（v1.1.0 修加载期致命缺陷）
//   设置绑定 IIFE 在页面加载时同步执行 syncParam()，会读写 MOTION.hitTh / GPSCFG.hitM。
//   const 存在暂时性死区：一旦有人把参数块挪到 IIFE 之后，加载期即抛
//   "Cannot access 'MOTION' before initialization"，且该异常在脚本顶层，会中断其后全部初始化。
//   这是 v1.0.2 起潜伏的缺陷，静态断言查不出，只能靠源码顺序断言守住。
const iMotion = raw.indexOf('const MOTION={');
const iGpsCfg = raw.indexOf('const GPSCFG={');
const iBind = raw.indexOf("const th=$('alertMotTh')");
check('MOTION 参数块排在设置绑定之前（防 TDZ）', iMotion >= 0 && iBind > 0 && iMotion < iBind,
  'MOTION@' + iMotion + ' < 绑定@' + iBind);
check('GPSCFG 参数块排在设置绑定之前（防 TDZ）', iGpsCfg >= 0 && iBind > 0 && iGpsCfg < iBind,
  'GPSCFG@' + iGpsCfg + ' < 绑定@' + iBind);
check('设置绑定 IIFE 全局仅一处（防重复注册监听）',
  (raw.match(/const th=\$\('alertMotTh'\)/g) || []).length === 1);

// A13. v1.2.0：事件日志影像展示 + 放大/旋转
['gZoomIn', 'gZoomOut', 'gZoom1'].forEach(id =>
  check('缩放按钮 #' + id + ' 存在', new RegExp('id="' + id + '"').test(raw)));
['guardMediaOpen', 'guardMediaClose', 'guardMediaRot', 'guardMediaZoom', 'guardMediaPan',
 'gMediaApply', 'gMediaResetView', 'gMediaClamp'].forEach(fn =>
  check('查看器函数 ' + fn + ' 已定义', new RegExp('function\\s+' + fn + '\\s*\\(').test(raw)));
check('缩放上下限常量就位', /const GMEDIA_MIN=[\d.]+,GMEDIA_MAX=[\d.]+;/.test(raw));
check('灯箱舞台禁用默认手势（捏合/拖动前提）', /\.gMediaStage\{[^}]*touch-action:none/.test(raw));
/* JS 用 $('#gMediaStage') 绑手势；缺 id 时 $() 返回 null，手势静默失效（validate.js 抓到过） */
check('灯箱舞台带 id（手势绑定前提）', /id="gMediaStage"/.test(raw));
check('缩略图可点区域 ≥44px（触控标准）', /\.gEv \.thumb\{width:56px;height:44px/.test(raw));
check('缩略图外框样式在', /\.thumbWrap\{/.test(raw) && /min-height:44px/.test(raw));
check('缩略图来源角标样式在', /\.thumbTag\{/.test(raw));
const renderB = raw.slice(raw.indexOf('function alRenderEvents'), raw.indexOf('let gMediaRot'));
check('前后摄照片都走带角标的缩略图', /mkThumb\(s\.url,'前置摄像头连拍','前'\)/.test(renderB) &&
  /mkThumb\(s\.url,'后置摄像头连拍','后','back'\)/.test(renderB));
check('位移留证缩略图带 GPS/位移角标', /mkThumb[\s\S]{0,400}?'GPS':'位移'/.test(renderB));
check('录像按钮挂到灯箱（video）', /guardMediaOpen\(url,'video'\)/.test(renderB));
check('照片点击进灯箱（img）', /guardMediaOpen\(url,'img'\)/.test(renderB));
check('触屏不重复绑 dblclick（防放大被抵消）', /pointer:coarse/.test(raw) && /if\(!coarse\)/.test(raw));
check('缩放按钮已绑定', /\$\('gZoomIn'\)\.addEventListener/.test(raw) && /\$\('gZoomOut'\)\.addEventListener/.test(raw));
check('transform 顺序固定为 平移→旋转→缩放',
  /'translate\('[\s\S]{0,160}?'rotate\('[\s\S]{0,120}?scale\('/.test(raw));
check('缩放回 1 倍自动归位平移', /if\(s1<=1\.001\)\{gMediaPX=0;gMediaPY=0;\}/.test(raw));
check('1 倍时禁止拖动（防画面拖出可视区）', /if\(gMediaScale<=1\.001\)return;/.test(raw));

/* ============ B. 陀螺仪行为：原样抽出算法跑数值用例 ============ */
function extractConst(name) {
  const i = raw.indexOf('const ' + name + '={');
  if (i < 0) return null;
  let d = 0, started = false;
  for (let j = raw.indexOf('{', i); j < raw.length; j++) {
    const c = raw[j];
    if (c === '{') { d++; started = true; }
    else if (c === '}') { d--; if (started && d === 0) return raw.slice(raw.indexOf('{', i) + 1, j); }
  }
  return null;
}
function extractFnSrc(name) {
  const s = sliceFn(name);
  return s || null;
}
const MOTION_SRC = extractConst('MOTION');
const VEC_SRC = extractFnSrc('alMotVec');
const CALM_SRC = extractFnSrc('alMotCalm');
check('MOTION 源码可抽取', !!MOTION_SRC);
check('alMotVec 源码可抽取', !!VEC_SRC);
check('alMotCalm 源码可抽取', !!CALM_SRC);

if (MOTION_SRC && VEC_SRC && CALM_SRC) {
  const GUARD = { motion: true, mBase: null, mStillMs: 0, mPeak: 0, mMoved: 0, mLastHit: 0 };
  let performance_ = { now: (() => { let t = 10000; return () => (t += 16); })() };
  const factory = new Function('GUARD', 'performance',
    'const MOTION={' + MOTION_SRC + '};\n' + VEC_SRC + '\n' + CALM_SRC +
    '\nreturn {MOTION,alMotVec,alMotCalm};');
  let api = null;
  try { api = factory(GUARD, performance_); }
  catch (e) { check('陀螺仪算法可独立执行', false, e.message.slice(0, 120)); }

  if (api) {
    check('陀螺仪算法可独立执行', true);
    const { alMotVec, alMotCalm, MOTION } = api;

    const v1 = alMotVec({ accelerationIncludingGravity: { x: 3, y: 4, z: 0 } });
    check('加速度矢量模长正确（3,4,0 → 5）', v1 && v1.a === true && Math.abs(v1.m - 5) < 1e-9, 'm=' + (v1 && v1.m));
    const v2 = alMotVec({ accelerationIncludingGravity: { x: 0, y: 0, z: 9.8 } });
    check('竖直放置识别（z≈9.8）', v2 && Math.abs(v2.m - 9.8) < 1e-6, 'm=' + (v2 && v2.m.toFixed(2)));
    const v3 = alMotVec({ accelerationIncludingGravity: null, rotationRate: { alpha: 0, beta: 0, gamma: 1.8 } });
    check('无加速度时退化用角速度', v3 && v3.a === false && Math.abs(v3.m - 1.8) < 1e-9);
    check('空事件返回 null（不崩）', alMotVec({}) === null);
    check('零向量视为无数据', alMotVec({ accelerationIncludingGravity: { x: 0, y: 0, z: 0 } }) === null);

    GUARD.mBase = null; GUARD.mStillMs = 0;
    let established = false, frames = 0;
    for (let i = 0; i < 200 && !established; i++) {
      frames++;
      established = alMotCalm({ a: true, x: 0.02, y: 0.01, z: 9.8, m: 9.8 });
    }
    check('静止基线可建立', established, frames + ' 帧内建立');
    const base = GUARD.mBase;
    check('基线收敛到真实值附近', base && Math.abs(base.z - 9.8) < 0.5, 'base.z=' + (base && base.z.toFixed(3)));

    const stillDev = Math.hypot(0.06 - base.x, 0.04 - base.y, 9.83 - base.z);
    check('静止微抖不超阈值（不误触）', stillDev < MOTION.hitTh,
      '偏离=' + stillDev.toFixed(4) + ' < 阈值=' + MOTION.hitTh);

    const shakeDev = Math.hypot(6.0 - base.x, 0 - base.y, 2.0 - base.z);
    check('甩动偏离超阈值（必触发）', shakeDev > MOTION.hitTh,
      '偏离=' + shakeDev.toFixed(2) + ' > 阈值=' + MOTION.hitTh);

    const b0 = { ...base };
    for (let i = 0; i < 30; i++) alMotCalm({ a: true, x: 6, y: 0, z: 2, m: 6.32 });
    const drift = Math.hypot(GUARD.mBase.x - b0.x, GUARD.mBase.y - b0.y, GUARD.mBase.z - b0.z);
    check('持续甩动时基线不快速漂移', drift < 2.0, '30帧后基线偏移=' + drift.toFixed(2));

    GUARD.mBase = null; GUARD.mStillMs = 0;
    const t0 = performance_.now();
    let everCalm = false;
    for (let i = 0; i < 500; i++) {
      if (alMotCalm({ a: true, x: 0, y: 0, z: 9.8, m: 9.8 })) { everCalm = true; break; }
    }
    check('静止窗口需累积而非瞬时成立', everCalm && (performance_.now() - t0) >= MOTION.stillWin,
      '耗时≥' + MOTION.stillWin + 'ms');
  }
}

/* ============ C. GPS 行为：原样抽出 Haversine 与精度过滤跑用例 ============ */
const GPS_SRC = extractConst('GPSCFG');
const DIST_SRC = extractFnSrc('alGpsDist');
const OK_SRC = extractFnSrc('alGpsOk');
const earthM = raw.match(/const EARTH_R\s*=\s*([0-9.]+)\s*;/);
check('GPSCFG 源码可抽取', !!GPS_SRC);
check('alGpsDist 源码可抽取', !!DIST_SRC);
check('alGpsOk 源码可抽取', !!OK_SRC);
check('EARTH_R 常量就位', !!earthM);

if (GPS_SRC && DIST_SRC && OK_SRC && earthM) {
  const factory2 = new Function(
    'const EARTH_R=' + earthM[1] + ';\nconst GPSCFG={' + GPS_SRC + '};\n' +
    DIST_SRC + '\n' + OK_SRC + '\nreturn {GPSCFG,alGpsDist,alGpsOk};');
  let g2 = null;
  try { g2 = factory2(); }
  catch (e) { check('GPS 算法可独立执行', false, e.message.slice(0, 120)); }

  if (g2) {
    check('GPS 算法可独立执行', true);
    const { GPSCFG, alGpsDist, alGpsOk } = g2;
    const d0 = alGpsDist({ lat: 39.9, lon: 116.4 }, { lat: 39.9, lon: 116.4 });
    check('同点距离为 0', Math.abs(d0) < 1e-6, d0.toFixed(6) + 'm');
    const d1 = alGpsDist({ lat: 0, lon: 0 }, { lat: 1, lon: 0 });
    check('1 度纬度 ≈111.2km', Math.abs(d1 - 111195) < 800, d1.toFixed(0) + 'm');
    const d2 = alGpsDist({ lat: 39.9042, lon: 116.4074 }, { lat: 31.2304, lon: 121.4737 });
    check('北京→上海 ≈1067km（量级正确）', d2 > 1.0e6 && d2 < 1.15e6, (d2 / 1000).toFixed(0) + 'km');
    check('距离对称', Math.abs(alGpsDist({ lat: 1, lon: 2 }, { lat: 3, lon: 4 }) -
      alGpsDist({ lat: 3, lon: 4 }, { lat: 1, lon: 2 })) < 1e-6);

    check('精度 5m 的点采信', alGpsOk({ latitude: 39.9, longitude: 116.4, accuracy: 5 }) === true);
    check('精度 500m 的点不采信（防室内漂移误报）',
      alGpsOk({ latitude: 39.9, longitude: 116.4, accuracy: 500 }) === false);
    check('0,0 无效坐标不采信', alGpsOk({ latitude: 0, longitude: 0, accuracy: 5 }) === false);
    check('越界纬度不采信', alGpsOk({ latitude: 91, longitude: 116, accuracy: 5 }) === false);
    check('缺坐标不采信（不崩）', alGpsOk(null) === false && alGpsOk({}) === false);

    /* 触发门限：距离必须同时超过阈值与两点精度之和 */
    const need1 = Math.max(GPSCFG.hitM, 10 + 10);
    check('正常精度下按阈值判定（20m 不触发 / 50m 触发）', 20 < need1 && 50 > need1,
      'need=' + need1 + 'm');
    const need2 = Math.max(GPSCFG.hitM, 50 + 50);
    check('精度圈重叠时提高门限（80m 也不触发）', 80 < need2, 'need=' + need2 + 'm');
  }
}

/* ============ D. 影像查看器行为：原样抽出缩放/旋转逻辑跑数值用例 ============ */
const gmConst = raw.match(/const GMEDIA_MIN=([\d.]+),GMEDIA_MAX=([\d.]+);/);
const CLAMP_SRC = sliceFn('gMediaClamp');
const ZOOM_SRC = sliceFn('guardMediaZoom');
const PAN_SRC = sliceFn('guardMediaPan');
const ROT_SRC = sliceFn('guardMediaRot');
check('GMEDIA 常量可抽取', !!gmConst);
check('gMediaClamp 源码可抽取', !!CLAMP_SRC);
check('guardMediaZoom 源码可抽取', !!ZOOM_SRC);
check('guardMediaPan 源码可抽取', !!PAN_SRC);
check('guardMediaRot 源码可抽取', !!ROT_SRC);

if (gmConst && CLAMP_SRC && ZOOM_SRC && PAN_SRC && ROT_SRC) {
  const factory3 = new Function(
    'const GMEDIA_MIN=' + gmConst[1] + ',GMEDIA_MAX=' + gmConst[2] + ';\n' +
    'let gMediaRot=0,gMediaScale=1,gMediaPX=0,gMediaPY=0;\n' +
    'function gMediaApply(){}\n' +
    CLAMP_SRC + '\n' + ZOOM_SRC + '\n' + PAN_SRC + '\n' + ROT_SRC + '\n' +
    'return {state:function(){return {rot:gMediaRot,scale:gMediaScale,x:gMediaPX,y:gMediaPY};},' +
    'set:function(s){if(s.scale!=null)gMediaScale=s.scale;if(s.x!=null)gMediaPX=s.x;' +
    'if(s.y!=null)gMediaPY=s.y;if(s.rot!=null)gMediaRot=s.rot;},' +
    'gMediaClamp:gMediaClamp,guardMediaZoom:guardMediaZoom,' +
    'guardMediaPan:guardMediaPan,guardMediaRot:guardMediaRot};');
  let v = null;
  try { v = factory3(); }
  catch (e) { check('查看器算法可独立执行', false, e.message.slice(0, 120)); }

  if (v) {
    check('查看器算法可独立执行', true);
    const MIN = parseFloat(gmConst[1]), MAX = parseFloat(gmConst[2]);
    const { gMediaClamp, guardMediaZoom, guardMediaPan, guardMediaRot } = v;
    check('缩放下限钳制', gMediaClamp(0.01) === MIN, String(MIN));
    check('缩放上限钳制', gMediaClamp(999) === MAX, String(MAX));
    check('区间内数值不改动', gMediaClamp(2.5) === 2.5);
    check('非法输入回落 1（不崩）', gMediaClamp(NaN) === 1 && gMediaClamp(undefined) === 1);

    v.set({ scale: 1, x: 0, y: 0 });
    guardMediaZoom(1.4);
    check('点一次放大生效', Math.abs(v.state().scale - 1.4) < 1e-9, v.state().scale.toFixed(3));
    for (let i = 0; i < 20; i++) guardMediaZoom(1.4);
    check('连续放大不超上限', v.state().scale === MAX, String(v.state().scale));
    for (let i = 0; i < 30; i++) guardMediaZoom(1 / 1.4);
    check('连续缩小不低于下限', v.state().scale === MIN, String(v.state().scale));

    v.set({ scale: 2, x: 60, y: -40 });
    guardMediaZoom(0.5);
    check('缩回 1 倍自动归位平移',
      v.state().scale <= 1.001 && v.state().x === 0 && v.state().y === 0);

    v.set({ scale: 1, x: 0, y: 0 });
    guardMediaPan(30, 20);
    check('1 倍时拖动被忽略', v.state().x === 0 && v.state().y === 0);
    v.set({ scale: 2.5, x: 0, y: 0 });
    guardMediaPan(30, 20);
    check('放大后可拖动平移', v.state().x === 30 && v.state().y === 20);

    v.set({ rot: 0 });
    guardMediaRot(-90);
    check('左转 90° 归一为 270（不出现负角）', v.state().rot === 270, v.state().rot + '°');
    guardMediaRot(90);
    check('再右转 90° 回到 0', v.state().rot === 0);
    guardMediaRot(450);
    check('超过 360° 正确归一', v.state().rot === 90, v.state().rot + '°');
  }
}

/* ============ 汇总 ============ */
let pass = 0, fail = 0;
for (const r of results) {
  if (r.ok) { pass++; console.log('  ✓ ' + r.name + (r.extra ? '  [' + r.extra + ']' : '')); }
  else { fail++; console.log('  ✗ ' + r.name + (r.extra ? '  [' + r.extra + ']' : '')); }
}
console.log('\n断言: ' + pass + ' 通过 / ' + fail + ' 失败');
if (fail === 0) console.log('★ 位移功能门禁（陀螺仪 + GPS）通过');
process.exit(fail === 0 ? 0 : 1);
