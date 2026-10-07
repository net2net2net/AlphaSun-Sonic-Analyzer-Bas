/* 移动版 v0.02 功能全验证：7 种可视化渲染 + 切换控件 + 声波参数弹出 + 中央区暂停 */
const path=require('path'),os=require('os'),fs=require('fs');
const MOBILE=path.resolve(__dirname,'..');
const DESK='D:/SynologyDrive/Workspace/AlphaSun-AudioSpectrumLab';
let pw=null;for(const c of [path.join(os.homedir(),'.workbuddy','binaries','node','workspace','node_modules','playwright-core'),path.join(DESK,'node_modules','playwright-core')]){try{pw=require(c);break;}catch(_){}}
(async()=>{delete process.env.ELECTRON_RUN_AS_NODE;
const app=await pw._electron.launch({args:[path.join(MOBILE,'index.html'),'--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream'],cwd:MOBILE,executablePath:require('./electron-path')(),timeout:60000});
const page=await app.firstWindow();
const errs=[];page.on('pageerror',e=>errs.push(e&&e.message));
await page.waitForTimeout(2000);
const cdp=await page.context().newCDPSession(page);
await cdp.send('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:2,mobile:true});
await page.waitForTimeout(800);
const out=path.join(MOBILE,'.workbuddy','shots');fs.mkdirSync(out,{recursive:true});

await page.click('#capBtn').catch(()=>{});
await page.waitForTimeout(2500);

const ink=()=>page.evaluate(()=>{const c=document.getElementById('cv');const g=c.getContext('2d');
  const d=g.getImageData(0,0,c.width,c.height).data;let n=0;
  for(let i=0;i<d.length;i+=400)if(d[i]>8||d[i+1]>8||d[i+2]>8)n++;
  return Math.round(n/(d.length/400)*100);});
const name=()=>page.evaluate(()=>(document.getElementById('vizNameM')||{}).textContent);

console.log('=== 7 种可视化逐个切换（每次点 ▶）===');
for(let i=0;i<7;i++){
  if(i>0){ await page.click('#vizNextM'); await page.waitForTimeout(1100); }
  const n=await name(); const k=await ink();
  await page.screenshot({path:path.join(out,'VIZ-'+i+'-'+(n||'?')+'.png')});
  console.log('  '+(i+1)+'. '+String(n).padEnd(6)+' canvas着色 '+String(k).padStart(3)+'%  '+(k>3?'OK':'FAIL 无渲染'));
}
// 中央区点击暂停/继续
console.log('\n=== 中央环点击暂停/继续 ===');
// 前置条件：中央环判定只在圆形族（经典环谱 / 极坐标）生效 —— 源码是 `if(vizMode>=4)return;`。
// 上一段循环结束时停在「瀑布图」(mode 6)，直接点中心必然无反应（曾因此报假 FAIL）。
// 故先切回默认「经典环谱」并**断言已就位**，再测暂停/继续。
const resetName=await page.evaluate(async()=>{
  const nm=()=>document.getElementById('vizNameM').textContent.trim();
  for(let i=0;i<8 && nm()!=='经典环谱';i++){ document.getElementById('vizNextM').click(); await new Promise(r=>setTimeout(r,160)); }
  return nm();});
console.log('  已切回模式：'+resetName+(resetName==='经典环谱'?'':'  ✗ 未回到经典环谱'));
const t1=await page.evaluate(()=>(document.getElementById('capTxt')||{}).textContent);
const box=await page.evaluate(()=>{const c=document.getElementById('cv').getBoundingClientRect();
  return {x:c.x+c.width/2,y:c.y+c.height/2};});
await page.mouse.click(box.x,box.y); await page.waitForTimeout(900);
const t2=await page.evaluate(()=>(document.getElementById('capTxt')||{}).textContent);
await page.mouse.click(box.x,box.y); await page.waitForTimeout(900);
const t3=await page.evaluate(()=>(document.getElementById('capTxt')||{}).textContent);
console.log('  点击前='+t1+' → 点击后='+t2+' → 再点='+t3+'  '+(t1!==t2&&t2!==t3?'OK 切换生效':'FAIL 未切换'));
// 声波参数
console.log('\n=== 声波参数按钮 ===');
// v0.04：声波参数改为**全屏功能层** #pmPop（#pmCard 只是弹层里的一张卡），
// 判据相应从卡片自身 display 改为弹层 display。
const before=await page.evaluate(()=>getComputedStyle(document.getElementById('pmPop')).display);
await page.click('#mParamBtn'); await page.waitForTimeout(600);
const after=await page.evaluate(()=>getComputedStyle(document.getElementById('pmPop')).display);
const live=await page.evaluate(()=>{const e=document.getElementById('centroidV');return e?e.textContent:'?';});
console.log('  点击前 display='+before+' → 点击后='+after+'  '+(before==='none'&&after!=='none'?'OK 弹出':'FAIL'));
console.log('  采集状态下参数实时值 centroidV='+live+'  '+(live&&live!=='—'?'OK 实时':'FAIL'));
await page.screenshot({path:path.join(out,'M-声波参数展开.png')});
await page.click('#pmPopClose'); await page.waitForTimeout(500);
const closed=await page.evaluate(()=>getComputedStyle(document.getElementById('pmPop')).display);
console.log('  再次点击收起='+closed+'  '+(closed==='none'?'OK':'FAIL'));
// 灵敏度
console.log('\n=== 灵敏度滑杆 ===');
const sens=await page.evaluate(()=>{const e=document.getElementById('sens');
  return e?{min:e.min,max:e.max,val:e.value,gain:(window.E&&E.gain)?'有':'-'}:null;});
console.log('  范围 '+sens.min+'~'+sens.max+'×，默认 '+sens.val+'×  '+(parseFloat(sens.min)===0.3&&parseFloat(sens.max)===6?'OK 0.3–6×':'FAIL'));
console.log('\npageerror:',errs.length?errs.join(' | '):'无');
await app.close();})().catch(e=>{console.error('ERR',e&&e.message);process.exit(3);});
