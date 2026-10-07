const path=require('path'),os=require('os');
const M=path.resolve(__dirname,'..'),D='D:/SynologyDrive/Workspace/AlphaSun-AudioSpectrumLab';
let pw=null;for(const c of [path.join(os.homedir(),'.workbuddy','binaries','node','workspace','node_modules','playwright-core'),path.join(D,'node_modules','playwright-core')]){try{pw=require(c);break;}catch(_){}}
(async()=>{delete process.env.ELECTRON_RUN_AS_NODE;
/* v0.04：electron 路径改用 tools/electron-path.js 统一解析（本仓库 → 桌面仓库 → 明确报错），
   不再硬编码桌面仓库路径 —— 硬编码会在本仓库装上 electron 后失效，也会给出误导性错误。 */
const app=await pw._electron.launch({args:[path.join(M,'index.html'),'--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream'],cwd:M,executablePath:require('./electron-path')(),timeout:60000});
const page=await app.firstWindow();await page.waitForTimeout(2000);
const cdp=await page.context().newCDPSession(page);
await cdp.send('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:2,mobile:true});
await page.waitForTimeout(800);
await page.click('#capBtn').catch(()=>{}); await page.waitForTimeout(2200);
const d=await page.evaluate(()=>{
  const R=e=>{const b=e.getBoundingClientRect();return{x:Math.round(b.x),y:Math.round(b.y),w:Math.round(b.width),h:Math.round(b.height),r:Math.round(b.right),bm:Math.round(b.bottom)};};
  const inter=(a,b)=>!(a.r<=b.x||b.r<=a.x||a.bm<=b.y||b.bm<=a.y);
  const pl=document.getElementById('plBox'), sw=document.getElementById('vizSwitch');
  const db=document.querySelector('.ov.db'), ck=document.querySelector('.ov.clock');
  const nodes=[...document.querySelectorAll('.pl-node')];
  // 每个 DSP 节点中心点是否被切换条盖住
  const covered=nodes.map(n=>{const b=n.getBoundingClientRect();const cx=b.x+b.width/2,cy=b.y+b.height/2;
    const el=document.elementFromPoint(cx,cy);
    return {t:n.textContent.trim().slice(0,4), occl: sw.contains(el)&&el!==n};});
  // 芯片行：哪些在视口内可见
  const tools=document.querySelector('.tools');
  const chips=[...tools.children].map(c=>{const b=c.getBoundingClientRect();
    return {t:(c.textContent||c.tagName).trim().slice(0,10), x:Math.round(b.x),
      inView:b.left<window.innerWidth&&b.right>0&&b.width>0};});
  const cv=document.getElementById('cv').getBoundingClientRect();
  // 中文字体判据修正（v0.03）：**不能只读 font-family 声明**。
  // 声明了自定义字体但文件 404 / 子集缺字时，浏览器会静默回退，读声明看不出问题。
  // 正确判据 = ① 家族名首位是内嵌艺术字体 ② document.fonts 里该家族 status=loaded
  //            ③ canvas 像素比对：用该字体与回退字体分别绘制同一串中文，像素必须不同
  //               （汉字的 advance width 恒为 1em，用宽度比对是**无效**的，见 tools/fontcheck.js）
  const h1=document.querySelector('.brand h1');
  const fam=getComputedStyle(h1).fontFamily.split(',')[0].replace(/["']/g,'').trim();
  let famLoaded=false; document.fonts.forEach(f=>{ if(f.family.replace(/["']/g,'')===fam && f.status==='loaded') famLoaded=true; });
  const draw=(family)=>{const c=document.createElement('canvas');c.width=300;c.height=60;
    const g=c.getContext('2d');g.fillStyle='#fff';g.font='900 34px '+family;g.textBaseline='top';
    g.fillText(h1.textContent,4,6);return g.getImageData(0,0,300,60).data;};
  const px=(a,b)=>{let n=0;for(let i=0;i<a.length;i+=4)if(a[i]!==b[i])n++;return n;};
  const fontPxDiff=px(draw("'"+fam+"'"), draw("'__NoSuchFont_X__','PingFang SC'"));
  return {pl:R(pl),sw:R(sw),db:R(db),ck:R(ck),
    pl_sw_overlap:inter(R(pl),R(sw)), db_ck_overlap:inter(R(db),R(ck)),
    covered, chips, toolsScrollW:tools.scrollWidth, toolsClientW:tools.clientWidth,
    cv:{w:Math.round(cv.width),h:Math.round(cv.height)},
    h1Font:fam, famLoaded, fontPxDiff,
    sensReachable:!!document.getElementById('sens') && !!document.getElementById('micSel'),
    /* v0.04：需求④把「输入设备 / 灵敏度」从声波参数弹层**再上移到顶栏第 2 行**(#rowCtl)，
       与可视化切换同排。判据随之改为"是否位于 #rowCtl 内"。 */
    sensInRow:(()=>{const c=document.getElementById('rowCtl'),s=document.getElementById('sens'),m=document.getElementById('micSel');
      return !!(c&&s&&m&&c.contains(s)&&c.contains(m));})(),
    /* v0.04 需求⑥：三块功能默认不得出现在主界面 */
    fnHidden:['fgBars','locCv','pmCard'].map(i=>{const e=document.getElementById(i);
      return {i,w:e?Math.round(e.getBoundingClientRect().width):-1};}),
    /* v0.04 需求⑦：底栏五连 */
    mbarBtns:['capBtn','mParamBtn','mFgBtn','mLocBtn','mGuardBtn'].map(i=>{
      const e=document.getElementById(i);const b=e?e.getBoundingClientRect():null;
      return {i,x:b?Math.round(b.x):-1,h:b?Math.round(b.height):-1};}),
    helpInFooter:(()=>{const f=document.querySelector('footer'),h=document.getElementById('helpBtn');
      return !!(f&&h&&f.contains(h)&&getComputedStyle(f).display!=='none');})()};
});
console.log('【缺陷1】DSP链 vs 切换条重叠:', d.pl_sw_overlap?'是 ✗':'否');
console.log('   DSP节点被遮挡:', JSON.stringify(d.covered.filter(c=>c.occl).map(c=>c.t)));
console.log('【缺陷2】电平表 vs 时间重叠:', d.db_ck_overlap?'是 ✗':'否', '| db',JSON.stringify(d.db),'ck',JSON.stringify(d.ck));
console.log('【缺陷3】中文字体:', d.h1Font, '| loaded=',d.famLoaded, '| 像素差=',d.fontPxDiff,
  (d.famLoaded&&d.fontPxDiff>200)?'✓ 内嵌艺术字体真实生效':'✗ 已静默回退');
console.log('【缺陷4】芯片行: 可视宽',d.toolsClientW,'内容宽',d.toolsScrollW);
d.chips.forEach(c=>console.log('     ',c.inView?'可见':'被藏', c.t));
console.log('【缺陷4b】多麦克风: 设备选择器+灵敏度已挂载 =', d.sensReachable, '| 位于顶栏第2行(#rowCtl) =', d.sensInRow,
  (d.sensReachable&&d.sensInRow)?'✓ 一键可达（原被挤到屏外）':'✗');
console.log('【v0.04-⑥】三块功能默认不在主界面:', JSON.stringify(d.fnHidden),
  d.fnHidden.every(x=>x.w===0)?'✓ 均为 0 宽（仅按钮点击后才弹出）':'✗ 仍有常驻');
console.log('【v0.04-⑦】底栏五连:', JSON.stringify(d.mbarBtns),
  (d.mbarBtns.length===5&&d.mbarBtns.every(b=>b.h>=44))?'✓ 5 个且均 ≥44px':'✗');
console.log('【v0.04-②】说明在页脚（软件底部）:', d.helpInFooter?'✓':'✗');
console.log('【可视化】canvas', d.cv.w+'×'+d.cv.h, '| 环谱直径受限于 min=',Math.min(d.cv.w,d.cv.h),'→ 纵向浪费',d.cv.h-Math.min(d.cv.w,d.cv.h),'px');
await app.close();})().catch(e=>{console.error('ERR',e&&e.message);process.exit(3);});
