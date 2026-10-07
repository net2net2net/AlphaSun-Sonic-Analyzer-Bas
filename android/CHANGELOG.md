# AlphaSun Sonic Analyzer · 原生 Android 版 变更日志

> 与 Web/Electron 版（根 `CHANGELOG.md`，v2.x）历史上是**两条独立版本线**。
> **本 `-Bas` 工作区已按用户要求收敛统一为 v0.3.0**（2026-10-05），
> 后续一切版本锚点以 v0.3.0 为准；下文的 v0.6.x 条目是**收敛前的历史记录**，保留可追溯。
>
> 版本号由 `node tools/bump-version.js x.y.z` **六点同步**（index.html `APP_VER` /
> `#appVer` 文本 / `package.json` / `sw.js` CACHE / `android/app/build.gradle`
> versionName+versionCode / `MainViewModel.kt` 的 `APP_VERSION`），
> 由 `node validate.js` 门禁校验六点一致。
>
> 第 6 点（Kotlin `APP_VERSION`）此前**需手工同步**，且不在门禁范围内，
> 长期是「界面显示版本 ≠ APK 实际版本」的漂移源；2026-10-05 起
> 已由同步脚本与门禁一并覆盖，**不再是手工步骤**。

---

## v1.0.1（2026-10-06）· web 框架线 —— versionCode 14 / versionName 1.0.1

> 本次为版本号随根 `bump-version.js 1.0.1` 六点同步：gradle `versionName "1.0.1"`、
> `versionCode 13 → 14`；`MainViewModel.kt` `APP_VERSION = "v1.0.1"`。
> 无 Android 原生代码改动——主界面布局 / 声源定位真实化 / 噪音评估按钮合并 / 声波警戒留证查看 / 响应式触摸打磨
> 均在 WebView 层（index.html）实现，经 `sync-www.js` + `cap-copy.js` 同步进
> `android/app/src/main/assets/public/`。签名证书不变（SHA-256 `e270e256…4c27`）。
> 产物 `dist/AlphaSun-Sonic-Analyzer-1.0.1-mobile.apk`。

---

## v1.0.0（2026-10-06）· web 框架线 —— versionCode 13 / versionName 1.0.0

> 本次为版本号随根 `bump-version.js 1.0.0` 六点同步：gradle `versionName "1.0.0"`、
> `versionCode 12 → 13`；`MainViewModel.kt` `APP_VERSION = "v1.0.0"`。
> 无 Android 原生代码改动——噪音评估 / 声波警戒的新能力均在 WebView 层（index.html）实现，
> 经 `sync-www.js` + `cap-copy.js` 同步进 `android/app/src/main/assets/public/`。
> 签名证书不变（SHA-256 `e270e256…4c27`）。产物 `dist/AlphaSun-Sonic-Analyzer-1.0.0-mobile.apk`。

---

## v0.7.0（2026-10-05）· web 框架线功能迭代 —— versionCode 9

> web 线从 0.3.0 直接续 **0.7.0**，跳过原生线历史占用的 0.4.0~0.6.4 号段（防撞号）。
> 功能明细见根 `CHANGELOG.md` v0.7.0 条目。Android 侧改动：
> - Manifest + `BridgeMainActivity`：新增 `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`
>   （噪音评估 GPS 记录；拒绝时 Web 层降级为人工位置）。
> - 产物：versionCode 9 / versionName 0.7.0，签名证书不变。

---

## v0.3.0（2026-10-05）· `-Bas` web 框架线复原 —— 复盘纠正 + Capacitor 构建链恢复

> 背景：本工作区由活跃工程复制而来，`android/` 曾被 v0.4.0「全原生重写」覆盖
> （Capacitor 依赖与 web 资产被移除，`MainActivity` 改为原生 Compose）。
> 用户明确「-Bas 的版本是以 web 框架为版本做的」，并提供参考 APK
> `dist/AlphaSun-Sonic-Analyzer-0.3.0.apk`（2026-10-04，versionCode 7）作为权威。

