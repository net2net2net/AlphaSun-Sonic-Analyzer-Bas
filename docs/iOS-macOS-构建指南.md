# AlphaSun 声波分析仪 · iOS / macOS 版本构建指南

> 重要说明：Apple 平台（iOS IPA / macOS APP）受苹果工具链限制，**只能在 macOS 电脑上构建**（需要 Xcode）。
> Windows 电脑无法交叉编译出 IPA，也无法打包 macOS 的 dmg/zip（electron-builder 硬性限制）。
> 本项目的 iOS 与 macOS 工程已全部生成并同步最新代码，拿到任意一台 Mac 后按下面步骤 10 分钟即可出包。

## 前置条件（Mac 上一次性准备）

1. App Store 安装 **Xcode**（14 以上）。
2. Xcode → Settings → Accounts 登录 Apple ID（免费账号即可真机调试自己的设备；分发给别人需要付费开发者账号 99 美元/年）。
3. 项目目录拷贝到 Mac（U 盘 / 网盘 / git 均可），确认目录里 `ios/` 与 `android/` 已存在（本项目已包含）。
4. Mac 上安装 Node.js LTS，然后在项目根目录执行：

```bash
npm install          # 安装依赖（Capacitor 等）
npm run sync         # 同步最新 web 代码到 iOS/Android 工程
```

## 构建 iOS 版（IPA，iPhone / iPad 通用）

```bash
cd ios/App
pod install                        # 首次需要（Mac 自带 ruby 即可）
open App.xcworkspace               # 注意：必须打开 .xcworkspace，不是 .xcodeproj
```

在 Xcode 中：

1. 左侧选中 **App** 项目 → Signing & Capabilities：
   - Team 选择你的 Apple ID（Personal Team 也可）；
   - Bundle Identifier 改成唯一值，例如 `com.alphasun.audiolab.你的名字`；
   - 勾选 **Automatically manage signing**。
2. 顶部设备选择你的 iPhone/iPad（或任一 iOS Simulator）。
3. 连接真机：iPhone 上弹窗选择"信任"，并在 设置→隐私与安全性→开发者模式 打开开发者模式（iOS 16+ 需要）。
4. **真机直装**：选中设备 → ▶ Run，装好即可使用。
5. **导出 IPA 文件**：Product → Archive → 结束后在 Organizer 里 **Distribute App → Ad Hoc / Development** → 导出得到 `App.ipa`。

> 信息说明：应用所需权限描述（麦克风）已在工程 Info.plist 配好：`NSMicrophoneUsageDescription`。
> 本应用完全本地计算、不上传任何音频数据。

## 构建 macOS 版（APP / DMG）

方式一（最简单，直接得到可运行的 App）：

```bash
cd ios/App && open App.xcworkspace   # 同一工程也可选 My Mac 目标
# Xcode 顶部目标选 "My Mac" → Product → Archive → 导出 Copy Mac Application
```

方式二（electron 版，打 zip/dmg）：

```bash
npm install
npx electron-builder --mac          # 在 Mac 上执行，得到 dmg + zip
```

> 建议用方式二，与 Windows/Linux 版同源（Electron 28），界面与功能完全一致。
> 未付费签名时，首次打开需右键 → 打开（绕过 Gatekeeper 提示）。

## 版本号对照（务必与主工程一致）

当前主工程版本：**v2.20.0**（`node tools/bump-version.js` 五点同步，versionCode 35）。

| 平台 | 文件 | 当前版本号位置 |
|------|------|----------------|
| 全部界面显示 | index.html | `APP_VER`（v2.20.0） |
| Windows / Linux / macOS（Electron） | package.json | `version`（2.20.0） |
| Android | android/app/build.gradle | `versionName "2.20.0"` / `versionCode 35` |
| iOS | ios/App/App/Info.plist | `CFBundleShortVersionString`（如仍是 1.0 请改为 2.20.0） |

iOS 版本号也可以直接在 Xcode 里改：App → General → Identity → Version 填 `2.20.0`，Build 填 `35`。

> 换版本统一用 `node tools/bump-version.js <版本号>`（幂等 + 回读校验，自动同步五点）。

## Android 构建（Windows 本机已完成，无需 Mac）

```bash
npm run sync                              # 同步 web 代码到 android 工程
cd android && ./gradlew assembleRelease   # 产出 app/build/outputs/apk/release/app-release.apk
```

- 本项目已内置**本机 release keystore**（`android/alphasun-release.jks`，口令见 `build.gradle`，
  已加入 `.gitignore` 绝不入库）。若该文件存在，`assembleRelease` 自动签名；不存在则退化为未签名（Debug 不受影响）。
- 产物重命名：`AlphaSun-AudioLab-2.20.0.apk`。
- **APK 需两个权限同时声明**才能在 Capacitor 下拿到麦克风（少 `MODIFY_AUDIO_SETTINGS` 则 `getUserMedia` 永远失败）：
  `RECORD_AUDIO` + `MODIFY_AUDIO_SETTINGS`。

## iOS 端横屏 / 竖屏 / 触摸适配（已内置，无需额外配置）

`index.html` 已针对 iPhone/iPad 做过以下适配，换新版本代码时**这些会自动同步**，请勿手改：

| 适配项 | 实现 |
|---|---|
| 刘海屏 / Home 条 / 手势条 | `viewport-fit=cover` + `env(safe-area-inset-*)` 安全区内边距（值守台、二级分析台、footer 均已覆盖） |
| 横屏不自动放大字号 | `html{-webkit-text-size-adjust:100%}` + 输入框 `focus` 时 `font-size:16px`（防 iOS 聚焦缩放跳动） |
| 触摸目标 ≥44px | `@media(hover:none) and (pointer:coarse)` 下按钮/下拉/输入框 `min-height:44px`，复选框由外层 `label.sw` 提供 44px 点击区 |
| 竖屏 / 横屏布局切换 | 17 个响应式断点，含 `(orientation:portrait)` / `(orientation:landscape)` / 矮屏 `(max-height:520px)`（横屏手机压缩顶部栏） |
| 禁止双击缩放与橡皮筋 | 已设 `touch-action` 与 `user-select` 策略 |

> 若新增了新的全屏浮层（如值守台），**请务必给它加 `env(safe-area-inset-*)` 内边距**，
> 否则在 iPhone 刘海屏上顶部会被遮挡。这是本项目 iOS 适配的唯一易漏点。

## 各平台现状速览

| 平台 | 形态 | 构建位置 | 状态 |
|------|------|----------|------|
| Windows | 单 EXE 免安装（portable，**单文件**） | Windows 本机 | ✅ 已产出 |
| Android | APK 免安装直装（本机 keystore 自签） | Windows 本机 | ✅ 已产出 |
| Linux | tar.gz / AppImage | Windows 本机 | ✅ 已产出 |
| iOS / iPadOS | IPA | **必须 Mac + Xcode** | 📋 本指南步骤即可（工程与代码已就绪同步） |
| macOS | zip / dmg（Electron 28，同源同界面） | **必须 Mac** | 📋 本指南步骤即可 |
| 浏览器 / PWA | index.html | 任意设备 | ✅ 随源码提供 |
