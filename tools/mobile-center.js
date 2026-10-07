const path=require('path'),os=require('os');
const MOBILE=path.resolve(__dirname,'..');
const DESK='D:/SynologyDrive/Workspace/AlphaSun-AudioSpectrumLab';
let pw=null;for(const c of [path.join(os.homedir(),'.workbuddy','binaries','node','workspace','node_modules','playwright-core'),path.join(DESK,'node_modules','playwright-core')]){try{pw=require(c);break;}catch(_){}}
(async()=>{delete process.env.ELECTRON_RUN_AS_NODE;
// hasTouch 需走 CDP（见下方触摸段落说明）——Electron 启动不支持该 context 选项
const app=await pw._electron.launch({args:[path.join(MOBILE,'index.html'),'--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream'],cwd:MOBILE,executablePath:path.join(DESK,'node_modules','electron','dist','electron.exe'),timeout:60000});
const page=await app.firstWindow();const errs=[];page.on('pageerror',e=>errs.push(e&&e.message));
await page.waitForTimeout(2000);
const cdp=await page.context().newCDPSession(page);
await cdp.send('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:2,mobile:true});
await page.waitForTimeout(800);
const st=()=>page.evaluate(()=>{const t=((document.getElementById('capTxt')||{}).textContent||'').trim();
  return {t:t,run:t!=='开始采集'};});  // 以按钮文案为唯一真源（running 是闭包内局部变量）
const ctr=()=>page.evaluate(()=>{const c=document.getElementById('cv').getBoundingClientRect();
  return {x:Math.round(c.x+c.width/2),y:Math.round(c.y+c.height/2),
          rr:Math.round(Math.min(c.width,c.height)*0.42)};});
console.log('=== 经典环谱（模式0）中央环点击暂停/继续 ===');
console.log('初始:', JSON.stringify(await st()));
const c=await ctr(); console.log('中心点:',c.x+','+c.y,'（判定半径 '+c.rr+'px）');
await page.mouse.click(c.x,c.y); await page.waitForTimeout(1000);
const a=await st(); console.log('第1次点击后:', JSON.stringify(a), a.run?'采集中':'已停');
await page.mouse.click(c.x,c.y); await page.waitForTimeout(1000);
const b=await st(); console.log('第2次点击后:', JSON.stringify(b), b.run?'采集中':'已停');
console.log('判定:', a.run!==b.run ? 'OK 暂停/继续切换生效' : 'FAIL 未切换');
// 触摸事件验证
// 注意：Electron 启动时**无法**通过 context 选项打开 hasTouch（本文件原来定义了 ctxOpts 却没用上），
// 因此 `page.touchscreen.tap()` 必然抛 "hasTouch must be enabled" —— 曾导致这里出现假 FAIL。
// 正确做法是走 CDP 原生 Input.dispatchTouchEvent，与 tools/mobile-touch.js 保持一致。
console.log('\n=== 触摸（CDP 真实 touch 事件）===');
await cdp.send('Emulation.setTouchEmulationEnabled',{enabled:true,maxTouchPoints:5});
await cdp.send('Emulation.setEmitTouchEventsForMouse',{enabled:true,configuration:'mobile'});
const tap=async(x,y)=>{const t={x:Math.round(x),y:Math.round(y),radiusX:6,radiusY:6,force:1,id:1};
  await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[t]});
  await new Promise(r=>setTimeout(r,90));
  await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
  await page.waitForTimeout(1000);};
const t2=await st();
await tap(c.x,c.y);
const t3=await st();
console.log('触摸前 running='+t2.run+' → 触摸后 running='+t3.run+'  '+(t2.run!==t3.run?'OK 触摸可切换':'FAIL'));
console.log('\npageerror:',errs.length?errs.join(' | '):'无');
await app.close();})().catch(e=>{console.error('ERR',e&&e.message);process.exit(3);});