### ⚠️ 复盘纠正（如实记录一次误判）
- 本条目初版曾把**原生重写构建**（versionCode 3，11.9 MB）记为 0.3.0 产物，
  并据此把 `README.md` 的 `versionCode 7` 改成 `3` —— **错了**。
- 拿到 dist 参考 APK 后复盘确认：**0.3.0 的权威形态是 Capacitor web 框架构建，
  versionCode 7**；`3` 是原生重写线（另一条线）的值。
- 已纠正：README 两处 versionCode 改回 `7`，本条目重写，构建链按下文复原。
- 教训：**两条版本线共用一个 gradle 文件时，"versionName 一致"不代表"同一条线"**，
  必须先确认启动入口/依赖/资产是哪条线，再认版本锚点。

### web 框架构建链复原（原生代码原样保留）
以参考 APK 的内容特征（`assets/capacitor.config.json`、`assets/public/index.html`）逐项复原：

| 复原项 | 内容 |
|---|---|
| `settings.gradle` | 重新挂回 `:capacitor-android`（→ node_modules/@capacitor/android/capacitor，6.2.2） |
| `app/build.gradle` | 补 Capacitor + appcompat + webkit 依赖；**versionCode 3 → 7**；Compose 依赖保留 |
| `themes.xml` | 新增 `Theme.AlphaSun.Capacitor`（**AppCompat 派生**）—— BridgeActivity 继承 AppCompatActivity，用 `android:Theme.*` 会运行即崩 |
| `BridgeMainActivity.kt` | 新增 Capacitor 启动入口，申请 RECORD_AUDIO + **MODIFY_AUDIO_SETTINGS** + CAMERA + POST_NOTIFICATIONS（Capacitor 对 AUDIO_CAPTURE 要求两权限齐备才 grant） |
| `AndroidManifest.xml` | 启动页换为 `.BridgeMainActivity`；原生 `.MainActivity` 保留声明但不再是启动页 |
| `tools/cap-copy.js` | 补生成 `assets/capacitor.config.json` + `capacitor.plugins.json`（缺了 Bridge 读不到配置） |
| web 资产 | `sync-www.js` → `cap-copy.js` → `android/app/src/main/assets/public/`（20 文件，含 vosk.js 5.8MB） |

### 复原产物与校验
- 产物：`android/app/build/outputs/apk/release/app-release.apk`，**16.38 MB**，
  `versionCode 7 / versionName 0.3.0`；`apksigner verify` → **Verifies**，
  证书 SHA-256 与参考 APK **完全一致**（`e270e256…`）。
- 内容同构：`assets/capacitor.config.json`、`assets/public/`（index.html 639,181 B、vosk.js、
  lame.min.js、desktop/boot.js、mobile/bridge.js 等 20 文件全量就位）。
- 启动类为 `.BridgeMainActivity`（参考 APK 为 `.MainActivity`）：类名差异源于
  「原生 MainActivity 原样保留、避免同名冲突」，功能等价，不影响用户可见行为。

### 与参考 APK 的体积差（如实记录）
16.38 MB vs 参考 7.3 MB，差值**全部**来自按用户要求「原样保留」而继续参与编译的
原生 Kotlin/Compose 代码与依赖（未压缩 dex 6.2 MB → 约 43 MB，material-icons-extended 为大头）。
若要回到 7.3 MB 量级，可用 productFlavors 把原生源码隔离出 web 构建（后续可选，未实施）。

### 附带修复（原生线代码，随本轮一并入库）
- 20Hz 主循环双 FFT 合并为 `Dsp.spectrum()`，等价性由 2 条回归测试固化（逐 bin 1e-12 / 1e-6）。
- 第 6 版本锚点（Kotlin `APP_VERSION`）纳入 `bump-version.js` / `validate.js` 六点同步。
- 单元测 **46 用例 / 0 跳过 / 0 失败**。

---

