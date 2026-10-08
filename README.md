# AlphaSun Sonic Analyzer 声波分析仪（移动版）

> **本仓库是独立软件**，从 `AlphaSun-AudioSpectrumLab`（桌面版）派生，**与桌面版互不影响**。
> 包名 `com.alphasun.sonicanalyzer`，与桌面版 `com.alphasun.audiolab` **并存安装、互不覆盖**。
> 
 AlphaSun 声波分析仪 · Sonic 移动版本（ 支持Android APK / iOS IPA），主要功能包含： 
1、声波频谱的多维分析，包含诸多声波参数，其中彩虹环型频谱在无聊的时候也可以带来一定的治愈和情绪价值；
2、噪音评估，可以为环境按标准进行噪音评估，并提供分贝曲线和数据记录处理能力；
3、声波警戒，主要是站在手机安全的角度，根据声音的异常变化、手机位移等去告警记录；
4、声源定位和声音的前景/背景分离还在不断完善中。
---

## 〇、下载与仓库

**当前版本：v1.2.0（versionCode 17 / CFBundleVersion 17）**

| 平台 | 产物 | 说明 |
|---|---|---|
| Android | `AlphaSun-Sonic-Analyzer-1.2.0-mobile.apk` | 已签名直装，armeabi-v7a/arm64-v8a |
| iOS | `AlphaSun-Sonic-Analyzer-1.2.0-ios-unsigned.ipa` | iPhone/iPad 通用，**未签名**，需重签后安装 |

**发布页（含全部历史版本附件）**

| 平台 | Release |
|---|---|
| GitHub | https://github.com/net2net2net/AlphaSun-Sonic-Analyzer-Bas/releases/tag/v1.2.0-sonic |
| Gitee | https://gitee.com/net2net2net/alpha-sun-sonic-analyzer-bas/releases/v1.2.0-sonic |

**源码仓库（双平台同步，内容一致）**

| 平台 | 地址 |
|---|---|
| GitHub | https://github.com/net2net2net/AlphaSun-Sonic-Analyzer-Bas |
| Gitee | https://gitee.com/net2net2net/alpha-sun-sonic-analyzer-bas |

分支：`master` / `ios-1.0.1`（两平台同内容同步）。发布页见仓库「发行 / Releases」区，
各版本 APK 与未签名 IPA 均在 Release 附件中。

**iOS 安装说明**：未签名 IPA 不能直接装真机。在 macOS 上重签两种方式：
1. 本地 `bash tools/resign-ios.sh`（需 p12 证书 + mobileprovision 描述文件）
2. GitHub Actions 签名版工作流 `iOS Build (Ad-Hoc 签名)`，配置 6 个 secrets 后一步出可装包

详见 [`docs/iOS-macOS-构建指南.md`](docs/iOS-macOS-构建指南.md) 与 [`docs/IOS-BUILD.md`](docs/IOS-BUILD.md)。

---

## 一、与桌面版的差异

| 项目 | 桌面版 | 移动版（本仓库） |
|---|---|---|
| 包名 | `com.alphasun.audiolab` | **`com.alphasun.sonicanalyzer`** |
| 应用名 | AlphaSun 声波分析仪 | **AlphaSun Sonic Analyzer** |
| 版本 | v2.24.1 | **v1.2.0**（versionCode 17） |
| 二级分析台 | 有 | **已下线** |
| 音频工具集 | 有（转写/环境采集/值守） | **已下线**，仅保留**警戒值守**（改名，提到主操作条） |
| CPU / 内存 展示 | 有 | **已移除**（移动端无系统级权限，原本恒为 —） |
| 多麦克风选择 | 顶栏芯片位 | **保留**，与可视化切换同排于顶栏第 2 行 |
| 主按钮位置 | 顶栏 | **底部固定条**，同排五连（单手拇指可达区，46px） |
| DSP 处理链 | 竖排（浮在画面内） | **横排可滑动，且移出画面独立成顶栏第 3 行** |
| 智能分类 | 有 | **已下线** |
| 参数中心 | 常驻卡片 | **底部按钮点击弹出全屏功能层**（采集时实时） |
| 可视化切换 | 顶部工具栏 | **顶栏第 2 行**，与输入设备/灵敏度同排 |
| 软件名呈现 | 纯文字 | **手绘 SVG 声波环谱徽标** + 中英双行艺术字（矢量，任意分辨率不糊） |
| 软件名字体 | 系统字体 | **Orbitron（英文）+ HarmonyOS Sans SC 子集（中文）**，本地内嵌、离线可用 |
| 前景/背景分离 · 声源定位 | 常驻右侧卡片 | **不常驻主界面**，改为底部按钮点击弹出全屏功能层 |
| 页脚 / 说明 | 有 | **保留并下移「说明」入口到软件底部**（页脚带 62px 下内边距让开固定底栏） |

