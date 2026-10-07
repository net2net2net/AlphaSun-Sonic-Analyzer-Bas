#!/usr/bin/env bash
# ============================================================================
# AlphaSun Sonic Analyzer — iOS 无签名 IPA 重签脚本（本地 macOS 使用）
# ----------------------------------------------------------------------------
# 把 GitHub Actions 产出的「未签名 IPA」用你自己的证书 + 描述文件重签成可装 IPA。
# 适用：Ad-Hoc（付费账号，UDID 已登记）或 Development（免费 Personal Team）。
#
# 依赖（仅 macOS 自带）：security / codesign / PlistBuddy / xcrun / zip / unzip
#
# 用法（环境变量传入，全必填除非标注可选）：
#   INPUT_IPA=AlphaSun-...-ios-unsigned.ipa \
#   IOS_CERT_P12=Dist.p12 \
#   IOS_CERT_PASSWORD=导出密码 \
#   IOS_PROVISIONING_PROFILE=app.mobileprovision \
#   [BUNDLE_ID=com.alphasun.audiolab] \          # 可选：覆盖 App 的 Bundle ID（需与描述文件 app-id 匹配）
#   [OUTPUT_IPA=...-signed.ipa] \                # 可选：默认在 INPUT 同目录加 -signed
#   bash tools/resign-ios.sh
#
# 说明：未签名 IPA 不能直接装真机；重签后即可经 Apple Configurator 2 / Xcode /
# Finder 安装到描述文件已登记的设备。
# ============================================================================

set -euo pipefail

INPUT_IPA="${INPUT_IPA:-}"
P12="${IOS_CERT_P12:-}"
P12_PWD="${IOS_CERT_PASSWORD:-}"
PROFILE="${IOS_PROVISIONING_PROFILE:-}"
BUNDLE_ID="${BUNDLE_ID:-}"
OUTPUT_IPA="${OUTPUT_IPA:-}"

err(){ echo "!! $*" >&2; exit 1; }

[ -n "$INPUT_IPA" ]   || err "缺少 INPUT_IPA（未签名 IPA 路径）"
[ -n "$P12" ]         || err "缺少 IOS_CERT_P12（.p12 证书路径）"
[ -n "$P12_PWD" ]     || err "缺少 IOS_CERT_PASSWORD（.p12 导出密码）"
[ -n "$PROFILE" ]     || err "缺少 IOS_PROVISIONING_PROFILE（.mobileprovision 路径）"
[ -f "$INPUT_IPA" ]   || err "找不到 INPUT_IPA: $INPUT_IPA"
[ -f "$P12" ]         || err "找不到 IOS_CERT_P12: $P12"
[ -f "$PROFILE" ]     || err "找不到 IOS_PROVISIONING_PROFILE: $PROFILE"

# 输出路径默认：在输入同目录把 -unsigned 换成 -signed，否则加 -signed
if [ -z "$OUTPUT_IPA" ]; then
  OUTPUT_IPA="${INPUT_IPA%-unsigned.ipa}-signed.ipa"
fi

WORK=$(mktemp -d)
KC_PWD="${KEYCHAIN_PASSWORD:-$(openssl rand -base64 12)}"
KEYCHAIN="$WORK/build.keychain"