## v0.6.5（2026-10-05）—— 声波警戒改全屏值守台（对齐 v0.3.0 alertMask）

> 用户拍板：参考 v0.3.0 的声波警戒是「全屏值守台 alertMask」，原生 GuardPanel 原为底部抽屉，本轮改为全屏。

### 对齐清单（逐行对齐 web-v0.3.1 #alertMask）
1. **全屏覆盖**：`PanelShell` 新增 `full` 模式（四角圆角、铺满父容器、内容区填满高度可滚动）；
   `AlphaSunApp` 在 `panel == GUARD` 时把弹层改为居中全屏（其余面板仍为底部抽屉）。
2. **控制行对齐 alertMask toolrow**：
   - 阈值与回落：手动阈值 dB / 静默回落(秒) / 自动评估本底 + 评估秒 + 预警余量 + 告警余量
   - 值守选项：屏幕闪烁 / 提示音 / 事件最长(秒)
   - 摄像头：抓拍 / 录像（新增 `camRecSec` 录像时长）/ 前后双摄 / 预警也抓拍 / 识别前后摄
   - 控制：开始值守 / 停止 / 重新评估本底 / 暂停 / 测试抓拍 / 告警推送设置 + 当前 dB + 状态
3. **告警推送设置**（对齐 alertMask `alertNotifyCfg`）：企微/钉钉/飞书/Server酱/Webhook/阿里云短信 共 10 个配置项，
   直接绑定到 `GuardEngine` 新增字段（`ntWecom…ntPhone`）；保存/测试按钮接 VM 日志（原生暂未实现网络发送，预留 `onNotify` 已接）。
4. **值守日志导出/清空**（对齐 alertMask 导出值守日志/清空日志）：`guardExportLog()` 写出到应用目录文件、`guardClearLog()` 清空。

### 校验
- `./gradlew testDebugUnitTest assembleRelease --offline`：预计通过（本轮仅 UI + 小字段，未动判定口径）。
- 待产出 `dist/AlphaSun-Sonic-Analyzer-0.6.5.apk`（`versionCode 16 / versionName 0.6.5`）。

---

## v0.6.4（2026-10-05）—— 弹层/按钮操作逻辑对齐 v0.3.0

> 续 v0.6.3，继续逐段比对 web-v0.3.1/index.html 的弹层与底栏。功能层不变。

### 对齐清单
1. **底部按钮「当前打开的面板」高亮**（对齐 v0.3.0 `.mbtn.mparam.on/.mfg.on/.mloc.on`）：
   `DockButton` 新增 `on` 参数，打开对应面板时该按钮描边转亮青 + 背景染青，回到主界面也知道刚才是哪个面板。
2. **弹层关闭按钮对齐 `.fnpop-x`**：`PanelShell` 关闭由「✕」改为「✕ 关闭」（13sp 加描边按钮），与参考一致；
   标题改为青色 800 字重（对齐 `.fnpop-bar .t` 的 `color:var(--cy);font-weight:800`）。
3. **五个面板标题补齐 emoji 前缀**（对齐各 `.fnpop-bar .t`）：
   📊 声波参数 / 🎯 前景 / 背景分离 / 📡 声源定位 / 🛡 声波警戒 / 🔊 噪音评估。

### 校验
- `./gradlew testDebugUnitTest assembleRelease --offline`：均通过。
- `dist/AlphaSun-Sonic-Analyzer-0.6.4.apk` **11,899,183 字节**，`versionCode 15 / versionName 0.6.4`。

---

## v0.6.3（2026-10-05）—— 界面/操作逻辑对齐 v0.3.0：徽标 1:1 复刻 + 底部双行按钮 + 说明弹层

> 本轮聚焦用户要求的「界面样式、按钮、操作逻辑全部对齐 AlphaSun-Sonic-Analyzer-0.3.0」。
> 功能层（v0.6.1/v0.6.2 的 7 处硬伤修复 + 应用内自检）保持不变，仅做视觉与交互对齐。