## 二、功能

- **实时声波采集与频谱可视化**（7 种效果，Canvas 驱动，点击中央环暂停/继续）
- **顶栏三行布局**（v0.04）：
  - 第 1 行 `#rowParams`：引擎 / 输入电平 / 舒适度 / 采样率 / FFT（只读芯片）
  - 第 2 行 `#rowCtl`：可视化切换 ◀ 名称 ▶ · 输入设备 · 灵敏度（0.3×～6×）
  - 第 3 行 `#plBox`：DSP 处理链横排 —— 声波采集 → 信号预处理 → 特征提取 → 分类解码 → 后处理输出
- **电平表 / 毫秒时钟 / BPM / 采集状态**浮在可视化区内，画面内不再压任何控件
- **三个按需弹层**（点底部按钮才打开，主界面零占位）：
  - 📊 声波参数：频谱域 · 时域电平 · 音高节奏（点击查看介绍、实时值与历史曲线）
  - 🎯 前景/背景分离：谱减法估计背景噪声谱并减去，输出前景占比/电平/抑制量/平稳性
  - 📡 声源定位：GCC-PHAT 时差 + 双曲线交汇，输出方位角/距离/定位质量
- **警戒值守**：阈值触发声光警报 · 事件日志 · 暂停/继续 · 日志导出与清理（应用内确认框）
- 六段频谱能量 / 事件日志 / 历史缓存与报告导出

## 三、界面结构

```
┌─────────────────────────────┐
│ (徽标) AlphaSun声波分析仪    │  ← 手绘 SVG 环谱徽标 + 中英双行艺术字
│        AlphaSun Sonic Analyzer│
│        v0.04 · 作者：阳光 …  │  ← 版本 + 作者（构建号见「说明」）
├─────────────────────────────┤
│ 引擎 输入 舒适 SR FFT       │  ← 第1行 只读参数芯片（横滑，右缘渐隐）
├─────────────────────────────┤
│ [◀经典环谱▶] [🎙输入设备▾]  │  ← 第2行 可操作控件
│ [🎚灵敏度 ────●──── 1.0×]   │
├─────────────────────────────┤
│ 🎙采集→🧹预处理→📈特征→…    │  ← 第3行 DSP 链（横排可滑）
├─────────────────────────────┤
│ -7.3dB          03:29:23     │  ← 读数浮层（在画面内上部）
│      声波可视化（满宽）       │  ← 经典环谱/频谱柱/示波器/极坐标/
│      点击中央环 = 暂停/继续   │     时域波形/FFT频谱/瀑布图
│  BPM ────────● 环谱静默      │
├─────────────────────────────┤
│ 六段能量 / 事件日志 / 历史缓存 │  ← 次要卡片，纵向可滚动
├─────────────────────────────┤
│ AlphaSun … v0.04      ⓘ 说明 │  ← 页脚（「说明」入口在软件底部）
├─────────────────────────────┤
│[采集][📊参数][🎯前景/背景]   │  ← 底部固定条：同排五连，46px
│[📡声源定位][🛡警戒值守]      │     图标在上 / 文字在下
└─────────────────────────────┘
```

## 三.5、版本号规则（v0.1.0 起）

版本号采用 **主版本.次版本.修订号**（SemVer 变体）：

| 位 | 何时递增 | 本项目示例 |
|---|---|---|
| **主版本** (x) | **功能性新增 / 架构性改动**，或有不兼容变更 | `0.4.0 → 1.0.0`：新增「噪音评估」整套功能、新增双摄抓拍 |
| **次版本** (y) | **已有功能的显著增强**（新增选项、新增参数、新增导出格式），向后兼容 | `1.0.0 → 1.1.0`：雷达图清晰化重写、分离试听链路 |
| **修订号** (z) | **修缺陷、优化、小幅调整**，不影响功能与接口 | `1.1.0 → 1.1.1`：修复作者行溢出、修复弹层级 |

**当前版本：v1.2.0（versionCode 17）** —— web 框架线从 0.3.0 跳续 0.7.0 → 0.8.0 → 0.9.0 → 0.9.1 → 1.0.0 → 1.0.1 → 1.0.2 → 1.1.0 → 1.2.0（原生线历史已占用 0.4.0~0.6.4 号段）；已达 1.0.0 稳定期，次版本（1.x）改动严格保持向后兼容。

