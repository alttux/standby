#!/bin/bash
# Builds "StandBy Companion.app" (universal when possible) into ./build and zips it into ../dist.
set -euo pipefail
cd "$(dirname "$0")"
APP="build/StandBy Companion.app"

if swift build -c release --arch arm64 --arch x86_64 >/dev/null 2>&1; then
    BIN=".build/apple/Products/Release/StandByCompanion"
else
    swift build -c release
    BIN=".build/release/StandByCompanion"
fi

rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/StandByCompanion"
cp Resources/Info.plist "$APP/Contents/Info.plist"
cp Resources/AppIcon.icns "$APP/Contents/Resources/AppIcon.icns"
codesign --force --deep --sign - "$APP"

mkdir -p ../dist
rm -f "../dist/StandBy-Companion-macOS.zip"
ditto -c -k --keepParent "$APP" "../dist/StandBy-Companion-macOS.zip"
echo "Built $APP ($(lipo -archs "$APP/Contents/MacOS/StandByCompanion"))"