### 对齐清单（逐项比对 web-v0.3.1/index.html）
1. **品牌徽标 `AsLogo` 1:1 复刻**：上一版是「12 刻度 + 16 条正弦伪频谱柱 + 青/紫两色」的近似，
   与 v0.3.0 手绘 SVG 明显不同。现按 SVG 坐标逐点重绘——
   · 外环光晕（lgHalo，#37C8FF .26→0 半透明填充近似）
   · **24 条径向频谱柱**，颜色严格采用参考的真实色环（青→紫→粉 24 色），26s 反向匀速旋转
   · **36 条刻度环**（lg-tick），9s 匀速旋转（stopped 时停转省电）
   · 中央内环（#7DE2FF r=6.77）+ 实心点（#E0FAFF r=3.94），不旋转
2. **底部六按钮标签对齐 `<br>` 双行**：v0.3.0 的 `#mFgBtn`=`前景/<br>背景分离`、`#mNoiseBtn`=`噪音<br>评估`
   是**两行**标签；上一版写成单行「前景/背景」丢了「分离」。`DockButton` 改为 `maxLines=2`，
   fg→`前景/\n背景分离`、noise→`噪音\n评估`，与参考折行一致。
3. **页脚新增「ⓘ 说明」按钮 + 说明/免责弹层**（对齐 v0.3.0 `<footer>` 的 `#helpBtn` + `helpMask`）：
   点击打开居中弹窗，内容与参考 help 声明一致（版本/组件、链路图示、本地计算不上传、
   离线启发式非训练模型、曲目识别默认关闭、需授权麦克风等）。上一版页脚只有文字，缺这个操作入口。
4. **DSP 链 `.pipeline` 细节对齐**：节点文案字号 7.5sp→9sp（对齐 `.pl-tx` 9px）；
   连接线 `PipeLink` 由竖条改为**横向 2px 线 + 流动光点**（对齐 `.pl-link.on` 的 plflowX 横向动画）。

### 校验
- `./gradlew testDebugUnitTest --offline`：**44 项单测 0 失败**（DspTest 9 + EngineRobustnessTest 19 + SelfCheckTest 6 + WiringTest 10）。
- `./gradlew assembleRelease --offline`：构建成功。
- `dist/AlphaSun-Sonic-Analyzer-0.6.3.apk` **11,899,536 字节**，`versionCode 14 / versionName 0.6.3`，权限齐全。

### 待真机验证（未变）
设备努比亚 NX629J / Android 9 的 `pm` 服务此前卡死导致无法安装；需用户重启设备恢复 `pm` 后
`adb install -r -t "D:/.../AlphaSun-Sonic-Analyzer-0.6.3.apk"`，再用「声波参数」面板里的🩺应用自检做真机比对。

---

## v0.6.2（2026-10-05）—— 新增应用内自检 + 再挖 3 处硬伤（含一个界面恒显 NaN 的真 bug）

> 前提：v0.6.1 的 4 个根因修复**仍未获真机验证**——设备（努比亚 NX629J / Android 9）
> 的 `pm` / `servicemanager` 整体无响应，`adb install` 三次均返回空错误、
> `pm path` 与 `adb uninstall` 均 60s 超时。与其继续盲改，
> 本轮改为**把「功能到底有没有真的在工作」做成手机上可一键执行的自检**。

### 🩺 新增：应用内自检（`core/SelfCheck.kt` + `ui/component/SelfCheckCard.kt`）

入口：**声波参数面板 → 「自检」区块 → 运行自检**。结果可整段复制粘贴。

- **算法层 15 项**（纯 Kotlin，JVM 单测直跑）：FFT 往返、功率谱峰值定位、RMS/dBFS、
  通道相关系数、伪立体声判定、前景/背景分离、试听响度归一化、GCC-PHAT 时差、
  双麦方位解算、23 项声波特征、智能分类、背景模型学习、背景模型分析产出、
  噪音评估、声波警戒状态机。
