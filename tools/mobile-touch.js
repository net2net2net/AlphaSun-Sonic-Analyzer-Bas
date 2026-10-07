/* 用 CDP 注入真实触摸事件，验证移动端触摸交互（中央环 tap / 按钮 tap）*/
const path=require('path'),os=require('os');
const MOBILE=path.resolve(__dirname,'..');
const DESK='D:/SynologyDrive/Workspace/AlphaSun-AudioSpectrumLab';
let pw=null;for(const c of [path.join(os.homedir(),'.workbuddy','binaries','node','workspace','node_modules','playwright-core'),path.join(DESK,'node_modules','playwright-core')]){try{pw=require(c);break;}catch(_){}}
(async()=>{delete process.env.ELECTRON_RUN_AS_NODE;
const app=await pw._electron.launch({args:[path.join(MOBILE,'index.html'),'--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream'],cwd:MOBILE,executablePath:path.join(DESK,'node_modules','electron','dist','electron.exe'),timeout:60000});
const page=await app.firstWindow();const errs=[];page.on('pageerror',e=>errs.push(e&&e.message));
await page.waitForTimeout(2000);
const cdp=await page.context().newCDPSession(page);
await cdp.send('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:3,mobile:true});
await cdp.send('Emulation.setTouchEmulationEnabled',{enabled:true,maxTouchPoints:5});
await cdp.send('Emulation.setEmitTouchEventsForMouse',{enabled:true,configuration:'mobile'});
await page.waitForTimeout(900);
// 完整 tap 序列：touchStart → touchEnd 均带坐标，Chromium 才会合成 click
const tap=async(x,y)=>{const t={x:Math.round(x),y:Math.round(y),radiusX:6,radiusY:6,force:1,id:1};
  await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[t]});
  await new Promise(r=>setTimeout(r,90));
  await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
  await page.waitForTimeout(1000);};
const st=()=>page.evaluate(()=>((document.getElementById('capTxt')||{}).textContent||'').trim());
const ctr=()=>page.evaluate(()=>{const c=document.getElementById('cv').getBoundingClientRect();
  return {x:c.x+c.width/2,y:c.y+c.height/2};});
console.log('=== 触摸（CDP 真实 touch 事件）===');
const c=await ctr();
console.log('初始:', await st());
await tap(c.x,c.y); const a=await st(); console.log('中央环 tap#1 →', a);
await tap(c.x,c.y); const b=await st(); console.log('中央环 tap#2 →', b);
console.log('中央环暂停/继续:', (a==='暂停采集'&&b==='开始采集')?'OK':'FAIL');
// 底部按钮触摸
const bb=await page.evaluate(()=>{const r=document.getElementById('mParamBtn').getBoundingClientRect();
  return {x:r.x+r.width/2,y:r.y+r.height/2};});
const pm1=await page.evaluate(()=>getComputedStyle(document.getElementById('pmCard')).display);
await tap(bb.x,bb.y);
const pm2=await page.evaluate(()=>getComputedStyle(document.getElementById('pmCard')).display);
console.log('声波参数 tap: '+pm1+' → '+pm2+'  '+(pm1==='none'&&pm2!=='none'?'OK 触摸可点':'FAIL'));
// 切换条触摸：参数卡展开后页面已滚动，可视化随之滚出视口（y 为负），
// 属正常滚动行为 —— 测试需先回到顶部再点。
await page.waitForTimeout(900);
await page.evaluate(()=>{window.scrollTo(0,0);const st=document.querySelector('.stage');if(st)st.scrollTop=0;});
await page.waitForTimeout(700);
const vb=await page.evaluate(()=>{const r=document.getElementById('vizNextM').getBoundingClientRect();
  return {x:r.x+r.width/2,y:r.y+r.height/2};});
const v1=await page.evaluate(()=>(document.getElementById('vizNameM')||{}).textContent);
const diag=await page.evaluate(pp=>{
  window.__lg=[];const b=document.getElementById('vizNextM');
  ['pointerdown','pointerup','touchstart','touchend','click'].forEach(t=>
    b.addEventListener(t,()=>window.__lg.push(t),true));
  const r=b.getBoundingClientRect();
  const hit=document.elementFromPoint(Math.round(r.x+r.width/2),Math.round(r.y+r.height/2));
  return {rect:{x:Math.round(r.x),y:Math.round(r.y),w:Math.round(r.width),h:Math.round(r.height)},
    vh:innerWidth+'x'+innerHeight, inView:(r.y>0&&r.y<innerHeight),
    hit:hit?(hit.id||hit.tagName):'null'};},vb);
console.log('  [诊断] 按钮矩形',JSON.stringify(diag.rect),'视口',diag.vh,'在视口内',diag.inView,'命中',diag.hit);
await tap(vb.x,vb.y);
const v2=await page.evaluate(()=>(document.getElementById('vizNameM')||{}).textContent);
const lg=await page.evaluate(()=>window.__lg);
console.log('  [诊断] 触发事件',JSON.stringify(lg));
console.log('可视化切换 tap: '+v1+' → '+v2+'  '+(v1!==v2?'OK 触摸可切换':'FAIL'));
console.log('\npageerror:',errs.length?errs.join(' | '):'无');
await app.close();})().catch(e=>{console.error('ERR',e&&e.message);process.exit(3);});
