#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/macos"
swift build -c release --product Teather
APP="$ROOT/output/Teather.app"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp .build/release/Teather "$APP/Contents/MacOS/Teather"
cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>dev.kunnect.mac</string>
<key>CFBundleExecutable</key><string>Teather</string>
<key>CFBundleName</key><string>Teather</string>
<key>CFBundleIconFile</key><string>Teather.icns</string>
<key>CFBundleVersion</key><string>3</string>
<key>CFBundleShortVersionString</key><string>0.2.1</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>LSMinimumSystemVersion</key><string>13.0</string>
<key>LSUIElement</key><true/>
<key>NSHighResolutionCapable</key><true/>
</dict></plist>
PLIST
mkdir -p "$ROOT/.tools/Teather.iconset"
swift "$ROOT/scripts/make-mac-icon.swift" "$ROOT/.tools/Teather.iconset"
iconutil -c icns "$ROOT/.tools/Teather.iconset" -o "$APP/Contents/Resources/Teather.icns"
codesign --force --deep --sign - "$APP"
ditto -c -k --norsrc --keepParent "$APP" "$ROOT/output/Teather-macOS-arm64.zip"
echo "Built $APP"

# A conventional drag-to-Applications installer, using metadata rather than UI automation.
if [ ! -x "$ROOT/.tools/dmg-venv/bin/python" ]; then
    python3 -m venv "$ROOT/.tools/dmg-venv"
    "$ROOT/.tools/dmg-venv/bin/pip" install ds-store==1.3.1 mac-alias==2.2.3
fi
STAGE="$ROOT/.tools/dmg-stage"
python3 - "$STAGE" <<'PY_STAGE'
import shutil, sys
from pathlib import Path
p = Path(sys.argv[1])
if p.exists(): shutil.rmtree(p)
p.mkdir(parents=True)
PY_STAGE
ditto "$APP" "$STAGE/Teather.app"
ln -s /Applications "$STAGE/Applications"
"$ROOT/.tools/dmg-venv/bin/python" "$ROOT/scripts/dmg-layout.py" "$STAGE"
hdiutil create -volname "Teather — Drag to Applications" -srcfolder "$STAGE" -ov -format UDZO "$ROOT/output/Teather-macOS-arm64.dmg"
hdiutil verify "$ROOT/output/Teather-macOS-arm64.dmg"