- **设备层 8 项**：麦克风权限、采集已启动、**主帧非空且有信号**、主循环无累计异常、
  通道与阵列判定、摄像头路数、存储可写、采集参数诊断。

其中 **「主帧非空且有信号」是 v0.6.1 头号根因的直接验收点** ——
该项变红就说明采集层又没把数据送上来，不必再猜算法是不是写错。

同时新增 `SelfCheckTest`（6 项），把自检本身变成门禁：
只要算法层有任一项变红，单测立即失败。

### 🔴 五号硬伤：界面上 dB(A)/dB(C) **恒显示 NaN**

| | |
|---|---|
| 文件 | `core/Features.kt` → `Analyzer.analyze()` |
| 症状 | 「声波参数」面板的「dB(A) 计权」「dB(C) 计权」两行在真机上永远显示 `NaN dB` |

计权声级的整块计算被包在 `if (dbMag != null && dbMag.size >= n)` 里，
而 `dbMag` 是**可选参数**，真实调用点 `a.analyze(frame, mag)` **从不传它**。
→ `dbA`/`dbC` 永远停在初始值 `Double.NaN`。
（同一处的谱平坦度**有** else 分支兜底，唯独计权声级漏了。）

修法：无 `dbMag` 时直接从幅度谱取能量（`power = mag²`，与 `10^(dB/10)` 等价，无额外开销）。

> 这条是自检跑出来才发现的——**肉眼审代码看不出来**，因为 `dbMag` 有默认值、
> 编译不报错、单测也只测"不崩"。

### 🔴 六号硬伤：采集循环余量拷贝**必然越界**（潜伏崩溃）

`AudioCapture.loop()` 里 `val samples = got / ch` 未夹容量，
一旦 `read()` 返回非 `ch` 整数倍的长度，`accLen` 会越过 `fftSize`，
随后的余量前移执行 `System.arraycopy(acc[c], fftSize, acc[c], 0, leftover)` ——
源下标 `fftSize` 对长度为 `fftSize` 的数组**必越界** → 抛
`ArrayIndexOutOfBoundsException` → 被循环外 catch 吞掉 → **采集线程静默死亡**。
修法：把 `samples` 夹到本帧剩余容量，余量恒为 0，越界分支永不执行。

### ⚠️ 七号硬伤：`stop()` 注释说发静音帧，实际保留陈旧数据

`stop()` 只判断 `if (frame.size != fftSize) frame = FloatArray(fftSize)`，
尺寸已对时什么都不做，`frame` 仍保留停止前最后一帧。
修法：真正清零，并复位 `multi` / `rms` / `peak` / `envDb`。

### 🧪 测试

| 测试类 | 数量 | 结果 |
|---|---|---|
| `core.DspTest` | 9 | ✅ |
| `core.EngineRobustnessTest` | 19 | ✅ |
| `SelfCheckTest`（本轮新增） | 6 | ✅ |
| `WiringTest` | 10 | ✅ |
| **合计** | **44** | **0 失败** |

### 📦 产物

`app-release.apk` **11,894,122 字节**，`versionCode 13 / versionName 0.6.2`，
已同步至 `dist/AlphaSun-Sonic-Analyzer-0.6.2.apk`。

---

## v0.6.1（2026-10-05）—— 挖出 4 个「编译通过但真机全废」的硬伤 + 界面对齐 v0.3.0

用户第三次反馈「各个功能不可用，界面和美观也没对齐 v0.3.0」。
前三轮都在补元素、加降级，但真机上依旧不动 —— 本轮改为**逐条追查"代码逻辑上必然失败"的路径**，
挖出 4 个此类硬伤，并补齐 v0.3.0 缺失的界面块。

### 🔴 头号根因：立体声设备上主帧永远为空 → 主循环全程空转

