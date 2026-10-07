#!/usr/bin/env bash
# ============================================================================
# AlphaSun Sonic Analyzer — iOS 签名导入脚本（macOS 构建机：CI 或本地）
# ----------------------------------------------------------------------------
# 把 Apple 开发者证书(.p12) 与 Ad-Hoc 描述文件(.mobileprovision) 导入临时钥匙串，
# 供 xcodebuild 在手动签名(CODE_SIGN_STYLE=Manual)时使用。
#
# 依赖环境变量：
#   IOS_CERT_P12_BASE64              : base64 编码的 .p12（iPhone Distribution 证书）
#   IOS_CERT_PASSWORD                : .p12 导出密码
#   IOS_PROVISIONING_PROFILE_BASE64  : base64 编码的 .mobileprovision（Ad-Hoc 描述文件）
#   KEYCHAIN_PASSWORD                : 临时钥匙串密码（缺省随机生成）
#
# 用法：
#   IOS_CERT_P12_BASE64=$(base64 -w0 Dist.p12) \
#   IOS_CERT_PASSWORD=xxx \
#   IOS_PROVISIONING_PROFILE_BASE64=$(base64 -w0 app.mobileprovision) \
#   bash tools/ios-sign.sh
# ============================================================================

set -euo pipefail

CERT_B64="${IOS_CERT_P12_BASE64:-}"
PROFILE_B64="${IOS_PROVISIONING_PROFILE_BASE64:-}"
CERT_PWD="${IOS_CERT_PASSWORD:-}"
KC_PWD="${KEYCHAIN_PASSWORD:-$(openssl rand -base64 12)}"

if [ -z "$CERT_B64" ] || [ -z "$PROFILE_B64" ]; then
  echo "!! 缺少签名材料（IOS_CERT_P12_BASE64 / IOS_PROVISIONING_PROFILE_BASE64），跳过导入。" >&2
  exit 1
fi

TMP=$(mktemp -d)
CERT_P12="$TMP/cert.p12"
PROFILE="$TMP/app.mobileprovision"
echo "$CERT_B64" | base64 -d > "$CERT_P12"
echo "$PROFILE_B64" | base64 -d > "$PROFILE"

# 创建临时钥匙串并导入证书（标准 CI 签名范式）
security create-keychain -p "$KC_PWD" build.keychain 2>/dev/null || true
security unlock-keychain -p "$KC_PWD" build.keychain
security import "$CERT_P12" -k build.keychain -P "$CERT_PWD" \
  -T /usr/bin/codesign -T /usr/bin/security -T /usr/bin/xcrun
security list-keychains -d user -s build.keychain login.keychain-db
security set-key-partition-list -S apple-tool:,apple: -s -k "$KC_PWD" build.keychain

# 安装描述文件到 Xcode 可发现目录
PROFILE_DIR="$HOME/Library/MobileDevice/Provisioning Profiles"
mkdir -p "$PROFILE_DIR"
cp "$PROFILE" "$PROFILE_DIR/app.mobileprovision"

echo "✓ 签名证书与 Ad-Hoc 描述文件已导入钥匙串"
rm -rf "$TMP"
