#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/build.sh
runtimebay_destination="${RUNTIMEBAY_PACKAGE_PATH:-$PWD/dist/Runtime Bay.app}"
mkdir -p "$(dirname "$runtimebay_destination")"
# Never overwrite a mapped Mach-O in place: macOS can retain its old signature
# validation for that inode and kill newly launched helpers with SIGKILL.
runtimebay_stage="$(mktemp -d "$(dirname "$runtimebay_destination")/.runtimebay-package.XXXXXX")"
runtimebay_app="$runtimebay_stage/Runtime Bay.app"
trap 'rm -rf "$runtimebay_stage"' EXIT
mkdir -p "$runtimebay_app/Contents/MacOS" "$runtimebay_app/Contents/Resources"
cp .build/RuntimeBay "$runtimebay_app/Contents/MacOS/RuntimeBay"
cp .build/runtimebay-launcher .build/runtimebay-terminal .build/runtimebay-askpass .build/runtimebay-keychain backend/target/runtimebay-backend.jar "$runtimebay_app/Contents/Resources/"
cp .build/loading-startup.js .build/loading.css "$runtimebay_app/Contents/Resources/"
cp .build/RuntimeBay.icns "$runtimebay_app/Contents/Resources/"
cp frontend/src/assets/runtime-bay.png "$runtimebay_app/Contents/Resources/"
cp LICENSE THIRD_PARTY_NOTICES.md "$runtimebay_app/Contents/Resources/"
mkdir -p "$runtimebay_app/Contents/Resources/licenses"
cp native/licenses/Apache-2.0.txt "$runtimebay_app/Contents/Resources/licenses/"
cp -R licenses/. "$runtimebay_app/Contents/Resources/licenses/"
rm -rf "$runtimebay_app/Contents/Resources/web"
mkdir -p "$runtimebay_app/Contents/Resources/web"
cp -R dist/web/browser/. "$runtimebay_app/Contents/Resources/web/"
test -s dist/web/3rdpartylicenses.txt
cp dist/web/3rdpartylicenses.txt "$runtimebay_app/Contents/Resources/"
# jlink needs an empty output path. Remove only this app's generated runtime.
rm -rf "$runtimebay_app/Contents/Resources/jre"
runtimebay_jdk="$(/usr/libexec/java_home -v 25)"
cp "$runtimebay_jdk/release" "$runtimebay_app/Contents/Resources/licenses/java-build-release.txt"
"$runtimebay_jdk/bin/jlink" --add-modules java.base,java.net.http,java.logging,java.management,java.sql,jdk.httpserver,jdk.unsupported --strip-debug --no-header-files --no-man-pages --output "$runtimebay_app/Contents/Resources/jre"
cat > "$runtimebay_app/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleName</key><string>Runtime Bay</string>
<key>CFBundleDisplayName</key><string>Runtime Bay</string>
<key>CFBundleIdentifier</key><string>dev.runtimebay.app</string>
<key>CFBundleExecutable</key><string>RuntimeBay</string>
<key>CFBundleIconFile</key><string>RuntimeBay.icns</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleSupportedPlatforms</key><array><string>MacOSX</string></array>
<key>NSPrincipalClass</key><string>NSApplication</string>
<key>CFBundleShortVersionString</key><string>0.5.0</string>
<key>CFBundleVersion</key><string>13</string>
<key>LSMinimumSystemVersion</key><string>14.0</string>
<key>NSHighResolutionCapable</key><true/>
<key>NSHumanReadableCopyright</key><string>Runtime Bay contributors</string>
</dict></plist>
PLIST
printf 'APPL????' > "$runtimebay_app/Contents/PkgInfo"
# Resources are not a nested-code location; sign executable helpers explicitly.
codesign --force --sign - "$runtimebay_app/Contents/Resources/runtimebay-launcher"
codesign --force --sign - "$runtimebay_app/Contents/Resources/runtimebay-terminal"
codesign --force --sign - "$runtimebay_app/Contents/Resources/runtimebay-askpass"
codesign --force --sign - "$runtimebay_app/Contents/Resources/runtimebay-keychain"
codesign --force --deep --sign - "$runtimebay_app"
codesign --verify --deep --strict "$runtimebay_app"
node scripts/check-packaged-terminal.mjs "$runtimebay_app"
if [ -e "$runtimebay_destination" ]; then mv "$runtimebay_destination" "$runtimebay_stage/previous.app"; fi
if ! mv "$runtimebay_app" "$runtimebay_destination"; then
  if [ -e "$runtimebay_stage/previous.app" ]; then mv "$runtimebay_stage/previous.app" "$runtimebay_destination"; fi
  exit 1
fi
runtimebay_app="$runtimebay_destination"
node scripts/check-packaged-terminal.mjs "$runtimebay_app"
ditto -c -k --sequesterRsrc --keepParent "$runtimebay_app" "$PWD/dist/Runtime-Bay-macos-$(uname -m).zip"
printf 'Built %s\n' "$runtimebay_app"