cleanup(){ security delete-keychain "$KEYCHAIN" >/dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT

echo "==> 解压未签名 IPA"
unzip -q -o "$INPUT_IPA" -d "$WORK"
APP=$(find "$WORK/Payload" -maxdepth 1 -name '*.app' -type d | head -1)
[ -n "$APP" ] || err "在 Payload/ 下未找到 .app"

echo "==> 建临时钥匙串并导入证书"
security create-keychain -p "$KC_PWD" "$KEYCHAIN" 2>/dev/null || true
security unlock-keychain -p "$KC_PWD" "$KEYCHAIN"
security import "$P12" -k "$KEYCHAIN" -P "$P12_PWD" \
  -T /usr/bin/codesign -T /usr/bin/security -T /usr/bin/xcrun
security list-keychains -d user -s "$KEYCHAIN" login.keychain-db
security set-key-partition-list -S apple-tool:,apple: -s -k "$KC_PWD" "$KEYCHAIN"

# 自动选取签名身份（取 codesigning 身份中的第一个）
IDENTITY=$(security find-identity -v -p codesigning "$KEYCHAIN" | grep -oE '".*"' | head -1 | tr -d '"')
[ -n "$IDENTITY" ] || err "钥匙串中找不到可用的 codesigning 身份，请检查 p12"
echo "    签名身份: $IDENTITY"

echo "==> 安装描述文件并解析"
PROFILE_DIR="$HOME/Library/MobileDevice/Provisioning Profiles"
mkdir -p "$PROFILE_DIR"
cp "$PROFILE" "$PROFILE_DIR/$(basename "$PROFILE")"
security cms -D -i "$PROFILE" > "$WORK/profile.plist"

# 抽取 Entitlements
/usr/libexec/PlistBuddy -x -c "Print :Entitlements" "$WORK/profile.plist" > "$WORK/entitlements.plist" 2>/dev/null \
  || err "无法从描述文件解析 Entitlements（可能描述文件已损坏）"

# 关闭 get-task-allow（分发类型必须为 false；开发类型描述文件本身已带 true）
/usr/libexec/PlistBuddy -c "Set :get-task-allow false" "$WORK/entitlements.plist" 2>/dev/null || \
  /usr/libexec/PlistBuddy -c "Add :get-task-allow bool false" "$WORK/entitlements.plist"

# 若指定 BUNDLE_ID，则同步 Info.plist 与 entitlements 的 application-identifier
if [ -n "$BUNDLE_ID" ]; then
  TEAMID=$(/usr/libexec/PlistBuddy -c "Print :Entitlements:application-identifier" "$WORK/profile.plist" | sed 's/\..*//')
  /usr/libexec/PlistBuddy -c "Set :CFBundleIdentifier $BUNDLE_ID" "$APP/Info.plist" 2>/dev/null || \
    /usr/libexec/PlistBuddy -c "Add :CFBundleIdentifier string $BUNDLE_ID" "$APP/Info.plist"
  /usr/libexec/PlistBuddy -c "Set :application-identifier $TEAMID.$BUNDLE_ID" "$WORK/entitlements.plist"
  /usr/libexec/PlistBuddy -c "Set :com.apple.developer.team-identifier $TEAMID" "$WORK/entitlements.plist" 2>/dev/null || \
    /usr/libexec/PlistBuddy -c "Add :com.apple.developer.team-identifier string $TEAMID" "$WORK/entitlements.plist"
  echo "    已将 Bundle ID 对齐为 $BUNDLE_ID (team=$TEAMID)"
fi

echo "==> 写入 embedded.mobileprovision"
cp "$PROFILE" "$APP/embedded.mobileprovision"

echo "==> 重签所有内嵌组件（framework / dylib / appex）"
find "$APP" -name '*.framework' -type d | while read -r f; do
  echo "    sign framework: $(basename "$f")"
  codesign --force --timestamp=none --sign "$IDENTITY" "$f"
done
find "$APP" -name '*.dylib' -type f -exec codesign --force --timestamp=none --sign "$IDENTITY" {} \;
find "$APP" -name '*.appex' -type d | while read -r e; do
  echo "    sign extension: $(basename "$e")"
  codesign --force --timestamp=none --sign "$IDENTITY" --entitlements "$WORK/entitlements.plist" "$e"
done

echo "==> 重签主 App"
codesign --force --timestamp=none --sign "$IDENTITY" --entitlements "$WORK/entitlements.plist" "$APP"

echo "==> 重新打包 IPA"
( cd "$WORK" && zip -q -r "$OUTPUT_IPA" Payload )
echo "    产物: $OUTPUT_IPA"

echo "==> 校验签名"
codesign -v --verbose=2 "$APP" && echo "✓ 主 App 签名有效"
echo "✓ 重签完成：用 Apple Configurator 2 / Xcode / Finder 安装到已登记 UDID 的设备"