**v1.0.2 新增**：声波警戒支持**手机位移触发**（陀螺仪/加速度计，静止基线法判定「从无变化到变化」，自动抓拍留证）；值守台新增位移状态实时芯片（校准中/静止/位移计数三态）；全界面竖横屏与触摸适配强化，位移相关 UI 与状态芯片按科幻风强化。

**v1.2.0 新增**：事件日志里的前后摄照片与录像**可放大、可旋转** —— 灯箱补齐缩放能力（按钮 ＋/−/1:1、双击、双指捏合、桌面滚轮，范围 0.5×~8×，放大后可拖动平移，缩回 1 倍自动归位），旋转与缩放合成同一 transform；缩略图可点区域放大到 56×44（原 44×30 低于触控标准）并加 `前/后/摄/位移/GPS` 来源角标；录像按钮统一标注来源，点开同样可放大旋转。

**v1.1.0 新增**：位移检测补上第二个来源 **GPS 位置变化**（Haversine 球面距离 + 定位精度过滤 + 基线锁定，与陀螺仪互补；Android 沿用 v0.4.0 已声明的定位权限，iOS 新增 `NSLocationWhenInUseUsageDescription`）；**事件日志三段化** —— 每条事件固定输出「告警内容」「手机位移」（含 GPS 轨迹坐标）「影像留证」（前置/后置照片与录像一并列出）；修正「位移只在抓拍成功时才入账」的缺陷（关掉抓拍或摄像头被占用时位移曾从日志里消失），改为检测即入账；导出 CSV 增加位移与 GPS 统计列；位移门禁升级为静态契约 + 陀螺仪行为 + GPS 行为三层（132 项断言）。详见 [`CHANGELOG.md`](CHANGELOG.md)。

**versionCode 规则**：每次 `bump` 自动 +1（单调递增），**永不复用**。
降级版本号时 versionCode 仍要递增，否则应用商店与 Android 的
`INSTALL_FAILED_VERSION_DOWNGRADE` 会拒绝安装。

**发布文件命名规则（2026-10-05 阳光指定，v0.9.0 起生效）**：
`dist/AlphaSun-Sonic-Analyzer-<版本号>-mobile.apk`（如 `AlphaSun-Sonic-Analyzer-0.9.0-mobile.apk`）。
`-mobile` 标志本交付线是**专门针对移动设备设计和优化**的版本（Capacitor WebView 线）。
不带日期（日期看文件属性与 CHANGELOG）；web/原生线以启动入口类判别（见 CHANGELOG）。
同名覆盖前必须确认，历史参考件保持原名不动。

```bash
npm run bump 0.1.1        # 修缺陷 → 升修订号
npm run bump 0.2.0        # 功能增强 → 升次版本
npm run bump 1.0.0        # 重大功能/不兼容 → 升主版本
node tools/bump-version.js  # 不带参数则回读当前六点版本
```

> ⚠️ **版本号必须与代码改动同步**。`npm run validate` 会对比 git HEAD：
> 若 `index.html` 有改动而 `APP_VER` 未变 → **直接判失败、构建被拦下**。
> 这条守卫是为了兜住一次真实事故：v0.1.0 之后又交付了整轮功能，
> 却只在提交信息里写了个内部标签就发布，APK 版本一直停在 0.1.0 而用户毫不知情。

`bump-version.js` 会**幂等地**同步六处并在改完后回读复核：
`index.html` 的 `APP_VER` / `#appVer` 文本 / `package.json` / `sw.js` CACHE /
`android/app/build.gradle` 的 `versionName` + `versionCode` /
`MainViewModel.kt` 的 `APP_VERSION`（第 6 点；2026-10-05 起由脚本接管，此前需手工同步，
曾是「界面显示版本 ≠ APK 实际版本」的漂移源）。
`npm run validate` 会校验六点一致且无陈旧版本号残留。

> 改 `APP_BUILD`（build 日期）需手动同步，它不参与六点校验。

## 四、开发与构建