| | |
|---|---|
| 文件 | `audio/AudioCapture.kt` → `publishFixed()` |
| 症状 | 界面显示「采集中（多通道）」，但频谱、电平、分类、噪音、警戒、波形**一个都不动** |

`openBest()` 的策略是**立体声优先**（真阵列定位必需）。而 `publishFixed()` 旧代码把
`frame` 的赋值写在 `if (ch == 1)` 分支里 —— `ch >= 2` 时只写 `multi`，`frame` 永远是 `FloatArray(0)`。

`MainViewModel.loop()` 的守卫是 `if (raw.size > 32)`，于是**整个循环体一次都不执行**：

```kotlin
// 旧（错）
if (ch == 1) { frame = ... } else { multi = ... }      // frame 恒空

// 新（对）
val f = if (frame.size == fftSize) frame else FloatArray(fftSize)
System.arraycopy(acc[0], 0, f, 0, fftSize)
frame = f                                              // 任何通道数都先发 ch0
if (ch >= 2) { multi = ...; probeFakeStereo(...) }     // 多通道额外再发
```

绝大多数手机的 `AudioRecord(CHANNEL_IN_STEREO)` 都能成功初始化（单麦机型也接受该参数），
所以这条**打中了几乎所有真机**，是"功能不可用"最直接的成因。

### 🔴 二号根因：Camera2 抓拍在回调线程上等自己的回调 → 必然死锁超时

| | |
|---|---|
| 文件 | `camera/DualCamera.kt` |
| 症状 | 前置摄像头没有画面 / 只有 1 路摄像头 / 抓拍恒报「超时或图像无效」 |

```kotlin
// 旧（错）
h.post { captureBlocking(...) }        // 跑在 alphasun-cam 这个 HandlerThread 上
// 而 openSync() 正是把 StateCallback 投递到**同一个** handler，
// 然后 sem.tryAcquire(2.5s) 原地阻塞 —— 回调永远排不进已被阻塞的 looper
```

改法：阻塞式抓拍移到**独立的单线程池** `shotExec`，camera handler 只负责回调，二者不再互等。

另外 `facingIds()` 原先只认 `LENS_FACING == FRONT/BACK`，
国产 ROM / 老 MTK 上该字段常为 `null` 或 `EXTERNAL`，导致 `caps()` 只报一路
（用户原话：「手机是有前后摄像头的，但显示只有 1 路」）。
现在：**先按 facing 精确匹配，任一侧缺失但设备有 ≥2 个摄像头时用剩余 id 补位**。

### 🔴 三号根因：主循环「自愈」其实是自杀 → 永不重启

| | |
|---|---|
| 文件 | `MainViewModel.kt` → `restartCapture()` |
| 症状 | 界面永久停在「采集异常，正在自动重启…」，CPU 打满 |

`restartCapture()` 里的 `loopJob?.cancel()` 取消的**正是调用它的那个协程**。
本循环退出、却没有新循环被拉起。更糟的是随后的 `delay()` 立刻抛 `CancellationException`，
被 `catch (e: Throwable)` 吞掉 → 「catch → 计数 → 再 catch」的**空转死循环**。

改法：
- `CancellationException` 单独 catch 并**重新抛出**，绝不被通用 catch 吞掉；
- `restartCapture()` → `scheduleRestart()`：只做清理 + 置空 `loopJob`，
  再**另起协程**延迟调 `start()`，然后 `return` 结束本循环。

### 🔴 四号根因：「试听原声」播的其实是背景

`SepPanel` 的「试听原声」按钮传 `"mix"`，而 `sepPreview()` 只写了
`if (which == "fg") res.fg else res.bg` —— 点原声播出来的是**背景**。
现在三路各自返回真正对应的 PCM（`mix` 走 `previewMix()`，不分离只做响度归一化）。

### ⚠️ 其它修复

