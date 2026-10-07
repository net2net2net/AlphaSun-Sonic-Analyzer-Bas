/* 移动版 UI 验证（临时）：借用桌面仓库的 playwright/electron 运行时，加载本目录 index.html。
   移动版本身不依赖 Electron（Capacitor 打包），此处仅为开发期视觉/布局验证。*/
const path=require('path'),os=require('os'),fs=require('fs');
const MOBILE=path.resolve(__dirname,'..');
const DESK='D:/SynologyDrive/Workspace/AlphaSun-AudioSpectrumLab';
let pw=null;for(const c of [path.join(os.homedir(),'.workbuddy','binaries','node','workspace','node_modules','playwright-core'),path.join(DESK,'node_modules','playwright-core')]){try{pw=require(c);break;}catch(_){}}
if(!pw||!pw._electron){console.error('✗ 无 playwright-core');process.exit(2);}
const ELECTRON=path.join(DESK,'node_modules','electron','dist','electron.exe');
const VPS=[{k:'phone-portrait',w:390,h:844},{k:'phone-landscape',w:844,h:390},{k:'tablet-portrait',w:820,h:1180}];
(async()=>{delete process.env.ELECTRON_RUN_AS_NODE;
const app=await pw._electron.launch({args:[path.join(MOBILE,'index.html'),'--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream'],cwd:MOBILE,executablePath:ELECTRON,timeout:60000});
const page=await app.firstWindow();
const errs=[];page.on('pageerror',e=>errs.push(e&&e.message));
await page.waitForTimeout(2000);
const out=path.join(MOBILE,'.workbuddy','shots');fs.mkdirSync(out,{recursive:true});
await page.click('#capBtn').catch(()=>{});
await page.waitForTimeout(2000);
for(const v of VPS){
  const cdp=await page.context().newCDPSession(page);
  await cdp.send('Emulation.setDeviceMetricsOverride',{width:v.w,height:v.h,deviceScaleFactor:2,mobile:true});
  await page.waitForTimeout(900);
  const d=await page.evaluate(()=>{
    const R=s=>{const e=document.querySelector(s);if(!e)return null;const b=e.getBoundingClientRect();
      return {x:Math.round(b.x),y:Math.round(b.y),w:Math.round(b.width),h:Math.round(b.height)};};
    const cap=R('#capBtn'),gr=R('#mGuardBtn'),pm=R('#mParamBtn'),mb=R('.mbar');
    const ov=(a,b)=>a&&b&&!(a.x+a.w<=b.x+1||b.x+b.w<=a.x+1||a.y+a.h<=b.y+1||b.y+b.h<=a.y+1);
    const overlap=ov(cap,gr)||ov(cap,pm)||ov(gr,pm);
    const vizR=R('.viz');
    return {cap,gr,pm,mb,vh:innerHeight,overlap,vizR,
      vizW: vizR? vizR.w:0, vizH: vizR? vizR.h:0, vw:innerWidth,
      pmOn: (document.getElementById('pmCard')||{className:''}).className.indexOf('on')>=0,
      capTxt: ((document.getElementById('capTxt')||{}).textContent||''),
      canvasInk: (()=>{const c=document.getElementById('cv');if(!c)return -1;
        const g=c.getContext('2d');if(!g)return -2;
        try{const d=g.getImageData(0,0,c.width,c.height).data;let n=0;
          for(let i=0;i<d.length;i+=400)if(d[i]>8||d[i+1]>8||d[i+2]>8)n++;
          return Math.round(n/(d.length/400)*100);}catch(e){return -3}})(),
      brandTxt: (document.querySelector('.sub.sonic')||{}).textContent,
      fontUsed: (()=>{const e=document.querySelector('.sub.sonic');
        return e?getComputedStyle(e).fontFamily.split(',')[0]:''})(),
      plDir:getComputedStyle(document.querySelector('.pipeline')).flexDirection,
      brandY:R('.brand').y,vizY:R('.viz').y,
      ovX:document.documentElement.scrollWidth>innerWidth+1,
      hint:((document.getElementById('hint')||{}).textContent||'').slice(0,22)};});
  const f=path.join(out,'M-'+v.k+'.png');await page.screenshot({path:f});
  console.log('\n== '+v.k+' ==');
  console.log('  底部条贴底 =', d.mb && d.mb.y>=d.vh-d.mb.h-2 ? 'OK':'FAIL');
  console.log('  三按钮两两不重叠 =', d.overlap?'FAIL 重叠':'OK', '| 开始',d.cap.w+'×'+d.cap.h, '警戒',d.gr.w+'×'+d.gr.h, '参数',d.pm.w+'×'+d.pm.h);
  console.log('  可视化尺寸 =', d.vizW+'×'+d.vizH, '视口宽', d.vw, '→ 满宽', Math.abs(d.vizW-d.vw)<=16?'OK':'FAIL', '| 占屏高', (d.vizH/d.vh*100).toFixed(0)+'%');
  console.log('  DSP链横排 =', d.plDir==='row'?'OK':'FAIL');
  console.log('  顺序 品牌.y='+d.brandY+' → 可视化.y='+d.vizY, d.brandY<=d.vizY?'OK':'FAIL');
  console.log('  横向溢出 =', d.ovX?'有 FAIL':'无 OK');
  console.log('  品牌名 =', JSON.stringify(d.brandTxt), '| 字体', d.fontUsed);
  console.log('  采集状态 =', d.capTxt, '| canvas 着色像素', d.canvasInk+'%');
  await cdp.send('Emulation.clearDeviceMetricsOverride').catch(()=>{});
  await cdp.detach().catch(()=>{});
}
console.log('\npageerror:',errs.length?errs.join(' | '):'无');
await app.close();})().catch(e=>{console.error('ERR',e&&e.message);process.exit(3);});