```bash
npm install

# 同步资源 + 构建期守卫（DOM/版本六点一致/陈旧版本号/离线资源）
npm run sync

# 移动分支 QA 门禁（81 项断言：下线项不可达 / 三块功能不常驻 / 字体真实生效 /
#   顶栏三行顺序 / 底栏五连顺序与触控下限 / 说明在底部 / 弹层可开可关 /
#   品牌文本不裁切 / 7 种可视化 / 中央环暂停 / 三视口布局 / 零错误）
npm run qa
# 缺陷审计（浮层重叠 / 芯片可达 / 纵向空耗 / v0.04 专项六项）（浮层重叠 / 芯片可达 / 纵向空耗）
npm run audit
# 字体生效诊断（canvas 像素比对，防静默回退）
npm run fontcheck
# 三视口布局验证（手机竖/横、平板竖）
npm run verify
# 触摸交互验证（CDP 真实 touch 事件）
npm run touchcheck
# 7 种可视化逐个渲染验证
npm run fullcheck
# 中央环暂停/继续（鼠标 + 触摸双通道）
npm run centercheck
# 值守台冒烟（29 项） / 麦克风占用专项冒烟（8 项）
npm run guardsmoke
npm run micsmoke

# 一次性跑完以上全部
npm run fullgate

# 构建 Android release APK
npm run build:android
# 产物：android/app/build/outputs/apk/release/app-release.apk
```

### 测试工具的两个前提

1. **本仓库刻意不安装 electron**（Capacitor 目标，`node_modules` 从 ~250MB 降到 ~46MB）。
   开发期测试统一借用桌面仓库的 electron 可执行文件，路径解析收敛在 `tools/electron-path.js`：
   找不到时会**明确报错并给修复指引**，而不是抛出无信息的 `Process failed to launch!`。
   可用 `ALPHASUN_DESKTOP_REPO` 环境变量指向桌面仓库根目录。
2. **触摸事件必须走 CDP**：Electron 启动无法通过 context 选项打开 `hasTouch`，
   `page.touchscreen.tap()` 必然抛 `hasTouch must be enabled`。
   正确做法是 `Emulation.setTouchEmulationEnabled` + `Input.dispatchTouchEvent`。

> `tools/qa-gate.js`、`ui-shot.js`、`responsive-gate.js`、`audio-tools-smoke.js`、`mic-diag.js`
> 是从桌面仓库继承的工具，断言绑定已下线的「音频工具集」，**在本仓库不可用**，仅作对照保留。
> 文件头已加显式标注，勿改成本仓库的 npm script。

### 本机环境

- JDK 17：`C:\Users\net2n\android-dev\jdk\jdk-17.0.20.1+1`
- Android SDK：`C:\Users\net2n\Android\Sdk`（写入 `android/local.properties`，不入库）

### 签名

签名密钥 `android/alphasun-release.jks` **只存在于本机、已加入 .gitignore、绝不入库**。
若本机无该文件，release 构建会自动退化为未签名（debug 不受影响）。

## 五、iOS 构建（需 macOS + Xcode）

iOS 工程的 bundle id 已独立为 `com.alphasun.sonicanalyzer`，
麦克风权限文案已声明于 `ios/App/App/Info.plist`。

在 **macOS** 上：

```bash
npm install
npx cap sync ios
npx cap open ios          # 在 Xcode 中
# Xcode → Signing & Capabilities 选择你的 Team
# Product → Archive → Distribute App
```

> 本机为 Windows，**无法构建 IPA**。iOS 目录已就绪，需在 macOS 上完成签名与打包。

## 六、字体授权

| 字体 | 用途 | 授权 | 体积 |
|---|---|---|---|
| [Orbitron](https://fontsource.org/orbitron)（fontsource） | 英文品牌名 | SIL OFL 1.1 | 3×6.5KB |
| HarmonyOS Sans SC（Black 字重子集） | 中文软件名及界面汉字 | 华为官方免费商用 | 157KB |

### 中文子集是怎么来的

HarmonyOS Sans SC 全字库 8.1MB，直接内嵌会把 APK 撑大。做法是按**本应用实际用到的 1270 个汉字**
做子集化（`pyftsubset` → woff2 + brotli），体积降到 **157KB**，中文标题因此能呈现真正的几何科技感，
而不是退回系统黑体。

- 字符集清单：`assets/fonts/harmony-display.chars.txt`（由 `index.html` 全文（含 JS 文案）自动抽取）
- 若后续界面新增了清单外的汉字，**这些字会退回系统字体**（混排会看出差异）→
  重新抽取字符集并重新子集化即可（详见仓库提交记录中的 `pyftsubset` 命令）。
- 字体已加入 `sw.js` 预缓存清单：否则离线时 `url()` 请求失败会被 fetch 兜底返回 `index.html`，
  字体解码失败后**静默回退**，艺术字在离线场景直接失效（这个坑已踩过）。