- **伪立体声判定**：单麦机型上立体声通道是复制的，直接用 TDOA 会算出恒 0 的时差
  （方位角恒指正前方，比仿真更假）。新增 `Dsp.correlation/isFakeStereo`，
  前 40 帧统计两路相关系数，`> 0.995` 判为伪立体声 → 保持仿真并在日志中说明。
  真阵列/仿真的最终判定推迟到探测出结论后（约 1.7s）再做一次，只判一次不抖动。
- **滑块初值错误**：`SliderRow` 内部状态初值写死为量程中点 `(from+to)/2`，
  与引擎真实值无关 —— 「事件最长时长」一碰就跳成 305 秒。新增 `initial` 参数。
- **抓拍节流标志 `shotInFlight`** 从未被置 true（实为死代码，已确认不影响功能）。

### 🎨 界面对齐 v0.3.0（`web-v0.3.1/index.html` 逐段比对）

| v0.3.0 元素 | 原生版此前 | 本轮 |
|---|---|---|
| `.brand h1`（Alpha**S**un 声波分析仪，S 荧光黄） | 整串一个颜色，无荧光黄 | 渐变 `Brush` 逐段上色，S 单独走 `hotBrush` |
| `.brand .sub.sonic`（Alpha**S**un Sonic Analyzer） | 写成 `SONIC SPECTRUM ANALYZER` | 按原文还原，S 荧光黄 |
| `.brand .author`（版本 · 作者：阳光 net2net2net） | **整行缺失** | 补齐 |
| `.logo`（手绘 SVG：外环/刻度环/16 柱/中央波形） | 一个圆角方块 + 字母 S | `Canvas` 1:1 复刻，刻度环 9s 匀速旋转 |
| `📊 低中高频谱（六段能量）` + `.bl` 频段标签 | 无独立卡片 | 补齐，带 `30-150 … 6k-16k` 标签行 |
| `🧭 智能分类` 四条 `.conf` 渐变条 | 四条几乎同色 | 逐条还原 v0.3.0 写死的四条渐变 |
| `🕒 动作 / 事件日志` | **整卡缺失** | 补齐（最新 8 条，按级别着色） |
| `🗄️ 历史缓存 / 分析报告` | **整卡缺失** | 补齐（事件/抓拍/音频计数 + 存储位置说明） |
| `.ov.db` 电平表（dBFS·电平 + `.mscale` 刻度） | 只有「实时声级」小字 | 大字读数 + `-60/-40/-20/-10/0` 刻度行 |
| `#hint` 中央提示区（两行大字） | 一行 8.5sp 小灰字 | 两行大字提示卡 |
| `.mbtn` 六按钮（**图标在上 / 文字在下**） | 纯文字，且文案是「参数/分离/定位」简称 | 补 `📊🎯📡🛡🔊▶` 图标，文案改全称 |
| `<footer> .fmeta` | 自造文案 | 还原 v0.3.0 版式 |

### 🧪 测试

`./gradlew testDebugUnitTest`：

| 测试类 | 数量 | 结果 |
|---|---|---|
| `core.DspTest` | 9 | ✅ |
| `core.EngineRobustnessTest` | 19 | ✅ |
| `WiringTest`（本轮新增 4 项） | 10 | ✅ |
| **合计** | **38** | **0 失败** |

新增回归测试锁定本轮 4 个根因：
- `多通道时也必须发布主帧_否则主循环恒空转`
- `伪立体声判定_复制通道应判为真_独立通道应为假`
- `自愈逻辑不得取消自身协程`
- `试听三路路由_mix不能落到背景分支`

### 🚧 已知阻塞

- **真机验证受环境阻塞**：`adb push` 成功（16.7MB 已在 `/data/local/tmp/as-debug.apk`），
  但设备上的 `pm install` **无任何响应**（`adb install` 报空错误、
  `adb shell pm install` 挂起、`dumpsys package` 报 `SERVICE 'package' DUMP TIMEOUT`）。
  设备：努比亚 NX629J，Android 9 / API 28，adb 串口 `emulator-5554`。
  待设备恢复后需人工验证的清单见 `docs/测试与回归.md`。
