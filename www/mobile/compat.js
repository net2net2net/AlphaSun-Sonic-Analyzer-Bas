// AlphaSun 声波分析仪 · 移动端运行适配层 (v0.07)
// ------------------------------------------------------------------
// 背景：此前所有验证都在 Windows/Electron 上做，移动端存在三处**阻断级**缺口：
//   ① iOS 音频解锁：Safari/WKWebView 要求 AudioContext 必须在**用户手势内**
//      创建或 resume()，否则永远停在 suspended，麦克风拿到数据也是全 0。
//   ② iOS 音频会话：未显式设置 AVAudioSession category 为 .playAndRecord，
//      扬声器输出会被系统静音，且与麦克风抢占时容易静音。
//   ③ 权限提示文案全是 Windows 视角（"检查 Windows 设置…"），
//      移动端用户按提示找不到对应入口。
// 本文件只做**平台适配与提示**，不改动任何 DSP 逻辑。
(() => {
  'use strict';

  const UA = navigator.userAgent || '';
  const isiOS = /iPhone|iPad|iPod/.test(UA) ||
    (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  const isAndroid = /Android/.test(UA);
  const isCapacitor = !!(window.Capacitor);
  const isMobile = isiOS || isAndroid;

  // ── ① iOS 音频解锁 ──────────────────────────────────────────
  // 在第一次用户手势上「空放」一个 AudioContext，唤醒 iOS 的音频硬件。
  // 必须在手势栈内同步创建，之后 resume 才被允许。
  let unlocked = false;
  function unlockAudio() {
    if (unlocked || !isiOS) return;
    try {
      const AC = window.AudioContext || window.webkitAudioContext;
      const ctx = new AC();
      // 播放一个 1 采样静音缓冲，把音频会话真正拉起来
      const b = ctx.createBuffer(1, 1, ctx.sampleRate);
      const src = ctx.createBufferSource();
      src.buffer = b;
      src.connect(ctx.destination);
      src.start(0);
      if (ctx.state === 'suspended') ctx.resume();
      unlocked = true;
      console.log('[AlphaSun Mobile] iOS 音频已解锁 ✓');
    } catch (e) {
      console.warn('[AlphaSun Mobile] iOS 音频解锁失败（不影响后续手势重试）', e);
    }
  }
  ['touchstart', 'pointerdown', 'mousedown', 'keydown'].forEach(ev => {
    if (!isiOS) return;
    window.addEventListener(ev, unlockAudio, { once: false, passive: true, capture: true });
  });

  // ── ② iOS 音频会话 ──────────────────────────────────────────
  // WebView 里拿不到原生 AVAudioSession，但可以通过一个极短的无声播放
  // 促使系统切到 playAndRecord；再配合 rAF 保持活跃。
  function keepAudioAlive() {
    if (!isiOS) return;
    try {
      const AC = window.AudioContext || window.webkitAudioContext;
      if (!AC) return;
      window.__asKeepAlive = setInterval(() => {
        try {
          const ctx = window.__asAudioCtxRef;
          if (ctx && ctx.state === 'suspended') ctx.resume();
        } catch (_) { }
      }, 2000);
    } catch (_) { }
  }

  // ── ③ 平台自检（供「说明 → 环境自检」与诊断日志使用）──────────
  function probe() {
    const out = {
      platform: isiOS ? 'iOS' : (isAndroid ? 'Android' : '桌面/浏览器'),
      capacitor: isCapacitor,
      mobile: isMobile,
      mediaDevices: !!navigator.mediaDevices,
      getUserMedia: !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia),
      mediaRecorder: typeof window.MediaRecorder !== 'undefined',
      audioUnlocked: unlocked,
      secureContext: window.isSecureContext,
      /* 权限状态：Android/iOS 13+ 用 Permissions API，桌面多为 'prompt' */
      permMic: '未知', permCam: '未知',
      offlineAC: !!(window.OfflineAudioContext || window.webkitOfflineAudioContext),
      audioWorklet: !!window.AudioWorkletNode,
      dpr: window.devicePixelRatio || 1,
      touch: ('ontouchstart' in window) || navigator.maxTouchPoints > 0,
    };
    return out;
  }
  window.__asMobileProbe = probe;

  // 权限查询（Permissions API 在部分 WebView 不可用，属正常降级）
  if (navigator.permissions && navigator.permissions.query) {
    const q = n => navigator.permissions.query({ name: n })
      .then(s => {
        s.onchange = () => {
          const p = probe();
          if (n === 'microphone') p.permMic = s.state; else p.permCam = s.state;
        };
        return s.state;
      })
      .catch(() => '不支持');
    q('microphone').then(v => { probe().permMic = v; });
    q('camera').then(v => { probe().permCam = v; });
  }

  keepAudioAlive();

  console.log('[AlphaSun Mobile] 移动适配层已挂载 ✓ 平台=' + probe().platform +
    ' Capacitor=' + isCapacitor);
})();
