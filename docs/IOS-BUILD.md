# iOS 构建说明（v0.07）

> 当前仓库**尚未生成 `ios/` 平台目录**（只有 `android/`）。本文件给出从零到可安装包的完整步骤，
> 以及**本项目特有的必配项**——这些不配就会在真机上"能装但用不了"。

---

## 一、生成 iOS 平台

前置条件（macOS + Xcode 14+）：

```bash
# 1) 装 Xcode Command Line Tools（若未装）
xcode-select --install

# 2) 生成 ios/ 目录（会读取 capacitor.config.json）
npx cap add ios

# 3) 把 www/ 同步进 ios/
npm run sync
npx cap sync ios
```

`npm run sync` 会把根 `index.html` / `mobile/` / `assets/` 等复制到 `ios/App/App/public/`，
**不要手改 `ios/` 里的 public 资源**，下次 sync 会覆盖。

---

## 二、必配项（不配就在真机上失效）

### 1. Info.plist 用途声明 —— 缺一项就弹不出系统授权框

打开 `ios/App/App/Info.plist`，在 `<dict>` 内加入：

```xml
<!-- 麦克风：实时采集与声波分析的核心权限 -->
<key>NSMicrophoneUsageDescription</key>
<string>需要使用麦克风采集环境声波，进行频谱分析、噪音评估与声源定位。音频全部在本机处理，不会上传。</string>

<!-- 摄像头：声波警戒在预警/告警时调用前后摄像头抓拍与录像 -->
<key>NSCameraUsageDescription</key>
<string>声波警戒触发时使用摄像头抓拍与录制现场画面，作为事件记录。所有媒体仅保存在本机。</string>

<!-- 相册：把抓拍/录像保存到系统相册（不配则只能存应用内） -->
<key>NSPhotoLibraryAddUsageDescription</key>
<string>将声波警戒事件的抓拍照片与录像片段保存到相册。</string>
<key>NSPhotoLibraryUsageDescription</key>
<string>从相册选择图片用于声波事件分析。</string>
```

> 系统**只弹一次**权限框。用户误点"不允许"后，
> 必须去 `设置 → 隐私与安全性 → 对应权限` 手动打开，App 内无法再唤起系统弹窗。
> 因此应用内的错误提示必须写清这个路径（已在 `micErrText()` 中按平台分别给出）。

### 2. 后台音频（可选）

若希望锁屏/切后台时仍继续采集与值守，在 `Info.plist` 增加：

```xml
<key>UIBackgroundModes</key>
<array>
  <string>audio</string>
</array>
```

不加则退到后台 App 被系统挂起（`mobile/bridge.js` 会自动挂起采集，防止僵死）。

> ⚠ 苹果审核对 `audio` 后台模式审查严格：必须有**真实的持续音频用途**，
> 且需在审核备注中说明。仅"为了不断采集"可能被拒。

### 3. 音频会话（`mobile/compat.js` 已处理大部分）

iOS 上 `AudioContext` 必须在**用户手势内**创建或 `resume()`，否则永远 `suspended`。
`mobile/compat.js` 已在首次 `touchstart / pointerdown / mousedown / keydown` 时自动解锁。

若仍无声，检查 Xcode 控制台是否有：
`AVAudioSession ... category` 相关警告 —— 那说明需要原生侧设置
`AVAudioSessionCategory = .playAndRecord` + `.defaultToSpeaker`，
这需要写一个自定义 Capacitor 插件（当前未实现）。

---

## 三、真机验证清单（务必逐项实测，模拟器不算）

| # | 验证项 | 期望 | 失败时排查 |
|---|---|---|---|
| 1 | 首次点「开始采集」弹麦克风授权 | 弹出且可选「允许」 | 缺 `NSMicrophoneUsageDescription` |
| 2 | 电平表有数值 | 不为 `--` | 音频未解锁 / 权限被拒 |
| 3 | 切后台再回前台 | 自动恢复，指标连续 | `mobile/bridge.js` 是否加载 |
| 4 | 声波警戒 → 预警/告警触发抓拍 | 前置摄像头出图 | 缺 `NSCameraUsageDescription` |
| 5 | 抓拍能存到相册 | 相册可见 | 缺 `NSPhotoLibraryAddUsageDescription` |
| 6 | 分离试听有声音 | 能听到前景轨 | `src.start()` / 门限，见代码注释 |
| 7 | 锁屏 10 分钟再解锁 | 不崩溃、不空耗 | 后台模式声明 |
| 8 | 权限设为"不允许"后重试 | 提示指向 iOS 设置路径 | `micErrText()` 分支 |

---

## 四、签名与打包

```bash
# Xcode → Product → Archive
# 或命令行
xcodebuild -workspace ios/App/App.xcworkspace \
           -scheme App -configuration Release \
           -archivePath ios/build/App.xcarchive archive
```

- Bundle ID 必须与 `capacitor.config.json` 的 `appId` 一致：
  `com.alphasun.sonicanalyzer`
- 与 Android 共用名称 `AlphaSun Sonic Analyzer`，但**包名/ID 不同**，可并存安装。
- 开发者证书需 Apple Developer Program（免费账号 7 天过期，仅够自测）。

---

## 五、当前状态与待办

- [x] Android 可构建、可安装（`dist/*.apk`）
- [x] `mobile/compat.js` 移动适配层（iOS 音频解锁 + 平台自检）
- [x] `capacitor.config.json` 补齐 `ios` 段与 `server.iosScheme`
- [ ] **生成 `ios/` 目录并提交**（需 macOS 环境）
- [ ] 真机跑通上面 8 项验证清单
- [ ] 原生音频会话插件（仅在"解锁后仍无声"时才需要）
