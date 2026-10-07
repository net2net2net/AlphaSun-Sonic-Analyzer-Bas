#!/usr/bin/env bash
# ============================================================================
# AlphaSun Sonic Analyzer — iOS 构建脚本（须在本机 macOS + Xcode 环境运行）
# ----------------------------------------------------------------------------
# 前置依赖：
#   · macOS + 已装 Xcode（含命令行工具：xcodebuild / xcodebuild -exportArchive）
#   · CocoaPods（用于安装 Capacitor 原生依赖）
#   · 有效的 Apple 开发者签名：开发团队 ID（DEV_TEAM）与可选的 Provisioning Profile
#
# 用法示例：
#   1) App Store 发布（自动签名）：
#      DEV_TEAM=XXXXXXXXXX ./tools/build-ios.sh
#   2) Ad Hoc / 企业 / 开发（指定描述文件）：
#      DEV_TEAM=XXXXXXXXXX APP_PROFILE="AlphaSun Dist" SIGN_METHOD=ad-hoc \
#        ./tools/build-ios.sh
#
# 说明：
#   · Windows 沙箱无法运行 xcodebuild / pod，故 iOS 包必须在本机 Mac 或
#     GitHub Actions（macOS runner）上执行本脚本。
#   · 工程已在 Windows 侧准备好：ios/ 原生壳 + 最新 web 资产（v1.0.1）+ 麦克风/摄像头
#     隐私描述 + 通用设备（iPhone+iPad）+ 横竖屏 + 版本对齐（1.0.1 / build 14）。
#   · 首次构建前需联网执行 pod install 拉取 CapacitorPod 依赖。
#
# 产物：dist/AlphaSun-Sonic-Analyzer-1.0.1-ios.ipa
# ============================================================================

set -euo pipefail
cd "$(dirname "$0")/.."

SCHEME="App"
WS="ios/App/App.xcworkspace"
ARCHIVE="ios/build/AlphaSun.xcarchive"
EXPORT_DIR="dist"
VERSION="1.0.1"
SIGN_METHOD="${SIGN_METHOD:-app-store}"
DEV_TEAM="${DEV_TEAM:-}"
APP_PROFILE="${APP_PROFILE:-}"

if ! command -v xcodebuild >/dev/null 2>&1; then
  echo "!! 未检测到 xcodebuild，请在 macOS + Xcode 环境下运行本脚本。" >&2
  exit 1
fi
if [ -z "$DEV_TEAM" ]; then
  echo "!! 请提供 Apple 开发团队 ID：DEV_TEAM=XXXXXXXXXX ./tools/build-ios.sh" >&2
  exit 1
fi

echo "==> [1/4] pod install（联网拉取 Capacitor 原生依赖）"
( cd ios && pod install --repo-update )

echo "==> [2/4] xcodebuild archive"
xcodebuild -workspace "$WS" -scheme "$SCHEME" -configuration Release \
  -archivePath "$ARCHIVE" \
  -allowProvisioningUpdates \
  -developmentTeam "$DEV_TEAM" \
  clean archive | tail -40

echo "==> [3/4] 生成 ExportOptions.plist"
mkdir -p ios/build
cat > ios/build/ExportOptions.plist <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>method</key><string>$SIGN_METHOD</string>
  <key>teamID</key><string>$DEV_TEAM</string>
  <key>uploadBitcode</key><false/>
  <key>uploadSymbols</key><true/>
  <key>stripSwiftSymbols</key><true/>
${APP_PROFILE:+  <key>provisioningProfiles</key>
  <dict>
    <key>com.alphasun.sonicanalyzer</key><string>$APP_PROFILE</string>
  </dict>}
</dict>
</plist>
EOF

echo "==> [4/4] xcodebuild -exportArchive"
xcodebuild -exportArchive -archivePath "$ARCHIVE" \
  -exportOptionsPlist ios/build/ExportOptions.plist \
  -exportPath "$EXPORT_DIR" | tail -40

IPA=$(ls -1 "$EXPORT_DIR"/*.ipa 2>/dev/null | head -1 || true)
if [ -n "$IPA" ]; then
  mv "$IPA" "$EXPORT_DIR/AlphaSun-Sonic-Analyzer-${VERSION}-ios.ipa"
  echo "==> 完成：$EXPORT_DIR/AlphaSun-Sonic-Analyzer-${VERSION}-ios.ipa"
else
  echo "!! 未导出 IPA，请检查签名/导出配置（DEV_TEAM / APP_PROFILE / SIGN_METHOD）。" >&2
  exit 1
fi
