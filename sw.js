// AlphaSun 声波分析仪 · 离线缓存（PWA）
const CACHE='alphasun-audio-v1.3.0';
// v0.03：视觉字体改为本地内嵌（Orbitron 英文 + HarmonyDisplay 中文子集），
// 必须一并进 SW 预缓存 —— 否则离线时 CSS 的 url() 请求失败会被 fetch 兜底返回 index.html，
// 字体解码失败后静默回退系统字体，"科技艺术字"在离线场景直接失效。
const ASSETS=['./','./index.html','./manifest.webmanifest','./assets/icon.svg','./assets/lame.min.js',
  './assets/fonts/orbitron-latin-500-normal.woff2','./assets/fonts/orbitron-latin-700-normal.woff2',
  './assets/fonts/orbitron-latin-900-normal.woff2','./assets/fonts/harmony-display.woff2',
  './desktop/boot.js','./mobile/bridge.js'];
self.addEventListener('install',e=>{e.waitUntil(caches.open(CACHE).then(c=>c.addAll(ASSETS)).then(()=>self.skipWaiting()));});
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(ks=>Promise.all(ks.filter(k=>k!==CACHE).map(k=>caches.delete(k)))).then(()=>self.clients.claim()));});
self.addEventListener('fetch',e=>{e.respondWith(caches.match(e.request).then(r=>r||fetch(e.request).catch(()=>caches.match('./index.html'))));});
